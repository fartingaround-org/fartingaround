// Package ui is `fa ui`: a dashboard served on 127.0.0.1 for the browser on
// this machine. Every action is one of fa's ordinary commands run over ssh
// (or `fa pull` locally), streamed back as server-sent events. Nothing here
// listens anywhere but localhost, and each run has its own token so another
// page in your browser can't drive it.
//
// Two ways to run a command. /api/stream ties it to the request: closing the
// page ends it, which is right for a log follow. /api/start makes it a job
// (jobs.go) that belongs to this `fa ui` process: a deploy keeps going when
// the tab closes, and a page that comes back is replayed what it missed.
package ui

import (
	"bufio"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed static/*
var static embed.FS

// Host is a server the UI knows about.
type Host struct {
	Name string `json:"name"`
	SSH  string `json:"ssh"`           // user@box
	Dir  string `json:"dir,omitempty"` // --dir on the remote side, when the install isn't auto-found
}

type config struct {
	Hosts []Host `json:"hosts"`
}

// Server holds state for one `fa ui` run.
type Server struct {
	token   string
	version string
	cfgPath string
	mu      sync.Mutex
	cfg     config
	selfExe string
	jobs    map[string]*job // host + channel → the job last started there (jobs.go)
}

// Options for Serve.
type Options struct {
	Port    int
	Open    bool
	Version string
	// InitialHost (from --host) is added to the host list if missing.
	InitialHost, InitialDir string
}

// Serve runs until interrupted.
func Serve(o Options) error {
	s := &Server{version: o.Version, jobs: map[string]*job{}}
	// A restart after a self-update keeps the token, so the page that asked for
	// it is still the page (restart, below).
	if t := os.Getenv("FA_UI_TOKEN"); len(t) == 32 {
		s.token = t
	} else {
		b := make([]byte, 16)
		rand.Read(b)
		s.token = hex.EncodeToString(b)
	}
	s.selfExe, _ = os.Executable()

	cfgDir, _ := os.UserConfigDir()
	if cfgDir == "" {
		home, _ := os.UserHomeDir()
		cfgDir = filepath.Join(home, ".config")
	}
	s.cfgPath = filepath.Join(cfgDir, "fa", "ui.json")
	s.loadConfig()
	if o.InitialHost != "" && s.findHost(o.InitialHost) == nil {
		s.cfg.Hosts = append(s.cfg.Hosts, Host{Name: o.InitialHost, SSH: o.InitialHost, Dir: o.InitialDir})
		s.saveConfig()
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", s.index)
	mux.Handle("/static/", http.FileServer(http.FS(static)))
	mux.HandleFunc("/api/hosts", s.auth(s.hosts))
	mux.HandleFunc("/api/run", s.auth(s.run))
	mux.HandleFunc("/api/stream", s.auth(s.stream))
	mux.HandleFunc("/api/env", s.auth(s.env))
	mux.HandleFunc("/api/backups", s.auth(s.backups))
	mux.HandleFunc("/api/restart", s.auth(s.restart))
	mux.HandleFunc("/api/start", s.auth(s.start))
	mux.HandleFunc("/api/events", s.auth(s.events))
	mux.HandleFunc("/api/stop", s.auth(s.stopJob))
	mux.HandleFunc("/api/jobs", s.auth(s.listJobs))

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", o.Port))
	if err != nil {
		return err
	}
	url := fmt.Sprintf("http://%s/?t=%s", ln.Addr().String(), s.token)
	fmt.Printf("fa ui at %s\n(ctrl-c to stop)\n", url)
	if o.Open {
		openBrowser(url)
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	return srv.Serve(ln)
}

func (s *Server) loadConfig() {
	b, err := os.ReadFile(s.cfgPath)
	if err == nil {
		_ = json.Unmarshal(b, &s.cfg)
	}
}

func (s *Server) saveConfig() {
	os.MkdirAll(filepath.Dir(s.cfgPath), 0o700)
	b, _ := json.MarshalIndent(s.cfg, "", "  ")
	_ = os.WriteFile(s.cfgPath, b, 0o600)
}

func (s *Server) findHost(name string) *Host {
	for i := range s.cfg.Hosts {
		if s.cfg.Hosts[i].Name == name || s.cfg.Hosts[i].SSH == name {
			return &s.cfg.Hosts[i]
		}
	}
	return nil
}

// auth checks the per-run token (query param or header) and refuses
// anything cross-origin — the page is the only client.
func (s *Server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		t := r.Header.Get("X-FA-Token")
		if t == "" {
			t = r.URL.Query().Get("t")
		}
		if t != s.token {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if o := r.Header.Get("Origin"); o != "" && !strings.HasPrefix(o, "http://127.0.0.1") && !strings.HasPrefix(o, "http://localhost") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		h(w, r)
	}
}

func (s *Server) index(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	if r.URL.Query().Get("t") != s.token {
		http.Error(w, "fa ui: open the exact link fa printed (it carries this run's token)", http.StatusForbidden)
		return
	}
	b, _ := static.ReadFile("static/index.html")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(b)
}

// ── hosts ───────────────────────────────────────────────────────────────────

func (s *Server) hosts(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch r.Method {
	case http.MethodGet:
	case http.MethodPost:
		// "was" is the name the server is listed under now, when the save is
		// also renaming it.
		var in struct {
			Host
			Was string `json:"was"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil || strings.TrimSpace(in.SSH) == "" {
			http.Error(w, "bad host", 400)
			return
		}
		h := in.Host
		h.SSH, h.Name = strings.TrimSpace(h.SSH), strings.TrimSpace(h.Name)
		if h.Name == "" {
			h.Name = h.SSH
		}
		if in.Was != "" && in.Was != h.Name {
			if msg := s.renameHost(in.Was, h); msg != "" {
				http.Error(w, msg, 409)
				return
			}
		} else if ex := s.findHost(h.Name); ex != nil {
			*ex = h
		} else {
			s.cfg.Hosts = append(s.cfg.Hosts, h)
		}
		s.saveConfig()
	case http.MethodDelete:
		name := r.URL.Query().Get("name")
		for i, h := range s.cfg.Hosts {
			if h.Name == name {
				s.cfg.Hosts = append(s.cfg.Hosts[:i], s.cfg.Hosts[i+1:]...)
				break
			}
		}
		s.saveConfig()
	default:
		http.Error(w, "method", 405)
		return
	}
	sort.Slice(s.cfg.Hosts, func(i, j int) bool { return s.cfg.Hosts[i].Name < s.cfg.Hosts[j].Name })
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"hosts": s.cfg.Hosts, "version": s.version})
}

// renameHost replaces the entry called was with h, which carries the new
// name. It returns why not, when it can't. Call with s.mu held.
func (s *Server) renameHost(was string, h Host) string {
	at := -1
	for i, ex := range s.cfg.Hosts {
		switch ex.Name {
		case h.Name:
			return fmt.Sprintf("there is already a server called %s — give this one another name", h.Name)
		case was:
			at = i
		}
	}
	if at < 0 {
		return fmt.Sprintf("no server called %s in the list any more", was)
	}
	// Jobs are filed under the server's name. A finished one is only a log to
	// look back at and can go; a running one must not lose its name.
	for k, j := range s.jobs {
		if j.Host != was {
			continue
		}
		if j.running() {
			return fmt.Sprintf("%s is still running on %s — rename it when that has finished", j.Label, was)
		}
		delete(s.jobs, k)
	}
	s.cfg.Hosts[at] = h
	return ""
}

// ── commands ────────────────────────────────────────────────────────────────

// Fixed vocabulary: the browser picks a name, never passes a command line.
// `local` runs this fa binary here (pull); the rest run `fa <args>` over ssh.
type cmdSpec struct {
	args    []string
	local   bool
	danger  bool // needs the confirm the page shows
	timeout time.Duration
}

var commands = map[string]cmdSpec{
	"status":  {args: []string{"status"}, timeout: 60 * time.Second},
	"doctor":  {args: []string{"doctor"}, timeout: 90 * time.Second},
	"disk":    {args: []string{"disk"}, timeout: 5 * time.Minute},
	"info":    {args: []string{"info"}, timeout: 30 * time.Second},
	"version": {args: []string{"version"}, timeout: 30 * time.Second},
	"update":  {args: []string{"update"}, timeout: 2 * time.Minute},
	"deploy":  {args: []string{"deploy"}, danger: true, timeout: 20 * time.Minute},
	"restart": {args: []string{"restart"}, danger: true, timeout: 3 * time.Minute},
	"up":      {args: []string{"up"}, danger: true, timeout: 20 * time.Minute},
	"backup":  {args: []string{"backup"}, timeout: 60 * time.Minute},
	"token":   {args: []string{"token", "rotate"}, danger: true, timeout: 3 * time.Minute},
	"pull":    {args: []string{"pull"}, local: true, timeout: 6 * time.Hour},
	"logs":    {args: []string{"logs"}, timeout: 24 * time.Hour},
	"env":     {args: []string{"env", "list"}, timeout: 30 * time.Second},
	// Off-server backups (restic). Reading is plain; a restore replaces the
	// site's data, so it is confirmed on the page, which is why --yes is fixed
	// here: the server-side prompt has no terminal to ask on.
	// Pull, build and replace fa: on this computer, then on the server (local:
	// it builds here and copies the result over). The page restarts fa ui after.
	"selfupdate": {args: []string{"self-update"}, local: true, danger: true, timeout: 20 * time.Minute},
	"backups-show":    {args: []string{"backups", "show", "--json"}, timeout: 60 * time.Second},
	"backups-list":    {args: []string{"backups", "list", "--json"}, timeout: 3 * time.Minute},
	"backups-run":     {args: []string{"backups", "run"}, timeout: 12 * time.Hour},
	"backups-restore": {args: []string{"backups", "restore", "--yes"}, danger: true, timeout: 12 * time.Hour},
}

// build turns a command name + host into an exec.Cmd.
func (s *Server) build(name string, h *Host, extra []string) (*exec.Cmd, cmdSpec, error) {
	spec, ok := commands[name]
	if !ok {
		return nil, spec, errors.New("unknown command")
	}
	for _, e := range extra {
		if !safeArg(e) {
			return nil, spec, errors.New("bad argument")
		}
	}
	remote := []string{"fa"}
	if h.Dir != "" {
		remote = append(remote, "--dir", shellQuote(h.Dir))
	}
	if spec.local {
		args := append([]string{}, spec.args...)
		args = append(args, h.SSH)
		if h.Dir != "" {
			args = append([]string{"--dir", h.Dir}, args...)
		}
		return exec.Command(s.selfExe, append(args, extra...)...), spec, nil
	}
	remote = append(remote, spec.args...)
	for _, e := range extra {
		remote = append(remote, shellQuote(e))
	}
	c := exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", h.SSH, strings.Join(remote, " "))
	return c, spec, nil
}

// safeArg allows the handful of option-shaped extras the page sends (log target, -f, -n 200, --since 15m).
func safeArg(a string) bool {
	if a == "" || len(a) > 32 {
		return false
	}
	for _, r := range a {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.') {
			return false
		}
	}
	return true
}

func shellQuote(s string) string {
	if s == "" {
		return "''" // an empty value must still be an argument
	}
	if !strings.ContainsAny(s, " \t\n'\"\\$`!*?[]{}()<>|&;#~") {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

// run executes a short command and returns its output whole.
func (s *Server) run(w http.ResponseWriter, r *http.Request) {
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	c, spec, err := s.build(r.URL.Query().Get("cmd"), h, nil)
	if err != nil || spec.danger {
		http.Error(w, "not allowed here", 400)
		return
	}
	done := make(chan struct{})
	go func() {
		select {
		case <-done:
		case <-time.After(spec.timeout):
			if c.Process != nil {
				c.Process.Kill()
			}
		}
	}()
	out, err := c.CombinedOutput()
	close(done)
	w.Header().Set("Content-Type", "application/json")
	res := map[string]any{"output": string(out), "ok": err == nil}
	if err != nil {
		res["error"] = err.Error()
	}
	json.NewEncoder(w).Encode(res)
}

// stream runs a command and sends each output line as an SSE event. Closing
// the page cancels it (and kills the ssh / rsync underneath).
func (s *Server) stream(w http.ResponseWriter, r *http.Request) {
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	extra := r.URL.Query()["arg"]
	c, spec, err := s.build(r.URL.Query().Get("cmd"), h, extra)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if spec.danger && r.URL.Query().Get("confirm") != "yes" {
		http.Error(w, "confirm required", 400)
		return
	}
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "no streaming", 500)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")

	pr, pw := io.Pipe()
	c.Stdout, c.Stderr = pw, pw
	c.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := c.Start(); err != nil {
		fmt.Fprintf(w, "event: end\ndata: %s\n\n", jsonStr("could not start: "+err.Error()))
		fl.Flush()
		return
	}
	go func() {
		err := c.Wait()
		pw.CloseWithError(err)
	}()
	ctx := r.Context()
	go func() {
		select {
		case <-ctx.Done():
			kill(c)
		case <-time.After(spec.timeout):
			kill(c)
		}
	}()
	sc := bufio.NewScanner(pr)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		fmt.Fprintf(w, "data: %s\n\n", jsonStr(sc.Text()))
		fl.Flush()
	}
	status := "ok"
	if err := sc.Err(); err != nil && !errors.Is(err, io.EOF) {
		status = err.Error()
	}
	fmt.Fprintf(w, "event: end\ndata: %s\n\n", jsonStr(status))
	fl.Flush()
}

func kill(c *exec.Cmd) {
	if c.Process == nil {
		return
	}
	// Whole process group: ssh's child, rsync, etc.
	syscall.Kill(-c.Process.Pid, syscall.SIGTERM)
	time.AfterFunc(3*time.Second, func() { syscall.Kill(-c.Process.Pid, syscall.SIGKILL) })
}

// env: GET lists (masked by remote fa), POST sets one key, or removes it
// when Unset is true.
func (s *Server) env(w http.ResponseWriter, r *http.Request) {
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	switch r.Method {
	case http.MethodGet:
		c, _, _ := s.build("env", h, nil)
		out, err := c.CombinedOutput()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"output": string(out), "ok": err == nil})
	case http.MethodPost:
		var body struct {
			Key, Value string
			Unset      bool
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&body); err != nil || !isEnvKey(body.Key) {
			http.Error(w, "bad key", 400)
			return
		}
		remote := "fa"
		if h.Dir != "" {
			remote += " --dir " + shellQuote(h.Dir)
		}
		if body.Unset {
			remote += " env unset " + body.Key
		} else {
			remote += " env set " + body.Key + " " + shellQuote(body.Value)
		}
		out, err := exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", h.SSH, remote).CombinedOutput()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"output": string(out), "ok": err == nil})
	default:
		http.Error(w, "method", 405)
	}
}

// restart replaces this process with the fa now at the same path: the last
// step of a self-update. Same port, same token, so the open page carries on
// after a reload. Refused while a job is running, since jobs belong to this
// process and would die with it.
func (s *Server) restart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	s.mu.Lock()
	for _, j := range s.jobs {
		if j.running() {
			s.mu.Unlock()
			http.Error(w, j.Label+" is still running on "+j.Host+"; wait for it or stop it first", http.StatusConflict)
			return
		}
	}
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true})
	if fl, ok := w.(http.Flusher); ok {
		fl.Flush()
	}
	go func() {
		time.Sleep(300 * time.Millisecond) // let the answer reach the page
		os.Setenv("FA_UI_TOKEN", s.token)
		os.Setenv("FA_UI_RESTARTED", "1") // do not open another browser tab
		err := syscall.Exec(s.selfExe, os.Args, os.Environ())
		fmt.Fprintln(os.Stderr, "fa ui: could not restart into the new fa:", err, "— stop it (ctrl-c) and run fa ui again")
	}()
}

// backups changes one backup setting: POST {action, …}. The values (an
// address, a storage key) are free text, so they travel as a JSON body and
// are shell-quoted here, never put in the URL.
//
//	setup     repo, env{KEY: value}     first time on this server
//	schedule  time ("03:30" or "off")
//	keep      daily, weekly, monthly
//	repo      repo
//	env       key, value ("" removes the key)
func (s *Server) backups(w http.ResponseWriter, r *http.Request) {
	h := s.hostFrom(r)
	if h == nil {
		http.Error(w, "unknown host", 400)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	var body struct {
		Action, Repo, Time, Key, Value string
		Daily, Weekly, Monthly         int
		Env                            map[string]string
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&body); err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	args, err := backupsArgs(body.Action, body.Repo, body.Time, body.Key, body.Value, body.Daily, body.Weekly, body.Monthly, body.Env)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	remote := "fa"
	if h.Dir != "" {
		remote += " --dir " + shellQuote(h.Dir)
	}
	for _, a := range args {
		remote += " " + shellQuote(a)
	}
	c := exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", h.SSH, remote)
	done := make(chan struct{})
	go func() {
		select {
		case <-done:
		case <-time.After(3 * time.Minute): // setup reaches out to the storage
			if c.Process != nil {
				c.Process.Kill()
			}
		}
	}()
	out, err := c.CombinedOutput()
	close(done)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"output": string(out), "ok": err == nil})
}

// backupsArgs turns a settings change into the `fa backups …` arguments,
// refusing anything that is not one of the known shapes.
func backupsArgs(action, repo, at, key, value string, daily, weekly, monthly int, env map[string]string) ([]string, error) {
	repo = strings.TrimSpace(repo)
	if strings.ContainsAny(repo+at+key+value, "\n\r\x00") {
		return nil, errors.New("one line, please")
	}
	switch action {
	case "setup":
		// no address: join the place another site on the server already uses
		args := []string{"backups", "setup"}
		if repo != "" {
			args = append(args, "--repo", repo)
		}
		keys := make([]string, 0, len(env))
		for k := range env {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if !isEnvKey(k) || strings.ContainsAny(env[k], "\n\r\x00") {
				return nil, errors.New("bad key: " + k)
			}
			if env[k] != "" {
				args = append(args, "--env", k+"="+env[k])
			}
		}
		return args, nil
	case "schedule":
		return []string{"backups", "set", "schedule", at}, nil // fa on the server checks the form
	case "keep":
		for _, n := range []int{daily, weekly, monthly} {
			if n < 0 || n > 999 {
				return nil, errors.New("keep counts are 0 to 999")
			}
		}
		return []string{"backups", "set", "keep", fmt.Sprintf("daily=%d", daily), fmt.Sprintf("weekly=%d", weekly), fmt.Sprintf("monthly=%d", monthly)}, nil
	case "repo":
		if repo == "" {
			return nil, errors.New("say where the backups go")
		}
		return []string{"backups", "set", "repo", repo}, nil
	case "env":
		if !isEnvKey(key) {
			return nil, errors.New("a key is capitals, digits and underscores")
		}
		return []string{"backups", "set", "env", key + "=" + value}, nil
	}
	return nil, errors.New("unknown action")
}

func isEnvKey(k string) bool {
	if k == "" || len(k) > 64 {
		return false
	}
	for _, r := range k {
		if !(r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_') {
			return false
		}
	}
	return true
}

func (s *Server) hostFrom(r *http.Request) *Host {
	s.mu.Lock()
	defer s.mu.Unlock()
	h := s.findHost(r.URL.Query().Get("host"))
	if h == nil {
		return nil
	}
	cp := *h
	return &cp
}

func jsonStr(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

func openBrowser(url string) {
	var c *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		c = exec.Command("open", url)
	case "windows":
		c = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		c = exec.Command("xdg-open", url)
	}
	_ = c.Start()
}
