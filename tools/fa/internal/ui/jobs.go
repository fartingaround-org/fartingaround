package ui

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// A job is a command that belongs to this `fa ui` process and not to the
// page that started it. Its output is kept so a page that opens (or comes
// back) mid-run is replayed what it missed, and closing the tab does not
// take a deploy down with it. One job per server and channel at a time; a
// finished one stays until the next is started there.
type job struct {
	Host    string    `json:"host"`
	Ch      string    `json:"ch"`
	Cmd     string    `json:"cmd"`
	Label   string    `json:"label"` // "fa deploy", as the page prints it
	Started time.Time `json:"started"`

	mu      sync.Mutex
	lines   []string
	base    int // index of lines[0]: the oldest lines are dropped past jobLinesMax
	done    bool
	status  string // "ok", or why not
	stopped bool
	wake    chan struct{} // closed and replaced whenever there is something new
	c       *exec.Cmd
}

const jobLinesMax = 4000

func (j *job) add(line string) {
	j.mu.Lock()
	j.lines = append(j.lines, line)
	if len(j.lines) > jobLinesMax {
		drop := len(j.lines) - jobLinesMax
		j.lines = append(j.lines[:0:0], j.lines[drop:]...)
		j.base += drop
	}
	j.signal()
	j.mu.Unlock()
}

func (j *job) finish(status string) {
	j.mu.Lock()
	if j.stopped {
		status = "stopped"
	}
	j.done, j.status = true, status
	j.signal()
	j.mu.Unlock()
}

// signal wakes every reader. Call with j.mu held.
func (j *job) signal() {
	close(j.wake)
	j.wake = make(chan struct{})
}

// since returns the lines from index `from` on, the index after the last of
// them, whether the job is over (and how), and a channel that closes when
// there is more.
func (j *job) since(from int) (lines []string, next int, done bool, status string, wake <-chan struct{}) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if from < j.base {
		from = j.base
	}
	if i := from - j.base; i < len(j.lines) {
		lines = append(lines, j.lines[i:]...)
	}
	return lines, j.base + len(j.lines), j.done, j.status, j.wake
}

func (j *job) running() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return !j.done
}

// channels a page may start a job on: its consoles.
// act: Actions · env: the restart after a settings change · bak: a backup or
// a restore · upd: fa updating itself
var jobChannels = map[string]bool{"act": true, "env": true, "bak": true, "upd": true}

func jobKey(host, ch string) string { return host + "\x00" + ch }

// start begins a job: POST /api/start?host=&ch=&cmd=[&arg=…][&confirm=yes].
// Refused while another job is running on the same server and channel.
func (s *Server) start(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	q := r.URL.Query()
	ch, name, extra := q.Get("ch"), q.Get("cmd"), q["arg"]
	if !jobChannels[ch] {
		http.Error(w, "unknown channel", 400)
		return
	}
	c, spec, err := s.build(name, h, extra)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if spec.danger && q.Get("confirm") != "yes" {
		http.Error(w, "confirm required", 400)
		return
	}
	j := &job{Host: h.Name, Ch: ch, Cmd: name, Label: strings.TrimSpace("fa " + name + " " + strings.Join(extra, " ")), Started: time.Now(), wake: make(chan struct{}), c: c}

	s.mu.Lock()
	if prev := s.jobs[jobKey(h.Name, ch)]; prev != nil && prev.running() {
		s.mu.Unlock()
		http.Error(w, prev.Label+" is still running there", http.StatusConflict)
		return
	}
	s.jobs[jobKey(h.Name, ch)] = j
	s.mu.Unlock()

	pr, pw := io.Pipe()
	c.Stdout, c.Stderr = pw, pw
	c.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := c.Start(); err != nil {
		j.finish("could not start: " + err.Error())
	} else {
		timer := time.AfterFunc(spec.timeout, func() { j.add("fa: gave up after " + spec.timeout.String()); kill(c) })
		go func() { pw.CloseWithError(c.Wait()) }()
		go func() {
			sc := bufio.NewScanner(pr)
			sc.Buffer(make([]byte, 64<<10), 1<<20)
			for sc.Scan() {
				j.add(sc.Text())
			}
			timer.Stop()
			status := "ok"
			if err := sc.Err(); err != nil {
				status = err.Error()
				io.Copy(io.Discard, pr) // a line too long for the scanner must not leave the command blocked on a full pipe
			}
			j.finish(status)
		}()
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(j)
}

func (s *Server) jobFrom(r *http.Request) *job {
	s.mu.Lock()
	defer s.mu.Unlock()
	h := s.findHost(r.URL.Query().Get("host"))
	if h == nil {
		return nil
	}
	return s.jobs[jobKey(h.Name, r.URL.Query().Get("ch"))]
}

// events replays a job's output and then follows it: GET /api/events?host=&ch=.
// Each line carries its index as the event id, so a connection the browser
// re-opens (Last-Event-ID) resumes where it broke off instead of printing the
// run twice. Closing the connection leaves the job running.
func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	j := s.jobFrom(r)
	if j == nil {
		http.Error(w, "no job there", 404)
		return
	}
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "no streaming", 500)
		return
	}
	from := 0
	if id := r.Header.Get("Last-Event-ID"); id != "" {
		if n, err := strconv.Atoi(id); err == nil {
			from = n + 1
		}
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	for {
		lines, next, done, status, wake := j.since(from)
		for i, l := range lines {
			fmt.Fprintf(w, "id: %d\ndata: %s\n\n", next-len(lines)+i, jsonStr(l))
		}
		from = next
		if done {
			fmt.Fprintf(w, "event: end\ndata: %s\n\n", jsonStr(status))
			fl.Flush()
			return
		}
		fl.Flush()
		select {
		case <-wake:
		case <-r.Context().Done():
			return
		}
	}
}

// stopJob ends the running job on a server and channel: POST /api/stop?host=&ch=.
func (s *Server) stopJob(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	j := s.jobFrom(r)
	if j == nil || !j.running() {
		http.Error(w, "nothing is running there", http.StatusConflict)
		return
	}
	j.mu.Lock()
	j.stopped = true
	j.mu.Unlock()
	kill(j.c)
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

// listJobs is what a page asks when it opens or changes server: the job last
// started on each of that server's channels, running or finished, so it can
// show the output it was not there for. GET /api/jobs?host=.
func (s *Server) listJobs(w http.ResponseWriter, r *http.Request) {
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	type entry struct {
		*job
		Running bool `json:"running"`
	}
	out := []entry{}
	s.mu.Lock()
	for ch := range jobChannels {
		if j := s.jobs[jobKey(h.Name, ch)]; j != nil {
			out = append(out, entry{j, j.running()})
		}
	}
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"jobs": out})
}
