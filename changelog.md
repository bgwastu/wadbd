# v5.7
- Replace full-width tab bar with Material 3 SingleChoiceSegmentedButtonRow
- Redesign active session card: spacious layout, full-width IP display, port pill badge, and full-width bottom Disconnect button
- Streamline hero card labels to 'Enabled' and 'Disabled' with clean port subtitle
- Center all empty-state messages horizontally across Keys screens

# v5.5
- Redesign Network screen: replace awkward chips with AOSP Settings-style preference rows
- Consolidate VPN / Tailscale preset into dynamic tunnel wildcard (`tun+`)
- Remove redundant 100.64.0.0/10 rule in favor of interface-based tunnel matching
- Add clean custom CIDR input dialog and firewall reset action

# v5.4
- Redesign UI to AOSP / Material 3 Settings specifications
- Add LargeTopAppBar with collapsible scroll behavior and edge-to-edge layout
- Add rounded preference groups with icon containers and dividers
- Add hero master toggle card for wireless debugging
- Enhance RSA key manager and unauthorized attempt approval workflow

# v5.2
- Add real-time active ADB connection alerts (Android system notification when clients connect)
- Add background session monitor daemon (`wadbd-monitor`)
- Automatic client IP display and multi-session tracking in notifications
- Automatic notification dismissal upon client disconnection
- Add `wadbd notify [on|off|status]` CLI command
- Add active connection inspection to `wadbd status`

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
