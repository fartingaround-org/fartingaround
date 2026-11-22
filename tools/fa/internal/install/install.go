// Package install locates and describes one fartingaround install: the
// checked-out site repo, its .env, and fa's own small settings file.
package install

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Settings is .fa.json in the install dir: things fa decided during setup
// that aren't the site's business.
type Settings struct {
	// Proxy is how the site is reached: "existing" (a reverse proxy you already
	// run, on the `web` network), "caddy" (fa runs one), or "tunnel"
	// (cloudflared, for a box with no inbound ports — a Pi at home).
	Proxy string `json:"proxy"`
	// CaddyContainer is the container name of the reverse proxy for
	// `fa logs caddy` and the doctor's reachability check.
	CaddyContainer string `json:"caddyContainer,omitempty"`
	// Domain is the public hostname.
	Domain string `json:"domain"`
	// BackupDir is where `fa backup` writes tarballs.
	BackupDir string `json:"backupDir,omitempty"`
	// BackupProfile is this site's profile name in the server's shared
	// resticprofile file (`fa backups`); "fartingaround" when empty.
	BackupProfile string `json:"backupProfile,omitempty"`
	// Repo is the git remote the install was cloned from.
	Repo string `json:"repo,omitempty"`
}

// Install is a located install.
type Install struct {
	Dir      string
	Env      *Env
	Settings Settings
}

// DefaultRepo is the reference implementation.
const DefaultRepo = "https://github.com/fartingaround-org/fartingaround.git"

// Candidates lists where fa looks for an install when FA_DIR isn't set.
func Candidates() []string {
	home, _ := os.UserHomeDir()
	c := []string{}
	if d := os.Getenv("FA_DIR"); d != "" {
		c = append(c, d)
	}
	if wd, err := os.Getwd(); err == nil {
		c = append(c, wd)
	}
	c = append(c, "/opt/fartingaround", "/opt/fartingaround.fun")
	if home != "" {
		c = append(c, filepath.Join(home, "fartingaround"), filepath.Join(home, "fartingaround.fun"))
	}
	return c
}

// IsInstall says whether dir holds a site checkout (compose file + package.json).
func IsInstall(dir string) bool {
	for _, f := range []string{"docker-compose.yml", "package.json"} {
		if _, err := os.Stat(filepath.Join(dir, f)); err != nil {
			return false
		}
	}
	return true
}

// Find locates the install, honouring an explicit dir first.
func Find(explicit string) (*Install, error) {
	dirs := Candidates()
	if explicit != "" {
		dirs = []string{explicit}
	}
	for _, d := range dirs {
		if IsInstall(d) {
			return Load(d)
		}
	}
	return nil, errors.New("no fartingaround install found — run `fa setup`, or point at one with --dir / FA_DIR")
}

// Load reads an install at dir.
func Load(dir string) (*Install, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	in := &Install{Dir: abs}
	in.Env, err = ReadEnv(filepath.Join(abs, ".env"))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if in.Env == nil {
		in.Env = &Env{path: filepath.Join(abs, ".env")}
	}
	if b, err := os.ReadFile(filepath.Join(abs, ".fa.json")); err == nil {
		_ = json.Unmarshal(b, &in.Settings)
	}
	if in.Settings.Proxy == "" {
		in.Settings.Proxy = "existing"
	}
	return in, nil
}

// SaveSettings writes .fa.json.
func (in *Install) SaveSettings() error {
	b, err := json.MarshalIndent(in.Settings, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(in.Dir, ".fa.json"), append(b, '\n'), 0o600)
}

// DataPath is the host folder mounted at /data (DATA_PATH, default ./data).
func (in *Install) DataPath() string {
	p := in.Env.Get("DATA_PATH")
	if p == "" {
		p = "./data"
	}
	if !filepath.IsAbs(p) {
		p = filepath.Join(in.Dir, p)
	}
	return p
}

// DebugPort is the 127.0.0.1 port compose publishes for on-host checks.
func (in *Install) DebugPort() string {
	if p := in.Env.Get("DEBUG_PORT"); p != "" {
		return p
	}
	return "8406"
}

// UsesPostgres is true when DATABASE_URL is set.
func (in *Install) UsesPostgres() bool { return in.Env.Get("DATABASE_URL") != "" }

// ComposeFiles returns the -f arguments for this install's proxy mode.
func (in *Install) ComposeFiles() []string {
	files := []string{"-f", "docker-compose.yml"}
	switch in.Settings.Proxy {
	case "caddy":
		files = append(files, "-f", "fa/docker-compose.caddy.yml")
	case "tunnel":
		files = append(files, "-f", "fa/docker-compose.tunnel.yml")
	}
	return files
}

// ── .env ────────────────────────────────────────────────────────────────────

// Env is a dotenv file kept as lines so comments and order survive edits.
type Env struct {
	path  string
	lines []string
}

// ReadEnv parses path.
func ReadEnv(path string) (*Env, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	e := &Env{path: path}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		e.lines = append(e.lines, sc.Text())
	}
	return e, sc.Err()
}

// Get returns a value ("" when absent). Surrounding quotes are stripped.
func (e *Env) Get(key string) string {
	for _, l := range e.lines {
		k, v, ok := splitKV(l)
		if ok && k == key {
			return v
		}
	}
	return ""
}

// Set replaces or appends KEY=value.
func (e *Env) Set(key, value string) {
	if strings.ContainsAny(value, " #\"'\n") {
		value = `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(value) + `"`
	}
	for i, l := range e.lines {
		if k, _, ok := splitKV(l); ok && k == key {
			e.lines[i] = key + "=" + value
			return
		}
	}
	e.lines = append(e.lines, key+"="+value)
}

// Keys lists the keys present, in file order.
func (e *Env) Keys() []string {
	var out []string
	for _, l := range e.lines {
		if k, _, ok := splitKV(l); ok {
			out = append(out, k)
		}
	}
	return out
}

// Unset removes every KEY=… line; it reports whether there was one.
func (e *Env) Unset(key string) bool {
	kept, found := e.lines[:0:0], false
	for _, l := range e.lines {
		if k, _, ok := splitKV(l); ok && k == key {
			found = true
			continue
		}
		kept = append(kept, l)
	}
	e.lines = kept
	return found
}

// Save writes the file back, 0600. The file as it was is kept beside it as
// .env.fa-backup (one generation), so a wrong edit can be undone by hand.
func (e *Env) Save() error {
	if prev, err := os.ReadFile(e.path); err == nil {
		_ = os.WriteFile(e.path+".fa-backup", prev, 0o600)
	}
	return os.WriteFile(e.path, []byte(strings.Join(e.lines, "\n")+"\n"), 0o600)
}

// Path is the file this Env came from.
func (e *Env) Path() string { return e.path }

func splitKV(line string) (string, string, bool) {
	t := strings.TrimSpace(line)
	if t == "" || strings.HasPrefix(t, "#") {
		return "", "", false
	}
	t = strings.TrimPrefix(t, "export ")
	i := strings.IndexByte(t, '=')
	if i <= 0 {
		return "", "", false
	}
	k := strings.TrimSpace(t[:i])
	v := strings.TrimSpace(t[i+1:])
	switch {
	case strings.HasPrefix(v, `"`):
		if j := strings.Index(v[1:], `"`); j >= 0 {
			v = strings.NewReplacer(`\"`, `"`, `\\`, `\`).Replace(v[1 : j+1])
		}
	case strings.HasPrefix(v, "'"):
		if j := strings.Index(v[1:], "'"); j >= 0 {
			v = v[1 : j+1]
		}
	default:
		if j := strings.Index(v, " #"); j >= 0 {
			v = strings.TrimSpace(v[:j])
		}
	}
	return k, v, true
}

// Describe is a one-line summary for status.
func (in *Install) Describe() string {
	db := "sqlite"
	if in.UsesPostgres() {
		db = "postgres"
	}
	return fmt.Sprintf("%s  (db: %s, data: %s, proxy: %s)", in.Dir, db, in.DataPath(), in.Settings.Proxy)
}
