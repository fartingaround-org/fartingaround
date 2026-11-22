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
	register(&command{name: "setup", summary: "first-run wizard: clone the site, write .env, pick how it's exposed, start it", noInstall: true, run: runSetup})
}

type prompter struct{ r *bufio.Reader }

func (p prompter) ask(q, def string) string {
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

func (p prompter) choose(q string, options [][2]string, def string) string {
	fmt.Println(q)
	for _, o := range options {
		mark := " "
		if o[0] == def {
			mark = "*"
		}
		fmt.Printf("  %s %-9s %s\n", mark, o[0], o[1])
	}
	for {
		a := p.ask("choice", def)
		for _, o := range options {
			if a == o[0] {
				return a
			}
		}
		fmt.Println("  pick one of the names above")
	}
}

func (p prompter) yes(q string, def bool) bool {
	d := "y/N"
	if def {
		d = "Y/n"
	}
	a := strings.ToLower(p.ask(q+" ("+d+")", ""))
	if a == "" {
		return def
	}
	return a == "y" || a == "yes"
}

// runSetup is deliberately linear: ask, write files, start, check. It is
// re-runnable — existing values are offered as defaults.
func runSetup(c *Ctx, _ []string) error {
	p := prompter{bufio.NewReader(os.Stdin)}
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
	dir := p.ask("Install directory", defDir)

	repo := p.ask("Site repository", install.DefaultRepo)
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
	in.Settings.Domain = p.ask("Public domain (e.g. example.org)", in.Settings.Domain)

	// data
	defData := in.Env.Get("DATA_PATH")
	if defData == "" {
		defData = filepath.Join(dir, "data")
		if m, _ := filepath.Glob("/mnt/*"); len(m) > 0 {
			say("  mounted volumes under /mnt: %s", strings.Join(m, ", "))
		}
	}
	say("\nWhere the database and every photo/video live. A separate volume (block storage,")
	say("a USB disk on a Pi) survives rebuilds and can grow; a folder on the root disk is fine to start.")
	in.Env.Set("DATA_PATH", p.ask("Data path", defData))

	// db
	dbDef := "sqlite"
	if in.UsesPostgres() {
		dbDef = "postgres"
	}
	switch p.choose("\nDatabase:", [][2]string{{"sqlite", "one file in the data path; nothing to run. Right for one author."}, {"postgres", "you already run a Postgres and want this site in it."}}, dbDef) {
	case "sqlite":
		in.Env.Set("DATABASE_URL", "")
	case "postgres":
		in.Env.Set("DATABASE_URL", p.ask("DATABASE_URL (postgres://user:pass@host:5432/db — host as reachable from the container)", in.Env.Get("DATABASE_URL")))
	}

	// secrets
	say("")
	if pw := in.Env.Get("ADMIN_PASSWORD"); pw == "" || pw == "change-me" {
		gen := randomPassword()
		in.Env.Set("ADMIN_PASSWORD", p.ask("Admin password for /admin (blank = use this generated one: "+gen+")", gen))
	} else if p.yes("Keep the existing admin password?", true) == false {
		in.Env.Set("ADMIN_PASSWORD", p.ask("New admin password", randomPassword()))
	}
	if in.Env.Get("CAPTURE_API_TOKEN") == "" {
		in.Env.Set("CAPTURE_API_TOKEN", randomHex(32))
		say("  generated CAPTURE_API_TOKEN for the phone app (fa env get CAPTURE_API_TOKEN)")
	}

	// exposure
	say("\nHow will people reach it?")
	say("  existing  you already run Caddy/Traefik/nginx in Docker on a network called `web` — fa just joins it")
	say("  caddy     fa runs Caddy for you: needs ports 80 and 443 open to the internet and DNS pointed here (a VPS)")
	say("  tunnel    Cloudflare Tunnel: no open ports, works behind a home router / ISP that blocks 80 (a Raspberry Pi)")
	in.Settings.Proxy = p.choose("", [][2]string{{"existing", ""}, {"caddy", ""}, {"tunnel", ""}}, in.Settings.Proxy)
	switch in.Settings.Proxy {
	case "existing":
		in.Settings.CaddyContainer = p.ask("Your reverse proxy's container name (for `fa logs caddy`; blank to skip)", orGuess(in.Settings.CaddyContainer))
		say("  add a site block to your proxy pointing at fartingaround-site:3000 — see docker-compose.yml for a Caddy example")
	case "caddy":
		in.Settings.CaddyContainer = "fartingaround-caddy"
		email := p.ask("Email for Let's Encrypt expiry notices", in.Env.Get("ACME_EMAIL"))
		in.Env.Set("ACME_EMAIL", email)
		if err := writeCaddyOverlay(in); err != nil {
			return err
		}
	case "tunnel":
		say("  In the Cloudflare dashboard: Zero Trust → Networks → Tunnels → Create → Docker. Copy the token")
		say("  after `--token`. Add a public hostname %s → http://fartingaround-site:3000.", in.Settings.Domain)
		in.Env.Set("TUNNEL_TOKEN", p.ask("Tunnel token", in.Env.Get("TUNNEL_TOKEN")))
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

	if p.yes("\nBuild and start it now? (first build takes a few minutes)", true) {
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
	reverse_proxy fartingaround-site:3000
}

# Big phone uploads bypass Cloudflare's 100 MB cap when UPLOAD_ORIGIN points here (DNS-only record).
upload.%s {
	request_body {
		max_size 2GB
	}
	reverse_proxy fartingaround-site:3000
}
`, d, d, d)
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
