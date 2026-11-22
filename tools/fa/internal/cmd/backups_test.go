package cmd

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
)

func TestEnsureProfileKeepsWhatItDoesNotOwn(t *testing.T) {
	dir := t.TempDir()
	c := rpConfig{
		// Another site's profile, and a destination set earlier: fa must leave both alone.
		"outerspiralarm": map[string]any{"inherit": "base", "backup": map[string]any{"source": []any{"/srv/atlas"}}},
		"base":           map[string]any{"repository": "s3:https://example.test/bucket", "env": map[string]any{"AWS_ACCESS_KEY_ID": "abc"}},
	}
	ensureProfile(c, dir, "fartingaround", "/mnt/data", "/usr/local/bin/fa --dir /home/t/site backups db-dump", "/usr/bin/restic")

	if got := str(sect(c, "base")["repository"]); got != "s3:https://example.test/bucket" {
		t.Fatalf("repository changed: %q", got)
	}
	if got := str(sect(sect(c, "base"), "env")["AWS_ACCESS_KEY_ID"]); got != "abc" {
		t.Fatalf("env changed: %q", got)
	}
	if !reflect.DeepEqual(sect(sect(c, "outerspiralarm"), "backup")["source"], []any{"/srv/atlas"}) {
		t.Fatal("the other site's profile was touched")
	}
	b := sect(sect(c, "fartingaround"), "backup")
	want := []any{"/mnt/data/objects", "/mnt/data/.fa-db"}
	if !reflect.DeepEqual(b["source"], want) {
		t.Fatalf("source = %v, want %v", b["source"], want)
	}
	if str(b["schedule"]) != rpDefaultSched {
		t.Fatalf("default schedule = %q", b["schedule"])
	}
	if _, set := sect(c, "global")["restic-binary"]; set {
		t.Fatal("restic in /usr/bin needs no restic-binary")
	}

	// A schedule and retention the owner changed survive a second setup.
	b["schedule"] = "*-*-* 01:15:00"
	sect(sect(c, "fartingaround"), "retention")["keep-daily"] = 30
	ensureProfile(c, dir, "fartingaround", "/mnt/data", "hook", "/home/t/.local/bin/restic")
	if str(sect(sect(c, "fartingaround"), "backup")["schedule"]) != "*-*-* 01:15:00" {
		t.Fatal("setup reset the schedule")
	}
	if num(sect(sect(c, "fartingaround"), "retention")["keep-daily"], 0) != 30 {
		t.Fatal("setup reset the retention")
	}
	if str(sect(c, "global")["restic-binary"]) != "/home/t/.local/bin/restic" {
		t.Fatal("restic outside cron's PATH must be named")
	}
}

func TestConfigRoundTrip(t *testing.T) {
	dir := t.TempDir()
	c := rpConfig{}
	ensureProfile(c, dir, "fartingaround", "/mnt/data", "hook", "")
	sect(c, "base")["repository"] = "/mnt/backups"
	if err := c.save(dir); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(filepath.Join(dir, "profiles.json"))
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("profiles.json must be 0600 (it can hold storage keys): %v %v", st.Mode().Perm(), err)
	}
	back, err := loadRP(dir)
	if err != nil {
		t.Fatal(err)
	}
	// Numbers come back as float64 from JSON; num must read both.
	if num(sect(sect(back, "fartingaround"), "retention")["keep-weekly"], 0) != 4 {
		t.Fatal("keep-weekly lost in the round trip")
	}
	if str(sect(back, "base")["repository"]) != "/mnt/backups" {
		t.Fatal("repository lost in the round trip")
	}
}

func TestSchedule(t *testing.T) {
	for in, want := range map[string]string{"03:30": "*-*-* 03:30:00", "23:59": "*-*-* 23:59:00", "off": "", "": ""} {
		got, err := scheduleFor(in)
		if err != nil || got != want {
			t.Errorf("scheduleFor(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"3:30", "24:00", "noon", "03:60", "03:30; rm -rf /"} {
		if _, err := scheduleFor(in); err == nil {
			t.Errorf("scheduleFor(%q) should be refused", in)
		}
	}
	if timeFromSchedule("*-*-* 03:30:00") != "03:30" || timeFromSchedule("Mon *-*-* 02:00:00") != "Mon *-*-* 02:00:00" {
		t.Error("timeFromSchedule")
	}
}

func TestMasking(t *testing.T) {
	if got := maskRepo("rest:https://tony:hunter2secret@backup.example.test/repo"); got != "rest:https://tony:•••••@backup.example.test/repo" {
		t.Errorf("maskRepo = %q", got)
	}
	if got := maskRepo("s3:https://nyc3.digitaloceanspaces.com/bucket"); got != "s3:https://nyc3.digitaloceanspaces.com/bucket" {
		t.Errorf("maskRepo changed an address with no password: %q", got)
	}
	if got := maskSecret("DO00ABCDEFGHIJKLMNOP"); got != "DO0•••••NOP" {
		t.Errorf("maskSecret = %q", got)
	}
	if got := maskSecret("short"); got != "•••••" {
		t.Errorf("a short secret must be hidden whole: %q", got)
	}
}

func TestParseSnapshots(t *testing.T) {
	out := "2026/11/19 03:30:00 profile 'fartingaround': starting 'snapshots'\n" +
		`[{"time":"2026-11-18T09:30:00.5Z","hostname":"box","paths":["/mnt/data/objects"],"id":"aaaaaaaabbbbbbbbccccccccdddddddd","short_id":"aaaaaaaa","summary":{"total_bytes_processed":1048576}}]` + "\n"
	snaps, err := parseSnapshots(out)
	if err != nil || len(snaps) != 1 || snaps[0].ShortID != "aaaaaaaa" || snaps[0].Summary.Size != 1048576 {
		t.Fatalf("parseSnapshots: %v %+v", err, snaps)
	}
	if _, err := parseSnapshots("Fatal: unable to open repository"); err == nil {
		t.Fatal("output with no list in it must be an error, not an empty list")
	}
	if snaps, err := parseSnapshots("[]"); err != nil || len(snaps) != 0 {
		t.Fatalf("an empty repository is an empty list: %v %v", snaps, err)
	}
}

func TestFindRestored(t *testing.T) {
	target := t.TempDir()
	// restic recreates the original absolute path under the target.
	root := filepath.Join(target, "mnt", "volume_nyc3_01", "fartingaround")
	for _, d := range []string{filepath.Join(root, "objects", "images"), filepath.Join(root, ".fa-db")} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	got, err := findRestored(target)
	if err != nil || got != root {
		t.Fatalf("findRestored = %q, %v; want %q", got, err, root)
	}
	if _, err := findRestored(t.TempDir()); err == nil {
		t.Fatal("a snapshot with no database copy must be refused")
	}
}

func TestTarballDirIsOutsideTheCheckout(t *testing.T) {
	in := &install.Install{Dir: filepath.Join(t.TempDir(), "fartingaround.fun")}
	if got, want := backupDir(in), filepath.Clean(in.Dir)+"-backups"; got != want {
		t.Fatalf("default = %q, want %q", got, want)
	}
	// The old default, saved by an earlier fa, is not a choice the owner made.
	in.Settings.BackupDir = filepath.Join(in.Dir, "backups")
	if got := backupDir(in); got != filepath.Clean(in.Dir)+"-backups" {
		t.Fatalf("a saved <install>/backups must move out of the checkout, got %q", got)
	}
	in.Settings.BackupDir = "/mnt/other/backups"
	if got := backupDir(in); got != "/mnt/other/backups" {
		t.Fatalf("a chosen folder must be kept, got %q", got)
	}
}

func TestCheckRepo(t *testing.T) {
	for _, good := range []string{"s3:https://s3.us-west-001.backblazeb2.com/my-bucket/restic", "b2:my-bucket:restic", "sftp:tony@host:/srv/backups", "rest:https://host:8000/repo", "/mnt/disk/backups"} {
		if err := checkRepo(good); err != nil {
			t.Errorf("%q should be accepted: %v", good, err)
		}
	}
	// Each of these would have been taken for a folder on the server.
	for _, bad := range []string{"s3.us-west-001.backblazeb2.com", "s3.us-west-001.backblazeb2.com/my-bucket", "https://s3.us-west-001.backblazeb2.com/my-bucket", "my-bucket", "backups/site", "s3:"} {
		if err := checkRepo(bad); err == nil {
			t.Errorf("%q should be refused", bad)
		}
	}
}
