package cmd

import (
	"os"
	"strconv"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/ui"
)

func init() {
	register(&command{name: "ui", summary: "open the dashboard in your browser: fa ui [--port N] [--no-open]  (runs on this machine, talks ssh)", noInstall: true, run: runUI})
}

func runUI(c *Ctx, args []string) error {
	o := ui.Options{Port: 7321, Open: true, Version: c.Version, InitialHost: os.Getenv("FA_HOST"), InitialDir: c.Dir}
	if os.Getenv("FA_UI_RESTARTED") != "" { // restarted by its own page after a self-update: the tab is already open
		o.Open = false
	}
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--port" && i+1 < len(args):
			o.Port, _ = strconv.Atoi(args[i+1])
			i++
		case args[i] == "--no-open":
			o.Open = false
		}
	}
	return ui.Serve(o)
}
