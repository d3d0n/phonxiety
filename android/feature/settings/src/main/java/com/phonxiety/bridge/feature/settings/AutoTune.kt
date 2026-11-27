package com.phonxiety.bridge.feature.settings

import androidx.annotation.StringRes
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.PathKind
import java.text.NumberFormat
import java.util.Locale

/** One preference auto-tune may change; the key is what the change log stores. */
enum class TuneKey(val key: String) {
    CODEC("preferred_codec"),
    MAX_BITRATE("max_bitrate"),
    WIFI_LOW_LATENCY("wifi_low_latency");

    companion object {
        fun of(key: String): TuneKey? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Why a rule fired, as a resource plus its measurements. The rule is a stable member name, so a
 * change log line written by one build still reads in the next.
 */
enum class TuneReason(@param:StringRes val textRes: Int) {
    LOCK_THERMAL_AND_BATTERY(R.string.settings_reason_lock_thermal_battery),
    LOCK_THERMAL(R.string.settings_reason_lock_thermal),
    LOCK_BATTERY(R.string.settings_reason_lock_battery),
    CODEC_LOWER_P95_LOSS(R.string.settings_reason_codec_lower_p95_loss),
    CODEC_LOWER_P95(R.string.settings_reason_codec_lower_p95),
    CODEC_LOWER_LOSS(R.string.settings_reason_codec_lower_loss),
    BITRATE_LOWER_P95_LOSS(R.string.settings_reason_bitrate_lower_p95_loss),
    BITRATE_LOWER_P95(R.string.settings_reason_bitrate_lower_p95),
    BITRATE_LOWER_LOSS(R.string.settings_reason_bitrate_lower_loss),
    BITRATE_RICHER(R.string.settings_reason_bitrate_richer),
    CODEC_CELLULAR(R.string.settings_reason_codec_cellular);
}

/**
 * One reversible change: what it was, what it became and the measurement that caused it. The reason
 * carries its numbers as arguments, so the sentence is resolved in the reader's locale.
 */
data class TuneChange(
    val key: TuneKey,
    val from: String,
    val to: String,
    val reason: TuneReason,
    val reasonArgs: List<String> = emptyList()
) {
    fun reasonText(): UiText = UiText.Res(reason.textRes, reasonArgs)
}

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
    val suggestions: List<UiText> = emptyList()
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
        val suggestions = mutableListOf<UiText>()
        val severeThermal = observation.thermal?.uppercase() in setOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        val lowBattery = observation.batteryPercent?.let { it < LOW_BATTERY_PERCENT } == true && observation.charging != true
        val thermalWantsLockOff = settings.thermalAwareness && severeThermal
        val batteryWantsLockOff = settings.batteryAwareness && lowBattery
        if (settings.wifiLowLatency && (thermalWantsLockOff || batteryWantsLockOff)) {
            changes += when {
                thermalWantsLockOff && batteryWantsLockOff -> TuneChange(
                    TuneKey.WIFI_LOW_LATENCY,
                    from = "true",
                    to = "false",
                    reason = TuneReason.LOCK_THERMAL_AND_BATTERY,
                    reasonArgs = listOf(
                        observation.thermal.orEmpty(),
                        percent((observation.batteryPercent ?: 0) / 100.0)
                    )
                )

                thermalWantsLockOff -> TuneChange(
                    TuneKey.WIFI_LOW_LATENCY,
                    from = "true",
                    to = "false",
                    reason = TuneReason.LOCK_THERMAL,
                    reasonArgs = listOf(observation.thermal.orEmpty())
                )

                else -> TuneChange(
                    TuneKey.WIFI_LOW_LATENCY,
                    from = "true",
                    to = "false",
                    reason = TuneReason.LOCK_BATTERY,
                    reasonArgs = listOf(percent((observation.batteryPercent ?: 0) / 100.0))
                )
            }
        }

        val test = observation.lastLinkTest
        val poorP95 = test?.p95?.let { it > POOR_P95_MS } == true
        val poorLoss = test?.loss?.let { it > POOR_LOSS } == true
        if (poorP95 || poorLoss) {
            val p95 = test?.p95?.let(::milliseconds)
            val loss = test?.loss?.let(::percent)
            if (settings.preferredCodec != PhoneCodec.OPUS) {
                val (reason, args) = when {
                    p95 != null && loss != null ->
                        TuneReason.CODEC_LOWER_P95_LOSS to listOf(p95, loss)

                    p95 != null -> TuneReason.CODEC_LOWER_P95 to listOf(p95)

                    else -> TuneReason.CODEC_LOWER_LOSS to listOf(requireNotNull(loss))
                }
                changes += TuneChange(
                    TuneKey.CODEC,
                    from = settings.preferredCodec,
                    to = PhoneCodec.OPUS,
                    reason = reason,
                    reasonArgs = args
                )
            }
            if (settings.maxBitrate > LOW_BITRATE) {
                val (reason, args) = when {
                    p95 != null && loss != null ->
                        TuneReason.BITRATE_LOWER_P95_LOSS to listOf(p95, loss, kbit(LOW_BITRATE))

                    p95 != null -> TuneReason.BITRATE_LOWER_P95 to listOf(p95, kbit(LOW_BITRATE))

                    else -> TuneReason.BITRATE_LOWER_LOSS to listOf(requireNotNull(loss), kbit(LOW_BITRATE))
                }
                changes += TuneChange(
                    TuneKey.MAX_BITRATE,
                    from = settings.maxBitrate.toString(),
                    to = LOW_BITRATE.toString(),
                    reason = reason,
                    reasonArgs = args
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
                    reason = TuneReason.BITRATE_RICHER,
                    reasonArgs = listOf(
                        milliseconds(p95 ?: 0),
                        kbit(throughput.toInt()),
                        kbit(RICH_BITRATE)
                    )
                )
            }
        }

        val cellular = observation.network?.lowercase()?.let { it in setOf("cellular", "lte", "5g", "nr") } == true
        if (cellular && (test?.loss ?: 0.0) > 0.0 && settings.preferredCodec != PhoneCodec.OPUS) {
            changes += TuneChange(
                TuneKey.CODEC,
                from = settings.preferredCodec,
                to = PhoneCodec.OPUS,
                reason = TuneReason.CODEC_CELLULAR,
                reasonArgs = listOf(kbit(PCM_BITRATE))
            )
        }

        if (observation.path == PathKind.RELAY && (observation.relayForMs ?: 0L) >= RELAY_WARNING_MS) {
            suggestions += UiText.Res(
                R.string.settings_suggestion_relay_only,
                listOf(seconds((observation.relayForMs ?: 0L) / 1000))
            )
        }
        if (observation.missingPermissions.any { it.contains("LOCAL_NETWORK") }) {
            suggestions += UiText.Res(R.string.settings_suggestion_local_network)
        }
        return TuneAction(changes = changes, suggestions = suggestions)
    }

    private fun milliseconds(value: Long): String = "${number(value)}\u00A0ms"

    private fun kbit(bitsPerSecond: Int): String = "${number((bitsPerSecond / 1000).toLong())}\u00A0kbit/s"

    private fun seconds(value: Long): String = "${number(value)}\u00A0s"

    private fun percent(fraction: Double): String = NumberFormat.getPercentInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(fraction)

    private fun number(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    /** PCM on a narrow-band call: 8 kHz × 16 bit, the figure the cellular rule cites. */
    private const val PCM_BITRATE = 768_000
}
