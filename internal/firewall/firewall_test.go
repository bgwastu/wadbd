package firewall

import "testing"

func TestResolveTarget(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"tailscale", "tun+"},
		{"Mesh", "tun+"},
		{" VPN ", "tun+"},
		{"wlan0", "wlan0"},
		{"100.64.0.0/10", "100.64.0.0/10"},
	}
	for _, tc := range cases {
		if got := ResolveTarget(tc.in); got != tc.want {
			t.Errorf("ResolveTarget(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestValidateTarget(t *testing.T) {
	valid := []string{"wlan0", "tun+", "tailscale", "192.168.1.0/24", "fd00::/8"}
	for _, v := range valid {
		if err := ValidateTarget(v); err != nil {
			t.Errorf("ValidateTarget(%q) unexpected error: %v", v, err)
		}
	}
	invalid := []string{"", "   ", "192.168.1.0/99", "verylonginterfacename12345"}
	for _, v := range invalid {
		if err := ValidateTarget(v); err == nil {
			t.Errorf("ValidateTarget(%q) expected error, got nil", v)
		}
	}
}
