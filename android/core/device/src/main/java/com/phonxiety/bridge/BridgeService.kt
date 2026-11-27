package com.phonxiety.bridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.phonxiety.bridge.net.CandidateMerge
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map

/**
 * A start reuses the running engine only for the pairing it was built for.
 * Any other stored identity — or no engine at all — builds a new one, so the
 * previous daemon's link never serves a request for the new pairing.
 */
internal fun mustBuildEngine(runningIdentity: String?, storedIdentity: String): Boolean = runningIdentity != storedIdentity

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
    private val scope = bridgeScope("phonxiety-service")
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
        // Reading the file, publishing its pairing and adopting its engine is one
        // transaction: a re-pair (or a host-written file) cannot land between the
        // read and the adoption and leave an older pairing exposed.
        val adoption = EngineAdoption(
            read = { readPairing() },
            runningIdentity = { engine?.pairingIdentity },
            expect = { identity -> BridgeRuntime.expectPairing(identity) },
            forget = { BridgeRuntime.forgetPairing() },
            replace = { replaceRunningEngine() },
            install = { pairing, identity -> installEngine(pairing, identity) }
        )
        if (!adoption.adopt()) {
            tearDownForeground()
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        return Service.START_STICKY
    }

    private fun readPairing(): PairingRead? = try {
        BridgeConfigLoader.read(this)
    } catch (e: BridgeConfigException) {
        BridgeLog.w(TAG, "bridge config unavailable: ${e.code}")
        null
    } catch (e: Exception) {
        BridgeLog.w(TAG, "bridge config unreadable: ${e.javaClass.simpleName}")
        null
    }

    /** Closes the engine built for the pairing being replaced, before the new one exists. */
    private fun replaceRunningEngine() {
        val current = engine ?: return
        BridgeLog.i(TAG, "pairing changed: replacing the live link")
        BridgeRuntime.detach(current)
        card?.cancel()
        card = null
        engine = null
        current.close()
    }

    private fun installEngine(pairing: PairingRead, identity: String) {
        BridgeRuntime.rememberPairing(pairing.config.kind)
        // The addresses come from the same read as the identity: another file
        // snapshot can never supply the candidates this engine starts with.
        val created = BridgeEngine(
            applicationContext,
            pairing.config,
            BridgeConfigLoader.candidates(pairing.config, pairing.addresses)
        )
        engine = created
        BridgeRuntime.attach(created, identity)
        card = slot.follow(created.state.map(BridgeNotice::of), scope) { notice ->
            Notifications.bridge(this, notice)
        }
        created.start()
    }

    override fun onDestroy() {
        slot.close()
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
        slot.close()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}

/**
 * Adopts the stored pairing once, inside [BridgeRuntime.pairingTransaction].
 *
 * The read belongs to the transaction: a start that could not read yet is
 * serialized behind a re-pair instead of publishing what it would have read
 * before it.  An unreadable store forgets the pairing; a different identity
 * replaces the running engine before the new one is installed.
 */
internal class EngineAdoption(
    private val read: () -> PairingRead?,
    private val runningIdentity: () -> String?,
    private val expect: (String) -> Unit,
    private val forget: () -> Unit,
    private val replace: () -> Unit,
    private val install: (PairingRead, String) -> Unit
) {
    /** True when an engine is running for the stored pairing; false when none is. */
    fun adopt(): Boolean = BridgeRuntime.pairingTransaction {
        val pairing = read()
        if (pairing == null) {
            forget()
            return@pairingTransaction false
        }
        val identity = CandidateMerge.identity(pairing.config)
        // From this line on, a request either gets the engine built below or none.
        expect(identity)
        if (!mustBuildEngine(runningIdentity(), identity)) return@pairingTransaction true
        replace()
        install(pairing, identity)
        true
    }
}
