package com.phonxiety.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * when setup already finished or no install was in flight.
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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Setup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Finish installing the Phonxiety module after a reboot"
            }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Finish setting up Phonxiety")
            .setContentText("The module was installed; open the app to verify it and pair with your computer.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "setup"
        const val NOTIFICATION_ID = 0x5048
    }
}
