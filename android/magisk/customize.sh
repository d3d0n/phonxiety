#!/system/bin/sh
# Sourced by the Magisk module installer (BusyBox ash, standalone mode).
# Do not exit: the installer still has cleanup to perform.

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

ui_print "- phonxiety bridge is a privileged app; the system partition overlay is active after reboot"
ui_print "- After the reboot open Phonxiety and pair it with the daemon"
