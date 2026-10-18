#!/system/bin/sh
# WADBD boot service — launches the native supervisor daemon.

MODDIR=${0%/*}
WADBD_BIN="$MODDIR/system/bin/wadbd"

# Wait for full boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Settle time for UsbDeviceManager and init USB state machine
sleep 3

if [ -x "$WADBD_BIN" ]; then
    nohup "$WADBD_BIN" daemon >/dev/null 2>&1 &
fi
