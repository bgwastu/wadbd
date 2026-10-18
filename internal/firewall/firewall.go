package firewall

import (
	"bytes"
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

const (
	ChainIPv4 = "WADBD_INPUT"
	ChainIPv6 = "WADBD6_INPUT"
)

var fwMu sync.Mutex

// ResolveTarget translates shortcuts to network interface patterns.
func ResolveTarget(target string) string {
	switch strings.ToLower(strings.TrimSpace(target)) {
	case "tailscale", "mesh", "vpn":
		return "tun+"
	default:
		return strings.TrimSpace(target)
	}
}

// ValidateTarget checks if the target is a valid CIDR, wildcard interface, or interface name.
func ValidateTarget(target string) error {
	t := ResolveTarget(target)
	if t == "" {
		return fmt.Errorf("empty target")
	}
	if strings.Contains(t, "/") {
		_, _, err := net.ParseCIDR(t)
		if err != nil {
			return fmt.Errorf("invalid CIDR '%s': %w", t, err)
		}
		return nil
	}
	// Interface name or wildcard (e.g. tun+, wlan0)
	if len(t) > 16 {
		return fmt.Errorf("interface name '%s' too long", t)
	}
	return nil
}

// ApplyConfig configures dedicated iptables and ip6tables chains atomically.
func ApplyConfig(port int, bindings []string) error {
	fwMu.Lock()
	defer fwMu.Unlock()

	if port <= 0 || port > 65535 {
		return fmt.Errorf("invalid port: %d", port)
	}

	// 1. Ensure custom chains exist in filter table
	ensureChain("iptables", ChainIPv4)
	ensureChain("ip6tables", ChainIPv6)

	// 2. Ensure root hooks exist in INPUT chain for this port
	ensureHook("iptables", ChainIPv4, port)
	ensureHook("ip6tables", ChainIPv6, port)

	// 3. Atomically populate rules via iptables-restore
	if err := applyIPv4Rules(bindings); err != nil {
		return fmt.Errorf("failed to apply IPv4 rules: %w", err)
	}
	if err := applyIPv6Rules(bindings); err != nil {
		// Log but do not fail completely if IPv6 iptables is unsupported in kernel
		_ = err
	}

	// 4. Clean up any leftover legacy rules on root INPUT
	cleanLegacyRules(port)

	return nil
}

// Clear flushes all custom chains and removes the hooks from INPUT.
func Clear(port int) error {
	fwMu.Lock()
	defer fwMu.Unlock()

	flushChain("iptables", ChainIPv4)
	flushChain("ip6tables", ChainIPv6)

	if port > 0 {
		removeHook("iptables", ChainIPv4, port)
		removeHook("ip6tables", ChainIPv6, port)
		cleanLegacyRules(port)
	}

	return nil
}

func ensureChain(tool, chain string) {
	// iptables -w 5 -N CHAIN
	_ = exec.Command(tool, "-w", "5", "-N", chain).Run()
}

func flushChain(tool, chain string) {
	_ = exec.Command(tool, "-w", "5", "-F", chain).Run()
}

func ensureHook(tool, chain string, port int) {
	portStr := strconv.Itoa(port)
	// Check if already hooked: iptables -w 5 -C INPUT -p tcp --dport <port> -j CHAIN
	checkCmd := exec.Command(tool, "-w", "5", "-C", "INPUT", "-p", "tcp", "--dport", portStr, "-j", chain)
	if checkCmd.Run() != nil {
		// Insert at top of INPUT: iptables -w 5 -I INPUT 1 -p tcp --dport <port> -j CHAIN
		_ = exec.Command(tool, "-w", "5", "-I", "INPUT", "1", "-p", "tcp", "--dport", portStr, "-j", chain).Run()
	}
}

func removeHook(tool, chain string, port int) {
	portStr := strconv.Itoa(port)
	for {
		cmd := exec.Command(tool, "-w", "5", "-D", "INPUT", "-p", "tcp", "--dport", portStr, "-j", chain)
		if cmd.Run() != nil {
			break
		}
	}
}

func applyIPv4Rules(bindings []string) error {
	var buf bytes.Buffer
	buf.WriteString("*filter\n")
	buf.WriteString(fmt.Sprintf(":%s - [0:0]\n", ChainIPv4))
	buf.WriteString(fmt.Sprintf("-F %s\n", ChainIPv4))

	// Localhost is always allowed
	buf.WriteString(fmt.Sprintf("-A %s -i lo -j ACCEPT\n", ChainIPv4))

	hasRestrictions := len(bindings) > 0

	for _, b := range bindings {
		target := ResolveTarget(b)
		if strings.Contains(target, "/") {
			// Subnet CIDR (check if IPv4)
			ip, _, err := net.ParseCIDR(target)
			if err == nil && ip.To4() != nil {
				buf.WriteString(fmt.Sprintf("-A %s -s %s -j ACCEPT\n", ChainIPv4, target))
			}
		} else {
			// Interface or wildcard
			buf.WriteString(fmt.Sprintf("-A %s -i %s -j ACCEPT\n", ChainIPv4, target))
		}
	}

	if hasRestrictions {
		buf.WriteString(fmt.Sprintf("-A %s -j DROP\n", ChainIPv4))
	} else {
		buf.WriteString(fmt.Sprintf("-A %s -j ACCEPT\n", ChainIPv4))
	}

	buf.WriteString("COMMIT\n")

	cmd := exec.Command("iptables-restore", "-n", "-w", "5")
	cmd.Stdin = &buf
	var errBuf bytes.Buffer
	cmd.Stderr = &errBuf
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%v: %s", err, errBuf.String())
	}
	return nil
}

func applyIPv6Rules(bindings []string) error {
	var buf bytes.Buffer
	buf.WriteString("*filter\n")
	buf.WriteString(fmt.Sprintf(":%s - [0:0]\n", ChainIPv6))
	buf.WriteString(fmt.Sprintf("-F %s\n", ChainIPv6))

	buf.WriteString(fmt.Sprintf("-A %s -i lo -j ACCEPT\n", ChainIPv6))

	hasRestrictions := len(bindings) > 0

	for _, b := range bindings {
		target := ResolveTarget(b)
		if strings.Contains(target, "/") {
			ip, _, err := net.ParseCIDR(target)
			if err == nil && ip.To4() == nil {
				buf.WriteString(fmt.Sprintf("-A %s -s %s -j ACCEPT\n", ChainIPv6, target))
			}
		} else {
			// Interface or wildcard
			buf.WriteString(fmt.Sprintf("-A %s -i %s -j ACCEPT\n", ChainIPv6, target))
		}
	}

	if hasRestrictions {
		buf.WriteString(fmt.Sprintf("-A %s -j DROP\n", ChainIPv6))
	} else {
		buf.WriteString(fmt.Sprintf("-A %s -j ACCEPT\n", ChainIPv6))
	}

	buf.WriteString("COMMIT\n")

	cmd := exec.Command("ip6tables-restore", "-n", "-w", "5")
	cmd.Stdin = &buf
	var errBuf bytes.Buffer
	cmd.Stderr = &errBuf
	return cmd.Run()
}

// cleanLegacyRules removes leftover raw rules inserted directly into INPUT by older versions.
func cleanLegacyRules(port int) {
	portStr := strconv.Itoa(port)
	for _, tool := range []string{"iptables", "ip6tables"} {
		for i := 0; i < 20; i++ {
			// Look for rules in INPUT containing dpt:<port> that don't jump to WADBD
			out, err := exec.Command(tool, "-w", "5", "-S", "INPUT").Output()
			if err != nil {
				break
			}
			found := false
			lines := strings.Split(string(out), "\n")
			for _, line := range lines {
				if strings.Contains(line, fmt.Sprintf("--dport %s", portStr)) &&
					!strings.Contains(line, ChainIPv4) && !strings.Contains(line, ChainIPv6) {
					// Rule e.g. "-A INPUT -s 100.64.0.0/10 -p tcp -m tcp --dport 5555 -j ACCEPT"
					// Replace -A with -D and execute
					delArgs := strings.Fields(line)
					if len(delArgs) > 0 && delArgs[0] == "-A" {
						delArgs[0] = "-D"
						_ = exec.Command(tool, append([]string{"-w", "5"}, delArgs...)...).Run()
						found = true
						break
					}
				}
			}
			if !found {
				break
			}
		}
	}
}

// GetActiveRules returns a list of formatted active rules from WADBD chains.
func GetActiveRules() []string {
	var results []string
	for _, pair := range []struct {
		tool  string
		chain string
	}{
		{"iptables", ChainIPv4},
		{"ip6tables", ChainIPv6},
	} {
		out, err := exec.Command(pair.tool, "-w", "5", "-S", pair.chain).Output()
		if err == nil {
			lines := strings.Split(strings.TrimSpace(string(out)), "\n")
			for _, l := range lines {
				if strings.HasPrefix(l, "-A") {
					results = append(results, fmt.Sprintf("[%s] %s", pair.tool, l))
				}
			}
		}
	}
	return results
}
