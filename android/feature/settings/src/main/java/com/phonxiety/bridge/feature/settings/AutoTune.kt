package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.PathKind

/** One preference auto-tune may change; the key is what the change log stores. */
enum class TuneKey(val key: String) {
    CODEC("preferred_codec"),
    MAX_BITRATE("max_bitrate"),
    WIFI_LOW_LATENCY("wifi_low_latency");

    companion object {
        fun of(key: String): TuneKey? = entries.firstOrNull { it.key == key }
    }
}

/** One reversible change: what it was, what it became and the measurement that caused it. */
data class TuneChange(
    val key: TuneKey,
    val from: String,
    val to: String,
    val reason: String
)

/** What auto-tune can see. Everything here is measured, not guessed. */
data class TuneObservation(
    val path: PathKind? = null,
    val relayForMs: Long? = null,
    val network: String? = null,
    val thermal: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val lastLinkTest: LinkTestResult? = null,
    val missingPermissions: List<String> = emptyList()
)

/** Changes to apply now, and things the user should know that auto-tune must not do silently. */
data class TuneAction(
    val changes: List<TuneChange> = emptyList(),
    val suggestions: List<String> = emptyList()
)

/**
 * The measured, reversible rules from the plan.  Pure: [evaluate] decides, the caller writes the
 * preferences and the change log.  A rule only fires when the target differs from the current
 * value, so evaluating the same observation twice changes nothing.
 */
object AutoTune {
    const val RELAY_WARNING_MS = 20_000L
    const val POOR_P95_MS = 400L
    const val POOR_LOSS = 0.02
    const val LOW_BITRATE = 16_000
    const val RICH_BITRATE = 64_000
    const val RICH_MIN_BITRATE = 48_000
    const val RICH_MAX_P95_MS = 120L
    const val RICH_MIN_THROUGHPUT_BPS = 400_000L
    const val LOW_BATTERY_PERCENT = 15

    fun evaluate(observation: TuneObservation, settings: PhoneSettings): TuneAction {
        val changes = mutableListOf<TuneChange>()
        val suggestions = mutableListOf<String>()
        val severeThermal = observation.thermal?.uppercase() in setOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        val lowBattery = observation.batteryPercent?.let { it < LOW_BATTERY_PERCENT } == true && observation.charging != true
        val thermalWantsLockOff = settings.thermalAwareness && severeThermal
        val batteryWantsLockOff = settings.batteryAwareness && lowBattery
        if (settings.wifiLowLatency && (thermalWantsLockOff || batteryWantsLockOff)) {
            val reason = when {
                thermalWantsLockOff && batteryWantsLockOff ->
                    "thermal ${observation.thermal} and ${observation.batteryPercent} % battery"

                thermalWantsLockOff -> "thermal status ${observation.thermal}"

                else -> "battery at ${observation.batteryPercent} % and not charging"
            }
            changes += TuneChange(
                TuneKey.WIFI_LOW_LATENCY,
                from = "true",
                to = "false",
                reason = "$reason: released the Wi-Fi low-latency lock, the call keeps running"
            )
        }

        val test = observation.lastLinkTest
        val poorP95 = test?.p95?.let { it > POOR_P95_MS } == true
        val poorLoss = test?.loss?.let { it > POOR_LOSS } == true
        if (poorP95 || poorLoss) {
            val measured = listOfNotNull(
                test?.p95?.let { "p95 $it ms" },
                test?.loss?.let { "loss ${(it * 100).toInt()}%" }
            ).joinToString(", ")
            if (settings.preferredCodec != PhoneCodec.OPUS) {
                changes += TuneChange(
                    TuneKey.CODEC,
                    from = settings.preferredCodec,
                    to = PhoneCodec.OPUS,
                    reason = "link test $measured: a lower-rate codec keeps speech ahead of the queue"
                )
            }
            if (settings.maxBitrate > LOW_BITRATE) {
                changes += TuneChange(
                    TuneKey.MAX_BITRATE,
                    from = settings.maxBitrate.toString(),
                    to = LOW_BITRATE.toString(),
                    reason = "link test $measured: dropped to Opus 16 kbit/s"
                )
            }
        } else if (test != null) {
            val p95 = test.p95
            val throughput = test.throughputBps ?: 0L
            val rich = p95 != null && p95 < RICH_MAX_P95_MS && throughput >= RICH_MIN_THROUGHPUT_BPS &&
                (test.loss ?: 0.0) <= POOR_LOSS
            if (rich && settings.maxBitrate < RICH_MIN_BITRATE) {
                changes += TuneChange(
                    TuneKey.MAX_BITRATE,
                    from = settings.maxBitrate.toString(),
                    to = RICH_BITRATE.toString(),
                    reason = "link test p95 $p95 ms at ${throughput / 1000} kbit/s: Opus 64 kbit/s is affordable"
                )
            }
        }

        val cellular = observation.network?.lowercase()?.let { it in setOf("cellular", "lte", "5g", "nr") } == true
        if (cellular && (test?.loss ?: 0.0) > 0.0 && settings.preferredCodec != PhoneCodec.OPUS) {
            changes += TuneChange(
                TuneKey.CODEC,
                from = settings.preferredCodec,
                to = PhoneCodec.OPUS,
                reason = "cellular with loss: PCM costs 768 kbit/s on a mobile link"
            )
        }

        if (observation.path == PathKind.RELAY && (observation.relayForMs ?: 0L) >= RELAY_WARNING_MS) {
            suggestions += "Relay-only for ${(observation.relayForMs ?: 0L) / 1000} s: point the app at your own " +
                "relay, or run a link test to see what the relay carries"
        }
        if (observation.missingPermissions.any { it.contains("LOCAL_NETWORK") }) {
            suggestions += "The local-network permission is missing: the LAN path fails because of the " +
                "permission, not the network"
        }
        return TuneAction(changes = changes, suggestions = suggestions)
    }
}
