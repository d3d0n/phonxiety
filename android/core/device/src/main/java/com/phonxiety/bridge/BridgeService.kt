package com.phonxiety.bridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map

/**
 * Idle device link.  The app starts it when it is paired, from [MainActivity]
 * and from [BootReceiver]; the daemon never starts it.
 *
 * It is an FGS of type specialUse and therefore has no while-in-use
 * precondition; it can hold the device link before any Telecom Call exists.  It
 * is exported behind android.permission.DUMP so a root shell can restart it for
 * diagnostics, while third-party apps cannot.
 *
 * The service is direct-boot aware and keeps the process-wide iroh endpoint
 * bound for its lifetime; that endpoint is shut down in [onDestroy].
 */
class BridgeService : Service() {
    companion object {
        private const val TAG = "PhonxietyBridge"

        /** Starts the link as a foreground service; safe to call repeatedly. */
        fun start(context: Context) {
            try {
                startOrThrow(context)
            } catch (e: Exception) {
                BridgeLog.w(TAG, "cannot start bridge service: ${e.javaClass.simpleName}")
            }
        }

        /**
         * [start] for a caller that must undo its own work when the start is refused, such as
         * pairing: throws whatever `startForegroundService` threw.
         */
        fun startOrThrow(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    @Volatile private var foregroundReady = false
    private var engine: BridgeEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var card: Job? = null

    private val slot by lazy {
        NotificationSlot<BridgeNotice>(getSystemService(NotificationManager::class.java), Notifications.BRIDGE_ID)
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { Notifications.ensureChannels(this) }
        try {
            startForeground(
                Notifications.BRIDGE_ID,
                Notifications.bridge(this, BridgeNotice.Offline),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            foregroundReady = true
        } catch (e: Exception) {
            BridgeLog.e(TAG, "cannot enter foreground: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        if (engine != null) return Service.START_STICKY

        val config = try {
            BridgeConfigLoader.load(this)
        } catch (e: BridgeConfigException) {
            BridgeLog.e(TAG, "bridge config unavailable: ${e.code}")
            BridgeRuntime.forgetPairing()
            tearDownForeground()
            stopSelf(startId)
            return Service.START_NOT_STICKY
        } catch (e: Exception) {
            BridgeLog.e(TAG, "bridge config unreadable: ${e.javaClass.simpleName}")
            BridgeRuntime.forgetPairing()
            tearDownForeground()
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }

        BridgeRuntime.rememberPairing(config.kind)
        // The card follows the state, which carries the path, the latency and the call; the
        // engine's per-link callback only names a string and would lose two of the three.
        val created = BridgeEngine(applicationContext, config) { }
        engine = created
        BridgeRuntime.attach(created)
        card = slot.follow(created.state.map(BridgeNotice::of), scope) { notice ->
            Notifications.bridge(this, notice)
        }
        created.start()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        val current = engine
        engine = null
        card?.cancel()
        card = null
        scope.cancel()
        if (current != null) BridgeRuntime.detach(current)
        current?.close()
        tearDownForeground()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The explicit cancel an ongoing card must have, whatever ended the service (F13). */
    private fun tearDownForeground() {
        foregroundReady = false
        slot.remove()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}
