#!/system/bin/sh
# WADBD v5.0 service.sh — boot persistence for wireless ADB + interface binding
# Runs as part of KernelSU/Magisk boot stage

MODPATH="/data/adb/modules/wadbd"
BOOT_FLAG="$MODPATH/enable_on_boot"
BIND_FILE="$MODPATH/bind_ifaces"

# Wait for full boot
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Check if wireless ADB on boot is enabled
if [ -f "$BOOT_FLAG" ]; then
    port=$(cat "$BOOT_FLAG")
    if [ -n "$port" ] && [ "$port" -gt 0 ] 2>/dev/null; then
        setprop service.adb.tcp.port "$port"
        stop adbd
        start adbd
        sleep 1

        # Apply interface bindings if configured
        if [ -f "$BIND_FILE" ] && [ -s "$BIND_FILE" ]; then
            # Flush existing ADB iptables rules
            iptables -L INPUT -n --line-numbers 2>/dev/null | grep "dpt:$port" | awk '{print $1}' | sort -nr | while read num; do
                iptables -D INPUT "$num" 2>/dev/null
            done

            # ACCEPT for each bound interface
            while IFS= read -r iface; do
                iface=$(echo "$iface" | tr -d '[:space:]')
                [ -z "$iface" ] && continue
                iptables -I INPUT -i "$iface" -p tcp --dport "$port" -j ACCEPT
            done < "$BIND_FILE"

            # Always allow localhost
            iptables -I INPUT -i lo -p tcp --dport "$port" -j ACCEPT

            # DROP everything else
            iptables -A INPUT -p tcp --dport "$port" -j DROP
        fi
    fi
fi
