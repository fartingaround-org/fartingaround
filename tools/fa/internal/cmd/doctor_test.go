package cmd

import "testing"

// The proxy's ports must be recognised as docker's, so doctor can tell when
// something else on the host holds 80/443.
func TestPublishedHostPorts(t *testing.T) {
	got := publishedHostPorts("0.0.0.0:80->80/tcp, [::]:80->80/tcp, 0.0.0.0:443->443/tcp, 443/udp, 2019/tcp\n" +
		"127.0.0.1:8406->3000/tcp\n" +
		"\n" +
		"5432/tcp")
	for _, want := range []string{"80", "443", "8406"} {
		if !got[want] {
			t.Errorf("port %s not reported as published; got %v", want, got)
		}
	}
	for _, not := range []string{"2019", "5432", "3000"} {
		if got[not] {
			t.Errorf("port %s reported as published but is only exposed; got %v", not, got)
		}
	}
	if n := len(publishedHostPorts("")); n != 0 {
		t.Errorf("empty output gave %d ports", n)
	}
}
