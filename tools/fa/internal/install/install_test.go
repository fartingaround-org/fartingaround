package install

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEnvRoundTrip(t *testing.T) {
	p := filepath.Join(t.TempDir(), ".env")
	os.WriteFile(p, []byte("# comment\nADMIN_PASSWORD=change-me\nDATA_PATH=\nQUOTED=\"a b\" # trailing\n"), 0o600)
	e, err := ReadEnv(p)
	if err != nil {
		t.Fatal(err)
	}
	if e.Get("ADMIN_PASSWORD") != "change-me" || e.Get("DATA_PATH") != "" || e.Get("QUOTED") != "a b" {
		t.Fatalf("bad parse: %q %q %q", e.Get("ADMIN_PASSWORD"), e.Get("DATA_PATH"), e.Get("QUOTED"))
	}
	e.Set("DATA_PATH", "/mnt/x")
	e.Set("NEW", "has space")
	if err := e.Save(); err != nil {
		t.Fatal(err)
	}
	e2, _ := ReadEnv(p)
	if e2.Get("DATA_PATH") != "/mnt/x" || e2.Get("NEW") != "has space" {
		t.Fatalf("round trip failed: %v", e2.lines)
	}
	if e2.lines[0] != "# comment" {
		t.Fatal("comment lost")
	}
}

func TestEnvUnsetAndBackup(t *testing.T) {
	p := filepath.Join(t.TempDir(), ".env")
	if err := os.WriteFile(p, []byte("# keep me\nA=1\nB=two\nA=again\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	e, err := ReadEnv(p)
	if err != nil {
		t.Fatal(err)
	}
	if !e.Unset("A") || e.Unset("NOPE") {
		t.Fatal("Unset must report whether the key was there")
	}
	if err := e.Save(); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(p)
	if string(got) != "# keep me\nB=two\n" {
		t.Fatalf("after unset: %q", got)
	}
	prev, _ := os.ReadFile(p + ".fa-backup")
	if string(prev) != "# keep me\nA=1\nB=two\nA=again\n" {
		t.Fatalf("backup: %q", prev)
	}
}
