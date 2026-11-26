package com.phonxiety.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.phonxiety.bridge.device.R
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The app's three notification surfaces: the device link, the live call and the setup reminder.
 *
 * Each surface has one ID, one channel and a tap that lands on the screen it describes.  The
 * call carries a Hang up action; no surface duplicates the tap with an "Open" action.  Lock-screen
 * versions are built separately so a number or a transcript can never reach them.
 *
 * The intent contract below is shared with `:app` (which owns the activity and the action
 * receiver), mirroring `HOST_ACTIVITY` in `feature/widgets`' `WidgetSupport.kt`.
 */
object Notifications {
    /**
     * Channel IDs are shipped: changing one creates a second row in the owner's settings that they
     * cannot delete, so only the name and the description evolve here.
     */
    const val BRIDGE_CHANNEL = "bridge"
    const val CALL_CHANNEL = "call_bridge"
    const val SETUP_CHANNEL = "setup"

    /** One ID per surface: two cards that share an ID replace each other (F3). */
    const val BRIDGE_ID = 0x5048
    const val CALL_ID = 0x5049
    const val SETUP_ID = 0x504A

    const val HOST_ACTIVITY = "com.phonxiety.bridge.MainActivity"
    const val ACTION_RECEIVER = "com.phonxiety.bridge.NotificationActions"
    const val ACTION_HANG_UP = "com.phonxiety.bridge.action.HANG_UP"
    const val EXTRA_ROUTE = "phonxiety.route"
    const val EXTRA_CALL_ID = "phonxiety.call_id"
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_CALL = "call"
    const val ROUTE_DOCTOR = "doctor"
    const val ROUTE_SETUP = "setup"

    /** A reminder that stopped being true is worse than none (A5). */
    private val SETUP_TIMEOUT_MS = TimeUnit.HOURS.toMillis(48)

    /**
     * The lock-screen copy is its own `PendingIntent`: two that differ only in extras are one, and
     * the later build would overwrite the card's own tap.
     */
    private const val PUBLIC_REQUEST = 0x100

    /**
     * Creates the three channels.  Idempotent: an existing channel keeps the importance and the
     * sound the owner chose, because the platform never updates a created channel's behaviour.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                BRIDGE_CHANNEL,
                context.getString(R.string.notification_channel_link),
                NotificationManager.IMPORTANCE_LOW
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_call_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SETUP_CHANNEL,
                context.getString(R.string.notification_channel_setup),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_setup_description)
                setShowBadge(true)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /** The device-link card: ongoing, silent, and the way into the dashboard. */
    fun bridge(context: Context, notice: BridgeNotice): Notification {
        val title: String
        val text: String
        var subText: String? = null
        when (notice) {
            is BridgeNotice.InCall -> {
                title = context.getString(R.string.notification_bridge_in_call)
                text = notice.destinationMasked
                subText = context.getString(notice.phase.phaseLabel())
            }

            is BridgeNotice.Linked -> {
                title = context.getString(R.string.notification_bridge_connected)
                text = when {
                    notice.path == PathKind.UNKNOWN -> context.getString(R.string.notification_path_unknown)

                    notice.rttMs == null -> context.getString(notice.path.pathLabel())

                    else -> context.getString(
                        R.string.notification_bridge_detail,
                        context.getString(notice.path.pathLabel()),
                        notice.rttMs
                    )
                }
            }

            BridgeNotice.Connecting -> {
                title = context.getString(R.string.notification_bridge_connecting)
                text = context.getString(R.string.notification_bridge_connecting_text)
            }

            BridgeNotice.Offline -> {
                title = context.getString(R.string.notification_bridge_offline)
                text = context.getString(R.string.notification_bridge_offline_text)
            }
        }
        val builder = base(context, BRIDGE_CHANNEL, R.drawable.ic_link)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, BRIDGE_ID, ROUTE_DASHBOARD))
            .setPublicVersion(publicVersion(context, BRIDGE_CHANNEL, R.string.notification_public_bridge_text, BRIDGE_ID))
        if (subText != null) builder.setSubText(subText)
        return builder.build()
    }

    /**
     * The live-call card: the far end, the phase and a running clock, plus the one action a call
     * needs.  It is a plain ongoing notification, not a `CallStyle` card — the app supervises a
     * call Telecom owns.
     */
    fun call(context: Context, call: CallState?): Notification {
        val shown = call?.takeIf { it.phase != CallPhase.ENDED }
        val title = when (shown?.phase) {
            CallPhase.DIALING -> R.string.notification_call_dialling
            CallPhase.ACTIVE -> R.string.notification_call_active
            CallPhase.ENDING -> R.string.notification_call_ending
            else -> R.string.notification_call_ready
        }
        val startedAt = shown?.startedAtEpochMs
        val builder = base(context, CALL_CHANNEL, R.drawable.ic_bridge)
            .setContentTitle(context.getString(title))
            .setContentText(shown?.destinationMasked ?: context.getString(R.string.notification_call_ready_text))
            .setOngoing(true)
            .setShowWhen(startedAt != null)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(
                if (shown == null) {
                    open(context, CALL_ID, ROUTE_DASHBOARD)
                } else {
                    open(context, CALL_ID, ROUTE_CALL, shown.callId)
                }
            )
            .setPublicVersion(publicVersion(context, CALL_CHANNEL, R.string.notification_public_call_text, CALL_ID))
        if (startedAt != null) {
            builder.setWhen(startedAt).setUsesChronometer(true).setChronometerCountDown(false)
        }
        if (shown != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_bridge),
                    context.getString(R.string.action_hang_up),
                    hangUp(context)
                ).build()
            )
        }
        return builder.build()
    }

    /** The reminder posted after the reboot the wizard asked for; the wizard itself is the tap. */
    fun setup(context: Context, title: String, text: String): Notification = base(context, SETUP_CHANNEL, R.drawable.ic_bridge)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(Notification.CATEGORY_ERROR)
        .setAutoCancel(true)
        .setShowWhen(false)
        .setTimeoutAfter(SETUP_TIMEOUT_MS)
        .setContentIntent(open(context, SETUP_ID, ROUTE_SETUP))
        .setPublicVersion(publicVersion(context, SETUP_CHANNEL, R.string.notification_public_setup_text, SETUP_ID))
        .build()

    private fun base(context: Context, channel: String, smallIcon: Int): Notification.Builder = Notification.Builder(context, channel)
        .setSmallIcon(smallIcon)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)

    /** The lock-screen copy: the app and what the surface is, never who is on the call. */
    private fun publicVersion(context: Context, channel: String, text: Int, request: Int): Notification = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_bridge)
        .setContentTitle(context.getString(R.string.notification_public_title))
        .setContentText(context.getString(text))
        .setContentIntent(open(context, request + PUBLIC_REQUEST, ROUTE_DASHBOARD))
        .build()

    /** A notification tap is an activity, never a trampoline (I4), and its extras are its own. */
    private fun open(context: Context, request: Int, route: String, callId: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        Intent().apply {
            setClassName(context.packageName, HOST_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ROUTE, route)
            if (callId != null) putExtra(EXTRA_CALL_ID, callId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** The action does the work and stops; `:app`'s receiver is the one hang-up path (N8, I4). */
    private fun hangUp(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        CALL_ID,
        Intent().apply {
            setClassName(context.packageName, ACTION_RECEIVER)
            action = ACTION_HANG_UP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun CallPhase.phaseLabel(): Int = when (this) {
        CallPhase.DIALING -> R.string.notification_call_phase_dialling
        CallPhase.ACTIVE -> R.string.notification_call_phase_live
        CallPhase.ENDING -> R.string.notification_call_phase_ending
        CallPhase.ENDED -> R.string.notification_call_phase_ending
    }

    private fun PathKind.pathLabel(): Int = when (this) {
        PathKind.DIRECT -> R.string.notification_path_direct
        PathKind.RELAY -> R.string.notification_path_relay
        PathKind.UNKNOWN -> R.string.notification_path_unknown
    }
}

/**
 * One notification surface: a single ID, an update only when the rendered value changed, and the
 * explicit removal an ongoing card must have.  [build] returning null removes the card.
 */
class NotificationSlot<T>(
    private val manager: NotificationManager?,
    private val id: Int
) {
    fun follow(items: Flow<T>, scope: CoroutineScope, build: (T) -> Notification?): Job = scope.launch {
        items.distinctUntilChanged().collect { item ->
            val notification = build(item)
            if (notification == null) {
                remove()
            } else {
                // The system may refuse while the process is being torn down; the service's
                // lifecycle, not the card, decides whether the link is alive.
                runCatching { manager?.notify(id, notification) }
            }
        }
    }

    fun remove() {
        runCatching { manager?.cancel(id) }
    }
}

/**
 * What the device-link card says, derived from one state sample.  A call outranks the link it runs
 * over, so the card answers "what is happening now" instead of repeating the last path.
 */
sealed interface BridgeNotice {
    data class InCall(val destinationMasked: String, val phase: CallPhase) : BridgeNotice

    data class Linked(val path: PathKind, val rttMs: Long?) : BridgeNotice

    data object Connecting : BridgeNotice

    data object Offline : BridgeNotice

    companion object {
        fun of(state: BridgeState): BridgeNotice {
            val call = state.call?.takeIf { it.phase != CallPhase.ENDED }
            if (call != null) return InCall(call.destinationMasked, call.phase)
            return when (val link = state.link) {
                is LinkState.Up -> Linked(link.path, link.rttMs)
                is LinkState.Connecting -> Connecting
                LinkState.Offline -> Offline
            }
        }
    }
}
