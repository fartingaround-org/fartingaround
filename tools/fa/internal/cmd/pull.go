package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	// Local side: runs on your laptop, talks to the server over ssh.
	register(&command{name: "pull", summary: "mirror the site to this machine: fa pull user@box [--to DIR] [--keep N]  (rsync media + DB dump)", noInstall: true, run: runPull})
	// Remote helpers `pull` calls over ssh. Not interesting to run by hand.
	register(&command{name: "info", summary: "machine-readable install facts (used by fa pull)", run: runInfo})
	register(&command{name: "dump", summary: "stream a consistent database copy to stdout (used by fa pull)", run: runDump})
}

type infoOut struct {
	Dir      string `json:"dir"`
	DataPath string `json:"dataPath"`
	Postgres bool   `json:"postgres"`
	Commit   string `json:"commit"`
	Version  string `json:"version"`
}

func runInfo(c *Ctx, _ []string) error {
	b, _ := json.Marshal(infoOut{Dir: c.In.Dir, DataPath: c.In.DataPath(), Postgres: c.In.UsesPostgres(), Commit: gitHead(c.In), Version: c.Version})
	fmt.Println(string(b))
	return nil
}

// runDump writes the database to stdout: pg_dump for Postgres, a VACUUM INTO
// copy for SQLite (made inside the site container, then streamed and removed).
func runDump(c *Ctx, _ []string) error {
	in := c.In
	if in.UsesPostgres() {
		return sh.ToFile("", os.Stdout, "docker", "run", "--rm", "--network", "web", "postgres:17-alpine", "pg_dump", "--no-owner", "--no-privileges", in.Env.Get("DATABASE_URL"))
	}
	name := ".dump-" + strconv.FormatInt(time.Now().UnixNano(), 36) + ".db"
	host := filepath.Join(in.DataPath(), name)
	js := `const {DatabaseSync}=require("node:sqlite");new DatabaseSync("/data/site.db",{readOnly:true}).exec("VACUUM INTO '/data/` + name + `'")`
	if _, err := compose(in, "exec", "-T", "site", "node", "-e", js); err != nil {
		return errorf("sqlite copy: %v", err)
	}
	defer os.Remove(host)
	f, err := os.Open(host)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteTo(os.Stdout)
	return err
}

// runPull: ssh host fa info → ssh host fa dump > local file → rsync objects/.
func runPull(c *Ctx, args []string) error {
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return errorf("usage: fa pull user@box [--to DIR] [--keep N]   (add --dir on the fa side if the install isn't auto-found there)")
	}
	host := args[0]
	home, _ := os.UserHomeDir()
	to := filepath.Join(home, "Backups", "fartingaround")
	keep := 14
	for i := 1; i < len(args); i++ {
		switch {
		case args[i] == "--to" && i+1 < len(args):
			to = args[i+1]
			i++
		case args[i] == "--keep" && i+1 < len(args):
			keep, _ = strconv.Atoi(args[i+1])
			i++
		}
	}
	if !sh.Have("ssh") || !sh.Have("rsync") {
		return errorf("fa pull needs ssh and rsync on this machine")
	}
	remoteFa := "fa"
	if c.Dir != "" {
		remoteFa += " --dir " + shellQuote(c.Dir)
	}

	say("asking %s about the install…", host)
	out, err := sh.OutTimeout(30*time.Second, "", "ssh", host, remoteFa+" info")
	if err != nil {
		if strings.Contains(err.Error(), "unknown command") {
			return errorf("the fa on %s is older than this one and lacks `info`/`dump` — update it (scp dist/fa-linux-amd64 %s:~/fa && ssh %s sudo install -m 755 fa /usr/local/bin/fa)", host, host, host)
		}
		return errorf("%v\n(is fa installed on %s and the install findable? try: ssh %s fa status)", err, host, host)
	}
	var info infoOut
	if err := json.Unmarshal([]byte(out), &info); err != nil {
		return errorf("unexpected reply from remote fa: %s", out)
	}
	if info.Version != "" && info.Version != c.Version {
		warn("server fa is %s, this is %s — keep them in step", info.Version, c.Version)
	}
	if err := os.MkdirAll(filepath.Join(to, "db"), 0o755); err != nil {
		return err
	}

	// 1. database
	stamp := time.Now().Format("20060102-150405")
	ext := ".db"
	if info.Postgres {
		ext = ".sql"
	}
	dbFile := filepath.Join(to, "db", "site-"+stamp+ext)
	f, err := os.Create(dbFile + ".part")
	if err != nil {
		return err
	}
	say("dumping database…")
	err = sh.ToFile("", f, "ssh", host, remoteFa+" dump")
	f.Close()
	if err != nil {
		os.Remove(dbFile + ".part")
		return err
	}
	os.Rename(dbFile+".part", dbFile)
	if st, err := os.Stat(dbFile); err == nil {
		ok("database → %s (%s)", dbFile, humanBytes(uint64(st.Size())))
	}
	if keep > 0 {
		m, _ := filepath.Glob(filepath.Join(to, "db", "site-*"))
		sort.Strings(m)
		for len(m) > keep {
			os.Remove(m[0])
			m = m[1:]
		}
	}

	// 2. media — incremental. --delete keeps the mirror honest when a story's
	// media is removed on the site; the DB dumps are the history.
	say("syncing media (only what changed)…")
	src := host + ":" + strings.TrimRight(info.DataPath, "/") + "/objects/"
	// Flags limited to what both rsync and macOS's openrsync accept.
	rs := exec.Command("rsync", "-az", "--delete", "--partial", "--stats", src, filepath.Join(to, "objects")+"/")
	rs.Stdout, rs.Stderr = os.Stdout, os.Stderr
	if err := rs.Run(); err != nil {
		return errorf("rsync: %v", err)
	}
	_ = os.WriteFile(filepath.Join(to, "LAST_PULL"), []byte(stamp+" "+info.Commit+" "+host+"\n"), 0o644)
	ok("mirror at %s is current (site commit %s)", to, info.Commit)
	say("restore = put objects/ back on the data path and the newest db/ file as site.db (or psql < …sql)")
	return nil
}
