#!/system/bin/sh
# WADBD v5.2 service.sh — boot persistence for wireless ADB + interface/subnet binding + session monitor
# Runs as part of KernelSU/Magisk boot stage

BOOT_FLAG="/data/adb/wadbd/enable_on_boot"
BIND_FILE="/data/adb/wadbd/bind_ifaces"

# Wait for full boot
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Check if wireless ADB on boot is enabled
if [ -f "$BOOT_FLAG" ]; then
    port=$(cat "$BOOT_FLAG" | tr -d '[:space:]')
    if [ -n "$port" ] && [ "$port" -gt 0 ] 2>/dev/null; then
        setprop service.adb.tcp.port "$port"
        stop adbd
        start adbd
        sleep 1

        # Apply interface & subnet bindings if configured
        if [ -f "$BIND_FILE" ] && [ -s "$BIND_FILE" ]; then
            # Flush existing ADB iptables rules
            iptables -L INPUT -n --line-numbers 2>/dev/null | grep "dpt:$port" | awk '{print $1}' | sort -nr | while read num; do
                iptables -D INPUT "$num" 2>/dev/null
            done

            # Always allow localhost
            iptables -I INPUT -i lo -p tcp --dport "$port" -j ACCEPT

            # ACCEPT for each bound interface, wildcard, or subnet
            while IFS= read -r target; do
                target=$(echo "$target" | tr -d '[:space:]')
                [ -z "$target" ] && continue
                if echo "$target" | grep -q "/"; then
                    # Subnet CIDR (e.g. 100.64.0.0/10, 192.168.1.0/24)
                    iptables -I INPUT -s "$target" -p tcp --dport "$port" -j ACCEPT
                else
                    # Interface or wildcard (e.g. wlan0, tun+, tun0)
                    iptables -I INPUT -i "$target" -p tcp --dport "$port" -j ACCEPT
                fi
            done < "$BIND_FILE"

            # DROP everything else
            iptables -A INPUT -p tcp --dport "$port" -j DROP
        fi

        # Start connection monitor daemon
        if [ -x "/data/adb/modules/wadbd/system/bin/wadbd-monitor" ]; then
            /data/adb/modules/wadbd/system/bin/wadbd-monitor &
        fi
    fi
fi
