#!/system/bin/sh
DIR="${0%/*}"

required_files="
module.prop
service.sh
uninstall.sh
system/bin/wadbd
webroot/index.html
webroot/assets/app.js
webroot/assets/app.css
"

for file in $required_files; do
    if [ ! -f "$DIR/$file" ]; then
        [ -f "$DIR/module.prop" ] && sed -i 's/^description=.*/description=Module is corrupted, please reinstall/' "$DIR/module.prop"
        touch "$DIR/disable"
        exit 1
    fi
done

for path in /data/adb/ksu/bin /data/adb/ap/bin; do
    if [ -d "$path" ] && [ -f "$DIR/system/bin/wadbd" ]; then
        if [ ! -f "$path/wadbd" ] || ! cmp -s "$DIR/system/bin/wadbd" "$path/wadbd"; then
            cp -f "$DIR/system/bin/wadbd" "$path/wadbd"
            chmod 755 "$path/wadbd"
        fi
    fi
done
