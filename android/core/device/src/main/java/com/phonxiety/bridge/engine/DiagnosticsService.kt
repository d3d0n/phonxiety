package com.phonxiety.bridge.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.phonxiety.bridge.CrashReports
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.model.AudioDiagnostics
import com.phonxiety.bridge.model.CrashDiagnostics
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.DiagnosticsSnapshot
import com.phonxiety.bridge.model.EngineDiagnostics
import com.phonxiety.bridge.model.LinkDiagnostics
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.os.Permissions
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The `diagnostics` command and the device telemetry loop.
 *
 * The diagnostics command runs on the command executor, where it captures the
 * call and link fields under the session lock, reads the crash records and
 * encodes the answer; it was the same thread before the engine was split.  The
 * telemetry loop runs on the engine scope on its own interval.
 */
internal class DiagnosticsService(
    private val context: Context,
    private val session: CallSession,
    private val links: () -> LinkSupervisor,
    private val store: EngineStateStore,
    private val wire: (DeviceOutbound) -> Boolean,
    private val json: Json,
    private val isRunning: () -> Boolean,
    private val ownerPackage: String
) {
    companion object {
        private const val TELEMETRY_INTERVAL_MS = 5_000L
    }

    private val startedElapsed = SystemClock.elapsedRealtime()
    private var telemetryJob: Job? = null

    fun start(scope: CoroutineScope) {
        telemetryJob = scope.launch {
            while (isActive && isRunning()) {
                runCatching { sampleDeviceState() }.getOrNull()?.let { store.device(it) }
                delay(TELEMETRY_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    fun handle(id: String) {
        var callId: String? = null
        var callState: String? = null
        var epoch = -1L
        var audioState: com.phonxiety.bridge.audio.PhoneAudio? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current != null && !current.finished) {
                callId = current.callId
                callState = current.state
                epoch = current.epoch
                audioState = current.attached?.audio
            }
        }
        val supervisor = links()
        val diagnostics = DiagnosticsSnapshot(
            engine = EngineDiagnostics(
                running = isRunning(),
                callId = callId,
                state = callState,
                epoch = epoch,
                uptimeSeconds = (SystemClock.elapsedRealtime() - startedElapsed) / 1_000,
                activeCalls = if (session.ownsCall()) 1 else 0
            ),
            audio = audioState?.diagnostics() ?: AudioDiagnostics(),
            link = LinkDiagnostics(
                up = supervisor.isUp(),
                path = supervisor.route()?.let { if (it.relay) "relay" else "direct" },
                rttMs = supervisor.route()?.rttMs,
                backlogBytes = supervisor.backlogBytes(),
                reconnects = supervisor.reconnects()
            ),
            // What killed the last processes: the daemon puts this next to its own state in the
            // diagnostics bundle, so a crash or an ANR arrives with the report instead of being
            // inferred from a gap in `logcat`.
            crashes = CrashReports.recent(context, limit = 5).map { report ->
                CrashDiagnostics(
                    atEpochMs = report.atEpochMs,
                    kind = report.kind.name.lowercase(Locale.US),
                    reason = report.reason,
                    description = report.description,
                    memoryLimiter = report.memoryLimiter,
                    trace = report.detail
                )
            }
        )
        replyOk(id, json.encodeToJsonElement(DiagnosticsSnapshot.serializer(), diagnostics).jsonObject)
    }

    private fun replyOk(id: String, data: JsonObject? = null) {
        wire(DeviceOutbound.Response(id = id, ok = true, data = data))
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun sampleDeviceState(): DeviceState {
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryPercent = runCatching {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        }.getOrNull()
        val charging = runCatching { battery?.isCharging }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = runCatching {
            when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> null
            }
        }.getOrNull()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val network = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val rssi = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)
                ?.connectionInfo
                ?.rssi
                ?.takeIf { it != -127 && it != 0 }
        }.getOrNull()
        val dozeExempt = runCatching {
            power?.isIgnoringBatteryOptimizations(ownerPackage)
        }.getOrNull()
        val missing = Permissions.runtimeMissing(context) + Permissions.privilegedMissing(context)
        return DeviceState(
            batteryPercent = batteryPercent,
            charging = charging,
            thermal = thermal,
            network = network,
            wifiRssi = rssi,
            dozeExempt = dozeExempt,
            missingPermissions = missing
        )
    }
}
