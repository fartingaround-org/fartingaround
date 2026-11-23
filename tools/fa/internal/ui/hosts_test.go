package ui

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestRenameHost(t *testing.T) {
	s := &Server{jobs: map[string]*job{}, cfgPath: filepath.Join(t.TempDir(), "ui.json")}
	s.cfg.Hosts = []Host{{Name: "tony@1.2.3.4", SSH: "tony@1.2.3.4", Dir: "/srv/site"}, {Name: "org", SSH: "tony@5.6.7.8"}}
	s.jobs[jobKey("tony@1.2.3.4", "main")] = &job{Host: "tony@1.2.3.4", Label: "fa deploy", done: true}

	if msg := s.renameHost("tony@1.2.3.4", Host{Name: "org", SSH: "tony@1.2.3.4"}); !strings.Contains(msg, "already a server called org") {
		t.Errorf("taking another server's name: got %q", msg)
	}
	if msg := s.renameHost("gone", Host{Name: "x", SSH: "y"}); !strings.Contains(msg, "no server called gone") {
		t.Errorf("renaming one that isn't there: got %q", msg)
	}
	running := &job{Host: "tony@1.2.3.4", Label: "fa backups run"}
	s.jobs[jobKey("tony@1.2.3.4", "bak")] = running
	if msg := s.renameHost("tony@1.2.3.4", Host{Name: "personal", SSH: "tony@1.2.3.4"}); !strings.Contains(msg, "still running") {
		t.Errorf("renaming under a running job: got %q", msg)
	}
	running.done = true
	if msg := s.renameHost("tony@1.2.3.4", Host{Name: "personal", SSH: "tony@1.2.3.4", Dir: "/srv/site"}); msg != "" {
		t.Fatalf("a plain rename was refused: %s", msg)
	}
	if got := s.cfg.Hosts[0]; got.Name != "personal" || got.SSH != "tony@1.2.3.4" || got.Dir != "/srv/site" {
		t.Errorf("after the rename: %+v", got)
	}
	if len(s.cfg.Hosts) != 2 || s.cfg.Hosts[1].Name != "org" {
		t.Errorf("the other server changed: %+v", s.cfg.Hosts)
	}
	if len(s.jobs) != 0 {
		t.Error("a finished job filed under the old name should be dropped")
	}
}
