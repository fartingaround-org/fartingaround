package cmd

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	register(&command{name: "backup", summary: "snapshot database + media to a tarball: fa backup [--to DIR] [--keep N]", run: runBackup})
	register(&command{name: "restore", summary: "restore a backup tarball into the data path (stops the site first)", run: runRestore})
}

// backupDir is Settings.BackupDir, else a folder BESIDE the install
// (/home/tony/fartingaround.fun → /home/tony/fartingaround.fun-backups).
//
// Not inside the install: `docker compose build` sends the whole checkout to
// docker, and gigabytes of tarballs went along with every deploy. The old
// default was <install>/backups, and fa saved it into .fa.json on first use,
// so a saved value that is exactly that is treated as "never chosen".
//
// Never the data volume by default either: a backup of the volume on the
// volume needs the volume to be half empty, and it dies with the disk.
func backupDir(in *install.Install) string {
	if d := in.Settings.BackupDir; d != "" && filepath.Clean(d) != filepath.Join(in.Dir, "backups") {
		return d
	}
	return filepath.Clean(in.Dir) + "-backups"
}

// runBackup: a consistent copy of the database (VACUUM INTO for SQLite via the
// site's own node; pg_dump via a throwaway postgres container for Postgres)
// plus the objects folder, as one plain tar — photos and video are already
// compressed, so gzip would only cost time. The DB copy is made inside the
// container so no sqlite3/pg tooling is needed on the host.
func runBackup(c *Ctx, args []string) error {
	in := c.In
	dest := backupDir(in)
	keep := 7
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--to" && i+1 < len(args):
			dest = args[i+1]
			i++
		case args[i] == "--keep" && i+1 < len(args):
			keep, _ = strconv.Atoi(args[i+1])
			i++
		}
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	if old := filepath.Join(in.Dir, "backups"); fileExists(old) && filepath.Clean(dest) != old {
		warn("older tarballs are still in %s, inside the checkout, where every deploy uploads them to docker. Move them: mv %s/* %s/ && rmdir %s", old, old, dest, old)
	}
	dp := in.DataPath()

	// Preflight: will it fit? Same filesystem as the data means it needs ~2x.
	_, objSize := dirStats(filepath.Join(dp, "objects"))
	need := objSize + objSize/20 + 200<<20
	if st, err := os.Stat(filepath.Join(dp, "site.db")); err == nil {
		need += uint64(st.Size())
	}
	if free, _, err := diskFree(dest); err == nil && free < need {
		return errorf("not enough room in %s: need about %s, %s free. Use --to <dir on another disk>, or set it once with: fa set backupDir <dir>", dest, humanBytes(need), humanBytes(free))
	}
	if sameFilesystem(dest, dp) {
		warn("%s is on the data volume — a backup there dies with the disk. Copy it elsewhere (rclone) or use --to.", dest)
	}

	stamp := time.Now().Format("20060102-150405")
	staging := filepath.Join(dp, ".backup-"+stamp)
	if err := os.MkdirAll(staging, 0o755); err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	_, _ = sh.Out("", "chown", "1000:1000", staging)

	if in.UsesPostgres() {
		f, err := os.Create(filepath.Join(staging, "db.sql"))
		if err != nil {
			return err
		}
		err = sh.ToFile("", f, "docker", "run", "--rm", "--network", "web", "postgres:17-alpine", "pg_dump", "--no-owner", "--no-privileges", in.Env.Get("DATABASE_URL"))
		f.Close()
		if err != nil {
			return errorf("pg_dump: %v", err)
		}
		ok("postgres dumped")
	} else {
		// VACUUM INTO gives a consistent copy even with WAL mode and the site live.
		js := `const {DatabaseSync}=require("node:sqlite");new DatabaseSync("/data/site.db",{readOnly:true}).exec("VACUUM INTO '/data/.backup-` + stamp + `/site.db'")`
		if _, err := compose(in, "exec", "-T", "site", "node", "-e", js); err != nil {
			return errorf("sqlite copy: %v", err)
		}
		ok("sqlite copied")
	}

	out := filepath.Join(dest, "fartingaround-"+stamp+".tar")
	if _, err := sh.Out(dp, "tar", "-cf", out, "-C", dp, "--transform", "s|^\\.backup-"+stamp+"|db|", ".backup-"+stamp, "objects"); err != nil {
		os.Remove(out) // never leave a half-written archive that looks like a backup
		return err
	}
	if st, err := os.Stat(out); err == nil {
		ok("wrote %s (%s)", out, humanBytes(uint64(st.Size())))
	}
	if keep > 0 {
		m, _ := filepath.Glob(filepath.Join(dest, "fartingaround-*.tar*"))
		sort.Strings(m)
		for len(m) > keep {
			os.Remove(m[0])
			m = m[1:]
		}
	}
	if in.Settings.BackupDir == "" || filepath.Clean(in.Settings.BackupDir) == filepath.Join(in.Dir, "backups") {
		in.Settings.BackupDir = dest
		_ = in.SaveSettings()
	}
	return nil
}

func runRestore(c *Ctx, args []string) error {
	in := c.In
	if len(args) != 1 {
		return errorf("usage: fa restore <fartingaround-….tar>")
	}
	tarball := args[0]
	if !fileExists(tarball) {
		return errorf("%s not found", tarball)
	}
	if in.UsesPostgres() {
		return errorf("restore for Postgres: psql \"$DATABASE_URL\" < db/db.sql from the tarball, then untar objects/ into %s — automating this is on the list", in.DataPath())
	}
	say("stopping the site…")
	if err := composePass(in, "stop", "site"); err != nil {
		return err
	}
	dp := in.DataPath()
	tmp := filepath.Join(dp, ".restore")
	os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return err
	}
	if _, err := sh.Out(dp, "tar", "-xf", tarball, "-C", tmp); err != nil { // tar sniffs gzip itself
		return err
	}
	// Move the live data aside rather than deleting it.
	aside := filepath.Join(dp, ".replaced-"+time.Now().Format("20060102-150405"))
	os.MkdirAll(aside, 0o755)
	for _, f := range []string{"site.db", "site.db-wal", "site.db-shm", "objects"} {
		_ = os.Rename(filepath.Join(dp, f), filepath.Join(aside, f))
	}
	if err := os.Rename(filepath.Join(tmp, "db", "site.db"), filepath.Join(dp, "site.db")); err != nil {
		return err
	}
	if err := os.Rename(filepath.Join(tmp, "objects"), filepath.Join(dp, "objects")); err != nil {
		return err
	}
	os.RemoveAll(tmp)
	_, _ = sh.Out("", "chown", "-R", "1000:1000", dp)
	if err := composePass(in, "start", "site"); err != nil {
		return err
	}
	if err := waitHealthy(in, 60*time.Second); err != nil {
		return err
	}
	ok("restored. The previous data is in %s — delete it once you're happy.", aside)
	return nil
}
