package tcp

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

const (
	TcpStateEstablished = "01"
	NotificationTag     = "wadbd_session"
	NotificationID      = 2020
)

type Session struct {
	RemoteIP   string `json:"remote_ip"`
	RemotePort int    `json:"remote_port"`
	Interface  string `json:"interface,omitempty"`
}

var (
	prevCount   int
	prevClients string
	notifMu     sync.Mutex
)

// TuneKeepalive configures Linux kernel TCP keepalive settings to prune zombie sockets quickly.
func TuneKeepalive(idleSec, intvlSec, probes int) error {
	_ = os.WriteFile("/proc/sys/net/ipv4/tcp_keepalive_time", []byte(strconv.Itoa(idleSec)+"\n"), 0644)
	_ = os.WriteFile("/proc/sys/net/ipv4/tcp_keepalive_intvl", []byte(strconv.Itoa(intvlSec)+"\n"), 0644)
	_ = os.WriteFile("/proc/sys/net/ipv4/tcp_keepalive_probes", []byte(strconv.Itoa(probes)+"\n"), 0644)
	return nil
}

// GetActiveSessions scans /proc/net/tcp and /proc/net/tcp6 for established connections to port.
func GetActiveSessions(port int) ([]Session, error) {
	portHex := fmt.Sprintf("%04X", port)
	var sessions []Session
	seen := make(map[string]bool)

	// Scan IPv4
	if file, err := os.Open("/proc/net/tcp"); err == nil {
		defer file.Close()
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			fields := strings.Fields(scanner.Text())
			if len(fields) >= 4 && fields[3] == TcpStateEstablished {
				local := fields[1]
				remote := fields[2]
				if strings.HasSuffix(local, ":"+portHex) {
					ip, rPort := parseIPv4Hex(remote)
					if ip != "" && ip != "127.0.0.1" && !seen[ip] {
						seen[ip] = true
						sessions = append(sessions, Session{
							RemoteIP:   ip,
							RemotePort: rPort,
							Interface:  detectInterface(ip),
						})
					}
				}
			}
		}
	}

	// Scan IPv6
	if file, err := os.Open("/proc/net/tcp6"); err == nil {
		defer file.Close()
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			fields := strings.Fields(scanner.Text())
			if len(fields) >= 4 && fields[3] == TcpStateEstablished {
				local := fields[1]
				remote := fields[2]
				if strings.HasSuffix(local, ":"+portHex) {
					ip, rPort := parseIPv6Hex(remote)
					if ip != "" && ip != "::1" && ip != "127.0.0.1" && !seen[ip] {
						seen[ip] = true
						sessions = append(sessions, Session{
							RemoteIP:   ip,
							RemotePort: rPort,
							Interface:  detectInterface(ip),
						})
					}
				}
			}
		}
	}

	return sessions, nil
}

func parseIPv4Hex(s string) (string, int) {
	parts := strings.Split(s, ":")
	if len(parts) != 2 {
		return "", 0
	}
	ipHex := parts[0]
	portHex := parts[1]

	port64, _ := strconv.ParseInt(portHex, 16, 32)
	port := int(port64)

	ipBytes, err := hex.DecodeString(ipHex)
	if err != nil || len(ipBytes) != 4 {
		return "", 0
	}
	// /proc/net/tcp stores IPv4 addresses in little-endian order
	ip := fmt.Sprintf("%d.%d.%d.%d", ipBytes[3], ipBytes[2], ipBytes[1], ipBytes[0])
	return ip, port
}

func parseIPv6Hex(s string) (string, int) {
	parts := strings.Split(s, ":")
	if len(parts) != 2 {
		return "", 0
	}
	ipHex := parts[0]
	portHex := parts[1]

	port64, _ := strconv.ParseInt(portHex, 16, 32)
	port := int(port64)

	ipBytes, err := hex.DecodeString(ipHex)
	if err != nil || len(ipBytes) != 16 {
		return "", 0
	}

	// Reverse 4-byte words for standard Linux representation
	var norm [16]byte
	for i := 0; i < 4; i++ {
		norm[i*4+0] = ipBytes[i*4+3]
		norm[i*4+1] = ipBytes[i*4+2]
		norm[i*4+2] = ipBytes[i*4+1]
		norm[i*4+3] = ipBytes[i*4+0]
	}

	parsedIP := net.IP(norm[:])

	// Check if IPv4-mapped IPv6 (::ffff:192.168.1.1)
	if v4 := parsedIP.To4(); v4 != nil {
		return v4.String(), port
	}
	return parsedIP.String(), port
}

func detectInterface(remoteIP string) string {
	ip := net.ParseIP(remoteIP)
	if ip == nil {
		return ""
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			if ipNet, ok := addr.(*net.IPNet); ok {
				if ipNet.Contains(ip) {
					return iface.Name
				}
			}
		}
	}
	return ""
}

// UpdateNotifications manages Android status notifications when sessions change.
func UpdateNotifications(sessions []Session, notifyEnabled bool) {
	notifMu.Lock()
	defer notifMu.Unlock()

	if !notifyEnabled {
		if prevCount > 0 {
			ClearNotification()
			prevCount = 0
			prevClients = ""
		}
		return
	}

	count := len(sessions)
	var ips []string
	for _, s := range sessions {
		ips = append(ips, s.RemoteIP)
	}
	clientsStr := strings.Join(ips, ", ")

	if count > 0 {
		if count != prevCount || clientsStr != prevClients {
			var text string
			if count == 1 {
				text = fmt.Sprintf("Connected: %s", clientsStr)
			} else {
				text = fmt.Sprintf("%d active sessions: %s", count, clientsStr)
			}
			PostNotification(text)
			prevCount = count
			prevClients = clientsStr
		}
	} else if prevCount > 0 {
		ClearNotification()
		prevCount = 0
		prevClients = ""
	}
}

// PostNotification displays a system alert using Android's notification command.
func PostNotification(text string) {
	_ = exec.Command("cmd", "notification", "post", "-S", "bigtext", "-t", "Wireless ADB Active", "-i", "@android:drawable/stat_sys_warning", NotificationTag, text).Run()
}

// ClearNotification dismisses the active connection notification.
func ClearNotification() {
	_ = exec.Command("cmd", "notification", "snooze", "--for", "1", fmt.Sprintf("0|com.android.shell|0|%s|2000", NotificationTag)).Run()
	_ = exec.Command("su", "2000", "-c", fmt.Sprintf("service call notification 8 s16 'com.android.shell' s16 'com.android.shell' s16 '%s' i32 0 i32 0", NotificationTag)).Run()
	_ = exec.Command("su", "2000", "-c", fmt.Sprintf("service call notification 8 s16 'com.android.shell' s16 'com.android.shell' s16 '%s' i32 %d i32 0", NotificationTag, NotificationID)).Run()
	_ = exec.Command("su", "2000", "-c", fmt.Sprintf("cmd notification snooze --for 1 '0|com.android.shell|%d|%s|2000'", NotificationID, NotificationTag)).Run()
}
