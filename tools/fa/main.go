// fa — the control center for a fartingaround install.
//
// One static binary. On the server it runs the commands against the
// install directly; on your laptop, `fa --host user@box ...` runs the same
// command over ssh. No daemon, no listening port: ssh is the auth.
package main

import (
	"fmt"
	"os"

	"github.com/fartingaround-org/fartingaround/tools/fa/internal/cmd"
)

var version = "dev" // set by -ldflags "-X main.version=..."

func main() {
	if err := cmd.Run(os.Args[1:], version); err != nil {
		fmt.Fprintln(os.Stderr, "fa:", err)
		os.Exit(1)
	}
}
