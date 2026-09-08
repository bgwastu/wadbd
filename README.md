# Wireless ADBD Controller (WADBD)

A Magisk, KernelSU, and APatch module for managing Android's wireless ADB daemon (`adbd`) with per-interface and subnet binding, boot persistence, authorized key management, and a modern WebUI (MMRL / KSUWebUI).

## Why This Fork?

Standard Android wireless ADB listens on all network interfaces (`0.0.0.0`), exposing the debugging daemon to public Wi-Fi, cellular, and any connected VPN tunnels.

This fork adds **iptables-based network isolation**:
- **Interface Whitelisting:** Restrict ADB access to trusted interfaces only (e.g. `wlan0`, `eth0`).
- **Dynamic VPN / Tunnel Support (`tun+`):** Matches all dynamic tunnel interfaces (`tun0`, `tun1`, etc.) so ADB remains accessible even when Android assigns tunnel interface numbers out of order upon reboot.
- **Subnet / CIDR Binding:** Restrict incoming ADB connections to specific trusted subnets (e.g. local LAN `192.168.1.0/24`, Tailscale mesh `100.64.0.0/10`, or private VPN mesh subnets) regardless of which interface they arrive on.
- **Persistent Configuration:** Interface bindings and boot persistence are preserved across module updates in `/data/adb/wadbd/`.
- **Authorized Key Management:** View, export, import, or revoke saved ADB keys directly from the WebUI or CLI without needing physical PC access.

## Features

- **Toggle Wireless ADB:** Enable or disable wireless ADB on any custom port (default: 5555).
- **Interface & Subnet Whitelisting:** Drops all incoming ADB traffic on unapproved interfaces using kernel `iptables`. Localhost (`lo`) is always permitted.
- **Boot Persistence:** Automatically starts wireless ADB on boot on your desired port and restores all firewall bindings.
- **Key Management:** List all authorized devices (`adb_keys`), revoke individual keys, or import keys without waiting for the RSA trust prompt.
- **WebUI Integration:** Accessible from MMRL or KernelSU WebUI standalone with real-time interface detection, toggle switches, and quick presets.

## Installation

1. Download the module zip from [Releases](https://github.com/bgwastu/wadbd/releases) or flash via MMRL.
2. Flash in **Magisk**, **KernelSU**, or **APatch**.
3. Reboot your device.
4. Open the WebUI via **MMRL** or **KernelSU WebUI**, or manage via terminal with `wadbd`.

## CLI Usage

Run `wadbd` in a root shell (`su`):

### Basic Controls
```bash
# Enable wireless ADB on default port 5555
wadbd on

# Enable on a custom port
wadbd on 5556

# Disable wireless ADB
wadbd off

# View current ADB daemon and network status
wadbd status
```

### Interface & Subnet Binding
```bash
# Restrict ADB to Wi-Fi only
wadbd bind wlan0

# Restrict ADB to all VPN tunnels (wildcard matches tun0, tun1, etc. dynamically)
wadbd bind tun+

# Restrict ADB to a specific local subnet
wadbd bind 192.168.1.0/24

# Restrict ADB to Tailscale mesh
wadbd bind tailscale

# Check current binding status and active iptables rules
wadbd bind-status

# Remove a specific restriction
wadbd unbind wlan0

# Remove all restrictions (open on all interfaces)
wadbd unbind-all
```

### Boot Persistence
```bash
# Enable wireless ADB on boot
wadbd enable-on-boot 5555

# Disable wireless ADB on boot
wadbd disable-on-boot
```

### Authorized Key Management
```bash
# List all devices authorized to connect to your phone
wadbd --list-keys

# Remove an authorized device by ID
wadbd --remove-key 0

# Import an adbkey.pub directly
wadbd --import-key /sdcard/Download/adbkey.pub

# Backup or restore authorized keys
wadbd --backup /sdcard/Download/adb_keys_backup
wadbd --restore /sdcard/Download/adb_keys_backup

# Revoke all authorized keys
wadbd --clear-keys
```

## How It Works

When any interface or subnet is bound:
1. All incoming TCP packets targeting the configured ADB port on unapproved interfaces are dropped via `iptables`:
   ```text
   -A INPUT -p tcp --dport <port> -j DROP
   ```
2. Whitelisted interfaces or subnets are inserted above the drop rule:
   ```text
   -A INPUT -i lo -p tcp --dport <port> -j ACCEPT
   -A INPUT -i <interface> -p tcp --dport <port> -j ACCEPT
   -A INPUT -s <subnet_cidr> -p tcp --dport <port> -j ACCEPT
   ```
3. When no interfaces are bound, no iptables restrictions are applied.
