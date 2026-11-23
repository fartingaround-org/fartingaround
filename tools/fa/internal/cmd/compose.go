package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

// compose runs `docker compose` in the install with its -f set.
func compose(in *install.Install, args ...string) (string, error) {
	return sh.Out(in.Dir, "docker", append(append([]string{"compose"}, in.ComposeFiles()...), args...)...)
}

func composePass(in *install.Install, args ...string) error {
	return sh.Pass(in.Dir, "docker", append(append([]string{"compose"}, in.ComposeFiles()...), args...)...)
}

type containerInfo struct {
	Running   bool
	Status    string
	Image     string
	StartedAt time.Time
	Error     string            // docker's reason the last start failed, if it did
	Mounts    map[string]string // destination → source
	Networks  []string          // docker networks the container is attached to
}

func (ci *containerInfo) onNetwork(name string) bool {
	for _, n := range ci.Networks {
		if n == name {
			return true
		}
	}
	return false
}

func inspectContainer(name string) (*containerInfo, error) {
	out, err := sh.Out("", "docker", "inspect", name)
	if err != nil {
		return nil, err
	}
	var raw []struct {
		State struct {
			Running   bool
			Status    string
			StartedAt string
			Error     string
		}
		Config          struct{ Image string }
		Mounts          []struct{ Source, Destination string }
		NetworkSettings struct {
			Networks map[string]json.RawMessage
		}
	}
	if err := json.Unmarshal([]byte(out), &raw); err != nil || len(raw) == 0 {
		return nil, fmt.Errorf("docker inspect %s: unreadable output", name)
	}
	r := raw[0]
	ci := &containerInfo{Running: r.State.Running, Status: r.State.Status, Image: r.Config.Image, Error: r.State.Error, Mounts: map[string]string{}}
	for n := range r.NetworkSettings.Networks {
		ci.Networks = append(ci.Networks, n)
	}
	ci.StartedAt, _ = time.Parse(time.RFC3339Nano, r.State.StartedAt)
	for _, m := range r.Mounts {
		ci.Mounts[m.Destination] = m.Source
	}
	return ci, nil
}

// healthy asks the site for its feed on the debug port.
func healthy(in *install.Install) error {
	c := &http.Client{Timeout: 5 * time.Second}
	res, err := c.Get("http://127.0.0.1:" + in.DebugPort() + "/rss.xml")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return fmt.Errorf("HTTP %d from the site", res.StatusCode)
	}
	return nil
}

// waitHealthy polls up to the deadline.
func waitHealthy(in *install.Install, d time.Duration) error {
	deadline := time.Now().Add(d)
	var last error
	for time.Now().Before(deadline) {
		if last = healthy(in); last == nil {
			return nil
		}
		time.Sleep(2 * time.Second)
	}
	return fmt.Errorf("site did not come up within %s: %v", d, last)
}

// gitHead returns the short commit of the checkout.
func gitHead(in *install.Install) string {
	out, err := sh.Out(in.Dir, "git", "rev-parse", "--short", "HEAD")
	if err != nil {
		return "?"
	}
	return out
}

// gitEnv sets GIT_SSH_COMMAND for a per-repo deploy key when one exists.
func gitArgsWithKey(in *install.Install, args ...string) []string {
	key := in.Env.Get("DEPLOY_KEY")
	if key == "" {
		if home, _ := homeDir(); home != "" {
			key = home + "/.ssh/fartingaround_deploy"
		}
	}
	if key != "" && fileExists(key) {
		return append([]string{"-c", "core.sshCommand=ssh -i " + key + " -o IdentitiesOnly=yes"}, args...)
	}
	return args
}

func joinNonEmpty(sep string, parts ...string) string {
	var out []string
	for _, p := range parts {
		if p != "" {
			out = append(out, p)
		}
	}
	return strings.Join(out, sep)
}
