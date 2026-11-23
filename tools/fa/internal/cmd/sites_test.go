package cmd

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
)

func nothing(string) bool { return false }
func anyPort(int) bool    { return true }

var oneSite = []existingSite{{Name: "main", Dir: "/home/t/fartingaround.fun", Domain: "example.org", Container: "fartingaround-site", Port: "8406"}}

func TestPlanAdd(t *testing.T) {
	p, err := planAdd("notes", "https://Notes.Example.org/", "", "/home/t/fartingaround.fun", oneSite, nothing, nothing, anyPort)
	if err != nil {
		t.Fatal(err)
	}
	want := addPlan{Name: "notes", Domain: "notes.example.org", Dir: "/home/t/fa-notes", Container: "fa-notes", Port: "8407"}
	if *p != want {
		t.Fatalf("got %+v, want %+v", *p, want)
	}
}

func TestPlanAddSkipsTakenPorts(t *testing.T) {
	have := append([]existingSite{}, oneSite...)
	have = append(have, existingSite{Name: "a", Dir: "/home/t/fa-a", Domain: "a.example.org", Container: "fa-a", Port: "8407"})
	busy := func(p int) bool { return p != 8408 } // something else on the machine holds 8408
	p, err := planAdd("b", "b.example.org", "", "/home/t/fartingaround.fun", have, nothing, nothing, busy)
	if err != nil {
		t.Fatal(err)
	}
	if p.Port != "8409" {
		t.Fatalf("port %s, want 8409 (8407 is another site's, 8408 is in use)", p.Port)
	}
}

func TestPlanAddRefusesCollisions(t *testing.T) {
	yes := func(string) bool { return true }
	cases := []struct {
		what, name, domain, dir string
		pathExists, container   func(string) bool
		wantInError             string
	}{
		{"the main site's name", "main", "x.example.org", "", nothing, nothing, "started with"},
		{"a bad name", "My Site", "x.example.org", "", nothing, nothing, "won't do"},
		{"a name ending in a hyphen", "notes-", "x.example.org", "", nothing, nothing, "won't do"},
		{"not a domain", "notes", "localhost", "", nothing, nothing, "doesn't look like a domain"},
		{"another site's domain", "notes", "EXAMPLE.org", "", nothing, nothing, "already belongs"},
		{"another site's folder", "notes", "x.example.org", "/home/t/fartingaround.fun/", nothing, nothing, "is the folder of"},
		{"a relative folder", "notes", "x.example.org", "sites/notes", nothing, nothing, "full path"},
		{"a folder that exists", "notes", "x.example.org", "", yes, nothing, "already exists"},
		{"a container that exists", "notes", "x.example.org", "", nothing, yes, "container called fa-notes"},
	}
	for _, tc := range cases {
		_, err := planAdd(tc.name, tc.domain, tc.dir, "/home/t/fartingaround.fun", oneSite, tc.pathExists, tc.container, anyPort)
		if err == nil || !strings.Contains(err.Error(), tc.wantInError) {
			t.Errorf("%s: got %v, want an error mentioning %q", tc.what, err, tc.wantInError)
		}
	}
	have := append([]existingSite{}, oneSite...)
	have = append(have, existingSite{Name: "notes", Dir: "/home/t/fa-notes", Domain: "n.example.org", Container: "fa-notes", Port: "8407"})
	if _, err := planAdd("notes", "other.example.org", "", "/home/t/fartingaround.fun", have, nothing, nothing, anyPort); err == nil || !strings.Contains(err.Error(), "already a site called") {
		t.Errorf("a second site of the same name: got %v", err)
	}
}

func TestParseAddArgs(t *testing.T) {
	a, err := parseAddArgs([]string{"notes", "--domain", "notes.example.org", "--env=A=1", "--env", "B=2", "--no-start"})
	if err != nil {
		t.Fatal(err)
	}
	if a.name != "notes" || a.domain != "notes.example.org" || !a.noStart || strings.Join(a.env, ",") != "A=1,B=2" {
		t.Fatalf("got %+v", a)
	}
	for _, bad := range [][]string{{"a", "b"}, {"--domain"}, {"--nope", "x"}, {"--env", "novalue"}} {
		if _, err := parseAddArgs(bad); err == nil {
			t.Errorf("%v should be refused", bad)
		}
	}
}

func TestSiteDir(t *testing.T) {
	t.Setenv("FA_SITES_FILE", filepath.Join(t.TempDir(), "sites.json"))
	t.Chdir(t.TempDir()) // not inside a site's folder

	// No list yet: one site, found the usual way.
	if d, err := siteDir("", ""); err != nil || d != "" {
		t.Fatalf("no list: got %q, %v", d, err)
	}
	if d, err := siteDir("", "main"); err != nil || d != "" {
		t.Fatalf("no list, -s main: got %q, %v", d, err)
	}
	if _, err := siteDir("", "notes"); err == nil {
		t.Fatal("an unknown site name should be an error")
	}

	if err := install.WriteSites([]install.Site{{Name: "notes", Dir: "/srv/fa-notes"}, {Name: "main", Dir: "/srv/main"}}); err != nil {
		t.Fatal(err)
	}
	for site, want := range map[string]string{"": "/srv/main", "main": "/srv/main", "notes": "/srv/fa-notes"} {
		if d, err := siteDir("", site); err != nil || d != want {
			t.Errorf("-s %q: got %q, %v; want %q", site, d, err, want)
		}
	}
	if d, _ := siteDir("/elsewhere", ""); d != "/elsewhere" {
		t.Errorf("--dir should win when given alone, got %q", d)
	}
	if _, err := siteDir("/elsewhere", "notes"); err == nil {
		t.Error("--dir together with -s should be refused")
	}
	sites, _ := install.ReadSites()
	if len(sites) != 2 || sites[0].Name != "main" {
		t.Errorf("main should be listed first, got %+v", sites)
	}
}

func TestSiteCaddyBlock(t *testing.T) {
	b := siteCaddyBlock("notes.example.org", "fa-notes")
	if !strings.HasPrefix(b, "notes.example.org {") || !strings.Contains(b, "reverse_proxy fa-notes:3000") || strings.Contains(b, "www.") {
		t.Fatalf("unexpected block:\n%s", b)
	}
}
