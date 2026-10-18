package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
)

const (
	PersistDir     = "/data/adb/wadbd"
	ConfigFile     = PersistDir + "/config.json"
	LegacyBootFile = PersistDir + "/enable_on_boot"
	LegacyBindFile = PersistDir + "/bind_ifaces"
	LegacyNotify   = PersistDir + "/notify_conn"
	LegacyAuthFile = PersistDir + "/auth_mode"
	SocketPath     = PersistDir + "/wadbd.sock"
	PidFile        = PersistDir + "/wadbd.pid"
	LogFile        = PersistDir + "/wadbd.log"
	DefaultPort    = 5555
)

type Config struct {
	Port         int      `json:"port"`
	Enabled      bool     `json:"enabled"`
	EnableOnBoot bool     `json:"enable_on_boot"`
	Notify       bool     `json:"notify"`
	AuthMode     string   `json:"auth_mode"` // "secure" or "open"
	Bindings     []string `json:"bindings"`
}

var (
	mu sync.RWMutex
)

func DefaultConfig() Config {
	return Config{
		Port:         DefaultPort,
		Enabled:      false,
		EnableOnBoot: false,
		Notify:       true,
		AuthMode:     "secure",
		Bindings:     []string{},
	}
}

// Load reads config from config.json, or migrates from legacy files if config.json is absent.
func Load() (Config, error) {
	mu.Lock()
	defer mu.Unlock()

	cfg := DefaultConfig()

	if err := os.MkdirAll(PersistDir, 0755); err != nil {
		return cfg, fmt.Errorf("failed to create persist dir: %w", err)
	}

	data, err := os.ReadFile(ConfigFile)
	if err == nil {
		if err := json.Unmarshal(data, &cfg); err == nil {
			if cfg.Port <= 0 || cfg.Port > 65535 {
				cfg.Port = DefaultPort
			}
			if cfg.AuthMode != "open" {
				cfg.AuthMode = "secure"
			}
			return cfg, nil
		}
	}

	// Migrate from legacy files
	cfg = migrateLegacy()
	_ = saveUnlocked(cfg)
	return cfg, nil
}

func migrateLegacy() Config {
	cfg := DefaultConfig()

	// Boot flag
	if data, err := os.ReadFile(LegacyBootFile); err == nil {
		val := strings.TrimSpace(string(data))
		if p, err := strconv.Atoi(val); err == nil && p > 0 && p <= 65535 {
			cfg.Port = p
			cfg.EnableOnBoot = true
		}
	}

	// Bindings
	if data, err := os.ReadFile(LegacyBindFile); err == nil {
		lines := strings.Split(string(data), "\n")
		for _, l := range lines {
			l = strings.TrimSpace(l)
			if l != "" && !contains(cfg.Bindings, l) {
				cfg.Bindings = append(cfg.Bindings, l)
			}
		}
	}

	// Notifications
	if data, err := os.ReadFile(LegacyNotify); err == nil {
		val := strings.TrimSpace(string(data))
		if val == "0" {
			cfg.Notify = false
		}
	}

	// Auth mode
	if data, err := os.ReadFile(LegacyAuthFile); err == nil {
		val := strings.TrimSpace(string(data))
		if val == "open" {
			cfg.AuthMode = "open"
		}
	}

	return cfg
}

// Save writes config atomically and updates legacy files for backward compatibility.
func Save(cfg Config) error {
	mu.Lock()
	defer mu.Unlock()
	return saveUnlocked(cfg)
}

func saveUnlocked(cfg Config) error {
	if cfg.Port <= 0 || cfg.Port > 65535 {
		cfg.Port = DefaultPort
	}
	if cfg.AuthMode != "open" {
		cfg.AuthMode = "secure"
	}

	if err := os.MkdirAll(PersistDir, 0755); err != nil {
		return err
	}

	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}

	tmpFile := ConfigFile + ".tmp"
	if err := os.WriteFile(tmpFile, data, 0644); err != nil {
		return err
	}
	if err := os.Rename(tmpFile, ConfigFile); err != nil {
		return err
	}

	// Sync legacy files
	if cfg.EnableOnBoot {
		_ = os.WriteFile(LegacyBootFile, []byte(strconv.Itoa(cfg.Port)+"\n"), 0644)
	} else {
		_ = os.Remove(LegacyBootFile)
	}

	if len(cfg.Bindings) > 0 {
		var sb strings.Builder
		for _, b := range cfg.Bindings {
			sb.WriteString(b + "\n")
		}
		_ = os.WriteFile(LegacyBindFile, []byte(sb.String()), 0644)
	} else {
		_ = os.Remove(LegacyBindFile)
	}

	if cfg.Notify {
		_ = os.WriteFile(LegacyNotify, []byte("1\n"), 0644)
	} else {
		_ = os.WriteFile(LegacyNotify, []byte("0\n"), 0644)
	}

	_ = os.WriteFile(LegacyAuthFile, []byte(cfg.AuthMode+"\n"), 0600)

	return nil
}

func contains(arr []string, s string) bool {
	for _, v := range arr {
		if v == s {
			return true
		}
	}
	return false
}

// LockFile ensures single instance execution using flock
type FileLock struct {
	file *os.File
}

func AcquireLock(path string) (*FileLock, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		return nil, fmt.Errorf("could not acquire lock on %s: %w", path, err)
	}
	return &FileLock{file: f}, nil
}

func (fl *FileLock) Release() {
	if fl != nil && fl.file != nil {
		_ = syscall.Flock(int(fl.file.Fd()), syscall.LOCK_UN)
		_ = fl.file.Close()
		_ = os.Remove(fl.file.Name())
	}
}
