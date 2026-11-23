package cmd

import (
	"strings"
	"testing"
)

func TestProxyConfig(t *testing.T) {
	c := proxyConfig("main", "example.org", "fartingaround-site", "upload.example.org")
	for _, want := range []string{proxyFileMark, "example.org, www.example.org {", "max_size 60MB", "upload.example.org {", "max_size 2GB", "reverse_proxy fartingaround-site:3000"} {
		if !strings.Contains(c, want) {
			t.Errorf("missing %q in:\n%s", want, c)
		}
	}
	// A site on a subdomain, with no separate upload address.
	c = proxyConfig("notes", "notes.example.org", "fa-notes", "")
	if strings.Contains(c, "www.") || strings.Contains(c, "60MB") || !strings.Contains(c, "notes.example.org {") || !strings.Contains(c, "max_size 2GB") {
		t.Errorf("unexpected config for a subdomain site:\n%s", c)
	}
	if !strings.HasPrefix(c, proxyFileMark) {
		t.Error("the file must start with fa's mark, or fa won't replace it later")
	}
}

func TestFindProxyPlaces(t *testing.T) {
	cases := []struct {
		what   string
		mounts map[string]string
		want   proxyPlaces
	}{
		{"only the Caddyfile is mounted", map[string]string{"/etc/caddy/Caddyfile": "/home/t/caddy/Caddyfile", "/data": "/var/x"}, proxyPlaces{"", "/home/t/caddy/Caddyfile"}},
		{"the whole folder is mounted", map[string]string{"/etc/caddy": "/home/t/proxy"}, proxyPlaces{"/home/t/proxy/sites", "/home/t/proxy/Caddyfile"}},
		{"a sites folder is mounted beside the file", map[string]string{"/etc/caddy/Caddyfile": "/home/t/caddy/Caddyfile", "/etc/caddy/sites": "/home/t/caddy/sites.d"}, proxyPlaces{"/home/t/caddy/sites.d", "/home/t/caddy/Caddyfile"}},
		{"nothing of Caddy's is mounted", map[string]string{}, proxyPlaces{}},
	}
	for _, tc := range cases {
		if got := findProxyPlaces(tc.mounts); got != tc.want {
			t.Errorf("%s: got %+v, want %+v", tc.what, got, tc.want)
		}
	}
}

const sampleCaddyfile = `# One block per site.
import sites/*.caddy

reasonsmith.com {
	file_server
}
www.reasonsmith.com {
	redir https://reasonsmith.com{uri} permanent
}
# fartingaround.org {   (commented out)
themoot.review, www.themoot.review {
	reverse_proxy moot-app:3000
}
`

func TestImportsSites(t *testing.T) {
	if !importsSites(sampleCaddyfile) || !importsSites("\timport /etc/caddy/sites/*\n") {
		t.Error("an import of the sites folder should be recognised")
	}
	for _, no := range []string{"", "# import sites/*.caddy\n", "import snippets/*\n", "example.org {\n}\n"} {
		if importsSites(no) {
			t.Errorf("should not count as importing the sites folder: %q", no)
		}
	}
}

func TestNamesSite(t *testing.T) {
	for _, d := range []string{"reasonsmith.com", "www.reasonsmith.com", "themoot.review", "www.themoot.review"} {
		if !namesSite(sampleCaddyfile, d) {
			t.Errorf("%s has a block in the sample", d)
		}
	}
	// Mentioned only in a comment, only as part of a longer name, or only inside a block.
	for _, d := range []string{"fartingaround.org", "smith.com", "moot-app", "example.org"} {
		if namesSite(sampleCaddyfile, d) {
			t.Errorf("%s has no block of its own in the sample", d)
		}
	}
}
