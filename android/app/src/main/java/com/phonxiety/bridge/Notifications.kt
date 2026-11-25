package com.phonxiety.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    private const val BRIDGE_CHANNEL = "bridge"
    private const val CALL_CHANNEL = "call_bridge"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                BRIDGE_CHANNEL,
                context.getString(R.string.notification_channel_link),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_link_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CALL_CHANNEL,
                context.getString(R.string.notification_channel_call),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_call_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun bridge(context: Context, text: String): Notification =
        build(context, BRIDGE_CHANNEL, context.getString(R.string.notification_link_title), text)

    fun call(
        context: Context,
        text: String = context.getString(R.string.notification_call_active),
    ): Notification =
        build(context, CALL_CHANNEL, context.getString(R.string.notification_call_title), text)

    private fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
    ): Notification =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
}
