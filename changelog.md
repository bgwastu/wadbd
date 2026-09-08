# v5.1
- Add subnet/CIDR binding support (e.g. 100.64.0.0/10, 192.168.1.0/24 via iptables -s)
- Add wildcard interface binding (tun+ via iptables -i) for dynamic VPN allocation
- Add presets: 'wadbd bind tailscale' and 'wadbd bind mesh'
- Fix persistent configuration paths in WebUI (/data/adb/wadbd)
- Fix WebUI boot toggle overwriting service.sh
- Add mesh & tun+ presets and interface tagging to WebUI
- Add authorized key management to WebUI and CLI

# v5.0
- Add per-interface iptables binding
- Add boot persistence via enable_on_boot flag
