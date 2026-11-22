// Package cmd is fa's command set. Plain dispatch on os.Args — the surface
// is small enough that a CLI framework would be more code than it saves.
package cmd

import (
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/install"
	"github.com/fartingaround-org/fartingaround/tools/fa/internal/sh"
)

type command struct {
	name, summary string
	run           func(ctx *Ctx, args []string) error
	// noInstall commands (setup, version) run without a located install.
	noInstall bool
}

// Ctx is what every command gets.
type Ctx struct {
	In      *install.Install
	Version string
	Dir     string // --dir, if given
}

var commands = map[string]*command{}

func register(c *command) { commands[c.name] = c }

// Run parses global flags, handles --host, locates the install and dispatches.
func Run(args []string, version string) error {
	fs := flag.NewFlagSet("fa", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	host := fs.String("host", os.Getenv("FA_HOST"), "run on this ssh host (user@box) instead of locally")
	dir := fs.String("dir", "", "install directory (default: FA_DIR, cwd, /opt/fartingaround, ~/fartingaround.fun)")
	verbose := fs.Bool("v", false, "echo the commands fa runs")
	fs.Usage = func() { usage(fs) }
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return nil
		}
		return err
	}
	sh.Verbose = *verbose
	rest := fs.Args()

	// ui, pull and self-update are laptop-side commands: --host tells them which server, it
	// doesn't move the command there.
	localOnly := len(rest) > 0 && (rest[0] == "ui" || rest[0] == "pull" || rest[0] == "self-update")
	if *host != "" && !localOnly {
		return runRemote(*host, *dir, *verbose, rest)
	}
	if *host != "" && rest[0] == "ui" {
		os.Setenv("FA_HOST", *host)
	}

	if len(rest) == 0 {
		usage(fs)
		return nil
	}
	name := rest[0]
	c, ok := commands[name]
	if !ok {
		return fmt.Errorf("unknown command %q — try `fa help`", name)
	}
	ctx := &Ctx{Version: version, Dir: *dir}
	if !c.noInstall {
		in, err := install.Find(*dir)
		if err != nil {
			return err
		}
		ctx.In = in
	}
	return c.run(ctx, rest[1:])
}

// runRemote re-invokes fa over ssh with the same arguments. -t keeps logs -f
// and the setup prompts interactive.
func runRemote(host, dir string, verbose bool, rest []string) error {
	remote := []string{"fa"}
	if dir != "" {
		remote = append(remote, "--dir", shellQuote(dir))
	}
	if verbose {
		remote = append(remote, "-v")
	}
	for _, a := range rest {
		remote = append(remote, shellQuote(a))
	}
	err := sh.Pass("", "ssh", "-t", host, strings.Join(remote, " "))
	if err != nil && strings.Contains(err.Error(), "exit status 127") {
		return fmt.Errorf("fa isn't installed on %s — see README: install it there first", host)
	}
	return err
}

func shellQuote(s string) string {
	if s == "" {
		return "''"
	}
	if !strings.ContainsAny(s, " \t\n'\"\\$`!*?[]{}()<>|&;#~") {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func usage(fs *flag.FlagSet) {
	fmt.Fprintln(os.Stderr, "fa — control center for a fartingaround install")
	fmt.Fprintln(os.Stderr, "\nusage: fa [--host user@box] [--dir path] [-v] <command> [args]\n\ncommands:")
	names := make([]string, 0, len(commands))
	for n := range commands {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		fmt.Fprintf(os.Stderr, "  %-10s %s\n", n, commands[n].summary)
	}
	fmt.Fprintln(os.Stderr, "\nflags:")
	fs.PrintDefaults()
}

func init() {
	register(&command{name: "help", summary: "this list", noInstall: true, run: func(*Ctx, []string) error {
		usage(flag.NewFlagSet("fa", flag.ContinueOnError))
		return nil
	}})
	register(&command{name: "version", summary: "print fa's version", noInstall: true, run: func(c *Ctx, _ []string) error {
		fmt.Println("fa", c.Version)
		return nil
	}})
}

// ── small shared helpers ───────────────────────────────────────────────────

func say(format string, a ...any)  { fmt.Printf(format+"\n", a...) }
func warn(format string, a ...any) { fmt.Fprintf(os.Stderr, "  ! "+format+"\n", a...) }
func ok(format string, a ...any)   { fmt.Printf("  ✓ "+format+"\n", a...) }
func bad(format string, a ...any)  { fmt.Printf("  ✗ "+format+"\n", a...) }
