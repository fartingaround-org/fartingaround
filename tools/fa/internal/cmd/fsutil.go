package cmd

import (
	"os"
	"syscall"
)

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func homeDir() (string, error) { return os.UserHomeDir() }

// ownerUID returns the uid owning p, or -1.
func ownerUID(p string) int {
	st, err := os.Stat(p)
	if err != nil {
		return -1
	}
	if s, ok := st.Sys().(*syscall.Stat_t); ok {
		return int(s.Uid)
	}
	return -1
}

// diskFree reports free/total bytes on the filesystem holding p.
func diskFree(p string) (free, total uint64, err error) {
	var st syscall.Statfs_t
	if err = syscall.Statfs(p, &st); err != nil {
		return
	}
	free = st.Bavail * uint64(st.Bsize)
	total = st.Blocks * uint64(st.Bsize)
	return
}

// isMountPoint says whether p is its own filesystem (a mounted volume).
func isMountPoint(p string) bool {
	var a, b syscall.Stat_t
	if syscall.Stat(p, &a) != nil || syscall.Stat(p+"/..", &b) != nil {
		return false
	}
	return a.Dev != b.Dev
}

func humanBytes(n uint64) string {
	const u = 1024
	switch {
	case n >= u*u*u*u:
		return fmtF(float64(n)/(u*u*u*u), "TB")
	case n >= u*u*u:
		return fmtF(float64(n)/(u*u*u), "GB")
	case n >= u*u:
		return fmtF(float64(n)/(u*u), "MB")
	case n >= u:
		return fmtF(float64(n)/u, "KB")
	}
	return fmtF(float64(n), "B")
}

func fmtF(v float64, unit string) string {
	if v >= 100 {
		return itoa(int(v)) + " " + unit
	}
	whole := int(v)
	frac := int((v - float64(whole)) * 10)
	return itoa(whole) + "." + itoa(frac) + " " + unit
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	neg := i < 0
	if neg {
		i = -i
	}
	var b [20]byte
	n := len(b)
	for i > 0 {
		n--
		b[n] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		n--
		b[n] = '-'
	}
	return string(b[n:])
}

// sameFilesystem reports whether a and b live on one filesystem.
func sameFilesystem(a, b string) bool {
	var sa, sb syscall.Stat_t
	if syscall.Stat(a, &sa) != nil || syscall.Stat(b, &sb) != nil {
		return false
	}
	return sa.Dev == sb.Dev
}
