package cmd

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// More than one site on a server.
//
// An added site is a complete install of its own: its own folder, .env, data
// and container, started from the image the server already has. So every
// other command works on it unchanged, once told which one (`fa -s NAME …`).
// The sites share two things only: the downloaded image and the reverse proxy.

func init() {
	register(&command{name: "sites", summary: "every site on this server, with its state: fa sites [--json]", run: runSites})
	register(&command{name: "add", summary: "add another site to this server: fa add NAME --domain example.org  (fa add --help)", run: runAdd})
}

const addUsage = `usage: fa add [NAME] [--domain example.org] [flags]

Adds another site to this server, beside the one already here. It gets its own
folder, its own data and its own container (fa-NAME), starts empty, and runs
the same version as the main site. Afterwards: fa -s NAME status, fa -s NAME
backup, and so on.

  NAME                 a short name for it: lowercase letters, digits, hyphens
  --domain VALUE       its public address, e.g. notes.example.org
  --dir VALUE          its folder (default: fa-NAME beside the main site's folder)
  --data-path VALUE    where its database and media live (default: data inside its folder)
  --database-url VALUE put it in a Postgres you run, instead of its own SQLite file
  --admin-password VALUE  password for its /admin (generated when not given)
  --env KEY=VALUE      another line for its .env; may be repeated
  --no-start           write everything but don't start it
`

type addArgs struct {
	name, domain, dir, dataPath, databaseURL, adminPassword string
	env                                                     []string
	noStart, help                                           bool
}

func parseAddArgs(args []string) (*addArgs, error) {
	a := &addArgs{}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !strings.HasPrefix(arg, "-") {
			if a.name != "" {
				return nil, errorf("add: one site at a time (got %q and %q)", a.name, arg)
			}
			a.name = arg
			continue
		}
		key, val, hasVal := strings.Cut(strings.TrimLeft(arg, "-"), "=")
		switch key {
		case "help", "h":
			a.help = true
			continue
		case "no-start":
			a.noStart = true
			continue
		}
		if !hasVal {
			if i+1 >= len(args) {
				return nil, errorf("add: %s needs a value", arg)
			}
			i++
			val = args[i]
		}
		switch key {
		case "domain":
			a.domain = val
		case "dir":
			a.dir = val
		case "data-path":
			a.dataPath = val
		case "database-url":
			a.databaseURL = val
		case "admin-password":
			a.adminPassword = val
		case "env":
			if k, _, ok := strings.Cut(val, "="); !ok || k == "" {
				return nil, errorf("add: --env wants KEY=VALUE, got %q", val)
			}
			a.env = append(a.env, val)
		default:
			return nil, errorf("add: unknown flag %s — fa add --help", arg)
		}
	}
	return a, nil
}

// cleanDomain turns what someone typed into a bare host name.
func cleanDomain(s string) (string, error) {
	d := strings.ToLower(strings.TrimSpace(s))
	d = strings.TrimPrefix(strings.TrimPrefix(d, "https://"), "http://")
	d = strings.TrimSuffix(d, "/")
	if d == "" || !strings.Contains(d, ".") || strings.ContainsAny(d, " /:@") {
		return "", errorf("add: %q doesn't look like a domain (wanted something like notes.example.org)", s)
	}
	return d, nil
}

// existing is what an addition is checked against: the sites already here.
type existingSite struct {
	Name, Dir, Domain, Container, Port string
}

// addPlan is everything about a new site that must not collide with another.
type addPlan struct {
	Name, Domain, Dir, Container, Port string
}

// firstAddedPort is where added sites' on-host ports start; the main site's
// default is 8406.
const firstAddedPort = 8407

// planAdd decides the new site's folder, container and port, and refuses
// anything that would collide with a site already on the server. It touches
// nothing; the three probes say what is taken on the machine itself.
func planAdd(name, domain, dir, mainDir string, have []existingSite, pathExists func(string) bool, containerExists func(string) bool, portFree func(int) bool) (*addPlan, error) {
	if err := install.ValidSiteName(name); err != nil {
		return nil, errorf("add: %v", err)
	}
	domain, err := cleanDomain(domain)
	if err != nil {
		return nil, err
	}
	p := &addPlan{Name: name, Domain: domain, Dir: dir, Container: "fa-" + name}
	if p.Dir == "" {
		p.Dir = filepath.Join(filepath.Dir(filepath.Clean(mainDir)), "fa-"+name)
	}
	if !filepath.IsAbs(p.Dir) {
		return nil, errorf("add: --dir must be a full path, got %q", p.Dir)
	}
	p.Dir = filepath.Clean(p.Dir)

	taken := map[string]bool{}
	for _, s := range have {
		switch {
		case s.Name == name:
			return nil, errorf("add: there is already a site called %q here (%s)", name, s.Dir)
		case s.Domain != "" && strings.EqualFold(s.Domain, domain):
			return nil, errorf("add: %s already belongs to the site %q", domain, s.Name)
		case filepath.Clean(s.Dir) == p.Dir:
			return nil, errorf("add: %s is the folder of the site %q", p.Dir, s.Name)
		case s.Container == p.Container:
			return nil, errorf("add: the site %q already uses the container name %s", s.Name, p.Container)
		}
		taken[s.Port] = true
	}
	if pathExists(p.Dir) {
		return nil, errorf("add: %s already exists — choose another folder with --dir, or another name", p.Dir)
	}
	if containerExists(p.Container) {
		return nil, errorf("add: a container called %s already exists on this server — choose another name", p.Container)
	}
	for port := firstAddedPort; port < firstAddedPort+200; port++ {
		if !taken[strconv.Itoa(port)] && portFree(port) {
			p.Port = strconv.Itoa(port)
			return p, nil
		}
	}
	return nil, errorf("add: no free port between %d and %d for the site's on-host check", firstAddedPort, firstAddedPort+199)
}

func localPortFree(port int) bool {
	l, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		return false
	}
	l.Close()
	return true
}

func dockerContainerExists(name string) bool {
	_, err := sh.Out("", "docker", "inspect", "--type", "container", name)
	return err == nil
}

// knownSites is the list of sites, or just the one found when there is no
// list yet.
func knownSites(c *Ctx) ([]install.Site, error) {
	sites, err := install.ReadSites()
	if err != nil {
		return nil, err
	}
	if len(sites) == 0 && c.In != nil {
		return []install.Site{{Name: install.MainSite, Dir: c.In.Dir}}, nil
	}
	return sites, nil
}

func describeSites(sites []install.Site) []existingSite {
	var out []existingSite
	for _, s := range sites {
		e := existingSite{Name: s.Name, Dir: s.Dir}
		if install.IsInstall(s.Dir) {
			if in, err := install.Load(s.Dir); err == nil {
				e.Domain, e.Container, e.Port = in.Settings.Domain, in.Container(), in.DebugPort()
			}
		}
		out = append(out, e)
	}
	return out
}

func runAdd(c *Ctx, args []string) error {
	a, err := parseAddArgs(args)
	if err != nil {
		return err
	}
	if a.help {
		fmt.Print(addUsage)
		return nil
	}
	if c.Site != "" && c.Site != install.MainSite {
		return errorf("add: -s doesn't apply — a site is added to the server, not to another site")
	}

	sites, err := knownSites(c)
	if err != nil {
		return err
	}
	// The site everything is modelled on: the one this server started with.
	main := c.In
	for _, s := range sites {
		if s.Name == install.MainSite && filepath.Clean(s.Dir) != filepath.Clean(c.In.Dir) {
			if main, err = install.Load(s.Dir); err != nil {
				return err
			}
		}
	}
	if main.Settings.Proxy == "tunnel" {
		return errorf("add: this server reaches the internet through a Cloudflare Tunnel, and fa can't add a second site behind one yet")
	}

	in := bufio.NewReader(os.Stdin)
	askFor := func(q string) string {
		fmt.Printf("%s: ", q)
		s, _ := in.ReadString('\n')
		return strings.TrimSpace(s)
	}
	if a.name == "" {
		a.name = askFor("A short name for the new site (letters, digits, hyphens — e.g. notes)")
	}
	if a.domain == "" {
		a.domain = askFor("Its public address (e.g. notes.example.org)")
	}

	plan, err := planAdd(a.name, a.domain, a.dir, main.Dir, describeSites(sites), fileExists, dockerContainerExists, localPortFree)
	if err != nil {
		return err
	}

	say("adding %s → %s", plan.Name, plan.Domain)
	say("  folder     %s", plan.Dir)
	say("  container  %s (on-host port %s)", plan.Container, plan.Port)

	// The new site is set up the way a first site is, with every answer given.
	repo := main.Settings.Repo
	if repo == "" {
		repo = install.DefaultRepo
	}
	setup := []string{"--yes", "--no-start",
		"--dir", plan.Dir, "--repo", repo, "--domain", plan.Domain, "--proxy", "existing",
		"--env", "FA_CONTAINER=" + plan.Container,
		"--env", "COMPOSE_PROJECT_NAME=" + plan.Container,
		"--env", "DEBUG_PORT=" + plan.Port,
	}
	if cn := proxyContainer(main); cn != "" {
		setup = append(setup, "--proxy-container", cn)
	}
	if v := main.Env.Get("FA_VERSION"); v != "" {
		setup = append(setup, "--version", v)
	}
	if img := main.Env.Get("FA_IMAGE"); img != "" {
		setup = append(setup, "--env", "FA_IMAGE="+img)
	}
	if a.dataPath != "" {
		setup = append(setup, "--data-path", a.dataPath)
	}
	if a.databaseURL != "" {
		setup = append(setup, "--db", "postgres", "--database-url", a.databaseURL)
	} else {
		setup = append(setup, "--db", "sqlite")
	}
	if a.adminPassword != "" {
		setup = append(setup, "--admin-password", a.adminPassword)
	}
	for _, kv := range a.env {
		setup = append(setup, "--env", kv)
	}
	nc := &Ctx{Version: c.Version}
	if err := runSetup(nc, setup); err != nil {
		if fileExists(plan.Dir) {
			return errorf("%v\n  nothing was started and the site is not on the list. Its half-made folder is %s — remove it before trying again", err, plan.Dir)
		}
		return err
	}
	added, err := install.Load(plan.Dir)
	if err != nil {
		return err
	}
	// Its own name in the server's shared backup settings, so two sites never
	// write to one profile.
	added.Settings.BackupProfile = plan.Container
	if err := added.SaveSettings(); err != nil {
		return err
	}

	// Onto the list before it is started: if the start fails the site is still
	// there to see (`fa sites`) and to retry (`fa -s NAME up`).
	if len(sites) == 1 && sites[0].Name == install.MainSite {
		sites[0].Dir = main.Dir
	}
	sites = append(sites, install.Site{Name: plan.Name, Dir: plan.Dir})
	if err := install.WriteSites(sites); err != nil {
		return err
	}
	ok("%s is on this server's list of sites (%s)", plan.Name, install.SitesFile())

	if !a.noStart {
		nc.In = added
		if err := runUp(nc, nil); err != nil {
			return errorf("%v\n  the site is set up but not running — fix the cause, then: fa -s %s up", err, plan.Name)
		}
	}
	say("")
	connectProxy(main, added)
	say("")
	say("%s is set up. Point DNS for %s at this server, then:", plan.Name, plan.Domain)
	say("  https://%s/admin        sign in (password: fa -s %s env get ADMIN_PASSWORD)", plan.Domain, plan.Name)
	say("  fa -s %s env get CAPTURE_API_TOKEN   the phone app's token", plan.Name)
	say("  fa -s %s doctor                      check it over", plan.Name)
	if a.noStart {
		say("  fa -s %s up                          start it", plan.Name)
	}
	return nil
}

// proxyContainer is the reverse proxy the main site sits behind.
func proxyContainer(main *install.Install) string {
	if main.Settings.CaddyContainer != "" {
		return main.Settings.CaddyContainer
	}
	return guessProxyContainer()
}

// siteCaddyBlock is the proxy entry for an added site. No www. name: an added
// site is often on a subdomain, where www.notes.example.org has no DNS record
// and Caddy would keep failing to get a certificate for it.
func siteCaddyBlock(domain, container string) string {
	return fmt.Sprintf(`%s {
	encode zstd gzip
	request_body {
		max_size 2GB
	}
	reverse_proxy %s:3000
}
`, domain, container)
}

// addedSiteBlocks is the proxy entries for every added site on the list.
func addedSiteBlocks() string {
	sites, _ := install.ReadSites()
	var b strings.Builder
	for _, s := range sites {
		if s.Name == install.MainSite || s.Kind != "" || !install.IsInstall(s.Dir) {
			continue
		}
		in, err := install.Load(s.Dir)
		if err != nil || in.Settings.Domain == "" {
			continue
		}
		fmt.Fprintf(&b, "\n# %s (added with fa add)\n%s", s.Name, siteCaddyBlock(in.Settings.Domain, in.Container()))
	}
	return b.String()
}

// connectProxy makes the new site reachable. Where fa runs the proxy itself
// it adds the entry and reloads. Where the proxy is the owner's own, the site
// gets a file of its own beside the others (see proxy.go); if the server isn't
// arranged for that yet, fa says what to add and changes nothing.
func connectProxy(main, added *install.Install) {
	if main.Settings.Proxy == "caddy" {
		if err := writeCaddyOverlay(main); err != nil {
			warn("couldn't add %s to %s/fa/Caddyfile: %v", added.Settings.Domain, main.Dir, err)
		} else if _, err := sh.Out("", "docker", "exec", main.Settings.CaddyContainer, "caddy", "reload", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"); err != nil {
			warn("added %s to the proxy's settings but couldn't reload it (%v) — docker restart %s", added.Settings.Domain, err, main.Settings.CaddyContainer)
		} else {
			ok("proxy: %s now goes to %s", added.Settings.Domain, added.Container())
		}
		return
	}
	err := writeProxyFile(added)
	if err == nil {
		return
	}
	say("The reverse proxy doesn't know about %s yet:", added.Settings.Domain)
	say("%s", indent(err.Error()))
	say("Or add this to its Caddyfile by hand, then reload it:")
	say("")
	say("%s", indent(siteCaddyBlock(added.Settings.Domain, added.Container())))
}

type siteRow struct {
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Domain    string `json:"domain"`
	Dir       string `json:"dir"`
	Container string `json:"container"`
	Port      string `json:"port"`
	State     string `json:"state"`
}

func runSites(c *Ctx, args []string) error {
	asJSON := len(args) > 0 && args[0] == "--json"
	sites, err := knownSites(c)
	if err != nil {
		return err
	}
	var rows []siteRow
	for _, s := range sites {
		r := siteRow{Name: s.Name, Kind: s.Kind, Dir: s.Dir}
		if r.Kind == "" {
			r.Kind = "site"
		}
		if !install.IsInstall(s.Dir) {
			r.State = "folder missing"
		} else if in, err := install.Load(s.Dir); err != nil {
			r.State = "unreadable"
		} else {
			r.Domain, r.Container, r.Port = in.Settings.Domain, in.Container(), in.DebugPort()
			switch ci, err := inspectContainer(r.Container); {
			case err != nil:
				r.State = "not started"
			case ci.Running:
				r.State = "running"
			default:
				r.State = ci.Status
			}
		}
		rows = append(rows, r)
	}
	if asJSON {
		b, _ := json.MarshalIndent(rows, "", "  ")
		fmt.Println(string(b))
		return nil
	}
	w := 4
	for _, r := range rows {
		if len(r.Name) > w {
			w = len(r.Name)
		}
	}
	for _, r := range rows {
		domain := r.Domain
		if domain == "" {
			domain = "(no domain set)"
		}
		say("%-*s  %-14s  %-28s  %s", w, r.Name, r.State, domain, r.Dir)
	}
	if len(rows) > 1 {
		say("\nfa -s NAME <command> works on one of them; without -s, fa means %s.", install.MainSite)
	} else {
		say("\none site here. `fa add` puts another beside it.")
	}
	return nil
}
