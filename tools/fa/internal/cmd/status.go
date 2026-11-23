package cmd

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	register(&command{name: "status", summary: "what's running, where the data is, how full the disk is", run: runStatus})
	register(&command{name: "logs", summary: "tail logs: fa logs [site|caddy|tunnel|db] [-f] [-n N] [--since 15m]", run: runLogs})
}

// overdueAfter is how old the newest off-site backup may be before status
// says so. With a daily schedule, a day and a few hours' grace: one missed
// night shows up the next morning. Without one, two days.
func overdueAfter(scheduled bool) time.Duration {
	if scheduled {
		return 30 * time.Hour
	}
	return 48 * time.Hour
}

func runStatus(c *Ctx, _ []string) error {
	in := c.In
	say("install   %s", in.Describe())
	say("commit    %s", gitHead(in))

	ci, err := inspectContainer(in.Container())
	switch {
	case err != nil:
		bad("site container: not created (run `fa up` or `fa deploy`)")
	case !ci.Running:
		bad("site container: %s", ci.Status)
	default:
		ok("site container: running since %s (%s)", ci.StartedAt.Local().Format("Jan 2 15:04"), ci.Image)
		if src := ci.Mounts["/data"]; src != "" && filepath.Clean(src) != filepath.Clean(in.DataPath()) {
			warn("container mounts /data from %s but .env says %s — recreate it (fa up)", src, in.DataPath())
		}
	}
	if err := healthy(in); err != nil {
		bad("site answers on :%s — %v", in.DebugPort(), err)
	} else {
		ok("site answers on 127.0.0.1:%s", in.DebugPort())
	}

	if cn := in.Settings.CaddyContainer; cn != "" {
		if pi, err := inspectContainer(cn); err == nil && pi.Running {
			ok("proxy %s: running", cn)
		} else {
			bad("proxy %s: not running", cn)
		}
	}

	dp := in.DataPath()
	if free, total, err := diskFree(dp); err == nil {
		pct := 0
		if total > 0 {
			pct = int(100 - free*100/total)
		}
		line := "data disk: " + humanBytes(free) + " free of " + humanBytes(total) + " (" + itoa(pct) + "% used)"
		if isMountPoint(dp) {
			line += ", own volume"
		}
		if pct >= 90 {
			warn("%s", line)
		} else {
			ok("%s", line)
		}
	} else {
		bad("data path %s: %v", dp, err)
	}
	if !in.UsesPostgres() {
		if st, err := os.Stat(filepath.Join(dp, "site.db")); err == nil {
			ok("sqlite: %s", humanBytes(uint64(st.Size())))
		}
	}
	if n, size := dirStats(filepath.Join(dp, "objects")); n > 0 {
		ok("media: %d files, %s", n, humanBytes(size))
	}

	// The backup that counts is the off-site one (fa backups). The tarball on
	// this server (fa backup) is a convenience before a risky change, so once
	// off-site backups are set up its absence is not worth a warning.
	offsite := false
	if info, err := gatherBackups(in); err == nil && info.Repository != "" {
		offsite = true
		switch last := info.Last; {
		case last == nil:
			warn("no off-site backup yet (fa backups run)")
		case !last.Success:
			bad("off-site backup failed: %s", firstNonEmpty(last.Error, "see fa backups show"))
		default:
			if t, err := time.Parse(time.RFC3339, last.Time); err != nil {
				ok("off-site backup made: %s", last.Time)
			} else if age := time.Since(t); age > overdueAfter(info.Schedule != "") {
				warn("off-site backup %s ago: older than it should be (fa backups show)", shortAge(age))
			} else {
				ok("off-site backup %s ago", shortAge(age))
			}
		}
	}
	if info, err := gatherBackups(in); err == nil && info.ScheduleMissing {
		bad("automatic backups are set for %s but are not in the crontab, so none will run: fa backups set schedule %s", info.Schedule, info.Schedule)
	}
	if bd := backupDir(in); bd != "" {
		if latest := latestBackup(bd); latest != "" {
			st, _ := os.Stat(latest)
			age := time.Since(st.ModTime()).Round(time.Hour)
			switch {
			case offsite:
				ok("copy on this server from %s ago: %s", shortAge(age), filepath.Base(latest))
			case age > 8*24*time.Hour:
				warn("last backup %s ago: %s", age, latest)
			default:
				ok("last backup %s ago: %s", age, filepath.Base(latest))
			}
		} else if !offsite {
			warn("no backups yet: set up automatic ones with fa backups setup, or make a quick copy in %s with fa backup", bd)
		}
	}
	return nil
}

func dirStats(root string) (n int, size uint64) {
	filepath.WalkDir(root, func(_ string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if fi, e := d.Info(); e == nil {
				n++
				size += uint64(fi.Size())
			}
		}
		return nil
	})
	return
}

func latestBackup(dir string) string {
	m, _ := filepath.Glob(filepath.Join(dir, "fartingaround-*.tar*"))
	if len(m) == 0 {
		return ""
	}
	sort.Strings(m)
	return m[len(m)-1]
}

func runLogs(c *Ctx, args []string) error {
	in := c.In
	target := "site"
	var pass []string
	for i := 0; i < len(args); i++ {
		switch a := args[i]; {
		case a == "-f" || a == "--follow":
			pass = append(pass, "-f")
		case a == "-n" && i+1 < len(args):
			pass = append(pass, "--tail", args[i+1])
			i++
		case a == "--since" && i+1 < len(args): // docker's own: 15m, 2h, or a timestamp
			pass = append(pass, "--since", args[i+1])
			i++
		case strings.HasPrefix(a, "-"):
			pass = append(pass, a)
		default:
			target = a
		}
	}
	if !containsTail(pass) {
		pass = append(pass, "--tail", "100")
	}
	switch target {
	case "site":
		return composePass(in, append([]string{"logs"}, append(pass, "site")...)...)
	case "caddy", "proxy":
		if in.Settings.CaddyContainer == "" {
			return errorf("no proxy container recorded; set caddyContainer in .fa.json or run fa setup")
		}
		return sh.Pass("", "docker", append([]string{"logs"}, append(pass, in.Settings.CaddyContainer)...)...)
	case "tunnel":
		return composePass(in, append([]string{"logs"}, append(pass, "tunnel")...)...)
	case "db":
		if !in.UsesPostgres() {
			return errorf("sqlite has no log — the site's own log is `fa logs site`")
		}
		host := pgHost(in.Env.Get("DATABASE_URL"))
		return sh.Pass("", "docker", append([]string{"logs"}, append(pass, host)...)...)
	}
	return errorf("unknown log target %q (site, caddy, tunnel, db)", target)
}

func containsTail(args []string) bool {
	for _, a := range args {
		if a == "--tail" {
			return true
		}
	}
	return false
}

// shortAge: 25m, 7h, 3d.
func shortAge(d time.Duration) string {
	switch {
	case d < time.Hour:
		return strconv.Itoa(int(d.Minutes())) + "m"
	case d < 48*time.Hour:
		return strconv.Itoa(int(d.Hours())) + "h"
	}
	return strconv.Itoa(int(d.Hours()/24)) + "d"
}

func firstNonEmpty(a, b string) string {
	if strings.TrimSpace(a) != "" {
		return a
	}
	return b
}
