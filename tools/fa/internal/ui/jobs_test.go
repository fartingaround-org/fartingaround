package ui

import (
	"bufio"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeSSH puts an `ssh` on PATH that prints three lines, slowly enough for a
// reader to arrive in the middle, or sleeps when the remote command says so.
func fakeSSH(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	script := "#!/bin/sh\nfor a; do last=$a; done\ncase \"$last\" in *backup*) sleep 30;; *) echo one; sleep 0.2; echo two; sleep 0.2; echo three;; esac\n"
	if err := os.WriteFile(filepath.Join(dir, "ssh"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
}

func testServer(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()
	s := &Server{token: "k", jobs: map[string]*job{}, cfgPath: filepath.Join(t.TempDir(), "ui.json")}
	s.cfg.Hosts = []Host{{Name: "box", SSH: "tony@box"}}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/start", s.auth(s.start))
	mux.HandleFunc("/api/events", s.auth(s.events))
	mux.HandleFunc("/api/stop", s.auth(s.stopJob))
	mux.HandleFunc("/api/jobs", s.auth(s.listJobs))
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return s, ts
}

func do(t *testing.T, method, url string, hdr ...string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(method, url, nil)
	req.Header.Set("X-FA-Token", "k")
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

// readEvents collects the data lines of an event stream until its end event.
func readEvents(t *testing.T, res *http.Response) (lines []string, end string) {
	t.Helper()
	defer res.Body.Close()
	sc, isEnd := bufio.NewScanner(res.Body), false
	for sc.Scan() {
		l := sc.Text()
		switch {
		case l == "event: end":
			isEnd = true
		case strings.HasPrefix(l, "data: "):
			if isEnd {
				return lines, strings.Trim(l[6:], `"`)
			}
			lines = append(lines, strings.Trim(l[6:], `"`))
		}
	}
	t.Fatalf("stream ended without an end event; got %q", lines)
	return
}

func TestJobOutlivesItsReaderAndReplays(t *testing.T) {
	fakeSSH(t)
	_, ts := testServer(t)

	if res := do(t, "POST", ts.URL+"/api/start?host=box&ch=act&cmd=deploy"); res.StatusCode != 400 {
		t.Fatalf("deploy without confirm: %d, want 400", res.StatusCode)
	}
	if res := do(t, "POST", ts.URL+"/api/start?host=box&ch=act&cmd=deploy&confirm=yes"); res.StatusCode != 200 {
		t.Fatalf("start: %d", res.StatusCode)
	}
	if res := do(t, "POST", ts.URL+"/api/start?host=box&ch=act&cmd=update"); res.StatusCode != http.StatusConflict {
		t.Fatalf("a second job on a busy channel: %d, want 409", res.StatusCode)
	}

	// a first reader leaves after one line: the job must carry on without it
	res := do(t, "GET", ts.URL+"/api/events?host=box&ch=act")
	sc := bufio.NewScanner(res.Body)
	for sc.Scan() && !strings.HasPrefix(sc.Text(), "data: ") {
	}
	res.Body.Close()

	// a reader that arrives later is replayed the whole run, then its end
	lines, end := readEvents(t, do(t, "GET", ts.URL+"/api/events?host=box&ch=act"))
	if strings.Join(lines, ",") != "one,two,three" || end != "ok" {
		t.Fatalf("replay: %q end %q", lines, end)
	}
	// a reconnect resumes after the last line it saw
	lines, _ = readEvents(t, do(t, "GET", ts.URL+"/api/events?host=box&ch=act", "Last-Event-ID", "1"))
	if strings.Join(lines, ",") != "three" {
		t.Fatalf("resume after id 1: %q", lines)
	}
	// finished: the channel is free again
	if res := do(t, "POST", ts.URL+"/api/start?host=box&ch=act&cmd=update"); res.StatusCode != 200 {
		t.Fatalf("start after finish: %d", res.StatusCode)
	}
}

func TestJobStop(t *testing.T) {
	fakeSSH(t)
	_, ts := testServer(t)
	if res := do(t, "POST", ts.URL+"/api/start?host=box&ch=act&cmd=backup"); res.StatusCode != 200 {
		t.Fatalf("start: %d", res.StatusCode)
	}
	time.Sleep(100 * time.Millisecond)
	if res := do(t, "POST", ts.URL+"/api/stop?host=box&ch=act"); res.StatusCode != 200 {
		t.Fatalf("stop: %d", res.StatusCode)
	}
	done := make(chan string, 1)
	go func() { _, end := readEvents(t, do(t, "GET", ts.URL+"/api/events?host=box&ch=act")); done <- end }()
	select {
	case end := <-done:
		if end != "stopped" {
			t.Fatalf("end = %q, want stopped", end)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("a stopped job did not end")
	}
	if res := do(t, "POST", ts.URL+"/api/stop?host=box&ch=act"); res.StatusCode != http.StatusConflict {
		t.Fatalf("stopping a finished job: %d, want 409", res.StatusCode)
	}
}

func TestJobKeepsOnlyTheNewestLines(t *testing.T) {
	j := &job{wake: make(chan struct{})}
	for i := 0; i < jobLinesMax+10; i++ {
		j.add("x")
	}
	lines, next, _, _, _ := j.since(0)
	if len(lines) != jobLinesMax || next != jobLinesMax+10 {
		t.Fatalf("kept %d lines, next %d", len(lines), next)
	}
}

func TestShellQuoteEmpty(t *testing.T) {
	if shellQuote("") != "''" {
		t.Fatal("an empty value must stay an argument")
	}
}
