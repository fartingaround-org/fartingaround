package install

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
)

// A server can hold more than one site. Each is a complete install in its own
// folder, with its own .env, data and container; nothing is shared between
// them but the downloaded image and the reverse proxy. The list of them is
// one small file in the server user's home, written by `fa add`.
//
// A server that has only ever had one site has no such file, and nothing
// about it changes.

// Site is one entry in that list.
type Site struct {
	Name string `json:"name"`
	Dir  string `json:"dir"`
	// Kind is what sort of thing this is. Empty means a fartingaround site,
	// the only kind fa can add so far; the field is here so that others (an
	// atlas, say) can join the same list without changing its shape.
	Kind string `json:"kind,omitempty"`
}

// MainSite is the name of the site the server started with.
const MainSite = "main"

// DefaultContainer is the site container's name when .env doesn't say.
const DefaultContainer = "fartingaround-site"

// SitesFile is where the list lives (FA_SITES_FILE overrides it, for tests).
func SitesFile() string {
	if f := os.Getenv("FA_SITES_FILE"); f != "" {
		return f
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "fa", "sites.json")
}

// ReadSites returns the list, main first and then by name. A server with no
// list yet returns none.
func ReadSites() ([]Site, error) {
	b, err := os.ReadFile(SitesFile())
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var sites []Site
	if err := json.Unmarshal(b, &sites); err != nil {
		return nil, fmt.Errorf("%s is not readable (%v) — fix or remove it", SitesFile(), err)
	}
	sortSites(sites)
	return sites, nil
}

// WriteSites replaces the list.
func WriteSites(sites []Site) error {
	sortSites(sites)
	b, err := json.MarshalIndent(sites, "", "  ")
	if err != nil {
		return err
	}
	f := SitesFile()
	if err := os.MkdirAll(filepath.Dir(f), 0o700); err != nil {
		return err
	}
	return os.WriteFile(f, append(b, '\n'), 0o600)
}

func sortSites(sites []Site) {
	sort.SliceStable(sites, func(i, j int) bool {
		if (sites[i].Name == MainSite) != (sites[j].Name == MainSite) {
			return sites[i].Name == MainSite
		}
		return sites[i].Name < sites[j].Name
	})
}

// FindSite looks a name up in the list.
func FindSite(name string) (Site, bool, error) {
	sites, err := ReadSites()
	if err != nil {
		return Site{}, false, err
	}
	for _, s := range sites {
		if s.Name == name {
			return s, true, nil
		}
	}
	return Site{}, false, nil
}

var siteNameRE = regexp.MustCompile(`^[a-z][a-z0-9-]{0,29}$`)

// ValidSiteName checks a name for an added site. It ends up in a folder name,
// a container name and a backup profile, so it is kept plain.
func ValidSiteName(name string) error {
	switch {
	case name == MainSite:
		return fmt.Errorf("%q is the name of the site this server started with — pick another", MainSite)
	case !siteNameRE.MatchString(name) || name[len(name)-1] == '-':
		return fmt.Errorf("site name %q won't do: lowercase letters, digits and hyphens, starting with a letter, 30 at most", name)
	}
	return nil
}

// Container is the name of this install's site container: FA_CONTAINER in
// .env for an added site, the fixed name for the first one.
func (in *Install) Container() string {
	if n := in.Env.Get("FA_CONTAINER"); n != "" {
		return n
	}
	return DefaultContainer
}
