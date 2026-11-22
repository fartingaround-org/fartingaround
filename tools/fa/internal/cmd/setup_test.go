package cmd

import (
	"bufio"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseSetupArgs(t *testing.T) {
	a, err := parseSetupArgs([]string{"--yes", "--domain", "example.org", "--proxy=caddy", "--env", "ELEVENLABS_API_KEY=abc=def", "--no-start"})
	if err != nil {
		t.Fatal(err)
	}
	if !a.auto || a.vals["domain"] != "example.org" || a.vals["proxy"] != "caddy" {
		t.Errorf("got %+v", a)
	}
	// The value keeps everything after the first "=".
	if len(a.env) != 1 || a.env[0] != [2]string{"ELEVENLABS_API_KEY", "abc=def"} {
		t.Errorf("env = %v", a.env)
	}
	if a.start == nil || *a.start {
		t.Errorf("--no-start should turn starting off")
	}

	// No flags: nothing answered, everything asked, as before.
	a, err = parseSetupArgs(nil)
	if err != nil || a.auto || len(a.vals) != 0 || a.start != nil {
		t.Errorf("no flags: %+v, %v", a, err)
	}

	for _, bad := range [][]string{
		{"--domian", "example.org"}, // a typo must not be silently ignored
		{"--domain"},                // no value
		{"example.org"},             // not a flag
		{"--env", "NOEQUALS"},
	} {
		if _, err := parseSetupArgs(bad); err == nil {
			t.Errorf("parseSetupArgs(%v) should fail", bad)
		}
	}
}

func TestParseSetupAnswersFile(t *testing.T) {
	f := filepath.Join(t.TempDir(), "answers")
	body := "# a new site\ndomain = file.example.org\nproxy=caddy\n\nacme-email=me@example.org\n"
	if err := os.WriteFile(f, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	// A flag wins over the same answer in the file.
	a, err := parseSetupArgs([]string{"--answers", f, "--domain", "flag.example.org"})
	if err != nil {
		t.Fatal(err)
	}
	if a.vals["domain"] != "flag.example.org" || a.vals["proxy"] != "caddy" || a.vals["acme-email"] != "me@example.org" {
		t.Errorf("got %v", a.vals)
	}

	if err := os.WriteFile(f, []byte("domain=x\nadmin_password=oops\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := parseSetupArgs([]string{"--answers", f}); err == nil || !strings.Contains(err.Error(), "line 2") {
		t.Errorf("an unknown key in the file should name its line, got %v", err)
	}
	if _, err := parseSetupArgs([]string{"--answers", filepath.Join(t.TempDir(), "missing")}); err == nil {
		t.Errorf("a missing answers file should fail")
	}
}

// With --yes nothing is read from the terminal: a given answer is used, and
// the default stands for the rest.
func TestPrompterAuto(t *testing.T) {
	p := &prompter{r: bufio.NewReader(strings.NewReader("")), ans: map[string]string{"domain": "example.org", "db": "postgres"}, auto: true}
	if got := p.ask("domain", "Public domain", "old.example.org"); got != "example.org" {
		t.Errorf("a given answer wins over the default, got %q", got)
	}
	if got := p.ask("data-path", "Data path", "/srv/data"); got != "/srv/data" {
		t.Errorf("an unanswered question takes its default, got %q", got)
	}
	dbs := [][2]string{{"sqlite", ""}, {"postgres", ""}}
	if got := p.choose("db", "Database:", dbs, "sqlite"); got != "postgres" {
		t.Errorf("choose = %q", got)
	}
	if !p.yes("Keep it?", true) || p.yes("Replace it?", false) {
		t.Errorf("yes/no questions take their default")
	}
	if p.err != nil {
		t.Errorf("unexpected error: %v", p.err)
	}
}

func TestPrompterRejectsABadChoice(t *testing.T) {
	proxies := [][2]string{{"existing", ""}, {"caddy", ""}, {"tunnel", ""}}

	p := &prompter{r: bufio.NewReader(strings.NewReader("")), ans: map[string]string{"proxy": "nginx"}, auto: true}
	p.choose("proxy", "", proxies, "existing")
	if p.err == nil || !strings.Contains(p.err.Error(), "existing, caddy, tunnel") {
		t.Errorf("a wrong choice should list the right ones, got %v", p.err)
	}

	// No answer and no usable default: say which flag is missing.
	p = &prompter{r: bufio.NewReader(strings.NewReader("")), ans: map[string]string{}, auto: true}
	p.choose("proxy", "", proxies, "")
	if p.err == nil || !strings.Contains(p.err.Error(), "--proxy is needed") {
		t.Errorf("got %v", p.err)
	}
}

// Without --yes the questions are still asked, and a given answer skips its own.
func TestPrompterInteractive(t *testing.T) {
	p := &prompter{r: bufio.NewReader(strings.NewReader("typed.example.org\n\n")), ans: map[string]string{"repo": "https://example.org/r.git"}}
	if got := p.ask("repo", "Site repository", "default"); got != "https://example.org/r.git" {
		t.Errorf("given answer: %q", got)
	}
	if got := p.ask("domain", "Public domain", ""); got != "typed.example.org" {
		t.Errorf("typed answer: %q", got)
	}
	if got := p.ask("data-path", "Data path", "/srv/data"); got != "/srv/data" {
		t.Errorf("Enter keeps the default: %q", got)
	}
}

func TestDescribePullFailure(t *testing.T) {
	r := imageRef{"ghcr.io", "fartingaround-org/fartingaround", "edge"}
	// The registry answers anyone, but docker was refused: an old sign-in.
	if got := describePullFailure(r, nil); !strings.Contains(got, "docker logout ghcr.io") {
		t.Errorf("want the logout hint, got %q", got)
	}
	// The registry refuses anyone: say what it said.
	got := describePullFailure(r, errors.New("the registry won't serve this image without a sign-in (is it public?)"))
	if !strings.Contains(got, "is it public?") || strings.Contains(got, "docker logout") {
		t.Errorf("got %q", got)
	}
}
