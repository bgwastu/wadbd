###########################################
## This file is a part of WADBD
# by @duds9
###########################################
if pm list packages | grep -q "io.github.a13e300.ksuwebui"; then
    echo "- Launching WebUI in KSUWebUIStandalone..."
    am start -n "io.github.a13e300.ksuwebui/.WebUIActivity" -e id "wadbd"
elif pm list packages | grep -q "com.dergoogler.mmrl"; then
    echo "- Launching WebUI in MMRL WebUI..."
    am start -n "com.dergoogler.mmrl/.ui.activity.webui.WebUIActivity" -e MOD_ID "wadbd"
else
    echo "Error: Can't Launch WebUI, KSUWebUI or MMRL not found."
fi
