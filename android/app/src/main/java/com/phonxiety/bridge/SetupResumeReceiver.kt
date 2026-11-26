package com.phonxiety.bridge

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.feature.onboarding.setupProgressStore

/**
 * After the reboot the wizard asked for, setup is only half applied until someone opens the app:
 * the first launch re-probes the module, the overlay and the privileged grants. A boot receiver
 * cannot start an activity, so it posts the reminder that takes the user there. It stays silent
 * when setup already finished, when no install was in flight, or when notifications are refused.
 *
 * The reminder has its own ID and its own channel: it must never replace the device-link card.
 */
class SetupResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SetupPreferences(context).ready) return
        if (setupProgressStore(context).read() == null) return
        notifyResume(context)
    }

    private fun notifyResume(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        Notifications.ensureChannels(context)
        manager.notify(
            Notifications.SETUP_ID,
            Notifications.setup(
                context,
                context.getString(R.string.notification_setup_title),
                context.getString(R.string.notification_setup_text)
            )
        )
    }
}
