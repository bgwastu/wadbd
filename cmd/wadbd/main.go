package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"wadbd/internal/config"
	"wadbd/internal/daemon"
	"wadbd/internal/firewall"
	"wadbd/internal/ipc"
	"wadbd/internal/supervisor"
)

const (
	ColorGreen  = "\033[0;32m"
	ColorPink   = "\033[1;35m"
	ColorRed    = "\033[0;31m"
	ColorYellow = "\033[1;33m"
	ColorNC     = "\033[0m"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	cmd := os.Args[1]

	// Direct daemon mode
	if cmd == "daemon" || cmd == "service" {
		if err := daemon.Run(); err != nil {
			fmt.Fprintf(os.Stderr, "Daemon error: %v\n", err)
			os.Exit(1)
		}
		return
	}

	// Ensure root
	if os.Geteuid() != 0 {
		fmt.Printf("%sError: This program must be run as root.%s\n", ColorPink, ColorNC)
		os.Exit(1)
	}

	ensureDaemon()

	switch cmd {
	case "on":
		port := config.DefaultPort
		if len(os.Args) >= 3 {
			p, err := strconv.Atoi(os.Args[2])
			if err != nil || p <= 0 || p > 65535 {
				fmt.Printf("%sError: Invalid port number.%s\n", ColorPink, ColorNC)
				os.Exit(1)
			}
			port = p
		}
		resp, err := ipc.Send(ipc.Request{Action: "enable", Port: port})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => Wireless ADB enabled on port %d.%s\n", ColorGreen, port, ColorNC)
			printConnectionTargets(port)
		})

	case "off":
		resp, err := ipc.Send(ipc.Request{Action: "disable"})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => Wireless ADB disabled.%s\n", ColorGreen, ColorNC)
		})

	case "status":
		isJson := hasFlag("--json")
		resp, err := ipc.Send(ipc.Request{Action: "status"})
		if err != nil {
			if isJson {
				fmt.Println(`{"enabled":false,"error":"` + err.Error() + `"}`)
			} else {
				fmt.Printf("%s => Error: %v%s\n", ColorRed, err, ColorNC)
			}
			os.Exit(1)
		}

		if isJson {
			// Augment with network addresses for WebUI single-call status
			dataBytes, _ := json.Marshal(resp.Data)
			var st supervisor.Status
			_ = json.Unmarshal(dataBytes, &st)

			type ExtendedStatus struct {
				supervisor.Status
				Addresses []IfaceAddr `json:"addresses"`
			}
			ext := ExtendedStatus{
				Status:    st,
				Addresses: listActiveAddresses(st.Bindings),
			}
			jsonOut, _ := json.MarshalIndent(ext, "", "  ")
			fmt.Println(string(jsonOut))
			return
		}

		printHumanStatus(resp.Data)

	case "enable-on-boot":
		port := config.DefaultPort
		if len(os.Args) >= 3 {
			p, err := strconv.Atoi(os.Args[2])
			if err != nil || p <= 0 || p > 65535 {
				fmt.Printf("%sError: Invalid port number.%s\n", ColorPink, ColorNC)
				os.Exit(1)
			}
			port = p
		}
		resp, err := ipc.Send(ipc.Request{Action: "set_boot", Enabled: true, Port: port})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => Wireless ADB will now be enabled on boot with port %d.%s\n", ColorGreen, port, ColorNC)
			fmt.Printf("%s    Interface & subnet bindings (if any) will also be restored.%s\n", ColorGreen, ColorNC)
		})

	case "disable-on-boot":
		resp, err := ipc.Send(ipc.Request{Action: "set_boot", Enabled: false})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => Wireless ADB will no longer be enabled on boot.%s\n", ColorGreen, ColorNC)
		})

	case "bind":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: No target specified.%s\n", ColorPink, ColorNC)
			fmt.Printf("%sUsage: wadbd bind <interface|subnet|preset>%s\n", ColorYellow, ColorNC)
			os.Exit(1)
		}
		target := os.Args[2]
		resp, err := ipc.Send(ipc.Request{Action: "bind", Target: target})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => ADB bound to target '%s'%s\n", ColorGreen, firewall.ResolveTarget(target), ColorNC)
			fmt.Printf("%s    ADB is now restricted to bound targets + localhost only.%s\n", ColorYellow, ColorNC)
		})

	case "unbind":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: No target specified.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		target := os.Args[2]
		resp, err := ipc.Send(ipc.Request{Action: "unbind", Target: target})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => '%s' unbound.%s\n", ColorGreen, target, ColorNC)
		})

	case "unbind-all":
		resp, err := ipc.Send(ipc.Request{Action: "unbind_all"})
		handleResponse(resp, err, func() {
			fmt.Printf("%s => All interface restrictions removed. ADB open on all interfaces.%s\n", ColorGreen, ColorNC)
		})

	case "bind-status":
		resp, err := ipc.Send(ipc.Request{Action: "status"})
		handleResponse(resp, err, func() {
			printBindStatus(resp.Data)
		})

	case "list-interfaces":
		printInterfaces()

	case "notify":
		sub := "status"
		if len(os.Args) >= 3 {
			sub = os.Args[2]
		}
		switch sub {
		case "on", "enable":
			resp, err := ipc.Send(ipc.Request{Action: "set_notify", Enabled: true})
			handleResponse(resp, err, func() {
				fmt.Printf("%s => Active connection notification enabled.%s\n", ColorGreen, ColorNC)
			})
		case "off", "disable":
			resp, err := ipc.Send(ipc.Request{Action: "set_notify", Enabled: false})
			handleResponse(resp, err, func() {
				fmt.Printf("%s => Active connection notification disabled.%s\n", ColorGreen, ColorNC)
			})
		case "status":
			resp, _ := ipc.Send(ipc.Request{Action: "status"})
			cfg, _ := config.Load()
			state := "Disabled"
			if cfg.Notify {
				state = "Enabled"
			}
			fmt.Printf("%sConnection notification:%s %s\n", ColorYellow, ColorNC, state)
			if resp != nil && resp.Success {
				fmt.Printf("%sMonitor daemon:%s Running (PID %d)\n", ColorGreen, ColorNC, os.Getpid())
			} else {
				fmt.Printf("%sMonitor daemon:%s Stopped\n", ColorRed, ColorNC)
			}
		default:
			fmt.Printf("%sUsage: wadbd notify [on|off|status]%s\n", ColorPink, ColorNC)
		}

	case "auth":
		sub := "status"
		if len(os.Args) >= 3 {
			sub = os.Args[2]
		}
		switch sub {
		case "open":
			resp, err := ipc.Send(ipc.Request{Action: "set_auth", Mode: "open"})
			handleResponse(resp, err, func() {
				fmt.Printf("%sADB RSA authentication is disabled. Any client that can reach an active ADB transport may connect.%s\n", ColorYellow, ColorNC)
			})
		case "secure":
			resp, err := ipc.Send(ipc.Request{Action: "set_auth", Mode: "secure"})
			handleResponse(resp, err, func() {
				fmt.Printf("%sADB RSA authentication is required.%s\n", ColorGreen, ColorNC)
			})
		case "status":
			cfg, _ := config.Load()
			st := supervisor.QueryStatus(cfg)
			supp := 0
			if st.AuthSupported {
				supp = 1
			}
			fmt.Printf("AUTH_MODE=%s\n", st.AuthMode)
			fmt.Printf("AUTH_SUPPORTED=%d\n", supp)
			fmt.Printf("AUTH_AVAILABLE=%d\n", supp)
			fmt.Printf("ADB_SECURE=%s\n", getAdbSecureProp(st.AuthMode))
		default:
			fmt.Printf("%sUsage: wadbd auth [open|secure|status]%s\n", ColorPink, ColorNC)
		}

	case "--list-keys", "list-keys":
		isJson := hasFlag("--json")
		resp, err := ipc.Send(ipc.Request{Action: "list_keys"})
		handleResponse(resp, err, func() {
			if isJson {
				dataBytes, _ := json.MarshalIndent(resp.Data, "", "  ")
				fmt.Println(string(dataBytes))
				return
			}
			dataBytes, _ := json.Marshal(resp.Data)
			var keys []struct {
				ID          int    `json:"id"`
				Fingerprint string `json:"fingerprint"`
				Comment     string `json:"comment"`
			}
			_ = json.Unmarshal(dataBytes, &keys)
			if len(keys) == 0 {
				fmt.Printf("%sNo authorized ADB keys found.%s\n", ColorYellow, ColorNC)
				return
			}
			fmt.Printf("%s── Authorized ADB Keys ──%s\n", ColorYellow, ColorNC)
			for _, k := range keys {
				fmt.Printf("  %s[%d]%s %s  %s(hash: %s)%s\n", ColorGreen, k.ID, ColorNC, k.Comment, ColorPink, k.Fingerprint, ColorNC)
			}
		})

	case "--remove-key":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: Valid key ID (number) required. Use --list-keys to see IDs.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		id, err := strconv.Atoi(os.Args[2])
		if err != nil {
			fmt.Printf("%sError: Valid key ID required.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		resp, err := ipc.Send(ipc.Request{Action: "remove_key", KeyID: id})
		handleResponse(resp, err, func() {
			fmt.Printf("%sKey [%d] removed. Run 'wadbd --list-keys' to verify.%s\n", ColorGreen, id, ColorNC)
		})

	case "--import-key":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: Path to adbkey.pub is required.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		resp, err := ipc.Send(ipc.Request{Action: "import_key", Path: os.Args[2]})
		handleResponse(resp, err, func() {
			fmt.Printf("%sKey imported successfully to adb_keys.%s\n", ColorGreen, ColorNC)
		})

	case "--clear-keys":
		resp, err := ipc.Send(ipc.Request{Action: "clear_keys"})
		handleResponse(resp, err, func() {
			fmt.Printf("%sADB keys cleared successfully.%s\n", ColorPink, ColorNC)
		})

	case "--backup":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: Backup path required.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		resp, err := ipc.Send(ipc.Request{Action: "backup_keys", Path: os.Args[2]})
		handleResponse(resp, err, func() {
			fmt.Printf("%sADB keys backed up to %s.%s\n", ColorGreen, os.Args[2], ColorNC)
		})

	case "--restore":
		if len(os.Args) < 3 {
			fmt.Printf("%sError: Backup path required.%s\n", ColorPink, ColorNC)
			os.Exit(1)
		}
		resp, err := ipc.Send(ipc.Request{Action: "restore_keys", Path: os.Args[2]})
		handleResponse(resp, err, func() {
			fmt.Printf("%sADB keys restored successfully from %s.%s\n", ColorGreen, os.Args[2], ColorNC)
		})

	default:
		printUsage()
		os.Exit(1)
	}
}

func ensureDaemon() {
	if !ipc.IsDaemonRunning() {
		binPath, err := os.Executable()
		if err != nil {
			binPath = "/data/adb/modules/wadbd/system/bin/wadbd"
		}
		cmd := exec.Command(binPath, "daemon")
		cmd.Dir = "/"
		_ = cmd.Start()
		// Wait up to 1 second for socket
		for i := 0; i < 10; i++ {
			time.Sleep(100 * time.Millisecond)
			if ipc.IsDaemonRunning() {
				break
			}
		}
	}
}

func handleResponse(resp *ipc.Response, err error, onSuccess func()) {
	if err != nil {
		fmt.Printf("%sError: %v%s\n", ColorRed, err, ColorNC)
		os.Exit(1)
	}
	if !resp.Success {
		fmt.Printf("%sError: %s%s\n", ColorRed, resp.Error, ColorNC)
		os.Exit(1)
	}
	if onSuccess != nil {
		onSuccess()
	}
}

type IfaceAddr struct {
	Interface string `json:"interface"`
	IP        string `json:"ip"`
	Allowed   bool   `json:"allowed"`
}

func listActiveAddresses(bindings []string) []IfaceAddr {
	var list []IfaceAddr
	ifaces, err := net.Interfaces()
	if err != nil {
		return list
	}

	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			if ipNet, ok := addr.(*net.IPNet); ok {
				ip = ipNet.IP
			}
			if ip == nil || ip.IsLoopback() {
				continue
			}
			ipStr := ip.String()
			allowed := isAllowed(iface.Name, ip, bindings)
			list = append(list, IfaceAddr{
				Interface: iface.Name,
				IP:        ipStr,
				Allowed:   allowed,
			})
		}
	}
	return list
}

func isAllowed(iface string, ip net.IP, bindings []string) bool {
	if len(bindings) == 0 {
		return true
	}
	for _, b := range bindings {
		target := firewall.ResolveTarget(b)
		if strings.Contains(target, "/") {
			_, cidr, err := net.ParseCIDR(target)
			if err == nil && cidr.Contains(ip) {
				return true
			}
		} else if strings.HasSuffix(target, "+") {
			prefix := strings.TrimSuffix(target, "+")
			if strings.HasPrefix(iface, prefix) {
				return true
			}
		} else if target == iface {
			return true
		}
	}
	return false
}

func printHumanStatus(data interface{}) {
	dataBytes, _ := json.Marshal(data)
	var st supervisor.Status
	_ = json.Unmarshal(dataBytes, &st)

	cfg, _ := config.Load()

	if st.Enabled {
		fmt.Printf("%s => Wireless ADB is enabled on port %d.%s\n", ColorGreen, st.Port, ColorNC)
		printConnectionTargets(st.Port)
	} else {
		fmt.Printf("%s => Wireless ADB is disabled.%s\n", ColorPink, ColorNC)
	}

	if len(st.Bindings) > 0 {
		fmt.Printf("%s => Interface/subnet binding: %d target(s) restricted%s\n", ColorGreen, len(st.Bindings), ColorNC)
	}

	if len(st.Sessions) > 0 {
		var ips []string
		for _, s := range st.Sessions {
			ips = append(ips, s.RemoteIP)
		}
		fmt.Printf("%s => Active connection(s) (%d): %s%s\n", ColorGreen, len(st.Sessions), strings.Join(ips, ", "), ColorNC)
	} else if st.Enabled {
		fmt.Printf("%s => Active connections: None (idle)%s\n", ColorYellow, ColorNC)
	}

	notifState := "enabled"
	if !cfg.Notify {
		notifState = "muted"
	}
	fmt.Printf("%s => Connection monitor: Running (notifications: %s)%s\n", ColorGreen, notifState, ColorNC)

	if cfg.EnableOnBoot {
		fmt.Printf("%s => Wireless ADB on boot is enabled on port %d.%s\n", ColorGreen, cfg.Port, ColorNC)
	} else {
		fmt.Printf("%s => Wireless ADB on boot is disabled.%s\n", ColorPink, ColorNC)
	}

	if st.UsbFunctionFSPaused {
		fmt.Printf("%s => FunctionFS USB spin loop: Paused (power optimized)%s\n", ColorGreen, ColorNC)
	}
}

func printConnectionTargets(port int) {
	cfg, _ := config.Load()
	if len(cfg.Bindings) > 0 {
		fmt.Printf("%s => (Restricted mode — only bound targets accessible)%s\n", ColorYellow, ColorNC)
		for _, b := range cfg.Bindings {
			target := firewall.ResolveTarget(b)
			if strings.Contains(target, "/") {
				fmt.Printf("%s   • Subnet allowed: %s%s\n", ColorGreen, target, ColorNC)
			} else if strings.HasSuffix(target, "+") {
				fmt.Printf("%s   • Wildcard allowed: %s (all matching tunnels)%s\n", ColorGreen, target, ColorNC)
			} else {
				if iface, err := net.InterfaceByName(target); err == nil {
					addrs, _ := iface.Addrs()
					for _, addr := range addrs {
						if ipNet, ok := addr.(*net.IPNet); ok && ipNet.IP.To4() != nil {
							fmt.Printf("%s   • adb connect %s:%d%s\n", ColorGreen, ipNet.IP.String(), port, ColorNC)
							break
						}
					}
				}
			}
		}
		return
	}

	addrs := listActiveAddresses(nil)
	if len(addrs) > 0 {
		fmt.Printf("%s => The following commands can be used to connect wirelessly:%s\n", ColorPink, ColorNC)
		for _, a := range addrs {
			fmt.Printf("%s   • adb connect %s:%d%s\n", ColorGreen, a.IP, port, ColorNC)
		}
	} else {
		fmt.Printf("%s => No usable IP addresses found. Ensure device is connected.%s\n", ColorPink, ColorNC)
	}
}

func printBindStatus(data interface{}) {
	dataBytes, _ := json.Marshal(data)
	var st supervisor.Status
	_ = json.Unmarshal(dataBytes, &st)

	fmt.Printf("%s── ADB Binding Status ──%s\n", ColorYellow, ColorNC)
	if !st.Enabled {
		fmt.Printf("%s  ADB is disabled.%s\n", ColorPink, ColorNC)
		return
	}
	fmt.Printf("%s  ADB port: %d%s\n", ColorGreen, st.Port, ColorNC)

	if len(st.Bindings) == 0 {
		fmt.Printf("%s  No interface restrictions — ADB is open on ALL interfaces.%s\n", ColorYellow, ColorNC)
		return
	}

	fmt.Printf("%s  Bound interfaces / subnets:%s\n", ColorGreen, ColorNC)
	for _, b := range st.Bindings {
		fmt.Printf("    %s\n", b)
	}

	fmt.Printf("\n%s  Active iptables rules for ADB:%s\n", ColorYellow, ColorNC)
	for _, r := range st.ActiveRules {
		fmt.Printf("    %s\n", r)
	}
}

func printInterfaces() {
	fmt.Printf("%sAvailable network interfaces & presets:%s\n", ColorYellow, ColorNC)
	fmt.Printf("  %s[PRESET]%s tun+ (or tailscale) - All VPN & Mesh Tunnels (WireGuard/Tailscale)\n\n", ColorGreen, ColorNC)
	fmt.Printf("%sActive system interfaces:%s\n", ColorYellow, ColorNC)

	cfg, _ := config.Load()
	boundSet := make(map[string]bool)
	for _, b := range cfg.Bindings {
		boundSet[b] = true
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			tag := ""
			if boundSet[iface.Name] {
				tag = fmt.Sprintf("%s[BOUND]%s ", ColorGreen, ColorNC)
			}
			state := "DOWN"
			if iface.Flags&net.FlagUp != 0 {
				state = "UP"
			}
			var ips []string
			addrs, _ := iface.Addrs()
			for _, a := range addrs {
				if ipNet, ok := a.(*net.IPNet); ok {
					ips = append(ips, ipNet.IP.String())
				}
			}
			extra := ""
			if strings.HasPrefix(iface.Name, "tun") {
				extra = fmt.Sprintf(" %s[VPN Tunnel]%s", ColorYellow, ColorNC)
			}
			fmt.Printf("  %s%s (%s) %s %s%s\n", tag, iface.Name, state, strings.Join(ips, " "), iface.HardwareAddr.String(), extra)
		}
	}
}

func getAdbSecureProp(authMode string) string {
	if authMode == "open" {
		return "0"
	}
	return "1"
}

func hasFlag(flag string) bool {
	for _, a := range os.Args {
		if a == flag {
			return true
		}
	}
	return false
}

func printUsage() {
	cmd := filepath.Base(os.Args[0])
	fmt.Printf("%sUsage: %s [on <port>|off|status|enable-on-boot <port>|disable-on-boot]%s\n", ColorPink, cmd, ColorNC)
	fmt.Printf("%s       %s bind <target>|unbind <target>|unbind-all|bind-status%s\n", ColorPink, cmd, ColorNC)
	fmt.Printf("\n%sCommands:%s\n", ColorYellow, ColorNC)
	fmt.Printf("  %s on <port>              - Enable wireless ADB on specified port.\n", cmd)
	fmt.Printf("  %s off                    - Disable wireless ADB.\n", cmd)
	fmt.Printf("  %s status [--json]        - Show current state and connected clients.\n", cmd)
	fmt.Printf("  %s enable-on-boot <port>  - Enable wireless ADB on boot with specified port.\n", cmd)
	fmt.Printf("  %s disable-on-boot        - Disable wireless ADB on boot.\n", cmd)
	fmt.Printf("  %s bind <target>          - Restrict ADB to interface, wildcard (tun+), or subnet.\n", cmd)
	fmt.Printf("  %s unbind <target>        - Remove target restriction.\n", cmd)
	fmt.Printf("  %s unbind-all             - Remove all interface/subnet restrictions.\n", cmd)
	fmt.Printf("  %s bind-status            - Show current binding state.\n", cmd)
	fmt.Printf("  %s list-interfaces        - List available network interfaces.\n", cmd)
	fmt.Printf("  %s notify [on|off|status] - Control active connection notifications.\n", cmd)
	fmt.Printf("  %s auth [open|secure|status] - Control ADB RSA authentication.\n", cmd)
	fmt.Printf("  %s --list-keys            - List all authorized ADB keys.\n", cmd)
	fmt.Printf("  %s --remove-key <id>      - Remove a specific authorized key by ID.\n", cmd)
	fmt.Printf("  %s --import-key <path>    - Import an adbkey.pub file to authorize ADB.\n", cmd)
	fmt.Printf("  %s --clear-keys           - Revokes all authorized keys.\n", cmd)
	fmt.Printf("  %s --backup <path>        - Backup authorized adb_keys.\n", cmd)
	fmt.Printf("  %s --restore <path>       - Restore backed up adb_keys.\n", cmd)
	fmt.Printf("  %s daemon                 - Run in foreground supervisor daemon mode.\n", cmd)
}
