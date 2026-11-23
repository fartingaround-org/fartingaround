package cmd

import (
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// A file of its own for each site in the reverse proxy.
//
// On a server whose proxy is the owner's own Caddy, fa never edits the main
// Caddyfile. It keeps one small file per site in a folder the proxy reads:
//
//	import sites/*.caddy          one line in the main Caddyfile, added once by hand
//	sites/example.org.caddy       written and rewritten by fa, one per site
//
// So adding a site, or changing where its uploads go, touches that site's
// file and nothing else. The folder is whatever the proxy's container has at
// /etc/caddy/sites; fa finds it by asking Docker, so there is nothing to set.

func init() {
	register(&command{name: "proxy", summary: "this site's entry in the reverse proxy: fa proxy [show|write]  (write = save its own file and reload)", run: runProxy})
}

// proxyFileMark heads every file fa writes; fa only ever replaces a file that
// starts with it.
const proxyFileMark = "# Written by fa"

// proxyConfig is the text of a site's own proxy file.
func proxyConfig(siteName, domain, container, uploadHost string) string {
	names := domain
	// The bare domain also answers at www. A site on a subdomain doesn't:
	// www.notes.example.org has no DNS record, and Caddy would keep failing
	// to get a certificate for it.
	if strings.Count(domain, ".") == 1 {
		names += ", www." + domain
	}
	// With a separate address for big uploads, the main one only ever sees
	// ordinary requests and photos.
	limit := "2GB"
	if uploadHost != "" {
		limit = "60MB"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s for the site %q. `fa -s %s proxy write` rewrites this file,\n# so changes made here by hand are lost. Anything custom belongs in the main Caddyfile.\n\n", proxyFileMark, siteName, siteName)
	fmt.Fprintf(&b, "%s {\n\tencode zstd gzip\n\trequest_body {\n\t\tmax_size %s\n\t}\n\treverse_proxy %s:3000\n}\n", names, limit, container)
	if uploadHost != "" {
		fmt.Fprintf(&b, "\n# Big uploads come here directly (UPLOAD_ORIGIN), past any proxy in front that caps their size.\n%s {\n\trequest_body {\n\t\tmax_size 2GB\n\t}\n\treverse_proxy %s:3000\n}\n", uploadHost, container)
	}
	return b.String()
}

// uploadHostOf is the host name in UPLOAD_ORIGIN, when it is a different
// address from the site's own.
func uploadHostOf(in *install.Install) string {
	u, err := url.Parse(in.Env.Get("UPLOAD_ORIGIN"))
	if err != nil || u.Hostname() == "" || strings.EqualFold(u.Hostname(), in.Settings.Domain) {
		return ""
	}
	return strings.ToLower(u.Hostname())
}

// siteName is what this install is called in the list of sites.
func siteNameOf(in *install.Install) string {
	sites, _ := install.ReadSites()
	for _, s := range sites {
		if filepath.Clean(s.Dir) == filepath.Clean(in.Dir) {
			return s.Name
		}
	}
	return install.MainSite
}

func proxyConfigFor(in *install.Install) string {
	return proxyConfig(siteNameOf(in), in.Settings.Domain, in.Container(), uploadHostOf(in))
}

// proxyPlaces is where the proxy's files are on the server, worked out from
// what its container has mounted (inside the container → on the server).
type proxyPlaces struct {
	SitesDir  string // on the server; "" when the container has no sites folder
	Caddyfile string // on the server; "" when it can't be told
}

func findProxyPlaces(mounts map[string]string) proxyPlaces {
	var p proxyPlaces
	if whole := mounts["/etc/caddy"]; whole != "" {
		p.SitesDir, p.Caddyfile = filepath.Join(whole, "sites"), filepath.Join(whole, "Caddyfile")
	}
	if f := mounts["/etc/caddy/Caddyfile"]; f != "" {
		p.Caddyfile = f
	}
	if d := mounts["/etc/caddy/sites"]; d != "" {
		p.SitesDir = d
	}
	return p
}

var importSitesRE = regexp.MustCompile(`(?m)^[ \t]*import[ \t]+(/etc/caddy/)?sites/\*`)

// importsSites says whether a Caddyfile pulls in the per-site files.
func importsSites(caddyfile string) bool { return importSitesRE.MatchString(caddyfile) }

// namesSite says whether a Caddyfile has a block of its own for domain: the
// name appears on a line that opens a block, outside a comment.
func namesSite(caddyfile, domain string) bool {
	re := regexp.MustCompile(`(?mi)^[^#\n]*(^|[\s,/])` + regexp.QuoteMeta(domain) + `([\s,:{][^\n]*)?\{[ \t]*$`)
	return re.MatchString(caddyfile)
}

// proxySetupHelp is the once-per-server step, spelled out.
func proxySetupHelp(cn string, p proxyPlaces) string {
	var b strings.Builder
	b.WriteString("To give every site its own proxy file, do this once on this server:\n")
	if p.SitesDir == "" {
		b.WriteString("  1. In the proxy's compose file, under the caddy service's volumes, add a folder for the files:\n")
		b.WriteString("         - ./sites.d:/etc/caddy/sites:ro\n")
		b.WriteString("     and recreate it: docker compose up -d   (in the proxy's folder)\n")
		b.WriteString("  2. In its Caddyfile, add this line on its own, outside any block:\n")
	} else {
		b.WriteString("  In the proxy's Caddyfile, add this line on its own, outside any block:\n")
	}
	b.WriteString("         import sites/*.caddy\n")
	fmt.Fprintf(&b, "     and reload: docker exec %s caddy reload --config /etc/caddy/Caddyfile\n", cn)
	b.WriteString("Then: fa proxy write")
	return b.String()
}

// writeProxyFile saves in's own proxy file and reloads the proxy. It returns
// a plain reason when the server isn't arranged for it.
func writeProxyFile(in *install.Install) error {
	cn := in.Settings.CaddyContainer
	if cn == "" {
		cn = guessProxyContainer()
	}
	if cn == "" {
		return errorf("fa doesn't know which container is the reverse proxy — fa set caddyContainer NAME")
	}
	if in.Settings.Domain == "" {
		return errorf("this site has no domain set — fa set domain example.org")
	}
	ci, err := inspectContainer(cn)
	if err != nil {
		return errorf("can't look at the proxy container %s: %v", cn, err)
	}
	places := findProxyPlaces(ci.Mounts)
	if places.SitesDir == "" {
		return errorf("the proxy %s has no folder for per-site files.\n%s", cn, proxySetupHelp(cn, places))
	}
	main := ""
	if places.Caddyfile != "" {
		if b, err := os.ReadFile(places.Caddyfile); err == nil {
			main = string(b)
		}
	}
	if main != "" && !importsSites(main) {
		return errorf("the proxy's Caddyfile (%s) doesn't read the per-site files yet.\n%s", places.Caddyfile, proxySetupHelp(cn, places))
	}
	for _, name := range []string{in.Settings.Domain, uploadHostOf(in)} {
		if name != "" && main != "" && namesSite(main, name) {
			return errorf("%s already has a block for %s. Remove that block there (this site's own file replaces it), then run this again. Nothing was changed", places.Caddyfile, name)
		}
	}

	file := filepath.Join(places.SitesDir, in.Settings.Domain+".caddy")
	before, readErr := os.ReadFile(file)
	if readErr == nil && !strings.HasPrefix(string(before), proxyFileMark) {
		return errorf("%s exists and fa didn't write it, so fa won't replace it", file)
	}
	if err := os.MkdirAll(places.SitesDir, 0o755); err != nil {
		return errorf("can't create %s: %v", places.SitesDir, err)
	}
	if err := os.WriteFile(file, []byte(proxyConfigFor(in)), 0o644); err != nil {
		return errorf("can't write %s: %v", file, err)
	}
	// The proxy serves every site on the server: check the whole configuration
	// before asking it to load, and put things back if it doesn't hold up.
	undo := func() {
		if readErr == nil {
			_ = os.WriteFile(file, before, 0o644)
		} else {
			_ = os.Remove(file)
		}
	}
	if out, err := sh.Out("", "docker", "exec", cn, "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"); err != nil {
		undo()
		return errorf("the proxy would not accept the new file, so it was taken back out and nothing changed:\n%s", indent(lastLines(strings.TrimSpace(out+"\n"+err.Error()), 6)))
	}
	if _, err := sh.Out("", "docker", "exec", cn, "caddy", "reload", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"); err != nil {
		return errorf("wrote %s, but the proxy didn't reload (%v) — docker restart %s", file, err, cn)
	}
	ok("proxy: %s → %s   (%s)", in.Settings.Domain, in.Container(), file)
	if u := uploadHostOf(in); u != "" {
		ok("proxy: %s → %s   (big uploads)", u, in.Container())
	}
	return nil
}

func runProxy(c *Ctx, args []string) error {
	in := c.In
	what := "show"
	if len(args) > 0 {
		what = args[0]
	}
	if in.Settings.Proxy != "existing" {
		return errorf("this site's proxy is run by fa itself (%s), which already keeps it up to date; there is no file to write", in.Settings.Proxy)
	}
	switch what {
	case "show":
		fmt.Print(proxyConfigFor(in))
		return nil
	case "write":
		return writeProxyFile(in)
	}
	return errorf("usage: fa proxy [show|write]")
}
