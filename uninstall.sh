#!/system/bin/sh

PID_FILE="/data/adb/wadbd/wadbd.pid"
SOCK_FILE="/data/adb/wadbd/wadbd.sock"

if [ -f "$PID_FILE" ]; then
    pid=$(cat "$PID_FILE" 2>/dev/null | tr -d '[:space:]')
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        sleep 1
        kill -9 "$pid" 2>/dev/null
    fi
    rm -f "$PID_FILE"
fi

rm -f "$SOCK_FILE"
rm -f /data/adb/ksu/bin/wadbd
rm -f /data/adb/ap/bin/wadbd
