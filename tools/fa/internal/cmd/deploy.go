package cmd

import (
	"os"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	register(&command{name: "up", summary: "start the stack, downloading (or building) the site image if needed", run: runUp})
	register(&command{name: "down", summary: "stop the stack (data stays)", run: func(c *Ctx, _ []string) error { return composePass(c.In, "down") }})
	register(&command{name: "restart", summary: "restart the site container", run: func(c *Ctx, _ []string) error { return composePass(c.In, "restart", "site") }})
	register(&command{name: "deploy", summary: "download the newest site image (or rebuild), restart, health-check; roll back if it doesn't come up", run: runDeploy})
	register(&command{name: "update", summary: "show what a deploy would pull (fa update) — alias for deploy --check", run: func(c *Ctx, a []string) error { return runDeploy(c, append(a, "--check")) }})
}

// builtImage is the name compose gave the image it built on this server:
// <project folder>-site. Read off the running container, because the folder
// is not always called the same thing.
func builtImage() string {
	if ci, err := inspectContainer(siteContainer); err == nil && ci.Image != "" {
		name := ci.Image
		if colon := strings.LastIndex(name, ":"); colon > strings.LastIndex(name, "/") {
			name = name[:colon]
		}
		return name
	}
	return "fartingaroundfun-site" // a checkout in a folder named fartingaround.fun
}

func ensureDataPath(c *Ctx) error {
	dp := c.In.DataPath()
	if err := os.MkdirAll(dp, 0o755); err != nil {
		return err
	}
	if uid := ownerUID(dp); uid != 1000 {
		if _, err := sh.Out("", "chown", "-R", "1000:1000", dp); err != nil {
			warn("couldn't chown %s to 1000:1000 (%v) — run: sudo chown -R 1000:1000 %s", dp, err, dp)
		}
	}
	return nil
}

func runUp(c *Ctx, _ []string) error {
	if err := ensureDataPath(c); err != nil {
		return err
	}
	args := []string{"up", "-d", "--remove-orphans"}
	if publishedImage(c.In) == "" {
		args = append(args, "--build") // this install builds its own
	}
	if err := composePass(c.In, args...); err != nil {
		return err
	}
	if err := waitHealthy(c.In, 90*time.Second); err != nil {
		return err
	}
	ok("up — %s", gitHead(c.In))
	return nil
}

func runDeploy(c *Ctx, args []string) error {
	in := c.In
	check := false
	for _, a := range args {
		if a == "--check" || a == "-n" {
			check = true
		}
	}
	before := gitHead(in)

	if _, err := sh.Out(in.Dir, "git", gitArgsWithKey(in, "fetch", "--quiet")...); err != nil {
		return err
	}
	ahead, err := sh.Out(in.Dir, "git", "log", "--oneline", "HEAD..@{u}")

	// A site that runs the published image has a second thing a deploy can
	// bring in: a newer image under the same tag.
	ref := publishedImage(in)
	imageLine, imageNewer := "", false
	if ref != "" {
		imageLine, imageNewer = imageNews(ref)
	}
	switch {
	case err != nil:
		// No upstream to compare with (a detached HEAD, or a branch that tracks
		// nothing). Saying "nothing new" here would be a guess.
		if check {
			return errorf("couldn't compare with what is pushed (%v) — is this branch tracking a remote?", err)
		}
		warn("couldn't compare with what is pushed (%v); pulling anyway", err)
	case ahead == "" && !imageNewer:
		say("already at %s — nothing new upstream", before)
		if imageLine != "" {
			say("%s", imageLine) // why the image could not be checked
		}
		if check {
			return nil
		}
		if ref != "" {
			say("restarting anyway (settings may have changed)")
		} else {
			say("rebuilding anyway (local changes / env)")
		}
	default:
		// The dashboard reads the lines under "incoming:" as what a deploy
		// would bring in, so the image goes there with the commits.
		incoming := ahead
		if imageNewer {
			incoming = strings.TrimSpace(imageLine + "\n" + ahead)
		}
		say("incoming:\n%s", indent(incoming))
		if check {
			return nil
		}
	}
	if _, err := sh.Out(in.Dir, "git", gitArgsWithKey(in, "pull", "--ff-only", "--quiet")...); err != nil {
		return err
	}
	if err := ensureDataPath(c); err != nil {
		return err
	}

	// The pull may have brought a different compose file, so ask again.
	if ref := publishedImage(in); ref != "" {
		return deployImage(c, ref, before)
	}

	// Built on this server. Keep the last good image around so a bad build can be undone.
	siteImage := builtImage()
	if _, err := sh.Out("", "docker", "image", "inspect", siteImage+":latest"); err == nil {
		_, _ = sh.Out("", "docker", "tag", siteImage+":latest", siteImage+":previous")
	}

	say("building %s…", gitHead(in))
	if err := composePass(in, "up", "-d", "--build", "--remove-orphans"); err != nil {
		return rollback(c, siteImage, before, err)
	}
	if err := waitHealthy(in, 90*time.Second); err != nil {
		out, _ := compose(in, "logs", "--tail", "40", "site")
		say("%s", indent(out))
		return rollback(c, siteImage, before, err)
	}
	_, _ = sh.Out("", "docker", "image", "prune", "-f")
	ok("deployed %s", gitHead(in))
	return nil
}

func rollback(c *Ctx, siteImage, commit string, cause error) error {
	in := c.In
	warn("deploy failed: %v", cause)
	if _, err := sh.Out("", "docker", "image", "inspect", siteImage+":previous"); err != nil {
		return errorf("no previous image to roll back to; fix and `fa up`")
	}
	say("rolling back to %s…", commit)
	if err := moveCheckoutBack(in.Dir, commit); err != nil {
		warn("couldn't move the checkout back to %s (%v) — the previous image is restored below, but the files stay at %s", commit, err, gitHead(in))
	}
	_, _ = sh.Out("", "docker", "tag", siteImage+":previous", siteImage+":latest")
	if err := composePass(in, "up", "-d", "--no-build", "site"); err != nil {
		return errorf("rollback failed too: %v", err)
	}
	if err := waitHealthy(in, 60*time.Second); err != nil {
		return errorf("rolled back but the site is still down: %v", err)
	}
	warn("rolled back to %s. Push the fix and deploy again — the branch is still checked out, so it pulls like any other.", commit)
	return cause
}

// moveCheckoutBack puts the branch itself back on commit, so the next deploy's
// `git pull --ff-only` brings the fix in like any other commit. (Checking the
// commit out instead would leave HEAD detached, and the next pull would fail
// until someone ran `git checkout main` on the server.) --keep leaves a local
// edit on the server alone, and refuses when one is in the way.
func moveCheckoutBack(dir, commit string) error {
	_, err := sh.Out(dir, "git", "reset", "--keep", "--quiet", commit)
	return err
}

func indent(s string) string {
	out := ""
	for _, l := range splitLines(s) {
		out += "    " + l + "\n"
	}
	return out
}

func splitLines(s string) []string {
	var out []string
	cur := ""
	for _, r := range s {
		if r == '\n' {
			out = append(out, cur)
			cur = ""
			continue
		}
		cur += string(r)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}
