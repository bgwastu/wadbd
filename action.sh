#!/system/bin/sh
###########################################
## This file is a part of WADBD
###########################################

ksu_webui=$(pm list packages 2>/dev/null | grep -E "ksuwebui" | head -1 | cut -d: -f2)
mmrl_pkg=$(pm list packages 2>/dev/null | grep -E "mmrl" | head -1 | cut -d: -f2)

if [ -n "$ksu_webui" ]; then
    echo "- Launching WebUI in KSUWebUI ($ksu_webui)..."
    am start -n "$ksu_webui/.WebUIActivity" -e id "wadbd"
elif [ -n "$mmrl_pkg" ]; then
    echo "- Launching WebUI in MMRL ($mmrl_pkg)..."
    am start -n "$mmrl_pkg/com.dergoogler.mmrl.ui.activity.webui.WebUIActivity" -e MOD_ID "wadbd"
else
    /data/adb/modules/wadbd/system/bin/wadbd status
fi
