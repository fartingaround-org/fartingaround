package cmd

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// git runs a git command in dir with an identity, failing the test on error.
func git(t *testing.T, dir string, args ...string) string {
	t.Helper()
	c := exec.Command("git", append([]string{"-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "init.defaultBranch=main"}, args...)...)
	c.Dir = dir
	out, err := c.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return strings.TrimSpace(string(out))
}

// After a rollback the server must still be on its branch, at the commit it
// was running, with a local edit intact — and the next pull must bring the
// fix in without anyone touching the checkout by hand.
func TestMoveCheckoutBackKeepsTheBranch(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("no git")
	}
	tmp := t.TempDir()
	origin, work, server := filepath.Join(tmp, "origin.git"), filepath.Join(tmp, "work"), filepath.Join(tmp, "server")
	git(t, tmp, "init", "-q", "--bare", origin)
	git(t, tmp, "init", "-q", work)
	git(t, work, "remote", "add", "origin", origin)
	commit := func(content, msg string) {
		if err := os.WriteFile(filepath.Join(work, "app.txt"), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		git(t, work, "add", "-A")
		git(t, work, "commit", "-qm", msg)
		git(t, work, "push", "-q", "origin", "HEAD:main")
	}
	commit("good\n", "good")
	if err := os.WriteFile(filepath.Join(work, "notes.txt"), []byte("tracked\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	commit("good\n", "add notes")
	git(t, tmp, "clone", "-q", "-b", "main", origin, server)
	good := git(t, server, "rev-parse", "--short", "HEAD")

	// someone edited a tracked file on the server; the deploy pulls a bad commit
	if err := os.WriteFile(filepath.Join(server, "notes.txt"), []byte("edited on the server\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	commit("bad\n", "bad")
	git(t, server, "pull", "--ff-only", "--quiet")

	if err := moveCheckoutBack(server, good); err != nil {
		t.Fatalf("moveCheckoutBack: %v", err)
	}
	if head := git(t, server, "rev-parse", "--short", "HEAD"); head != good {
		t.Fatalf("HEAD = %s, want %s", head, good)
	}
	if branch := git(t, server, "symbolic-ref", "--short", "HEAD"); branch != "main" {
		t.Fatalf("branch = %q, want main (not detached)", branch)
	}
	if b, _ := os.ReadFile(filepath.Join(server, "app.txt")); string(b) != "good\n" {
		t.Fatalf("app.txt = %q, want the good commit's", b)
	}
	if b, _ := os.ReadFile(filepath.Join(server, "notes.txt")); string(b) != "edited on the server\n" {
		t.Fatalf("the local edit was lost: %q", b)
	}

	// the fix lands: an ordinary pull brings it in
	commit("fixed\n", "fix")
	git(t, server, "pull", "--ff-only", "--quiet")
	if b, _ := os.ReadFile(filepath.Join(server, "app.txt")); string(b) != "fixed\n" {
		t.Fatalf("after the fix: app.txt = %q", b)
	}
}
