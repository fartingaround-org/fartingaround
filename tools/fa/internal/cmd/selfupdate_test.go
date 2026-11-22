package cmd

import (
	"os"
	"path/filepath"
	"testing"
)

func writeMod(t *testing.T, dir, module string) {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte("module "+module+"\n\ngo 1.24\n"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestFindSource(t *testing.T) {
	t.Setenv("FA_SRC", "")
	SourceDir = ""
	root := t.TempDir()
	repo := filepath.Join(root, "fartingaround.fun")
	src := filepath.Join(repo, "tools", "fa")
	writeMod(t, src, faModule)
	writeMod(t, filepath.Join(root, "other"), "example.test/other") // someone else's go.mod is not ours

	// A binary built inside the checkout finds it by walking up.
	if got, err := findSource(filepath.Join(src, "fa")); err != nil || got != src {
		t.Fatalf("from inside the module: %q, %v", got, err)
	}
	// A binary at the top of the repository checkout finds tools/fa.
	if got, err := findSource(filepath.Join(repo, "bin", "fa")); err != nil || got != src {
		t.Fatalf("from the repository: %q, %v", got, err)
	}
	// FA_SRC may name the repository or the module folder.
	t.Setenv("FA_SRC", repo)
	if got, err := findSource("/usr/local/bin/fa"); err != nil || got != src {
		t.Fatalf("FA_SRC=repo: %q, %v", got, err)
	}
	t.Setenv("FA_SRC", filepath.Join(root, "other"))
	if _, err := findSource("/usr/local/bin/fa"); err == nil {
		t.Fatal("FA_SRC pointing at another module must be refused")
	}
	// The folder the binary was built from, stamped in at build time.
	t.Setenv("FA_SRC", "")
	SourceDir = src
	t.Chdir(root)
	if got, err := findSource("/usr/local/bin/fa"); err != nil || got != src {
		t.Fatalf("stamped source: %q, %v", got, err)
	}
	SourceDir = ""
	if _, err := findSource("/usr/local/bin/fa"); err == nil {
		t.Fatal("with no source anywhere it must say so")
	}
}

func TestSwapInKeepsThePreviousBinary(t *testing.T) {
	dir := t.TempDir()
	exe, fresh := filepath.Join(dir, "fa"), filepath.Join(dir, "fa.new")
	os.WriteFile(exe, []byte("old"), 0o755)
	os.WriteFile(fresh, []byte("new"), 0o755)
	if err := swapIn(fresh, exe); err != nil {
		t.Fatal(err)
	}
	now, _ := os.ReadFile(exe)
	prev, _ := os.ReadFile(exe + ".prev")
	if string(now) != "new" || string(prev) != "old" {
		t.Fatalf("exe=%q prev=%q", now, prev)
	}
	if _, err := os.Stat(fresh); !os.IsNotExist(err) {
		t.Fatal("the .new file must be gone")
	}
}

func TestGoArch(t *testing.T) {
	for in, want := range map[string]string{"x86_64": "amd64", "aarch64": "arm64", "arm64": "arm64", "armv7l": "arm", "riscv64": ""} {
		if got := goArch(in); got != want {
			t.Errorf("goArch(%q) = %q, want %q", in, got, want)
		}
	}
}
