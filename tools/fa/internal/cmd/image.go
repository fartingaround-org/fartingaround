package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// A site runs one of two ways:
//
//   - from the published image (the usual case): docker-compose.yml names
//     ghcr.io/…/fartingaround:<FA_VERSION> and a deploy downloads it;
//   - built on the server from the checkout: an older compose file that only
//     has `build:`, or FA_BUILD=1 in .env for someone running their own changes.
//
// Everything here is about the first.

// publishedImage is the image the compose file names for the site, with .env
// applied (ghcr.io/fartingaround-org/fartingaround:edge). Empty means this
// install builds its own.
func publishedImage(in *install.Install) string {
	switch strings.ToLower(in.Env.Get("FA_BUILD")) {
	case "1", "true", "yes":
		return ""
	}
	out, err := compose(in, "config", "--format", "json")
	if err != nil {
		return ""
	}
	return imageFromComposeConfig(out)
}

// imageFromComposeConfig reads services.site.image out of `docker compose
// config --format json`. A service with only `build:` has no image there.
func imageFromComposeConfig(js string) string {
	var cfg struct {
		Services map[string]struct {
			Image string `json:"image"`
		} `json:"services"`
	}
	if err := json.Unmarshal([]byte(js), &cfg); err != nil {
		return ""
	}
	return strings.TrimSpace(cfg.Services["site"].Image)
}

// imageRef is registry/repo:tag taken apart.
type imageRef struct{ Registry, Repo, Tag string }

// parseImageRef splits a reference that names its registry and is not pinned
// by digest. Anything else returns false: fa then says it cannot check for a
// newer image, which is true, and deploys by asking docker.
func parseImageRef(s string) (imageRef, bool) {
	if s == "" || strings.Contains(s, "@") {
		return imageRef{}, false
	}
	slash := strings.Index(s, "/")
	if slash < 0 {
		return imageRef{}, false
	}
	host, rest := s[:slash], s[slash+1:]
	if !strings.ContainsAny(host, ".:") && host != "localhost" {
		return imageRef{}, false // "library/thing": Docker Hub's short form
	}
	tag := "latest"
	if colon := strings.LastIndex(rest, ":"); colon > strings.LastIndex(rest, "/") {
		rest, tag = rest[:colon], rest[colon+1:]
	}
	if rest == "" || tag == "" {
		return imageRef{}, false
	}
	return imageRef{Registry: host, Repo: rest, Tag: tag}, true
}

// registryBase is where a registry's API lives. A variable so tests can point
// it at a local server.
var registryBase = func(host string) string { return "https://" + host }

// The kinds of manifest a tag can point at. Asking for all of them gets the
// digest of the multi-platform index when there is one, which is the digest
// docker records for an image pulled by tag.
const manifestTypes = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

// remoteDigest asks the registry which image a tag points at right now,
// without downloading it. Public images need no sign-in: the registry answers
// 401 with where to get an anonymous token, and the request is repeated with it.
func remoteDigest(c *http.Client, ref imageRef) (string, error) {
	u := registryBase(ref.Registry) + "/v2/" + ref.Repo + "/manifests/" + url.PathEscape(ref.Tag)
	head := func(token string) (*http.Response, error) {
		req, err := http.NewRequest(http.MethodHead, u, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", manifestTypes)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		return c.Do(req)
	}
	res, err := head("")
	if err != nil {
		return "", err
	}
	res.Body.Close()
	if res.StatusCode == http.StatusUnauthorized {
		token, err := anonymousToken(c, res.Header.Get("WWW-Authenticate"))
		if err != nil {
			return "", err
		}
		if res, err = head(token); err != nil {
			return "", err
		}
		res.Body.Close()
	}
	switch {
	case res.StatusCode == http.StatusNotFound:
		return "", fmt.Errorf("the registry has no %s:%s", ref.Repo, ref.Tag)
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return "", fmt.Errorf("the registry wants a sign-in for %s (is the image public?)", ref.Repo)
	case res.StatusCode != http.StatusOK:
		return "", fmt.Errorf("the registry answered HTTP %d", res.StatusCode)
	}
	d := res.Header.Get("Docker-Content-Digest")
	if d == "" {
		return "", fmt.Errorf("the registry did not say which image %s is", ref.Tag)
	}
	return d, nil
}

// anonymousToken follows a `Bearer realm="…",service="…",scope="…"` challenge.
func anonymousToken(c *http.Client, challenge string) (string, error) {
	realm, params := parseBearerChallenge(challenge)
	if realm == "" {
		return "", fmt.Errorf("the registry wants a sign-in")
	}
	q := url.Values{}
	for _, k := range []string{"service", "scope"} {
		if v := params[k]; v != "" {
			q.Set(k, v)
		}
	}
	res, err := c.Get(realm + "?" + q.Encode())
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusUnauthorized || res.StatusCode == http.StatusForbidden {
		// How a registry answers an anonymous visitor asking about a private image.
		return "", fmt.Errorf("the registry won't serve this image without a sign-in (is it public?)")
	}
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("the registry's token service answered HTTP %d", res.StatusCode)
	}
	var body struct {
		Token       string `json:"token"`
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return "", err
	}
	if body.Token != "" {
		return body.Token, nil
	}
	return body.AccessToken, nil
}

// parseBearerChallenge reads a WWW-Authenticate header into its realm and the
// rest of its key="value" pairs.
func parseBearerChallenge(h string) (realm string, params map[string]string) {
	params = map[string]string{}
	rest, ok := strings.CutPrefix(strings.TrimSpace(h), "Bearer ")
	if !ok {
		return "", params
	}
	for _, part := range strings.Split(rest, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		params[strings.ToLower(k)] = strings.Trim(v, `"`)
	}
	return params["realm"], params
}

// localDigests lists the registry digests docker has recorded for an image
// that is on this server. Empty when the image has never been pulled.
func localDigests(ref string) []string {
	out, err := sh.Out("", "docker", "image", "inspect", "--format", "{{json .RepoDigests}}", ref)
	if err != nil {
		return nil
	}
	return digestsFromRepoDigests(out)
}

// digestsFromRepoDigests turns ["ghcr.io/x/y@sha256:…"] into ["sha256:…"].
func digestsFromRepoDigests(js string) []string {
	var entries []string
	if err := json.Unmarshal([]byte(js), &entries); err != nil {
		return nil
	}
	var out []string
	for _, e := range entries {
		if _, d, ok := strings.Cut(e, "@"); ok && d != "" {
			out = append(out, d)
		}
	}
	return out
}

// imageNews compares the image on this server with the one the registry has
// for the same tag. It returns a line worth showing (empty when there is
// nothing to say) and whether a deploy would bring in a different image.
func imageNews(ref string) (line string, newer bool) {
	r, ok := parseImageRef(ref)
	if !ok {
		return "image  " + ref + " — can't check this kind of reference for a newer one", false
	}
	remote, err := remoteDigest(&http.Client{Timeout: 15 * time.Second}, r)
	if err != nil {
		return fmt.Sprintf("image  couldn't check for a newer %s: %v", ref, err), false
	}
	return describeImageNews(ref, localDigests(ref), remote)
}

func describeImageNews(ref string, local []string, remote string) (string, bool) {
	if len(local) == 0 {
		return fmt.Sprintf("image  %s is not on this server yet (%s)", ref, shortDigest(remote)), true
	}
	for _, d := range local {
		if d == remote {
			return "", false
		}
	}
	return fmt.Sprintf("image  a newer %s is published (%s → %s)", ref, shortDigest(local[0]), shortDigest(remote)), true
}

// shortDigest is sha256:0123456789ab… cut to something a person can compare.
func shortDigest(d string) string {
	_, hex, ok := strings.Cut(d, ":")
	if !ok {
		hex = d
	}
	if len(hex) > 12 {
		hex = hex[:12]
	}
	return hex
}

// runningImageID is the image the site's container was started from, as an
// id that still names that exact image after its tag has moved on.
func runningImageID(in *install.Install) string {
	out, err := sh.Out("", "docker", "inspect", "--format", "{{.Image}}", in.Container())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(out)
}

// deployImage downloads the newest image for the install's FA_VERSION and
// restarts the site on it. If the site does not come up, the image it was
// running before is put back under the same name and started again.
func deployImage(c *Ctx, ref, commitBefore string) error {
	in := c.In
	previous := runningImageID(in)

	say("downloading %s…", ref)
	if err := composePass(in, "pull", "site"); err != nil {
		return errorf("couldn't download %s (%v) — the site is still running what it was%s", ref, err, pullHint(ref))
	}
	if err := composePass(in, "up", "-d", "--no-build", "--remove-orphans"); err != nil {
		return rollbackImage(c, ref, previous, commitBefore, err)
	}
	if err := waitHealthy(in, 90*time.Second); err != nil {
		out, _ := compose(in, "logs", "--tail", "40", "site")
		say("%s", indent(out))
		return rollbackImage(c, ref, previous, commitBefore, err)
	}
	_, _ = sh.Out("", "docker", "image", "prune", "-f")
	version := ""
	if d := localDigests(ref); len(d) > 0 {
		version = " (" + shortDigest(d[0]) + ")"
	}
	ok("deployed %s%s", ref, version)
	return nil
}

func rollbackImage(c *Ctx, ref, previous, commitBefore string, cause error) error {
	in := c.In
	warn("deploy failed: %v", cause)
	if previous == "" {
		return errorf("there was no running site to go back to; fix and `fa up`")
	}
	say("going back to the image that was running (%s)…", shortDigest(previous))
	// The checkout may have moved too (a newer compose file); put it back so
	// the old image runs with the settings it was started with.
	if err := moveCheckoutBack(in.Dir, commitBefore); err != nil {
		warn("couldn't move the checkout back to %s (%v) — the previous image is restored below, but the files stay at %s", commitBefore, err, gitHead(in))
	}
	if _, err := sh.Out("", "docker", "tag", previous, ref); err != nil {
		return errorf("rollback failed too: %v", err)
	}
	if err := composePass(in, "up", "-d", "--no-build", "site"); err != nil {
		return errorf("rollback failed too: %v", err)
	}
	if err := waitHealthy(in, 60*time.Second); err != nil {
		return errorf("went back to the previous image but the site is still down: %v", err)
	}
	warn("back on the previous image. `fa deploy` again once a fixed one is published.")
	return cause
}

// pullHint says what is probably wrong when docker could not download the
// image, by asking the registry the same question without any sign-in.
func pullHint(ref string) string {
	r, ok := parseImageRef(ref)
	if !ok {
		return ""
	}
	_, err := remoteDigest(&http.Client{Timeout: 15 * time.Second}, r)
	return describePullFailure(r, err)
}

func describePullFailure(r imageRef, anonymousErr error) string {
	if anonymousErr == nil {
		// Anyone may read it, yet docker was refused: docker is sending a
		// sign-in the registry rejects, usually one saved long ago.
		return fmt.Sprintf("\n  The registry serves this image without a sign-in, so docker is probably sending an old one.\n  Try: docker logout %s   and then fa deploy again.", r.Registry)
	}
	return "\n  Asked without a sign-in: " + anonymousErr.Error() + "."
}
