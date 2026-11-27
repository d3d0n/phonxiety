package com.phonxiety.bridge.feature.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.LinkTestResult
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@Composable
internal fun pathLabel(key: String): String = when (key) {
    "iroh" -> stringResource(R.string.lab_path_iroh)
    "lan" -> stringResource(R.string.lab_path_lan)
    "tailscale" -> stringResource(R.string.lab_path_tailscale)
    else -> humanize(key)
}

@Composable
internal fun transportLabel(transport: String): String = when (transport.lowercase(Locale.US)) {
    "stream" -> stringResource(R.string.lab_transport_stream)
    "datagram" -> stringResource(R.string.lab_transport_datagram)
    else -> humanize(transport)
}

@Composable
internal fun logLevelLabel(level: String): String = when (level.lowercase(Locale.US)) {
    "debug" -> stringResource(R.string.lab_level_debug)
    "info" -> stringResource(R.string.lab_level_info)
    "warn" -> stringResource(R.string.lab_level_warn)
    "error" -> stringResource(R.string.lab_level_error)
    else -> humanize(level)
}

@Composable
internal fun codecLabel(codec: String?): String = when (codec?.lowercase(Locale.US)) {
    null -> stringResource(R.string.lab_codec_auto)
    "opus" -> stringResource(R.string.lab_codec_opus)
    "pcm" -> stringResource(R.string.lab_codec_pcm)
    else -> humanize(codec)
}

@Composable
internal fun codecLabel(codec: String, bitrate: Int?): String = if (codec == "opus" && bitrate != null) {
    stringResource(R.string.lab_codec_opus_rate, numberText(bitrate / 1_000))
} else {
    codecLabel(codec)
}

@Composable
internal fun verdictLabel(verdict: String?, healthy: Boolean): String = when {
    verdict == "good" || (verdict == null && healthy) -> stringResource(R.string.lab_verdict_good)
    verdict == "poor" -> stringResource(R.string.lab_verdict_poor)
    verdict == "failed" -> stringResource(R.string.lab_verdict_failed)
    verdict != null -> humanize(verdict)
    else -> stringResource(R.string.lab_none)
}

@Composable
internal fun verdictLabel(verdict: ProbeVerdict): String = when (verdict) {
    ProbeVerdict.REACHED -> stringResource(R.string.lab_probe_reached)
    ProbeVerdict.NOT_REACHED -> stringResource(R.string.lab_probe_not_reached)
    ProbeVerdict.NOT_CHECKED -> stringResource(R.string.lab_probe_not_checked)
}

internal fun verdictTone(verdict: ProbeVerdict): StatusTone = when (verdict) {
    ProbeVerdict.REACHED -> StatusTone.POSITIVE
    ProbeVerdict.NOT_REACHED -> StatusTone.WARNING
    ProbeVerdict.NOT_CHECKED -> StatusTone.NEUTRAL
}

internal fun LinkTestResult.tone(): StatusTone = when {
    verdict == "good" || healthy -> StatusTone.POSITIVE
    verdict == "failed" -> StatusTone.NEGATIVE
    verdict == "poor" -> StatusTone.WARNING
    else -> StatusTone.NEUTRAL
}

internal fun LinkTestResult.p95Tone(): StatTone {
    val value = p95 ?: return StatTone.NEUTRAL
    return when {
        value > 400 -> StatTone.NEGATIVE
        value > 200 -> StatTone.WARNING
        else -> StatTone.POSITIVE
    }
}

/** A latency in a monospace slot: a narrow no-break space keeps the unit close to the digits. */
internal fun monoMillis(millis: Long): String = "$millis\u202Fms"

/** `32\u00A0kbit/s`; one decimal below 10 so a 1 500 bit/s run is not reported as 1 kbit/s. */
internal fun rateLabel(bps: Long): String {
    val kbit = bps / 1_000.0
    val value = if (kbit >= 10) {
        kbit.roundToLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", kbit)
    }
    return "$value\u202Fkbit/s"
}

/** A fraction as the locale writes percentages; a missing measurement never becomes 0 %. */
internal fun percentText(fraction: Double): String {
    val format = NumberFormat.getPercentInstance()
    format.minimumFractionDigits = 1
    format.maximumFractionDigits = 1
    return format.format(fraction)
}

/** A number in the reader's own grouping, so 1 000 and 1,000 are both natural. */
internal fun numberText(value: Number): String {
    val text = NumberFormat.getIntegerInstance().format(value.toLong())
    return text.replace('-', '\u2212')
}

/** The clock that decides whether a stamp is from today; screenshot tests pin it. */
internal val LocalLabClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/**
 * Daemon timestamps are UTC ISO-8601; the lab is read on this phone, so a run from today shows its
 * time and an older one shows its date. A stamp without an offset is already local wall-clock (old
 * records and previews) and is kept.
 */
internal fun localTime(at: String?, nowMillis: Long = System.currentTimeMillis()): String? {
    if (at == null) return null
    val zone = ZoneId.systemDefault()
    val millis = (
        runCatching { OffsetDateTime.parse(at) }.getOrNull()?.toInstant()
            ?: runCatching { LocalDateTime.parse(at).atZone(zone).toInstant() }.getOrNull()
        )?.toEpochMilli()
        ?: return at.replace('T', ' ').take(19)
    val format = if (isSameDay(millis, nowMillis, zone)) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(millis))
}

private fun isSameDay(first: Long, second: Long, zone: ZoneId): Boolean {
    val left = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
    val right = Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
    return left == right
}
