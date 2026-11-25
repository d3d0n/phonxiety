#!/system/bin/sh
# Action button in the Magisk, KernelSU, KernelSU-Next and APatch managers.
# It only opens the Phonxiety app's Doctor screen: no install, no reboot, no root logic.
PKG="com.phonxiety.bridge"
ACTIVITY="$PKG/.MainActivity"

# Newer builds read the route from the intent extra; the launcher intent is the fallback.
if am start -n "$ACTIVITY" --es phonxiety.route doctor >/dev/null 2>&1; then
    exit 0
fi
if am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$ACTIVITY" >/dev/null 2>&1; then
    exit 0
fi
echo "Phonxiety is not installed or cannot be started; reinstall the module."
exit 1
