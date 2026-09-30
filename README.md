# WADBD

WADBD is a module that allows you to control Android Wireless ADB from any network interface.

## Features

- Enable or disable Wireless ADB and choose its port.
- Limit connections to selected network interfaces, VPN tunnels, or subnets.
- Start Wireless ADB automatically at boot.
- Show shell notifications when an ADB client connects. Notifications can be turned off.
- View, import, and revoke authorized ADB keys.
- Toggle ADB RSA authentication from the WebUI.

## Install

Flash the module ZIP in KernelSU, APatch, or Magisk, then reboot if the manager requests it. KernelSU and APatch provide the module WebUI. Magisk users can run the commands below from a root shell.

## Commands

```sh
wadbd on [port]             # Enable Wireless ADB; default port is 5555
wadbd off                   # Disable Wireless ADB
wadbd status                # Show current state and connected clients
wadbd enable-on-boot [port] # Enable Wireless ADB at boot
wadbd disable-on-boot       # Disable start at boot
wadbd bind wlan0            # Allow connections through one interface
wadbd bind tun+             # Allow connections through matching VPN tunnels
wadbd bind 192.168.1.0/24   # Allow connections from a subnet
wadbd unbind <target>       # Remove one network restriction
wadbd unbind-all            # Remove all network restrictions
wadbd notify on|off|status  # Control connection notifications
wadbd auth open|secure|status # Control ADB RSA authentication
wadbd --list-keys           # List authorized ADB keys
wadbd --import-key <path>   # Import a public key from the phone
wadbd --remove-key <id>     # Revoke one key
wadbd --clear-keys          # Revoke all keys
```

## Security

ADB authentication is enabled by default. **Allow anyone** disables the RSA trust prompt for ADB clients, including on locked production builds. WADBD restores the device's `ro.debuggable` property after adbd starts. This can affect USB ADB as well as wireless ADB. Any client that can reach ADB can open a shell; this does not grant root by itself. Turn the setting off to require RSA authentication again.

Stored keys are kept when unauthenticated mode is enabled. Key management is read-only until RSA authentication is restored.
