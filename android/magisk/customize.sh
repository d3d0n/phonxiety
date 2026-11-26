#!/system/bin/sh
# Sourced by the Magisk/KernelSU/APatch module installer (BusyBox ash, standalone mode).
# Do not exit: the installer still has cleanup to perform.
# No MAGISK_VER checks: KernelSU and APatch fake that variable with different values.

if [ "$API" -lt 34 ]; then
    abort "phonxiety bridge requires Android 14 (API 34) or newer; found API $API"
fi

if [ ! -f "$MODPATH/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk" ]; then
    abort "phonxiety bridge APK is missing from the module"
fi

if [ ! -f "$MODPATH/system/etc/permissions/privapp-permissions-com.phonxiety.bridge.xml" ]; then
    abort "phonxiety bridge privapp permission allowlist is missing from the module"
fi

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644

ui_print "- phonxiety bridge is a privileged app; the system partition overlay is active after reboot"

# An update keeps the pairing and settings. Magisk, KernelSU and APatch all keep the installed copy
# at /data/adb/modules/<id> while staging the new one under /data/adb/modules_update, and MODPATH
# points at that staged copy, so the module's id directory already existing means this is an update.
MODULE_ID="$(basename "$MODPATH")"
if [ -n "$MODULE_ID" ] && [ -d "/data/adb/modules/$MODULE_ID" ]; then
    ui_print "- Update: the pairing and settings are kept"
    ui_print "- The update applies after the reboot"
else
    ui_print "- After the reboot open Phonxiety and pair it with the daemon"
fi
