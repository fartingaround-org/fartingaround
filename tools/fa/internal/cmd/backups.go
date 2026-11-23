package cmd

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// `fa backups`: off-server backups with restic, driven through resticprofile.
//
// The server has ONE resticprofile setup, shared by every site on it:
//
//	~/.config/resticprofile/profiles.json   one "base" profile (where the
//	                                        backups go, and the keys for it)
//	                                        plus a profile per site
//	~/.config/resticprofile/password.txt    the repository password
//
// fa owns only this site's profile in that file and leaves the others alone
// (osa keeps Outer Spiral Arm's). JSON rather than YAML because fa is
// standard library only, and resticprofile reads either.
//
// What a snapshot holds is the same as the tarball of `fa backup`: a
// consistent copy of the database, made by a hook just before restic reads
// it (`fa backups db-dump`), and the objects folder. Scheduling is
// resticprofile's, written into the user's crontab: no daemon.
//
// `fa backup` (the quick tarball on the server) is separate and unchanged.

func init() {
	register(&command{name: "backups", summary: "off-server backups (restic): fa backups <show|setup|set|run|list|restore> …", run: runBackups})
}

const (
	rpBase          = "base"
	rpDefaultSched  = "*-*-* 03:30:00"
	dbDumpDir       = ".fa-db"
	resticInstall   = "sudo apt install restic        (or see https://restic.net)"
	rprofileInstall = "curl -sfL https://raw.githubusercontent.com/creativeprojects/resticprofile/master/install.sh | sh -s -- -b ~/.local/bin"
)

// resticDir is the shared resticprofile folder (FA_RESTIC_DIR overrides, for tests).
func resticDir() string {
	if d := os.Getenv("FA_RESTIC_DIR"); d != "" {
		return d
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "resticprofile")
}

// profileName is this site's profile in the shared file.
func profileName(in *install.Install) string {
	if in.Settings.BackupProfile != "" {
		return in.Settings.BackupProfile
	}
	return "fartingaround"
}

// ── the shared config file ──────────────────────────────────────────────────

// rpConfig is profiles.json, kept as a plain map so that everything fa does
// not know about (another site's profile, a key added by hand) survives.
type rpConfig map[string]any

func rpPath(dir string) string { return filepath.Join(dir, "profiles.json") }

func loadRP(dir string) (rpConfig, error) {
	b, err := os.ReadFile(rpPath(dir))
	if os.IsNotExist(err) {
		return rpConfig{}, nil
	}
	if err != nil {
		return nil, err
	}
	c := rpConfig{}
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, errorf("%s is not valid JSON (%v) — fix or move it aside, then run this again", rpPath(dir), err)
	}
	return c, nil
}

// save writes the file whole, 0600 (it can hold storage keys), by rename.
func (c rpConfig) save(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp := rpPath(dir) + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, rpPath(dir))
}

// sect returns m[key] as a map, creating it when missing or not a map.
func sect(m map[string]any, key string) map[string]any {
	if s, ok := m[key].(map[string]any); ok {
		return s
	}
	s := map[string]any{}
	m[key] = s
	return s
}

// has reports whether m[key] is a map (without creating it).
func has(m map[string]any, key string) bool {
	_, ok := m[key].(map[string]any)
	return ok
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func num(v any, def int) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	}
	return def
}

// ensureProfile writes what fa owns: the globals it relies on, the pointer to
// the password file, and this site's profile. It never touches where the
// backups go (base.repository, base.env) or settings the owner has changed
// (schedule, retention), only fills them in when absent.
func ensureProfile(c rpConfig, dir, name, dataPath, hook, resticBin string) {
	c["version"] = "1"
	g := sect(c, "global")
	// crontab, not systemd units: works for an ordinary user with no lingering session.
	if g["scheduler"] == nil {
		g["scheduler"] = "crond"
	}
	// cron's PATH is short; say where restic is when it is somewhere unusual.
	if resticBin != "" && !strings.HasPrefix(resticBin, "/usr/bin/") && !strings.HasPrefix(resticBin, "/bin/") {
		g["restic-binary"] = resticBin
	}
	base := sect(c, rpBase)
	base["password-file"] = filepath.Join(dir, "password.txt")
	base["initialize"] = true

	p := sect(c, name)
	p["inherit"] = rpBase
	p["lock"] = filepath.Join(dir, name+".lock")
	p["status-file"] = filepath.Join(dir, name+".status.json")
	b := sect(p, "backup")
	b["source"] = []any{filepath.Join(dataPath, "objects"), filepath.Join(dataPath, dbDumpDir)}
	// The tag is how this site's snapshots are told from the others in the shared repository.
	b["tag"] = []any{name}
	b["run-before"] = []any{hook}
	b["schedule-permission"] = "user"
	b["schedule-lock-wait"] = "45m"
	if _, set := b["schedule"]; !set {
		b["schedule"] = rpDefaultSched
	}
	r := sect(p, "retention")
	for k, v := range map[string]any{"after-backup": true, "keep-daily": 7, "keep-weekly": 4, "keep-monthly": 6, "prune": true, "tag": true} {
		if _, set := r[k]; !set {
			r[k] = v
		}
	}
}

var timeOfDay = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)

// scheduleFor turns "03:30" into resticprofile's calendar form; "" for off.
func scheduleFor(v string) (string, error) {
	if v == "off" || v == "" {
		return "", nil
	}
	if !timeOfDay.MatchString(v) {
		return "", errorf("a time of day like 03:30 (24-hour, the server's clock), or off")
	}
	return "*-*-* " + v + ":00", nil
}

// timeFromSchedule is the reverse, for showing: "*-*-* 03:30:00" → "03:30".
// Anything set by hand in another form is shown as it is.
func timeFromSchedule(s string) string {
	if m := regexp.MustCompile(`^\*-\*-\* (\d\d:\d\d):00$`).FindStringSubmatch(s); m != nil {
		return m[1]
	}
	return s
}

var repoScheme = regexp.MustCompile(`^(s3|b2|sftp|rest|azure|gs|swift|rclone|local):.`)

// checkRepo refuses an address restic would quietly take for a folder name.
// Anything without a scheme is a path to restic, so a bare host name such as
// s3.us-west-001.backblazeb2.com/bucket "works": it makes a folder of that
// name on this server and backs up into it.
func checkRepo(r string) error {
	if repoScheme.MatchString(r) || strings.HasPrefix(r, "/") {
		return nil
	}
	if strings.Contains(r, ".") && !strings.ContainsAny(r, " ") {
		return errorf("%q has no kind in front, so restic would treat it as a folder on this server and back up into that. For S3-style storage (Backblaze's S3 address, R2, Spaces) write it as:\n    s3:https://%s\nwith the bucket and a folder after the host, like s3:https://host/my-bucket/restic", r, strings.TrimPrefix(strings.TrimPrefix(r, "https://"), "http://"))
	}
	return errorf("%q is not an address restic knows. It starts with the kind of storage (s3:https://host/bucket/folder · b2:bucket:folder · sftp:user@host:/path · rest:https://host/repo), or is a full path beginning with / for a folder on this server", r)
}

var envKeyRe = regexp.MustCompile(`^[A-Z][A-Z0-9_]{0,63}$`)

// maskSecret keeps the ends of a value so it can be recognised, not read.
func maskSecret(v string) string {
	if len(v) <= 8 {
		return "•••••"
	}
	return v[:3] + "•••••" + v[len(v)-3:]
}

// maskRepo hides a password embedded in a repository address (rest:https://user:pass@host/…).
func maskRepo(r string) string {
	return regexp.MustCompile(`(://[^:/@\s]+:)[^@\s]+@`).ReplaceAllString(r, "${1}•••••@")
}

// ── running resticprofile ───────────────────────────────────────────────────

func needTools() error {
	var missing []string
	if !sh.Have("restic") {
		missing = append(missing, "restic — install it with:\n      "+resticInstall)
	}
	if !sh.Have("resticprofile") {
		missing = append(missing, "resticprofile — install it with:\n      "+rprofileInstall)
	}
	if len(missing) > 0 {
		return errorf("backups need two programs on this server that are not here yet:\n    %s", strings.Join(missing, "\n    "))
	}
	return nil
}

func rpArgs(in *install.Install, args ...string) []string {
	return append([]string{"--config", rpPath(resticDir()), "--name", profileName(in), "--no-ansi"}, args...)
}

// configured: the tools are here, and the shared file has somewhere to send
// backups and this site's profile.
func configured(in *install.Install) (rpConfig, error) {
	if err := needTools(); err != nil {
		return nil, err
	}
	c, err := loadRP(resticDir())
	if err != nil {
		return nil, err
	}
	if str(sect(c, rpBase)["repository"]) == "" || !has(c, profileName(in)) {
		return nil, errorf("backups are not set up on this server yet: fa backups setup --repo <where they go>")
	}
	return c, nil
}

// extendPath adds the places a user installs programs to this process's PATH.
// A command arriving over ssh (which is how the dashboard runs everything)
// gets the system's short PATH, without ~/.local/bin, and that is exactly
// where resticprofile's own installer puts it.
func extendPath() {
	home, _ := os.UserHomeDir()
	path := os.Getenv("PATH")
	have := map[string]bool{}
	for _, d := range filepath.SplitList(path) {
		have[d] = true
	}
	for _, d := range []string{filepath.Join(home, ".local", "bin"), filepath.Join(home, "bin"), "/usr/local/bin"} {
		if home != "" && !have[d] {
			path += string(os.PathListSeparator) + d
		}
	}
	os.Setenv("PATH", path)
}

func runBackups(c *Ctx, args []string) error {
	extendPath()
	if len(args) == 0 {
		args = []string{"show"}
	}
	switch args[0] {
	case "show":
		return backupsShow(c.In, len(args) > 1 && args[1] == "--json")
	case "setup":
		return backupsSetup(c.In, args[1:])
	case "set":
		return backupsSet(c.In, args[1:])
	case "run":
		if _, err := configured(c.In); err != nil {
			return err
		}
		// restic only reports progress to a terminal unless told how often;
		// this makes it print a line every 20 seconds for the dashboard.
		if os.Getenv("RESTIC_PROGRESS_FPS") == "" {
			os.Setenv("RESTIC_PROGRESS_FPS", "0.05")
		}
		err := sh.Pass("", "resticprofile", rpArgs(c.In, "backup")...)
		if err != nil {
			// resticprofile removes its lock when it exits, so one still here
			// after ours failed belongs to another run: we were turned away.
			if _, e := os.Stat(filepath.Join(resticDir(), profileName(c.In)+".lock")); e == nil {
				return errorf("a backup of this site is already running, so this one did not start. Nothing is wrong: let the running one finish, then refresh.")
			}
		}
		return err
	case "list":
		return backupsList(c.In, len(args) > 1 && args[1] == "--json")
	case "restore":
		return backupsRestore(c.In, args[1:])
	case "db-dump":
		return backupsDBDump(c.In)
	}
	return errorf("usage: fa backups <show|setup|set|run|list|restore>\n" +
		"  setup --repo ADDRESS [--env KEY=VALUE]…   first time: where backups go, and the keys for it\n" +
		"  set schedule 03:30|off                    when the daily backup runs (the server's clock)\n" +
		"  set keep daily=7 weekly=4 monthly=6       how many to keep\n" +
		"  set repo ADDRESS · set env KEY=VALUE      change the destination or a key (KEY= removes it)\n" +
		"  run                                       back up now\n" +
		"  list                                      the snapshots of this site\n" +
		"  restore [--yes] ID|latest                 put the site back as it was in a snapshot")
}

// ── setup ───────────────────────────────────────────────────────────────────

func backupsSetup(in *install.Install, args []string) error {
	if err := needTools(); err != nil {
		return err
	}
	repo, env := "", map[string]string{}
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--repo" && i+1 < len(args):
			repo = args[i+1]
			i++
		case args[i] == "--env" && i+1 < len(args):
			k, v, found := strings.Cut(args[i+1], "=")
			if !found || !envKeyRe.MatchString(k) {
				return errorf("--env takes KEY=VALUE (capitals, digits, underscores), got %q", args[i+1])
			}
			env[k] = v
			i++
		default:
			return errorf("usage: fa backups setup --repo ADDRESS [--env KEY=VALUE]…")
		}
	}
	dir := resticDir()
	c, err := loadRP(dir)
	if err != nil {
		return err
	}
	base := sect(c, rpBase)
	if repo != "" {
		if err := checkRepo(repo); err != nil {
			return err
		}
		base["repository"] = repo
	}
	if str(base["repository"]) == "" {
		return errorf("say where the backups go: fa backups setup --repo ADDRESS\n" +
			"  s3:https://nyc3.digitaloceanspaces.com/my-bucket/backups   (with --env AWS_ACCESS_KEY_ID=… --env AWS_SECRET_ACCESS_KEY=…)\n" +
			"  b2:my-bucket:backups                                        (with --env B2_ACCOUNT_ID=… --env B2_ACCOUNT_KEY=…)\n" +
			"  sftp:user@host:/srv/backups                                 (this server's ssh key must be accepted there)\n" +
			"  /mnt/other-disk/backups                                     (a folder; only as good as the disk it is on)")
	}
	if len(env) > 0 {
		e := sect(base, "env")
		for k, v := range env {
			e[k] = v
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}

	// The repository password. Made once, here; every site on the server shares it.
	pw := filepath.Join(dir, "password.txt")
	madePassword := false
	if !fileExists(pw) {
		b := make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			return err
		}
		if err := os.WriteFile(pw, []byte(base64.RawURLEncoding.EncodeToString(b)+"\n"), 0o600); err != nil {
			return err
		}
		madePassword = true
	}

	self, err := os.Executable()
	if err != nil {
		return err
	}
	hook := shellQuote(self) + " --dir " + shellQuote(in.Dir) + " backups db-dump"
	resticBin, _ := exec.LookPath("restic")
	ensureProfile(c, dir, profileName(in), in.DataPath(), hook, resticBin)
	if err := c.save(dir); err != nil {
		return err
	}
	ok("wrote %s (profile %q)", rpPath(dir), profileName(in))

	// Create the repository if this is the first site to use it.
	if out, err := sh.Out("", "resticprofile", rpArgs(in, "init")...); err != nil {
		msg := err.Error() + out
		if !strings.Contains(msg, "already initialized") && !strings.Contains(msg, "already exists") {
			if strings.Contains(msg, "another process is already running this profile") {
				return errorf("the settings are saved, but a backup of this site is running right now, so the new place could not be checked yet. Let it finish (or stop it), then save again.\n%s", lastLines(msg, 2))
			}
			// restic's own reason is what matters, and resticprofile passes it on
			// through its output, not always through stderr: show the end of both.
			return errorf("could not reach or create the repository. What restic said:\n%s", lastLines(strings.TrimSpace(out+"\n"+err.Error()), 8))
		}
		ok("repository already there")
	} else {
		ok("repository created")
	}
	if err := applySchedule(in, c); err != nil {
		warn("the schedule was not installed: %v", err)
	}
	if madePassword {
		say("")
		say("  The repository password is in %s", pw)
		say("  COPY IT SOMEWHERE ELSE NOW (a password manager). Without it the backups cannot be")
		say("  read by anyone, you included, and it exists only on this server:")
		say("      cat %s", pw)
	}
	say("")
	say("next: fa backups run     (the first one copies everything; later ones only what changed)")
	return nil
}

// lastLines is the last n lines of s that have anything on them.
func lastLines(s string, n int) string {
	var keep []string
	for _, l := range strings.Split(s, "\n") {
		if strings.TrimSpace(l) != "" {
			keep = append(keep, "    "+strings.TrimRight(l, " \t\r"))
		}
	}
	if len(keep) > n {
		keep = keep[len(keep)-n:]
	}
	return strings.Join(keep, "\n")
}

// applySchedule makes the crontab match the profile.
//
// It never runs `resticprofile unschedule`. Since resticprofile 0.30 that
// command, given one profile, also removes the scheduled jobs of every other
// profile in the same file, and the file is shared by every site on the
// server: setting one site's schedule silently switched the others' backups
// off. `schedule` on its own replaces this profile's line and leaves the
// rest, on old versions and new. Switching a schedule off takes this
// profile's line out of the crontab directly.
func applySchedule(in *install.Install, c rpConfig) error {
	name := profileName(in)
	if str(sect(sect(c, name), "backup")["schedule"]) == "" {
		if err := removeScheduleLine(name); err != nil {
			return err
		}
		ok("no schedule: backups run only when asked")
		return nil
	}
	if _, err := sh.Out("", "resticprofile", rpArgs(in, "schedule")...); err != nil {
		return err
	}
	if !scheduleInstalled(name) {
		return errorf("resticprofile reported the schedule as set, but the crontab has no line for %s (crontab -l)", name)
	}
	ok("scheduled: every day at %s (server time)", timeFromSchedule(str(sect(sect(c, name), "backup")["schedule"])))
	return nil
}

// scheduleMark is what identifies a profile's line in the crontab.
func scheduleMark(profile string) string { return "run-schedule backup@" + profile }

// isScheduleLine: a live (not commented-out) crontab line for this profile.
func isScheduleLine(line, profile string) bool {
	t := strings.TrimSpace(line)
	return t != "" && !strings.HasPrefix(t, "#") && strings.HasSuffix(t, scheduleMark(profile))
}

// crontab returns the user's crontab; none at all is an empty one.
func crontab() []string {
	out, err := exec.Command("crontab", "-l").Output()
	if err != nil {
		return nil
	}
	return strings.Split(strings.TrimRight(string(out), "\n"), "\n")
}

// scheduleInstalled says whether cron really has this profile's backup: the
// setting in the profile is only what was asked for.
func scheduleInstalled(profile string) bool {
	for _, l := range crontab() {
		if isScheduleLine(l, profile) {
			return true
		}
	}
	return false
}

// withoutSchedule drops one profile's line and keeps every other line as it is.
func withoutSchedule(lines []string, profile string) ([]string, bool) {
	var kept []string
	found := false
	for _, l := range lines {
		if isScheduleLine(l, profile) {
			found = true
			continue
		}
		kept = append(kept, l)
	}
	return kept, found
}

func removeScheduleLine(profile string) error {
	kept, found := withoutSchedule(crontab(), profile)
	if !found {
		return nil
	}
	c := exec.Command("crontab", "-")
	c.Stdin = strings.NewReader(strings.Join(kept, "\n") + "\n")
	if out, err := c.CombinedOutput(); err != nil {
		return errorf("could not take the backup line out of the crontab: %v %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ── set ─────────────────────────────────────────────────────────────────────

func backupsSet(in *install.Install, args []string) error {
	if len(args) < 2 {
		return errorf("usage: fa backups set schedule 03:30|off · set keep daily=7 weekly=4 monthly=6 · set repo ADDRESS · set env KEY=VALUE")
	}
	c, err := configured(in)
	if err != nil {
		return err
	}
	dir := resticDir()
	p := sect(c, profileName(in))
	reschedule := false
	switch args[0] {
	case "schedule":
		s, err := scheduleFor(args[1])
		if err != nil {
			return err
		}
		b := sect(p, "backup")
		if s == "" {
			delete(b, "schedule")
		} else {
			b["schedule"] = s
		}
		reschedule = true
	case "keep":
		r := sect(p, "retention")
		for _, a := range args[1:] {
			k, v, found := strings.Cut(a, "=")
			n, err := strconv.Atoi(v)
			if !found || err != nil || n < 0 || n > 999 || (k != "daily" && k != "weekly" && k != "monthly") {
				return errorf("keep takes daily=N weekly=N monthly=N, got %q", a)
			}
			r["keep-"+k] = n
		}
		if num(r["keep-daily"], 0)+num(r["keep-weekly"], 0)+num(r["keep-monthly"], 0) == 0 {
			return errorf("keeping nothing would delete every snapshot at the next backup — leave at least one above zero")
		}
	case "repo":
		if err := checkRepo(args[1]); err != nil {
			return err
		}
		sect(c, rpBase)["repository"] = args[1]
		warn("every site on this server now backs up to the new address. Snapshots in the old one stay there and are no longer listed.")
	case "env":
		k, v, found := strings.Cut(args[1], "=")
		if !found || !envKeyRe.MatchString(k) {
			return errorf("env takes KEY=VALUE (KEY= removes it), got %q", args[1])
		}
		e := sect(sect(c, rpBase), "env")
		if v == "" {
			delete(e, k)
		} else {
			e[k] = v
		}
	default:
		return errorf("fa backups set: unknown setting %q", args[0])
	}
	if err := c.save(dir); err != nil {
		return err
	}
	ok("saved")
	if reschedule {
		return applySchedule(in, c)
	}
	return nil
}

// ── show ────────────────────────────────────────────────────────────────────

type backupsInfo struct {
	Configured bool              `json:"configured"`
	Missing    []string          `json:"missing,omitempty"` // programs not installed
	Profile    string            `json:"profile"`
	Config     string            `json:"config"`
	Repository string            `json:"repository"`
	Env        map[string]string `json:"env"` // values masked
	Schedule   string            `json:"schedule"`
	// ScheduleMissing: a schedule is set, but cron has no line for it, so
	// nothing will run. Seen when another site's setup removed it.
	ScheduleMissing bool           `json:"scheduleMissing,omitempty"`
	Keep            map[string]int `json:"keep"`
	Sources         []string       `json:"sources"`
	Last            *backupsLast   `json:"last,omitempty"`
	Others          []string       `json:"others,omitempty"` // other sites' profiles in the same file
}

type backupsLast struct {
	Success  bool   `json:"success"`
	Time     string `json:"time"`
	Error    string `json:"error,omitempty"`
	Duration int    `json:"duration"` // seconds
}

func gatherBackups(in *install.Install) (*backupsInfo, error) {
	dir, name := resticDir(), profileName(in)
	info := &backupsInfo{Profile: name, Config: rpPath(dir), Env: map[string]string{}, Keep: map[string]int{}}
	for _, t := range []string{"restic", "resticprofile"} {
		if !sh.Have(t) {
			info.Missing = append(info.Missing, t)
		}
	}
	c, err := loadRP(dir)
	if err != nil {
		return nil, err
	}
	for k, v := range c {
		if _, isProfile := v.(map[string]any); isProfile && k != "global" && k != rpBase && k != name && k != "groups" {
			info.Others = append(info.Others, k)
		}
	}
	sort.Strings(info.Others)
	if !has(c, rpBase) || !has(c, name) {
		return info, nil
	}
	base, p := sect(c, rpBase), sect(c, name)
	info.Repository = maskRepo(str(base["repository"]))
	info.Configured = info.Repository != "" && len(info.Missing) == 0
	if e, ok := base["env"].(map[string]any); ok {
		for k, v := range e {
			info.Env[k] = maskSecret(str(v))
		}
	}
	b, r := sect(p, "backup"), sect(p, "retention")
	info.Schedule = timeFromSchedule(str(b["schedule"]))
	info.ScheduleMissing = info.Schedule != "" && !scheduleInstalled(name)
	for _, k := range []string{"daily", "weekly", "monthly"} {
		info.Keep[k] = num(r["keep-"+k], 0)
	}
	if src, ok := b["source"].([]any); ok {
		for _, s := range src {
			info.Sources = append(info.Sources, str(s))
		}
	}
	// resticprofile's own record of how the last run went.
	if raw, err := os.ReadFile(str(p["status-file"])); err == nil {
		var st struct {
			Profiles map[string]struct {
				Backup *backupsLast `json:"backup"`
			} `json:"profiles"`
		}
		if json.Unmarshal(raw, &st) == nil {
			info.Last = st.Profiles[name].Backup
		}
	}
	return info, nil
}

func backupsShow(in *install.Install, asJSON bool) error {
	info, err := gatherBackups(in)
	if err != nil {
		return err
	}
	if asJSON {
		b, _ := json.Marshal(info)
		fmt.Println(string(b))
		return nil
	}
	for _, m := range info.Missing {
		bad("%s is not installed", m)
	}
	if info.Repository == "" {
		say("backups are not set up on this server: fa backups setup --repo <where they go>")
		return nil
	}
	say("backups for %s", info.Profile)
	say("  go to     %s", info.Repository)
	if len(info.Env) > 0 {
		keys := make([]string, 0, len(info.Env))
		for k := range info.Env {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		say("  keys      %s", strings.Join(keys, ", "))
	}
	if info.Schedule == "" {
		say("  schedule  off (only when asked)")
	} else {
		say("  schedule  every day at %s, server time", info.Schedule)
	}
	if info.ScheduleMissing {
		bad("the schedule is set but is not in the crontab, so no backup will run by itself. Put it back with: fa backups set schedule %s", info.Schedule)
	}
	say("  keep      %d daily, %d weekly, %d monthly", info.Keep["daily"], info.Keep["weekly"], info.Keep["monthly"])
	switch {
	case info.Last == nil:
		say("  last run  none recorded yet")
	case info.Last.Success:
		ok("last run %s, took %ds", info.Last.Time, info.Last.Duration)
	default:
		bad("last run %s FAILED: %s", info.Last.Time, info.Last.Error)
	}
	if len(info.Others) > 0 {
		say("  also in %s: %s", info.Config, strings.Join(info.Others, ", "))
	}
	return nil
}

// ── list ────────────────────────────────────────────────────────────────────

type snapshot struct {
	ID      string    `json:"id"`
	ShortID string    `json:"short_id"`
	Time    time.Time `json:"time"`
	Host    string    `json:"hostname"`
	Paths   []string  `json:"paths"`
	Summary *struct {
		Size  uint64 `json:"total_bytes_processed"`
		Files uint64 `json:"total_files_processed"`
	} `json:"summary,omitempty"`
}

// parseSnapshots finds restic's JSON array in the output; resticprofile may
// print lines of its own around it.
func parseSnapshots(out string) ([]snapshot, error) {
	var snaps []snapshot
	if json.Unmarshal([]byte(out), &snaps) == nil {
		return snaps, nil
	}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "[") && json.Unmarshal([]byte(line), &snaps) == nil {
			return snaps, nil
		}
	}
	return nil, errorf("could not read the snapshot list restic returned")
}

func listSnapshots(in *install.Install) ([]snapshot, error) {
	out, err := sh.Out("", "resticprofile", rpArgs(in, "snapshots", "--json", "--tag", profileName(in))...)
	if err != nil {
		// resticprofile lets one command at a time use a profile, reading included.
		if _, e := os.Stat(filepath.Join(resticDir(), profileName(in)+".lock")); e == nil {
			return nil, errorf("a backup of this site is running; the snapshots can be listed when it finishes")
		}
		return nil, err
	}
	snaps, err := parseSnapshots(out)
	if err != nil {
		return nil, err
	}
	sort.Slice(snaps, func(i, j int) bool { return snaps[i].Time.After(snaps[j].Time) }) // newest first
	for i := range snaps {
		if snaps[i].ShortID == "" && len(snaps[i].ID) >= 8 {
			snaps[i].ShortID = snaps[i].ID[:8]
		}
	}
	return snaps, nil
}

func backupsList(in *install.Install, asJSON bool) error {
	if _, err := configured(in); err != nil {
		return err
	}
	snaps, err := listSnapshots(in)
	if err != nil {
		return err
	}
	if asJSON {
		b, _ := json.Marshal(snaps)
		fmt.Println(string(b))
		return nil
	}
	if len(snaps) == 0 {
		say("no snapshots yet: fa backups run")
		return nil
	}
	for _, s := range snaps {
		size := ""
		if s.Summary != nil {
			size = humanBytes(s.Summary.Size)
		}
		say("  %s  %s  %s", s.ShortID, s.Time.Local().Format("2006-01-02 15:04"), size)
	}
	say("restore one with: fa backups restore <id>   (or: latest)")
	return nil
}

// ── the hook: a consistent copy of the database for restic to read ──────────

// backupsDBDump runs just before every backup (run-before in the profile). The
// copy stays in <data>/.fa-db between runs: it is small, and restic only
// uploads what changed in it.
func backupsDBDump(in *install.Install) error {
	dp := in.DataPath()
	dir := filepath.Join(dp, dbDumpDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	_, _ = sh.Out("", "chown", "1000:1000", dir) // the container writes here as `node`
	if in.UsesPostgres() {
		tmp := filepath.Join(dir, "db.sql.tmp")
		if err := pgDump(in, tmp); err != nil {
			os.Remove(tmp)
			return err
		}
		os.Remove(filepath.Join(dir, "site.db")) // from a time this site ran on SQLite
		return os.Rename(tmp, filepath.Join(dir, "db.sql"))
	}
	// VACUUM INTO refuses an existing file; it gives a consistent copy with the site live.
	os.Remove(filepath.Join(dir, "site.db"))
	os.Remove(filepath.Join(dir, "db.sql"))
	js := `const {DatabaseSync}=require("node:sqlite");new DatabaseSync("/data/site.db",{readOnly:true}).exec("VACUUM INTO '/data/` + dbDumpDir + `/site.db'")`
	if _, err := compose(in, "exec", "-T", "site", "node", "-e", js); err != nil {
		return errorf("sqlite copy: %v", err)
	}
	return nil
}

// pgDump writes a dump that replaces what it is loaded over (--clean), via a
// throwaway postgres container so the host needs no client installed.
func pgDump(in *install.Install, path string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := sh.ToFile("", f, "docker", "run", "--rm", "--network", "web", "postgres:17-alpine",
		"pg_dump", "--clean", "--if-exists", "--no-owner", "--no-privileges", in.Env.Get("DATABASE_URL")); err != nil {
		return errorf("pg_dump: %v", err)
	}
	return nil
}

// pgLoad runs a dump in one transaction: it either all applies or nothing does.
func pgLoad(in *install.Install, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return sh.FromFile("", f, "docker", "run", "--rm", "-i", "--network", "web", "postgres:17-alpine",
		"psql", "--quiet", "-v", "ON_ERROR_STOP=1", "--single-transaction", in.Env.Get("DATABASE_URL"))
}

// ── restore ─────────────────────────────────────────────────────────────────

// findRestored locates the data folder inside what restic wrote: restic
// recreates the full original path under the target, and the original path
// is not necessarily today's (the data may have moved since the snapshot).
func findRestored(target string) (string, error) {
	found := ""
	_ = filepath.WalkDir(target, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		if d.Name() == dbDumpDir {
			found = filepath.Dir(p)
			return fs.SkipAll
		}
		if d.Name() == "objects" {
			return fs.SkipDir // nothing to find inside the media
		}
		return nil
	})
	if found == "" {
		return "", errorf("the snapshot has no database copy in it (no %s folder) — not a backup fa made", dbDumpDir)
	}
	return found, nil
}

func backupsRestore(in *install.Install, args []string) error {
	yes, id := false, ""
	for _, a := range args {
		if a == "--yes" {
			yes = true
		} else {
			id = a
		}
	}
	if id == "" || !regexp.MustCompile(`^(latest|[0-9a-f]{8,64})$`).MatchString(id) {
		return errorf("usage: fa backups restore [--yes] <snapshot id | latest>     (ids: fa backups list)")
	}
	if _, err := configured(in); err != nil {
		return err
	}
	name, dp := profileName(in), in.DataPath()
	stamp := time.Now().Format("20060102-150405")

	if !yes {
		say("This puts the site back as it was in snapshot %s:", id)
		say("  · the site stops while it happens")
		say("  · today's database and media are moved aside into %s, not deleted", filepath.Join(dp, ".replaced-"+stamp))
		say("  · anything posted since that snapshot will no longer be on the site")
		fmt.Print("Type yes to go ahead: ")
		line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		if strings.TrimSpace(line) != "yes" {
			return errorf("nothing was changed")
		}
	}

	// 1. Fetch the snapshot beside the live data (same disk, so the swap is a rename).
	target := filepath.Join(dp, ".restore-"+stamp)
	if err := os.MkdirAll(target, 0o755); err != nil {
		return err
	}
	defer os.RemoveAll(target)
	say("fetching snapshot %s…", id)
	if err := sh.Pass("", "resticprofile", rpArgs(in, "restore", id, "--tag", name, "--target", target)...); err != nil {
		return errorf("the snapshot could not be fetched; nothing on the site was changed (%v)", err)
	}
	root, err := findRestored(target)
	if err != nil {
		return err
	}
	// 2. Check it is whole, and for the database this site runs on, before touching anything live.
	newDB := filepath.Join(root, dbDumpDir, "site.db")
	if in.UsesPostgres() {
		newDB = filepath.Join(root, dbDumpDir, "db.sql")
	}
	if !fileExists(newDB) {
		return errorf("the snapshot's database copy is not the kind this site runs on now (looked for %s); nothing was changed", filepath.Base(newDB))
	}
	newObjects := filepath.Join(root, "objects")
	if !fileExists(newObjects) {
		return errorf("the snapshot has no objects folder; nothing was changed")
	}

	// 3. Stop, move today's data aside, put the snapshot in its place.
	say("stopping the site…")
	if err := composePass(in, "stop", "site"); err != nil {
		return err
	}
	aside := filepath.Join(dp, ".replaced-"+stamp)
	if err := os.MkdirAll(aside, 0o755); err != nil {
		return err
	}
	liveObjects := filepath.Join(dp, "objects")
	if in.UsesPostgres() {
		if err := pgDump(in, filepath.Join(aside, "db.sql")); err != nil {
			_ = composePass(in, "start", "site")
			return errorf("could not save today's database before replacing it, so nothing was changed: %v", err)
		}
		if err := pgLoad(in, newDB); err != nil {
			// One transaction: the database is as it was. Bring the site back untouched.
			_ = composePass(in, "start", "site")
			return errorf("the snapshot's database would not load, so nothing was changed: %v", err)
		}
	} else {
		for _, f := range []string{"site.db", "site.db-wal", "site.db-shm"} {
			_ = os.Rename(filepath.Join(dp, f), filepath.Join(aside, f))
		}
		if err := os.Rename(newDB, filepath.Join(dp, "site.db")); err != nil {
			return errorf("%v — today's data is in %s", err, aside)
		}
	}
	_ = os.Rename(liveObjects, filepath.Join(aside, "objects"))
	if err := os.Rename(newObjects, liveObjects); err != nil {
		return errorf("%v — today's data is in %s", err, aside)
	}
	if _, err := sh.Out("", "chown", "-R", "1000:1000", liveObjects); err != nil {
		warn("could not hand the restored files to the site's user (%v) — if photos fail to load: sudo chown -R 1000:1000 %s", err, dp)
	}
	_, _ = sh.Out("", "chown", "1000:1000", filepath.Join(dp, "site.db"))

	// 4. Back up.
	if err := composePass(in, "start", "site"); err != nil {
		return err
	}
	if err := waitHealthy(in, 90*time.Second); err != nil {
		return errorf("%v — the snapshot is in place but the site is not answering. Today's data is in %s", err, aside)
	}
	ok("restored snapshot %s", id)
	say("  What was there before is in %s", aside)
	say("  Look the site over, then delete that folder to get the space back.")
	return nil
}
