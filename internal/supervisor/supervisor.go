package supervisor

import (
	"context"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"wadbd/internal/config"
	"wadbd/internal/firewall"
	"wadbd/internal/props"
	"wadbd/internal/tcp"
)

var (
	supMu sync.Mutex
)

type Status struct {
	AdbdStatus          string        `json:"adbd_status"`
	AdbdPid             int           `json:"adbd_pid"`
	Port                int           `json:"port"`
	Enabled             bool          `json:"enabled"`
	BootEnabled         bool          `json:"boot_enabled"`
	BootPort            int           `json:"boot_port"`
	AuthMode            string        `json:"auth_mode"`
	AuthSupported       bool          `json:"auth_supported"`
	Notify              bool          `json:"notify"`
	Sessions            []tcp.Session `json:"sessions"`
	Bindings            []string      `json:"bindings"`
	ActiveRules         []string      `json:"active_rules"`
	UsbFunctionFSPaused bool          `json:"usb_functionfs_paused"`
}

// StartAdbd applies port, properties, and starts adbd.
func StartAdbd(port int, authMode string) error {
	supMu.Lock()
	defer supMu.Unlock()

	if port <= 0 || port > 65535 {
		port = config.DefaultPort
	}
	portStr := strconv.Itoa(port)

	// Set runtime TCP port (boot persistence is managed by wadbd daemon via config.json)
	_ = props.Set("service.adb.tcp.port", portStr)

	// Xiaomi HyperOS fix: prevent adbd kill on USB unplug
	_ = props.SetPersistent("persist.adb.tls_server.enable", "1")

	// FunctionFS spin loop suppression: if USB is not in ADB mode, pause USB FunctionFS
	usbConfig := props.Get("sys.usb.config")
	if !strings.Contains(usbConfig, "adb") {
		_ = props.Set("sys.usb.adb.disabled", "1")
	}

	// Prepare authentication mode, restart adbd once, then restore temporary properties
	restoreAuth := prepareAuthMode(authMode)
	restartAdbd()
	if restoreAuth != nil {
		restoreAuth()
	}

	return nil
}

// StopAdbd disables wireless ADB and flushes firewall rules.
func StopAdbd() error {
	supMu.Lock()
	defer supMu.Unlock()

	_ = props.Set("service.adb.tcp.port", "-1")
	if p := props.Get("persist.adb.tcp.port"); p != "" && p != "-1" {
		_ = props.SetPersistent("persist.adb.tcp.port", "-1")
	}
	_ = props.Set("sys.usb.adb.disabled", "0")

	restartAdbd()
	return nil
}

func restartAdbd() {
	_ = exec.Command("/system/bin/stop", "adbd").Run()
	for i := 0; i < 10; i++ {
		if props.Get("init.svc.adbd") == "stopped" {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = exec.Command("/system/bin/start", "adbd").Run()
	for i := 0; i < 20; i++ {
		if props.Get("init.svc.adbd") == "running" {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
}

func prepareAuthMode(mode string) func() {
	rp := props.FindResetprop()
	if rp == "" {
		return nil
	}

	desiredSecure := "1"
	if mode == "open" {
		desiredSecure = "0"
	}

	currentSecure := props.Get("ro.adb.secure")
	currentDebug := props.Get("ro.debuggable")
	if currentDebug == "" {
		currentDebug = "0"
	}
	verified := props.Get("ro.boot.verifiedbootstate")

	if currentSecure != desiredSecure {
		_ = exec.Command(rp, "-n", "ro.adb.secure", desiredSecure).Run()
	}

	// If open mode requested on locked user build, temporarily expose ro.debuggable while adbd starts.
	if mode == "open" && currentDebug != "1" && verified != "orange" {
		if exec.Command(rp, "-n", "ro.debuggable", "1").Run() == nil {
			return func() {
				_ = exec.Command(rp, "-n", "ro.debuggable", currentDebug).Run()
			}
		}
	}
	return nil
}

// Watchdog continuously checks adbd health, Xiaomi properties, and FunctionFS state.
func Watchdog(ctx context.Context, cfgGetter func() config.Config) {
	ticker := time.NewTicker(4 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			cfg := cfgGetter()
			if !cfg.Enabled {
				continue
			}

			// 1. Verify adbd state
			svcState := props.Get("init.svc.adbd")
			if svcState != "running" {
				log.Printf("[watchdog] adbd is '%s' (expected running), reviving...", svcState)
				_ = StartAdbd(cfg.Port, cfg.AuthMode)
				_ = firewall.ApplyConfig(cfg.Port, cfg.Bindings)
			}

			// 2. Enforce Xiaomi HyperOS keepalive property
			if props.Get("persist.adb.tls_server.enable") != "1" {
				_ = props.SetPersistent("persist.adb.tls_server.enable", "1")
			}

			// 3. Keep FunctionFS paused when USB ADB is unused
			usbConfig := props.Get("sys.usb.config")
			if !strings.Contains(usbConfig, "adb") {
				if props.Get("sys.usb.adb.disabled") != "1" {
					_ = props.Set("sys.usb.adb.disabled", "1")
				}
			} else {
				if props.Get("sys.usb.adb.disabled") == "1" {
					_ = props.Set("sys.usb.adb.disabled", "0")
				}
			}

			// 4. Update TCP sessions and notifications
			sessions, err := tcp.GetActiveSessions(cfg.Port)
			if err == nil {
				tcp.UpdateNotifications(sessions, cfg.Notify)
			}
		}
	}
}

// QueryStatus returns the comprehensive runtime diagnostic status.
func QueryStatus(cfg config.Config) Status {
	svcState := props.Get("init.svc.adbd")
	pidStr := props.Get("init.svc_debug_pid.adbd")
	pid, _ := strconv.Atoi(pidStr)

	sessions, _ := tcp.GetActiveSessions(cfg.Port)
	rules := firewall.GetActiveRules()

	authSupported := props.FindResetprop() != ""

	return Status{
		AdbdStatus:          svcState,
		AdbdPid:             pid,
		Port:                cfg.Port,
		Enabled:             cfg.Enabled,
		BootEnabled:         cfg.EnableOnBoot,
		BootPort:            cfg.Port,
		AuthMode:            cfg.AuthMode,
		AuthSupported:       authSupported,
		Notify:              cfg.Notify,
		Sessions:            sessions,
		Bindings:            cfg.Bindings,
		ActiveRules:         rules,
		UsbFunctionFSPaused: props.Get("sys.usb.adb.disabled") == "1",
	}
}
