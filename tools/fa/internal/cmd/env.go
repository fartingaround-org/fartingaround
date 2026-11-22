package cmd

import (
	"crypto/rand"
	"encoding/hex"
	"strings"
)

func init() {
	register(&command{name: "env", summary: "fa env list | get KEY | set KEY VALUE | unset KEY  (edits .env, keeps .env.fa-backup; restart to apply)", run: runEnv})
	register(&command{name: "token", summary: "fa token rotate — new CAPTURE_API_TOKEN for the phone app", run: runToken})
	register(&command{name: "set", summary: "fa set <proxy|caddyContainer|domain|backupDir> VALUE — fa's own settings", run: runSet})
}

var secretKeys = map[string]bool{"ADMIN_PASSWORD": true, "CAPTURE_API_TOKEN": true, "UPLOAD_SECRET": true, "DATABASE_URL": true, "TUNNEL_TOKEN": true}

func runEnv(c *Ctx, args []string) error {
	e := c.In.Env
	if len(args) == 0 || args[0] == "list" {
		for _, k := range e.Keys() {
			v := e.Get(k)
			if secretKeys[k] && v != "" {
				v = mask(v)
			}
			say("%s=%s", k, v)
		}
		return nil
	}
	switch args[0] {
	case "get":
		if len(args) != 2 {
			return errorf("usage: fa env get KEY")
		}
		say("%s", e.Get(args[1]))
		return nil
	case "set":
		if len(args) != 3 {
			return errorf("usage: fa env set KEY VALUE")
		}
		e.Set(strings.ToUpper(args[1]), args[2])
		if err := e.Save(); err != nil {
			return err
		}
		ok("%s saved — `fa restart` to apply", strings.ToUpper(args[1]))
		return nil
	case "unset":
		if len(args) != 2 {
			return errorf("usage: fa env unset KEY")
		}
		k := strings.ToUpper(args[1])
		if !e.Unset(k) {
			say("%s is not in .env — nothing to remove", k)
			return nil
		}
		if err := e.Save(); err != nil {
			return err
		}
		ok("%s removed — `fa restart` to apply", k)
		return nil
	}
	return errorf("usage: fa env [list|get KEY|set KEY VALUE|unset KEY]")
}

func runToken(c *Ctx, args []string) error {
	if len(args) != 1 || args[0] != "rotate" {
		return errorf("usage: fa token rotate")
	}
	t := randomHex(32)
	c.In.Env.Set("CAPTURE_API_TOKEN", t)
	if err := c.In.Env.Save(); err != nil {
		return err
	}
	say("new CAPTURE_API_TOKEN (paste into the app's Settings):\n\n  %s\n", t)
	return composePass(c.In, "up", "-d", "site")
}

func runSet(c *Ctx, args []string) error {
	if len(args) != 2 {
		return errorf("usage: fa set <proxy|caddyContainer|domain|backupDir> VALUE")
	}
	s := &c.In.Settings
	switch args[0] {
	case "proxy":
		if args[1] != "existing" && args[1] != "caddy" && args[1] != "tunnel" {
			return errorf("proxy must be existing, caddy or tunnel")
		}
		s.Proxy = args[1]
	case "caddyContainer":
		s.CaddyContainer = args[1]
	case "domain":
		s.Domain = args[1]
	case "backupDir":
		s.BackupDir = args[1]
	default:
		return errorf("unknown setting %q", args[0])
	}
	return c.In.SaveSettings()
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func mask(v string) string {
	if len(v) <= 6 {
		return "••••"
	}
	return v[:3] + "•••••" + v[len(v)-3:]
}
