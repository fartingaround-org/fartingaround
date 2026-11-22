// Package sh runs external commands. fa is mostly a careful wrapper around
// docker, git and friends, so this is deliberately thin.
package sh

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"time"
)

// Verbose echoes every command before running it (fa -v).
var Verbose bool

func echo(dir, name string, args []string) {
	if !Verbose {
		return
	}
	where := ""
	if dir != "" {
		where = dir + " "
	}
	fmt.Fprintf(os.Stderr, "+ %s$ %s %s\n", where, name, strings.Join(args, " "))
}

// Out runs and returns trimmed stdout; stderr goes into the error.
func Out(dir, name string, args ...string) (string, error) {
	echo(dir, name, args)
	c := exec.Command(name, args...)
	c.Dir = dir
	var stdout, stderr bytes.Buffer
	c.Stdout, c.Stderr = &stdout, &stderr
	if err := c.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return strings.TrimSpace(stdout.String()), fmt.Errorf("%s %s: %s", name, strings.Join(args, " "), msg)
	}
	return strings.TrimSpace(stdout.String()), nil
}

// OutTimeout is Out with a deadline.
func OutTimeout(d time.Duration, dir, name string, args ...string) (string, error) {
	echo(dir, name, args)
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	c := exec.CommandContext(ctx, name, args...)
	c.Dir = dir
	var stdout, stderr bytes.Buffer
	c.Stdout, c.Stderr = &stdout, &stderr
	err := c.Run()
	if ctx.Err() != nil {
		return "", fmt.Errorf("%s timed out after %s", name, d)
	}
	if err != nil {
		return strings.TrimSpace(stdout.String()), fmt.Errorf("%s: %s", name, strings.TrimSpace(stderr.String()))
	}
	return strings.TrimSpace(stdout.String()), nil
}

// Pass runs with the terminal attached — for logs -f, interactive psql, etc.
func Pass(dir, name string, args ...string) error {
	echo(dir, name, args)
	c := exec.Command(name, args...)
	c.Dir = dir
	c.Stdin, c.Stdout, c.Stderr = os.Stdin, os.Stdout, os.Stderr
	return c.Run()
}

// ToFile runs and streams stdout into w (pg_dump > file).
func ToFile(dir string, w io.Writer, name string, args ...string) error {
	echo(dir, name, args)
	c := exec.Command(name, args...)
	c.Dir = dir
	var stderr bytes.Buffer
	c.Stdout, c.Stderr = w, &stderr
	if err := c.Run(); err != nil {
		return fmt.Errorf("%s: %s", name, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// FromFile runs with r as stdin (psql < dump); stderr goes into the error.
func FromFile(dir string, r io.Reader, name string, args ...string) error {
	echo(dir, name, args)
	c := exec.Command(name, args...)
	c.Dir = dir
	var stderr bytes.Buffer
	c.Stdin, c.Stderr = r, &stderr
	if err := c.Run(); err != nil {
		return fmt.Errorf("%s: %s", name, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// Have reports whether a program is on PATH.
func Have(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}
