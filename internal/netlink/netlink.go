package netlink

import (
	"context"
	"log"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// ListenNetworkChanges opens a Netlink socket and calls onChange when interfaces or IPs change.
func ListenNetworkChanges(ctx context.Context, onChange func()) {
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW, unix.NETLINK_ROUTE)
	if err != nil {
		log.Printf("[netlink] failed to create netlink socket: %v", err)
		return
	}
	defer unix.Close(fd)

	sa := &unix.SockaddrNetlink{
		Family: unix.AF_NETLINK,
		Groups: unix.RTMGRP_LINK | unix.RTMGRP_IPV4_IFADDR | unix.RTMGRP_IPV6_IFADDR,
	}

	if err := unix.Bind(fd, sa); err != nil {
		log.Printf("[netlink] failed to bind netlink socket: %v", err)
		return
	}

	var (
		debounceTimer *time.Timer
		dMu           sync.Mutex
	)

	buf := make([]byte, 4096)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		n, _, err := unix.Recvfrom(fd, buf, 0)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			time.Sleep(500 * time.Millisecond)
			continue
		}
		if n <= 0 {
			continue
		}

		// Debounce changes so rapid interface flappings (e.g. VPN handshake) trigger 1 update
		dMu.Lock()
		if debounceTimer != nil {
			debounceTimer.Stop()
		}
		debounceTimer = time.AfterFunc(1200*time.Millisecond, func() {
			onChange()
		})
		dMu.Unlock()
	}
}
