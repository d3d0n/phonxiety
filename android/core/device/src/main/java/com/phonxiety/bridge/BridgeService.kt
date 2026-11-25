package com.phonxiety.bridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.phonxiety.bridge.device.R

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
        private const val NOTIFICATION_ID = 0x5048

        /** Starts the link as a foreground service; safe to call repeatedly. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start bridge service: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    @Volatile private var foregroundReady = false
    private var engine: BridgeEngine? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { Notifications.ensureChannels(this) }
        try {
            startForeground(
                NOTIFICATION_ID,
                Notifications.bridge(this, getString(R.string.notification_link_waiting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            foregroundReady = true
        } catch (e: Exception) {
            Log.e(TAG, "cannot enter foreground: ${e.javaClass.simpleName}")
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
            Log.e(TAG, "bridge config unavailable: ${e.code}")
            BridgeRuntime.forgetPairing()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return Service.START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "bridge config unreadable: ${e.javaClass.simpleName}")
            BridgeRuntime.forgetPairing()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }

        BridgeRuntime.rememberPairing(config.kind)
        val created = BridgeEngine(applicationContext, config) { textRes ->
            updateNotification(getString(textRes))
        }
        engine = created
        BridgeRuntime.engine = created
        created.start()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        val current = engine
        engine = null
        BridgeRuntime.engine = null
        current?.close()
        foregroundReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        if (!foregroundReady) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                Notifications.bridge(this, text)
            )
        } catch (_: Exception) {
            // The service may be going away.
        }
    }
}
