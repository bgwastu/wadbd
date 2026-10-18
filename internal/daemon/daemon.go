package daemon

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"

	"wadbd/internal/config"
	"wadbd/internal/firewall"
	"wadbd/internal/ipc"
	"wadbd/internal/keymgr"
	"wadbd/internal/netlink"
	"wadbd/internal/supervisor"
	"wadbd/internal/tcp"
)

var (
	currentCfg config.Config
	cfgMu      sync.RWMutex
)

func getCfg() config.Config {
	cfgMu.RLock()
	defer cfgMu.RUnlock()
	return currentCfg
}

func updateCfg(fn func(c *config.Config)) config.Config {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	fn(&currentCfg)
	_ = config.Save(currentCfg)
	return currentCfg
}

// Run executes the main daemon process with supervision, network monitoring, and IPC server.
func Run() error {
	log.Printf("[daemon] starting WADBD daemon (PID %d)...", os.Getpid())

	// 1. Acquire single-instance process lock
	lock, err := config.AcquireLock(config.PidFile)
	if err != nil {
		return fmt.Errorf("wadbd daemon already running or PID locked: %w", err)
	}
	defer lock.Release()

	// Write PID file
	_ = os.WriteFile(config.PidFile, []byte(strconv.Itoa(os.Getpid())+"\n"), 0644)

	// 2. Tune TCP keepalive
	_ = tcp.TuneKeepalive(60, 10, 3)

	// 3. Load configuration
	loaded, err := config.Load()
	if err != nil {
		log.Printf("[daemon] warning: config load error: %v", err)
		loaded = config.DefaultConfig()
	}
	cfgMu.Lock()
	currentCfg = loaded
	cfgMu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 4. If enabled or enable_on_boot, restore active state
	if loaded.EnableOnBoot || loaded.Enabled {
		log.Printf("[daemon] initializing wireless ADB on port %d...", loaded.Port)
		_ = supervisor.StartAdbd(loaded.Port, loaded.AuthMode)
		_ = firewall.ApplyConfig(loaded.Port, loaded.Bindings)
		updateCfg(func(c *config.Config) {
			c.Enabled = true
		})
	}

	// 5. Start background supervision watchdog
	go supervisor.Watchdog(ctx, getCfg)

	// 6. Start kernel Netlink listener for network link & address changes
	go netlink.ListenNetworkChanges(ctx, func() {
		cfg := getCfg()
		if cfg.Enabled {
			log.Printf("[daemon] network link/address event detected, re-applying firewall rules...")
			_ = firewall.ApplyConfig(cfg.Port, cfg.Bindings)
		}
	})

	// 7. Start UNIX socket IPC server
	ipc.CleanStaleSocket()
	listener, err := net.Listen("unix", config.SocketPath)
	if err != nil {
		return fmt.Errorf("failed to listen on socket %s: %w", config.SocketPath, err)
	}
	defer listener.Close()
	defer os.Remove(config.SocketPath)

	_ = os.Chmod(config.SocketPath, 0660)

	// Signal handling
	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	go func() {
		sig := <-sigCh
		log.Printf("[daemon] received signal %v, shutting down...", sig)
		cancel()
		listener.Close()
	}()

	log.Printf("[daemon] listening for commands on %s", config.SocketPath)

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			default:
				continue
			}
		}
		go handleClient(conn)
	}
}

func handleClient(conn net.Conn) {
	defer conn.Close()

	var req ipc.Request
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		_ = json.NewEncoder(conn).Encode(ipc.Response{
			Success: false,
			Error:   "invalid request payload: " + err.Error(),
		})
		return
	}

	resp := processRequest(req)
	_ = json.NewEncoder(conn).Encode(resp)
}

func processRequest(req ipc.Request) ipc.Response {
	cfg := getCfg()

	switch req.Action {
	case "ping":
		return ipc.Response{Success: true, Message: "pong"}

	case "status":
		status := supervisor.QueryStatus(cfg)
		return ipc.Response{Success: true, Data: status}

	case "enable":
		port := req.Port
		if port <= 0 {
			port = cfg.Port
		}
		if port <= 0 || port > 65535 {
			port = config.DefaultPort
		}

		newCfg := updateCfg(func(c *config.Config) {
			c.Enabled = true
			c.Port = port
		})

		_ = supervisor.StartAdbd(newCfg.Port, newCfg.AuthMode)
		_ = firewall.ApplyConfig(newCfg.Port, newCfg.Bindings)

		return ipc.Response{
			Success: true,
			Message: fmt.Sprintf("Wireless ADB enabled on port %d", newCfg.Port),
			Data:    supervisor.QueryStatus(newCfg),
		}

	case "disable":
		newCfg := updateCfg(func(c *config.Config) {
			c.Enabled = false
		})

		_ = supervisor.StopAdbd()
		_ = firewall.Clear(cfg.Port)
		tcp.ClearNotification()

		return ipc.Response{
			Success: true,
			Message: "Wireless ADB disabled",
			Data:    supervisor.QueryStatus(newCfg),
		}

	case "bind":
		target := firewall.ResolveTarget(req.Target)
		if err := firewall.ValidateTarget(target); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}

		newCfg := updateCfg(func(c *config.Config) {
			for _, b := range c.Bindings {
				if b == target {
					return
				}
			}
			c.Bindings = append(c.Bindings, target)
		})

		if newCfg.Enabled {
			_ = firewall.ApplyConfig(newCfg.Port, newCfg.Bindings)
		}

		return ipc.Response{
			Success: true,
			Message: fmt.Sprintf("Bound target '%s'", target),
			Data:    newCfg.Bindings,
		}

	case "unbind":
		target := firewall.ResolveTarget(req.Target)
		newCfg := updateCfg(func(c *config.Config) {
			var updated []string
			for _, b := range c.Bindings {
				if b != target {
					updated = append(updated, b)
				}
			}
			c.Bindings = updated
		})

		if newCfg.Enabled {
			_ = firewall.ApplyConfig(newCfg.Port, newCfg.Bindings)
		}

		return ipc.Response{
			Success: true,
			Message: fmt.Sprintf("Unbound target '%s'", target),
			Data:    newCfg.Bindings,
		}

	case "unbind_all":
		newCfg := updateCfg(func(c *config.Config) {
			c.Bindings = []string{}
		})

		if newCfg.Enabled {
			_ = firewall.ApplyConfig(newCfg.Port, newCfg.Bindings)
		}

		return ipc.Response{
			Success: true,
			Message: "All interface & subnet restrictions removed",
			Data:    newCfg.Bindings,
		}

	case "set_boot":
		port := req.Port
		if port <= 0 {
			port = cfg.Port
		}
		newCfg := updateCfg(func(c *config.Config) {
			c.EnableOnBoot = req.Enabled
			if req.Enabled && port > 0 {
				c.Port = port
			}
		})

		msg := "Start on boot disabled"
		if req.Enabled {
			msg = fmt.Sprintf("Start on boot enabled on port %d", newCfg.Port)
		}
		return ipc.Response{Success: true, Message: msg}

	case "set_notify":
		newCfg := updateCfg(func(c *config.Config) {
			c.Notify = req.Enabled
		})
		if !newCfg.Notify {
			tcp.ClearNotification()
		}
		msg := "Connection notifications disabled"
		if newCfg.Notify {
			msg = "Connection notifications enabled"
		}
		return ipc.Response{Success: true, Message: msg}

	case "set_auth":
		mode := req.Mode
		if mode != "open" {
			mode = "secure"
		}
		newCfg := updateCfg(func(c *config.Config) {
			c.AuthMode = mode
		})
		if newCfg.Enabled {
			_ = supervisor.StartAdbd(newCfg.Port, newCfg.AuthMode)
		}
		msg := "ADB RSA authentication is required (secure)"
		if mode == "open" {
			msg = "ADB RSA authentication is disabled (open)"
		}
		return ipc.Response{Success: true, Message: msg}

	case "list_keys":
		keys, err := keymgr.ListKeys()
		if err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Data: keys}

	case "remove_key":
		if err := keymgr.RemoveKey(req.KeyID); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Message: fmt.Sprintf("Key [%d] removed", req.KeyID)}

	case "import_key":
		if err := keymgr.ImportKey(req.Path); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Message: "Key imported successfully"}

	case "clear_keys":
		if err := keymgr.ClearKeys(); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Message: "All authorized ADB keys cleared"}

	case "backup_keys":
		if err := keymgr.BackupKeys(req.Path); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Message: fmt.Sprintf("Keys backed up to %s", req.Path)}

	case "restore_keys":
		if err := keymgr.RestoreKeys(req.Path); err != nil {
			return ipc.Response{Success: false, Error: err.Error()}
		}
		return ipc.Response{Success: true, Message: fmt.Sprintf("Keys restored from %s", req.Path)}

	default:
		return ipc.Response{Success: false, Error: "unknown action: " + req.Action}
	}
}
