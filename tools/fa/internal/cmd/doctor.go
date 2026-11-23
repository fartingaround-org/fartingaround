package cmd

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

func init() {
	register(&command{name: "doctor", summary: "check the install for the usual problems and say how to fix them", run: runDoctor})
}

// runDoctor is the week of hand troubleshooting, automated. Every check
// prints ✓ or ✗ with the fix; nothing here changes anything.
func runDoctor(c *Ctx, _ []string) error {
	in := c.In
	problems := 0
	fail := func(format string, a ...any) { problems++; bad(format, a...) }

	say("checking %s", in.Dir)

	// tooling
	if !sh.Have("docker") {
		fail("docker is not installed — https://docs.docker.com/engine/install/")
	} else if _, err := sh.Out("", "docker", "compose", "version"); err != nil {
		fail("docker compose v2 plugin missing — install docker-compose-plugin")
	} else {
		ok("docker + compose present")
	}
	if _, err := sh.Out("", "docker", "info"); err != nil {
		fail("can't talk to the docker daemon as this user — add yourself to the docker group or use sudo")
	}

	// .env
	if !fileExists(in.Env.Path()) {
		fail(".env missing — cp .env.example .env, or run fa setup")
	} else {
		switch pw := in.Env.Get("ADMIN_PASSWORD"); {
		case pw == "":
			fail("ADMIN_PASSWORD is empty — the site refuses to start without it")
		case pw == "change-me" || len(pw) < 12:
			fail("ADMIN_PASSWORD is weak/default — fa env set ADMIN_PASSWORD \"$(openssl rand -base64 24)\"")
		default:
			ok("ADMIN_PASSWORD set")
		}
		if in.Env.Get("CAPTURE_API_TOKEN") == "" {
			warn("CAPTURE_API_TOKEN unset — the phone app can't sync (fa token rotate)")
		} else {
			ok("CAPTURE_API_TOKEN set")
		}
		if in.Env.Get("DATA_PATH") == "" {
			warn("DATA_PATH unset — data lives in %s/data; fine for a start, but put it on its own volume before the photos pile up", in.Dir)
		}
	}

	// data path
	dp := in.DataPath()
	st, err := os.Stat(dp)
	switch {
	case err != nil:
		fail("data path %s doesn't exist — mkdir -p %s && chown 1000:1000 %s", dp, dp, dp)
	case !st.IsDir():
		fail("data path %s is not a directory", dp)
	default:
		if uid := ownerUID(dp); uid != 1000 && uid != 0 {
			fail("data path %s is owned by uid %d; the container writes as uid 1000 — sudo chown -R 1000:1000 %s", dp, uid, dp)
		} else if uid == 0 {
			fail("data path %s is owned by root — the container (uid 1000) can't write it: sudo chown -R 1000:1000 %s", dp, dp)
		} else {
			ok("data path %s writable by the container", dp)
		}
		if strings.HasPrefix(dp, "/mnt/") && !isMountPoint(dp) {
			fail("%s looks like a volume mount point but nothing is mounted there — the volume detached or isn't in /etc/fstab (add it with nofail)", dp)
		}
		if free, total, err := diskFree(dp); err == nil && total > 0 && free*100/total < 10 {
			fail("data disk is %d%% full", 100-free*100/total)
		}
	}

	// docker network + containers
	if _, err := sh.Out("", "docker", "network", "inspect", "web"); err != nil {
		if in.Settings.Proxy == "tunnel" {
			ok("no `web` network (not needed in tunnel mode)")
		} else {
			fail("docker network `web` missing — docker network create web")
		}
	} else {
		ok("docker network `web` exists")
	}
	ci, err := inspectContainer(in.Container())
	switch {
	case err != nil:
		fail("site container not created — fa up")
	case !ci.Running:
		fail("site container is %s — fa logs site", ci.Status)
		if ci.Status == "restarting" {
			out, _ := sh.Out(in.Dir, "docker", "logs", "--tail", "8", in.Container())
			for _, l := range strings.Split(out, "\n") {
				say("      %s", l)
			}
		}
	default:
		ok("site container running")
		if src := ci.Mounts["/data"]; src != "" && filepath.Clean(src) != filepath.Clean(dp) {
			fail("container mounts /data from %s, .env says %s — docker compose up -d --force-recreate site", src, dp)
		}
	}
	if err := healthy(in); err != nil {
		fail("site not answering on 127.0.0.1:%s (%v)", in.DebugPort(), err)
	} else {
		ok("site answers on 127.0.0.1:%s", in.DebugPort())
	}

	// proxy
	switch in.Settings.Proxy {
	case "tunnel":
		if ti, err := inspectContainer("fartingaround-tunnel"); err != nil || !ti.Running {
			fail("cloudflared tunnel container not running — fa logs tunnel")
		} else {
			ok("tunnel running")
		}
	default:
		cn := in.Settings.CaddyContainer
		if cn == "" {
			cn = guessProxyContainer()
			if cn != "" {
				warn("proxy container not recorded; guessing %s (save with: fa set caddyContainer %s)", cn, cn)
			}
		}
		if cn == "" {
			warn("no reverse proxy found on the `web` network — the site is only reachable on the debug port")
		} else if pi, err := inspectContainer(cn); err != nil {
			fail("proxy container %s not found — if it was renamed: fa set caddyContainer <name>", cn)
		} else if !pi.Running {
			// A failed start (port taken at boot, say) leaves the container
			// stopped: docker doesn't retry it. Its reason is the fix.
			if pi.Error != "" {
				fail("proxy %s is %s — its last start failed: %s", cn, pi.Status, pi.Error)
			} else {
				fail("proxy %s is %s — docker start %s", cn, pi.Status, cn)
			}
		} else if !pi.onNetwork("web") {
			// Seen after a failed start at boot: the proxy comes back without
			// the network it was connected to by hand, and every site 502s.
			ok("proxy %s running", cn)
			fail("proxy %s is not on the `web` network, so it can't reach any site — docker network connect web %s && docker restart %s (and list `web` under the proxy's networks in its compose file, so a recreate keeps it)", cn, cn, cn)
		} else {
			ok("proxy %s running", cn)
			if _, err := sh.OutTimeout(8*time.Second, "", "docker", "exec", cn, "wget", "-q", "-O", "/dev/null", "http://"+in.Container()+":3000/rss.xml"); err != nil {
				if _, err2 := sh.OutTimeout(8*time.Second, "", "docker", "exec", cn, "curl", "-fs", "-o", "/dev/null", "http://"+in.Container()+":3000/rss.xml"); err2 != nil {
					fail("proxy can't reach %s:3000 — are both on the `web` network? Is there a site block in the Caddyfile?", in.Container())
				} else {
					ok("proxy reaches the site")
				}
			} else {
				ok("proxy reaches the site")
			}
		}
		// A web server installed on the host itself races the proxy container
		// for 80/443 at every boot. When it wins, the proxy's start fails and
		// docker leaves it down.
		if cn != "" {
			for _, svc := range hostWebServers() {
				fail("host service %s is %s — it competes with %s for ports 80/443 and can win at boot, leaving the proxy down: sudo systemctl disable --now %s", svc.name, svc.state, cn, svc.name)
			}
		}
		var published map[string]bool
		if out, err := sh.Out("", "docker", "ps", "--format", "{{.Ports}}"); err == nil {
			published = publishedHostPorts(out)
		}
		for _, port := range []string{"80", "443"} {
			if portListening(port) {
				if cn != "" && published != nil && !published[port] {
					fail("port %s is held by something outside docker, not by %s — sudo ss -ltnp | grep ':%s ' names it", port, cn, port)
				} else {
					ok("port %s is listening", port)
				}
			} else {
				fail("nothing listening on :%s — is the proxy published? (and open it in the cloud firewall)", port)
			}
		}
	}

	// DNS
	if d := in.Settings.Domain; d != "" {
		ips, err := net.LookupHost(d)
		if err != nil || len(ips) == 0 {
			fail("%s doesn't resolve yet", d)
		} else if looksCloudflare(ips) {
			ok("%s → Cloudflare proxy (%s). SSL mode must be Full (strict); uploads >100 MB need a DNS-only UPLOAD_ORIGIN", d, ips[0])
			if in.Env.Get("UPLOAD_ORIGIN") == "" && in.Settings.Proxy != "tunnel" {
				warn("UPLOAD_ORIGIN unset behind Cloudflare — phone videos over 100 MB will fail (docs/deploy.md §5)")
			}
		} else {
			ok("%s → %s", d, strings.Join(ips, ", "))
		}
	}

	if problems == 0 {
		say("\nall good.")
	} else {
		say("\n%d problem(s).", problems)
	}
	return nil
}

func portListening(port string) bool {
	c, err := net.DialTimeout("tcp", "127.0.0.1:"+port, time.Second)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// looksCloudflare checks against Cloudflare's published anycast ranges
// (https://www.cloudflare.com/ips/), v4 and v6.
func looksCloudflare(ips []string) bool {
	for _, ip := range ips {
		a := net.ParseIP(ip)
		if a == nil {
			continue
		}
		for _, cidr := range cloudflareRanges {
			if _, n, err := net.ParseCIDR(cidr); err == nil && n.Contains(a) {
				return true
			}
		}
	}
	return false
}

var cloudflareRanges = []string{
	"173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
	"108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
	"162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
	"2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
}

// guessProxyContainer finds a running container with "caddy" in its name.
func guessProxyContainer() string {
	out, err := sh.Out("", "docker", "ps", "--format", "{{.Names}}")
	if err != nil {
		return ""
	}
	for _, n := range strings.Split(out, "\n") {
		if strings.Contains(strings.ToLower(n), "caddy") || strings.Contains(strings.ToLower(n), "traefik") || strings.Contains(strings.ToLower(n), "nginx") {
			return n
		}
	}
	return ""
}

type hostService struct{ name, state string }

// hostWebServers lists web servers installed as systemd services on the host
// that are running now or enabled for the next boot.
func hostWebServers() []hostService {
	if !sh.Have("systemctl") {
		return nil
	}
	var found []hostService
	for _, name := range []string{"caddy", "nginx", "apache2", "httpd"} {
		// Both exit non-zero for "disabled"/"inactive"; the word is on stdout.
		active, _ := sh.Out("", "systemctl", "is-active", name)
		enabled, _ := sh.Out("", "systemctl", "is-enabled", name)
		switch {
		case active == "active" && enabled == "enabled":
			found = append(found, hostService{name, "running and enabled at boot"})
		case active == "active":
			found = append(found, hostService{name, "running"})
		case enabled == "enabled":
			found = append(found, hostService{name, "enabled at boot"})
		}
	}
	return found
}

// publishedHostPorts reads `docker ps --format {{.Ports}}` output and returns
// the host ports containers publish, e.g. "0.0.0.0:80->80/tcp, [::]:80->80/tcp".
func publishedHostPorts(psPorts string) map[string]bool {
	ports := map[string]bool{}
	for _, line := range strings.Split(psPorts, "\n") {
		for _, part := range strings.Split(line, ",") {
			host, _, found := strings.Cut(strings.TrimSpace(part), "->")
			if !found {
				continue // exposed but not published
			}
			if i := strings.LastIndex(host, ":"); i >= 0 {
				ports[host[i+1:]] = true
			}
		}
	}
	return ports
}

var _ = install.DefaultRepo
