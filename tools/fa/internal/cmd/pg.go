package cmd

import "net/url"

// pgHost pulls the host out of a postgres:// URL — in the fleet it's the
// container name of the shared cluster.
func pgHost(dsn string) string {
	u, err := url.Parse(dsn)
	if err != nil {
		return ""
	}
	return u.Hostname()
}
