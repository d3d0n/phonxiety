package com.phonxiety.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the device link after a reboot or an app update, but only when a
 * valid pairing is stored in device-protected storage.
 *
 * The receiver is direct-boot aware: it also runs on LOCKED_BOOT_COMPLETED, so
 * a paired phone reconnects before anyone unlocks it.  Android 14+ permits a
 * specialUse foreground service to be started from these broadcasts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (BridgeConfigLoader.loadOrNull(context) != null) {
                    BridgeService.start(context)
                }
            }
        }
    }
}
