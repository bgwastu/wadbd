package tcp

import "testing"

func TestParseIPv4Hex(t *testing.T) {
	// 192.168.1.50 in little-endian hex = 3201A8C0, port 5555 = 15B3
	ip, port := parseIPv4Hex("3201A8C0:15B3")
	if ip != "192.168.1.50" || port != 5555 {
		t.Fatalf("got (%q, %d), want (%q, %d)", ip, port, "192.168.1.50", 5555)
	}
}

func TestParseIPv6HexMapped(t *testing.T) {
	// ::ffff:192.168.1.50 in /proc/net/tcp6 word-reversed little-endian
	ip, port := parseIPv6Hex("0000000000000000FFFF00003201A8C0:15B3")
	if ip != "192.168.1.50" || port != 5555 {
		t.Fatalf("got (%q, %d), want (%q, %d)", ip, port, "192.168.1.50", 5555)
	}
}
