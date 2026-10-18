package ipc

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"time"

	"wadbd/internal/config"
)

type Request struct {
	Action  string `json:"action"`
	Port    int    `json:"port,omitempty"`
	Target  string `json:"target,omitempty"`
	Enabled bool   `json:"enabled,omitempty"`
	Mode    string `json:"mode,omitempty"`
	KeyID   int    `json:"key_id,omitempty"`
	Path    string `json:"path,omitempty"`
}

type Response struct {
	Success bool        `json:"success"`
	Error   string      `json:"error,omitempty"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

// Send dispatches a Request to the running wadbd daemon over the UNIX domain socket.
func Send(req Request) (*Response, error) {
	conn, err := net.DialTimeout("unix", config.SocketPath, 3*time.Second)
	if err != nil {
		return nil, fmt.Errorf("daemon is not running: %w", err)
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return nil, fmt.Errorf("failed to send request: %w", err)
	}

	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}

	return &resp, nil
}

// IsDaemonRunning checks if the UNIX domain socket is active and responsive.
func IsDaemonRunning() bool {
	conn, err := net.DialTimeout("unix", config.SocketPath, 1*time.Second)
	if err != nil {
		return false
	}
	defer conn.Close()
	_ = json.NewEncoder(conn).Encode(Request{Action: "ping"})
	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err == nil && resp.Success {
		return true
	}
	return false
}

// CleanStaleSocket removes the socket file if no process is listening on it.
func CleanStaleSocket() {
	if _, err := os.Stat(config.SocketPath); err == nil {
		conn, err := net.DialTimeout("unix", config.SocketPath, 500*time.Millisecond)
		if err != nil {
			_ = os.Remove(config.SocketPath)
		} else {
			conn.Close()
		}
	}
}
