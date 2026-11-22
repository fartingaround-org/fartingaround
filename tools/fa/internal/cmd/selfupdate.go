package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// `fa self-update`: pull the source, build fa, and replace the fa that is
// running — on this computer, and on a server when one is named. It is what
// the "update fa" button in `fa ui` runs.
//
//	fa self-update              this computer's fa
//	fa self-update user@box     this computer's, then the one on that server
//	      --no-pull             build what is in the checkout as it stands
//
// fa lives in the site's repository (tools/fa), so "the source" is a checkout
// of that repository on THIS computer. The server needs no Go: its fa is
// cross-compiled here for whatever it runs on and copied over ssh, which also
// keeps the two the same version (the dashboard talks to the server's fa, and
// a newer page asks an older fa for things it does not know).
//
// Where the checkout is, in order: FA_SRC; the folder this binary was built
// from (stamped in by the last self-update); a go.mod found by walking up
// from the binary; the same from the current directory.

// SourceDir is stamped by self-update's own builds: -X …/internal/cmd.SourceDir=…
var SourceDir string

const (
	faModule   = "github.com/fartingaround-org/fartingaround/tools/fa"
	faModuleLn = "module " + faModule
)

func init() {
	register(&command{name: "self-update", summary: "pull, build and replace fa here (and on a server): fa self-update [user@box] [--no-pull]", noInstall: true, run: runSelfUpdate})
}

// isSource reports whether dir is fa's source (its go.mod names the module).
func isSource(dir string) bool {
	b, err := os.ReadFile(filepath.Join(dir, "go.mod"))
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.TrimSpace(l) == faModuleLn {
			return true
		}
	}
	return false
}

// walkUpForSource looks in dir and each parent for fa's source, either the
// module folder itself or a repository checkout with tools/fa in it.
func walkUpForSource(dir string) string {
	for d := dir; ; d = filepath.Dir(d) {
		if isSource(d) {
			return d
		}
		if t := filepath.Join(d, "tools", "fa"); isSource(t) {
			return t
		}
		if filepath.Dir(d) == d {
			return ""
		}
	}
}

func findSource(exe string) (string, error) {
	if d := os.Getenv("FA_SRC"); d != "" {
		if isSource(d) {
			return d, nil
		}
		if t := filepath.Join(d, "tools", "fa"); isSource(t) {
			return t, nil
		}
		return "", errorf("FA_SRC=%s is not fa's source (no go.mod for %s there or in tools/fa)", d, faModule)
	}
	if SourceDir != "" && isSource(SourceDir) {
		return SourceDir, nil
	}
	if d := walkUpForSource(filepath.Dir(exe)); d != "" {
		return d, nil
	}
	if wd, err := os.Getwd(); err == nil {
		if d := walkUpForSource(wd); d != "" {
			return d, nil
		}
	}
	return "", errorf("can't find fa's source on this computer. It is the tools/fa folder of a fartingaround checkout: run this from inside one, or set FA_SRC to it once (after that fa remembers)")
}

// goBuild compiles the source at src into out. Empty goos/goarch build for
// this machine.
func goBuild(src, out, version, goos, goarch string) error {
	// quoted: the checkout's path may have a space in it
	ld := "-s -w -X main.version=" + version + " -X '" + faModule + "/internal/cmd.SourceDir=" + src + "'"
	c := exec.Command("go", "build", "-trimpath", "-ldflags", ld, "-o", out, ".")
	c.Dir = src
	c.Env = append(os.Environ(), "CGO_ENABLED=0")
	if goos != "" {
		c.Env = append(c.Env, "GOOS="+goos, "GOARCH="+goarch)
	}
	if b, err := c.CombinedOutput(); err != nil {
		return errorf("the build failed, so nothing was replaced:\n%s", strings.TrimSpace(string(b)))
	}
	return nil
}

// sourceVersion names what is being built: the commit's date and hash, with
// +local when the folder has changes that are not committed.
func sourceVersion(src string) string {
	v, err := sh.Out(src, "git", "log", "-1", "--date=format:%Y.%m.%d", "--format=%cd-%h")
	if err != nil || v == "" {
		return "dev"
	}
	if dirty, _ := sh.Out(src, "git", "status", "--porcelain", "--", "."); dirty != "" {
		v += "+local"
	}
	return v
}

// swapIn puts the new binary where the running one is. The old one is kept
// beside it as <name>.prev (one generation), and the replacement is a rename,
// so there is never a moment with no fa at that path.
func swapIn(newPath, exe string) error {
	prev := exe + ".prev"
	_ = os.Remove(prev)
	if err := os.Link(exe, prev); err != nil {
		warn("could not keep the previous fa as %s (%v)", prev, err)
	}
	return os.Rename(newPath, exe)
}

// goArch maps `uname -m` to Go's name for it.
func goArch(machine string) string {
	switch machine {
	case "x86_64", "amd64":
		return "amd64"
	case "aarch64", "arm64":
		return "arm64"
	case "armv7l", "armv6l":
		return "arm"
	}
	return ""
}

func runSelfUpdate(c *Ctx, args []string) error {
	host, pull := "", true
	for _, a := range args {
		switch {
		case a == "--no-pull":
			pull = false
		case strings.HasPrefix(a, "-"):
			return errorf("usage: fa self-update [user@box] [--no-pull]")
		default:
			host = a
		}
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if real, err := filepath.EvalSymlinks(exe); err == nil {
		exe = real
	}
	src, err := findSource(exe)
	if err != nil {
		return err
	}
	if !sh.Have("go") {
		return errorf("building fa needs Go on this computer, and it is not installed: brew install go   (or https://go.dev/dl)")
	}
	say("source: %s", src)
	if pull {
		if err := sh.Pass(src, "git", "pull", "--ff-only"); err != nil {
			return errorf("git pull did not go through, so nothing was built. Sort the checkout out, or build what is there with: fa self-update --no-pull")
		}
	}
	version := sourceVersion(src)
	say("building fa %s…", version)

	// This computer.
	fresh := exe + ".new"
	if err := goBuild(src, fresh, version, "", ""); err != nil {
		return err
	}
	if out, err := sh.Out("", fresh, "version"); err != nil || !strings.Contains(out, version) {
		os.Remove(fresh)
		return errorf("the new fa does not run (%v %s), so nothing was replaced", err, out)
	}
	if err := swapIn(fresh, exe); err != nil {
		os.Remove(fresh)
		return errorf("could not replace %s: %v", exe, err)
	}
	ok("this computer: %s is now fa %s (was %s)", exe, version, c.Version)

	if host == "" {
		return nil
	}
	return updateRemote(host, src, version)
}

// updateRemote builds fa for the server's system and installs it there.
func updateRemote(host, src, version string) error {
	ssh := func(remote string) (string, error) {
		return sh.Out("", "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, remote)
	}
	// What it runs on, where its fa is, and what that fa says it is.
	out, err := ssh(`uname -s; uname -m; command -v fa || echo "$HOME/.local/bin/fa"; (fa version 2>/dev/null || echo "fa none") | head -1`)
	if err != nil {
		return errorf("%s: %v", host, err)
	}
	f := strings.Split(out, "\n")
	if len(f) < 4 {
		return errorf("%s gave an answer fa could not read: %q", host, out)
	}
	goos, arch, remotePath, had := strings.ToLower(strings.TrimSpace(f[0])), goArch(strings.TrimSpace(f[1])), strings.TrimSpace(f[2]), strings.TrimPrefix(strings.TrimSpace(f[3]), "fa ")
	if (goos != "linux" && goos != "darwin") || arch == "" {
		return errorf("%s runs %s/%s, which fa is not built for", host, f[0], f[1])
	}
	if had == version {
		ok("%s: already fa %s", host, version)
		return nil
	}
	tmp, err := os.CreateTemp("", "fa-"+goos+"-"+arch+"-*")
	if err != nil {
		return err
	}
	tmp.Close()
	defer os.Remove(tmp.Name())
	if err := goBuild(src, tmp.Name(), version, goos, arch); err != nil {
		return err
	}
	staged := fmt.Sprintf("/tmp/fa-update-%d", os.Getpid())
	if _, err := sh.Out("", "scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", tmp.Name(), host+":"+staged); err != nil {
		return errorf("could not copy fa to %s: %v", host, err)
	}
	// Check it runs there, keep the old one as .prev, then rename into place
	// (a copy beside the target first, so the swap itself is within one folder).
	p, s := shellQuote(remotePath), shellQuote(staged)
	// A fa on the PATH may be a link into a folder the user owns (the way to
	// update without sudo); the file it points at is the one to replace.
	script := `set -e; p=` + p + `; s=` + s + `; if [ -L "$p" ]; then p=$(readlink -f "$p"); fi; d=$(dirname "$p"); chmod +x "$s"; "$s" version >/dev/null; ` +
		`if [ ! -e "$d" ]; then mkdir -p "$d"; fi; ` +
		`if [ -w "$d" ]; then S=""; elif sudo -n true 2>/dev/null; then S="sudo"; else echo "NEEDS_SUDO"; exit 4; fi; ` +
		`if [ -e "$p" ]; then $S cp -p "$p" "$p.prev"; fi; $S cp "$s" "$p.new"; $S chmod 755 "$p.new"; $S mv "$p.new" "$p"; rm -f "$s"; "$p" version`
	out, err = ssh(script)
	if err != nil {
		if strings.Contains(out, "NEEDS_SUDO") {
			return errorf("%s keeps fa in %s, which your user there cannot write to, and sudo wants a password. The new fa is waiting on the server; finish it there with:\n    sudo mv %s %s\n"+
				"To never be asked again, keep fa in your own folder there and leave a link on the PATH (once, on the server):\n    mkdir -p ~/.local/bin && mv %s ~/.local/bin/fa && sudo ln -sf ~/.local/bin/fa %s",
				host, filepath.Dir(remotePath), staged, remotePath, staged, remotePath)
		}
		return errorf("%s: could not install the new fa: %v", host, err)
	}
	if !strings.Contains(out, version) {
		return errorf("%s: installed, but `fa version` there says %q, not %s — is another fa earlier on its PATH?", host, strings.TrimSpace(out), version)
	}
	ok("%s: %s is now fa %s (was %s)", host, remotePath, version, had)
	return nil
}
