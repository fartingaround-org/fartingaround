package cmd

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// What `docker compose config --format json` prints for this repo's compose
// file (trimmed), with and without the image line.
func TestImageFromComposeConfig(t *testing.T) {
	withImage := `{"name":"fartingaround","services":{"site":{"build":{"context":"/srv/fartingaround","dockerfile":"Dockerfile"},"container_name":"fartingaround-site","image":"ghcr.io/fartingaround-org/fartingaround:edge"}}}`
	if got := imageFromComposeConfig(withImage); got != "ghcr.io/fartingaround-org/fartingaround:edge" {
		t.Errorf("image = %q", got)
	}
	// An older checkout: the service only builds.
	buildOnly := `{"name":"fartingaroundfun","services":{"site":{"build":{"context":"/home/tony/fartingaround.fun"},"container_name":"fartingaround-site"}}}`
	if got := imageFromComposeConfig(buildOnly); got != "" {
		t.Errorf("a build-only service has no published image, got %q", got)
	}
	for _, junk := range []string{"", "not json", `{"services":{}}`} {
		if got := imageFromComposeConfig(junk); got != "" {
			t.Errorf("imageFromComposeConfig(%q) = %q, want empty", junk, got)
		}
	}
}

func TestParseImageRef(t *testing.T) {
	good := map[string]imageRef{
		"ghcr.io/fartingaround-org/fartingaround:edge":  {"ghcr.io", "fartingaround-org/fartingaround", "edge"},
		"ghcr.io/fartingaround-org/fartingaround:0.2.0": {"ghcr.io", "fartingaround-org/fartingaround", "0.2.0"},
		"ghcr.io/fartingaround-org/fartingaround":       {"ghcr.io", "fartingaround-org/fartingaround", "latest"},
		"localhost:5000/site:test":                      {"localhost:5000", "site", "test"},
		"registry.example.org:8443/a/b/c":               {"registry.example.org:8443", "a/b/c", "latest"},
	}
	for in, want := range good {
		got, ok := parseImageRef(in)
		if !ok || got != want {
			t.Errorf("parseImageRef(%q) = %+v, %v; want %+v", in, got, ok, want)
		}
	}
	// Not checkable: no registry named, pinned by digest, or nothing at all.
	for _, in := range []string{"", "fartingaround", "library/postgres:17", "ghcr.io/x/y@sha256:abc", "ghcr.io/"} {
		if got, ok := parseImageRef(in); ok {
			t.Errorf("parseImageRef(%q) = %+v, want not ok", in, got)
		}
	}
}

func TestParseBearerChallenge(t *testing.T) {
	realm, p := parseBearerChallenge(`Bearer realm="https://ghcr.io/token",service="ghcr.io",scope="repository:fartingaround-org/fartingaround:pull"`)
	if realm != "https://ghcr.io/token" || p["service"] != "ghcr.io" || p["scope"] != "repository:fartingaround-org/fartingaround:pull" {
		t.Errorf("realm=%q params=%v", realm, p)
	}
	if realm, _ := parseBearerChallenge(`Basic realm="x"`); realm != "" {
		t.Errorf("a Basic challenge is not one we can answer, got realm %q", realm)
	}
}

// A stand-in registry that behaves like a public one: the first request is
// refused with directions to a token service, and the token then works.
func fakeRegistry(t *testing.T, tags map[string]string) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/token":
			if r.URL.Query().Get("scope") != "repository:org/site:pull" {
				http.Error(w, "bad scope", http.StatusBadRequest)
				return
			}
			fmt.Fprint(w, `{"token":"anon-token"}`)
		case strings.HasPrefix(r.URL.Path, "/v2/org/site/manifests/"):
			if r.Header.Get("Authorization") != "Bearer anon-token" {
				w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s/token",service="test",scope="repository:org/site:pull"`, srv.URL))
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			if !strings.Contains(r.Header.Get("Accept"), "application/vnd.oci.image.index.v1+json") {
				http.Error(w, "no index type accepted", http.StatusNotAcceptable)
				return
			}
			digest, ok := tags[strings.TrimPrefix(r.URL.Path, "/v2/org/site/manifests/")]
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			w.Header().Set("Docker-Content-Digest", digest)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	old := registryBase
	registryBase = func(string) string { return srv.URL }
	t.Cleanup(func() { registryBase = old })
	return srv
}

func TestRemoteDigest(t *testing.T) {
	srv := fakeRegistry(t, map[string]string{"edge": "sha256:aaaa", "0.2.0": "sha256:bbbb"})
	c := srv.Client()

	got, err := remoteDigest(c, imageRef{"registry.test", "org/site", "edge"})
	if err != nil || got != "sha256:aaaa" {
		t.Errorf("edge: %q, %v", got, err)
	}
	// A tag nobody has published yet, e.g. `latest` before the first release.
	if _, err := remoteDigest(c, imageRef{"registry.test", "org/site", "latest"}); err == nil || !strings.Contains(err.Error(), "has no org/site:latest") {
		t.Errorf("a missing tag should say so, got %v", err)
	}
}

// A private image: the token service hands out a token that still is not
// allowed to read it. fa must say so instead of reporting "nothing new".
func TestRemoteDigestPrivateImage(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			fmt.Fprint(w, `{"token":"useless"}`)
			return
		}
		w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s/token",service="test"`, srv.URL))
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	old := registryBase
	registryBase = func(string) string { return srv.URL }
	defer func() { registryBase = old }()

	_, err := remoteDigest(srv.Client(), imageRef{"registry.test", "org/site", "edge"})
	if err == nil || !strings.Contains(err.Error(), "is the image public?") {
		t.Errorf("want a hint that the image is not public, got %v", err)
	}
}

func TestDigestsFromRepoDigests(t *testing.T) {
	got := digestsFromRepoDigests(`["ghcr.io/fartingaround-org/fartingaround@sha256:aaaa","other/name@sha256:bbbb"]`)
	if len(got) != 2 || got[0] != "sha256:aaaa" || got[1] != "sha256:bbbb" {
		t.Errorf("got %v", got)
	}
	// Built locally, never pulled: docker prints an empty list.
	for _, in := range []string{"[]", "null", ""} {
		if got := digestsFromRepoDigests(in); len(got) != 0 {
			t.Errorf("digestsFromRepoDigests(%q) = %v, want none", in, got)
		}
	}
}

func TestDescribeImageNews(t *testing.T) {
	const ref = "ghcr.io/fartingaround-org/fartingaround:edge"

	// Same image here and there: nothing to say, nothing to deploy.
	if line, newer := describeImageNews(ref, []string{"sha256:aaaa"}, "sha256:aaaa"); line != "" || newer {
		t.Errorf("up to date: %q, %v", line, newer)
	}
	// The tag has moved on.
	line, newer := describeImageNews(ref, []string{"sha256:aaaa1111222233334444"}, "sha256:bbbb1111222233334444")
	if !newer || !strings.Contains(line, "aaaa11112222 → bbbb11112222") {
		t.Errorf("newer: %q, %v", line, newer)
	}
	// Never downloaded: a deploy would bring it in.
	if line, newer := describeImageNews(ref, nil, "sha256:bbbb"); !newer || !strings.Contains(line, "not on this server yet") {
		t.Errorf("absent: %q, %v", line, newer)
	}
	// The dashboard lists every line under "incoming:", so the image's line
	// must be one line.
	if strings.Contains(line, "\n") {
		t.Errorf("image news must be a single line: %q", line)
	}
}

func TestShortDigest(t *testing.T) {
	for in, want := range map[string]string{
		"sha256:0123456789abcdef0123": "0123456789ab",
		"sha256:abc":                  "abc",
		"0123456789abcdef":            "0123456789ab",
		"":                            "",
	} {
		if got := shortDigest(in); got != want {
			t.Errorf("shortDigest(%q) = %q, want %q", in, got, want)
		}
	}
}

// What GitHub's registry does for a private image: the token service itself
// refuses an anonymous visitor.
func TestRemoteDigestTokenServiceRefuses(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/token" {
			w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s/token",service="test"`, srv.URL))
		}
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	old := registryBase
	registryBase = func(string) string { return srv.URL }
	defer func() { registryBase = old }()

	_, err := remoteDigest(srv.Client(), imageRef{"registry.test", "org/site", "edge"})
	if err == nil || !strings.Contains(err.Error(), "is it public?") {
		t.Errorf("want a hint that the image is not public, got %v", err)
	}
}
