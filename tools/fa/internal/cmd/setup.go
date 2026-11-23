package cmd

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	register(&command{name: "setup", summary: "first run: get the site, write .env, pick how it's exposed, start it (fa setup --help for doing it without questions)", noInstall: true, run: runSetup})
}

// setupKeys are the questions setup can be answered ahead of time, in the
// order it asks them. Each is a flag (--domain example.org) and a line in an
// answers file (domain=example.org).
var setupKeys = []struct{ key, help string }{
	{"dir", "install directory"},
	{"repo", "where to get the site from (leave alone unless you run your own fork)"},
	{"domain", "public domain, e.g. example.org"},
	{"data-path", "folder for the database and all media"},
	{"db", "sqlite or postgres"},
	{"database-url", "postgres://… (with --db postgres)"},
	{"admin-password", "password for /admin (generated when not given)"},
	{"proxy", "existing, caddy or tunnel"},
	{"proxy-container", "your reverse proxy's container name (with --proxy existing)"},
	{"acme-email", "email for Let's Encrypt notices (with --proxy caddy)"},
	{"tunnel-token", "Cloudflare Tunnel token (with --proxy tunnel)"},
	{"version", "which published image to run: latest, edge, or a release such as 0.2.0"},
}

// secretAnswers are never echoed back.
var secretAnswers = map[string]bool{"admin-password": true, "database-url": true, "tunnel-token": true}

func setupUsage() {
	say("usage: fa setup [--yes] [--answers FILE] [--<question> VALUE …] [--env KEY=VALUE …] [--start | --no-start]")
	say("\nWith no flags, setup asks each question. Any question can be answered ahead of time:")
	for _, k := range setupKeys {
		say("  --%-16s %s", k.key, k.help)
	}
	say("\n  --answers FILE     the same answers as lines of key=value (# starts a comment); flags win over the file")
	say("  --env KEY=VALUE    also set this in the site's .env, e.g. --env ELEVENLABS_API_KEY=…  (repeatable)")
	say("  --yes, -y          ask nothing: use the answers given and the defaults for the rest")
	say("  --start            start the site when done (the default with --yes)")
	say("  --no-start         write the files and stop")
}

// setupAnswers is what the command line and the answers file said.
type setupAnswers struct {
	vals  map[string]string
	env   [][2]string
	auto  bool
	start *bool
	help  bool
}

func isSetupKey(k string) bool {
	for _, s := range setupKeys {
		if s.key == k {
			return true
		}
	}
	return false
}

// parseSetupArgs reads the flags; --answers FILE is loaded first and the
// flags laid over it.
func parseSetupArgs(args []string) (*setupAnswers, error) {
	a := &setupAnswers{vals: map[string]string{}}
	flags := map[string]string{}
	file := ""
	yes, no := true, false
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "-") {
			return nil, errorf("setup: unexpected %q — see fa setup --help", arg)
		}
		name, value, hasValue := strings.Cut(strings.TrimLeft(arg, "-"), "=")
		switch name {
		case "help", "h":
			a.help = true
			continue
		case "yes", "y":
			a.auto = true
			continue
		case "start":
			a.start = &yes
			continue
		case "no-start":
			a.start = &no
			continue
		}
		if name != "answers" && name != "env" && !isSetupKey(name) {
			return nil, errorf("setup: unknown flag --%s — see fa setup --help", name)
		}
		if !hasValue {
			if i+1 >= len(args) {
				return nil, errorf("setup: --%s needs a value", name)
			}
			i++
			value = args[i]
		}
		switch name {
		case "answers":
			file = value
		case "env":
			k, v, ok := strings.Cut(value, "=")
			if !ok || strings.TrimSpace(k) == "" {
				return nil, errorf("setup: --env wants KEY=VALUE, got %q", value)
			}
			a.env = append(a.env, [2]string{strings.TrimSpace(k), v})
		default:
			flags[name] = value
		}
	}
	if file != "" {
		b, err := os.ReadFile(file)
		if err != nil {
			return nil, errorf("setup: can't read the answers file: %v", err)
		}
		for n, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			k, v, ok := strings.Cut(line, "=")
			k = strings.TrimSpace(k)
			if !ok {
				return nil, errorf("setup: %s line %d is not key=value", file, n+1)
			}
			if !isSetupKey(k) {
				return nil, errorf("setup: %s line %d: %q is not a setup question — see fa setup --help", file, n+1, k)
			}
			a.vals[k] = strings.TrimSpace(v)
		}
	}
	for k, v := range flags {
		a.vals[k] = v
	}
	return a, nil
}

// prompter asks a question unless it was answered ahead of time, or setup was
// told to ask nothing (auto), in which case the default stands.
type prompter struct {
	r    *bufio.Reader
	ans  map[string]string
	auto bool
	err  error // the first answer that could not be used
}

func (p *prompter) given(key string) (string, bool) {
	v, ok := p.ans[key]
	return v, ok
}

func (p *prompter) fail(format string, a ...any) {
	if p.err == nil {
		p.err = errorf(format, a...)
	}
}

// asking reports whether the question for key will really be put to a person:
// not when the answer was given, and not with --yes.
func (p *prompter) asking(key string) bool {
	_, ok := p.given(key)
	return !ok && !p.auto
}

// note explains the question that follows, when it is going to be asked.
func (p *prompter) note(key string, lines ...string) {
	if !p.asking(key) {
		return
	}
	for _, l := range lines {
		say("%s", l)
	}
}

func (p *prompter) ask(key, q, def string) string {
	if v, ok := p.given(key); ok {
		if secretAnswers[key] {
			say("%s: (given)", q)
		} else {
			say("%s: %s", q, v)
		}
		return v
	}
	if p.auto {
		return def
	}
	if def != "" {
		fmt.Printf("%s [%s]: ", q, def)
	} else {
		fmt.Printf("%s: ", q)
	}
	s, _ := p.r.ReadString('\n')
	s = strings.TrimSpace(s)
	if s == "" {
		return def
	}
	return s
}

func (p *prompter) choose(key, q string, options [][2]string, def string) string {
	valid := func(a string) bool {
		for _, o := range options {
			if a == o[0] {
				return true
			}
		}
		return false
	}
	names := make([]string, len(options))
	for i, o := range options {
		names[i] = o[0]
	}
	if v, ok := p.given(key); ok {
		if !valid(v) {
			p.fail("setup: --%s must be one of %s, not %q", key, strings.Join(names, ", "), v)
			return def
		}
		label := strings.TrimSpace(q)
		if label == "" {
			label = key + ":"
		}
		say("%s %s", label, v)
		return v
	}
	if p.auto {
		if !valid(def) {
			p.fail("setup: --%s is needed (one of %s)", key, strings.Join(names, ", "))
		}
		return def
	}
	fmt.Println(q)
	for _, o := range options {
		mark := " "
		if o[0] == def {
			mark = "*"
		}
		fmt.Printf("  %s %-9s %s\n", mark, o[0], o[1])
	}
	for {
		a := p.ask("", "choice", def)
		if valid(a) {
			return a
		}
		fmt.Println("  pick one of the names above")
	}
}

func (p *prompter) yes(q string, def bool) bool {
	if p.auto {
		return def
	}
	d := "y/N"
	if def {
		d = "Y/n"
	}
	a := strings.ToLower(p.ask("", q+" ("+d+")", ""))
	if a == "" {
		return def
	}
	return a == "y" || a == "yes"
}

// runSetup is deliberately linear: ask, write files, start, check. It is
// re-runnable — existing values are offered as defaults. Every question can
// be answered on the command line or in a file, so a script can run it.
func runSetup(c *Ctx, args []string) error {
	a, err := parseSetupArgs(args)
	if err != nil {
		return err
	}
	if a.help {
		setupUsage()
		return nil
	}
	p := &prompter{r: bufio.NewReader(os.Stdin), ans: a.vals, auto: a.auto}
	say("fa setup — a fartingaround site of your own.\n")

	if !sh.Have("docker") {
		return errorf("install Docker first: https://docs.docker.com/engine/install/  (then re-run fa setup)")
	}
	if !sh.Have("git") {
		return errorf("install git first")
	}

	// where
	home, _ := os.UserHomeDir()
	defDir := "/opt/fartingaround"
	if c.Dir != "" {
		defDir = c.Dir
	} else if wd, _ := os.Getwd(); install.IsInstall(wd) {
		defDir = wd
	} else if os.Geteuid() != 0 {
		defDir = filepath.Join(home, "fartingaround")
	}
	dir := p.ask("dir", "Install directory", defDir)

	p.note("repo",
		"",
		"The site's files come from the official fartingaround project.",
		"Unless you really know what you're doing (you run your own fork), press Enter to keep it as is.",
		"Your settings and your content never go there. They stay on this server.")
	repo := p.ask("repo", "Get the site from", install.DefaultRepo)
	if !install.IsInstall(dir) {
		say("cloning %s → %s", repo, dir)
		if err := os.MkdirAll(filepath.Dir(dir), 0o755); err != nil {
			return err
		}
		if _, err := sh.Out("", "git", "clone", "--quiet", repo, dir); err != nil {
			return err
		}
	} else {
		say("using the existing checkout at %s", dir)
	}
	in, err := install.Load(dir)
	if err != nil {
		return err
	}
	in.Settings.Repo = repo
	if !fileExists(in.Env.Path()) {
		if ex, err := install.ReadEnv(filepath.Join(dir, ".env.example")); err == nil {
			for _, k := range ex.Keys() {
				in.Env.Set(k, ex.Get(k))
			}
		}
	}

	// identity
	in.Settings.Domain = p.ask("domain", "Public domain (e.g. example.org)", in.Settings.Domain)
	if in.Settings.Domain == "" && p.auto {
		p.fail("setup: --domain is needed")
	}

	// data
	defData := in.Env.Get("DATA_PATH")
	if defData == "" {
		defData = filepath.Join(dir, "data")
		if m, _ := filepath.Glob("/mnt/*"); len(m) > 0 && !p.auto {
			say("  mounted volumes under /mnt: %s", strings.Join(m, ", "))
		}
	}
	if !p.auto {
		say("\nWhere the database and every photo/video live. A separate volume (block storage,")
		say("a USB disk on a Pi) survives rebuilds and can grow; a folder on the root disk is fine to start.")
	}
	in.Env.Set("DATA_PATH", p.ask("data-path", "Data path", defData))

	// db
	dbDef := "sqlite"
	if in.UsesPostgres() {
		dbDef = "postgres"
	}
	if _, ok := p.given("database-url"); ok {
		if _, said := p.given("db"); !said {
			dbDef = "postgres" // giving the address is choosing Postgres
		}
	}
	switch p.choose("db", "\nDatabase:", [][2]string{{"sqlite", "one file in the data path; nothing to run. Right for one author."}, {"postgres", "you already run a Postgres and want this site in it."}}, dbDef) {
	case "sqlite":
		in.Env.Set("DATABASE_URL", "")
	case "postgres":
		u := p.ask("database-url", "DATABASE_URL (postgres://user:pass@host:5432/db — host as reachable from the container)", in.Env.Get("DATABASE_URL"))
		if u == "" && p.auto {
			p.fail("setup: --db postgres needs --database-url")
		}
		in.Env.Set("DATABASE_URL", u)
	}

	// which published image
	version := in.Env.Get("FA_VERSION")
	if version == "" {
		version = "latest"
	}
	if v, ok := p.given("version"); ok && strings.TrimSpace(v) != "" {
		version = strings.TrimPrefix(strings.TrimSpace(v), "v")
	}
	in.Env.Set("FA_VERSION", version)

	// secrets
	say("")
	generatedPassword := false
	if pw, ok := p.given("admin-password"); ok && pw != "" {
		in.Env.Set("ADMIN_PASSWORD", pw)
	} else if pw := in.Env.Get("ADMIN_PASSWORD"); pw == "" || pw == "change-me" {
		gen := randomPassword()
		in.Env.Set("ADMIN_PASSWORD", p.ask("", "Admin password for /admin (blank = use this generated one: "+gen+")", gen))
		generatedPassword = p.auto
	} else if p.yes("Keep the existing admin password?", true) == false {
		in.Env.Set("ADMIN_PASSWORD", p.ask("", "New admin password", randomPassword()))
	}
	if generatedPassword {
		// Not printed: with --yes this output usually lands in a log.
		say("  generated an admin password for /admin (fa env get ADMIN_PASSWORD)")
	}
	if in.Env.Get("CAPTURE_API_TOKEN") == "" {
		in.Env.Set("CAPTURE_API_TOKEN", randomHex(32))
		say("  generated CAPTURE_API_TOKEN for the phone app (fa env get CAPTURE_API_TOKEN)")
	}
	for _, kv := range a.env {
		in.Env.Set(kv[0], kv[1])
		say("  set %s", kv[0])
	}

	// exposure
	if !p.auto {
		say("\nHow will people reach it?")
		say("  existing  you already run Caddy/Traefik/nginx in Docker on a network called `web` — fa just joins it")
		say("  caddy     fa runs Caddy for you: needs ports 80 and 443 open to the internet and DNS pointed here (a VPS)")
		say("  tunnel    Cloudflare Tunnel: no open ports, works behind a home router / ISP that blocks 80 (a Raspberry Pi)")
	}
	in.Settings.Proxy = p.choose("proxy", "", [][2]string{{"existing", ""}, {"caddy", ""}, {"tunnel", ""}}, in.Settings.Proxy)
	if p.err != nil {
		return p.err // before anything is written
	}
	switch in.Settings.Proxy {
	case "existing":
		in.Settings.CaddyContainer = p.ask("proxy-container", "Your reverse proxy's container name (for `fa logs caddy`; blank to skip)", orGuess(in.Settings.CaddyContainer))
		say("  the proxy needs an entry sending %s to %s:3000 — after this, `fa proxy write` adds it as a file of the site's own (`fa proxy show` prints it)", in.Settings.Domain, in.Container())
	case "caddy":
		in.Settings.CaddyContainer = "fartingaround-caddy"
		email := p.ask("acme-email", "Email for Let's Encrypt expiry notices", in.Env.Get("ACME_EMAIL"))
		in.Env.Set("ACME_EMAIL", email)
		if err := writeCaddyOverlay(in); err != nil {
			return err
		}
	case "tunnel":
		if !p.auto {
			say("  In the Cloudflare dashboard: Zero Trust → Networks → Tunnels → Create → Docker. Copy the token")
			say("  after `--token`. Add a public hostname %s → http://%s:3000.", in.Settings.Domain, in.Container())
		}
		token := p.ask("tunnel-token", "Tunnel token", in.Env.Get("TUNNEL_TOKEN"))
		if token == "" && p.auto {
			return errorf("setup: --proxy tunnel needs --tunnel-token")
		}
		in.Env.Set("TUNNEL_TOKEN", token)
		in.Settings.CaddyContainer = ""
		if err := writeTunnelOverlay(in); err != nil {
			return err
		}
	}

	if err := in.Env.Save(); err != nil {
		return err
	}
	if err := in.SaveSettings(); err != nil {
		return err
	}
	ok("wrote %s and %s/.fa.json", in.Env.Path(), dir)

	if in.Settings.Proxy != "tunnel" {
		if _, err := sh.Out("", "docker", "network", "inspect", "web"); err != nil {
			if _, err := sh.Out("", "docker", "network", "create", "web"); err != nil {
				return err
			}
			ok("created docker network `web`")
		}
	}

	start := true
	switch {
	case a.start != nil:
		start = *a.start
	case !p.auto:
		start = p.yes("\nDownload and start it now?", true)
	}
	if start {
		c.In = in
		if err := runUp(c, nil); err != nil {
			return err
		}
		say("")
		return runDoctor(c, nil)
	}
	say("later: cd %s && fa up", dir)
	return nil
}

func orGuess(cur string) string {
	if cur != "" {
		return cur
	}
	return guessProxyContainer()
}

func randomPassword() string {
	b := make([]byte, 18)
	rand.Read(b)
	return strings.TrimRight(base64.URLEncoding.EncodeToString(b), "=")
}

// writeCaddyOverlay adds a Caddy service and a Caddyfile for the `caddy` mode.
// The Caddyfile is rewritten in place (same file, new contents), which is what
// lets the running proxy see the change and reload it.
func writeCaddyOverlay(in *install.Install) error {
	fa := filepath.Join(in.Dir, "fa")
	if err := os.MkdirAll(fa, 0o755); err != nil {
		return err
	}
	d := in.Settings.Domain
	caddyfile := fmt.Sprintf(`{
	email {$ACME_EMAIL}
}

%s, www.%s {
	encode zstd gzip
	request_body {
		max_size 60MB
	}
	reverse_proxy %s:3000
}

# Big phone uploads bypass Cloudflare's 100 MB cap when UPLOAD_ORIGIN points here (DNS-only record).
upload.%s {
	request_body {
		max_size 2GB
	}
	reverse_proxy %s:3000
}
`, d, d, in.Container(), d, in.Container())
	// Sites added beside this one share its proxy. They are written out again
	// every time, so re-running setup here never drops them.
	caddyfile += addedSiteBlocks()
	if err := os.WriteFile(filepath.Join(fa, "Caddyfile"), []byte(caddyfile), 0o644); err != nil {
		return err
	}
	compose := `# fa: Caddy reverse proxy for installs that don't already have one.
services:
  caddy:
    image: caddy:2-alpine
    container_name: fartingaround-caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      - ACME_EMAIL=${ACME_EMAIL:-}
    volumes:
      - ./fa/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    networks: [web]

volumes:
  caddy_data:
  caddy_config:
`
	return os.WriteFile(filepath.Join(fa, "docker-compose.caddy.yml"), []byte(compose), 0o644)
}

// writeTunnelOverlay adds cloudflared; the site is reached only through it.
func writeTunnelOverlay(in *install.Install) error {
	fa := filepath.Join(in.Dir, "fa")
	if err := os.MkdirAll(fa, 0o755); err != nil {
		return err
	}
	compose := `# fa: Cloudflare Tunnel for a box with no inbound ports (home / Pi).
services:
  tunnel:
    image: cloudflare/cloudflared:latest
    container_name: fartingaround-tunnel
    restart: unless-stopped
    command: tunnel --no-autoupdate run
    environment:
      - TUNNEL_TOKEN=${TUNNEL_TOKEN:?set TUNNEL_TOKEN in .env}
    networks: [default]
  site:
    networks: [default]   # no external web network needed in tunnel mode

networks:
  web:
    external: false
    name: fartingaround_web_unused
`
	return os.WriteFile(filepath.Join(fa, "docker-compose.tunnel.yml"), []byte(compose), 0o644)
}
