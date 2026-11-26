package com.phonxiety.bridge.designsystem

import android.content.res.Resources
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * The E.164 number as a person reads it, keeping the country: `+998 90 123 45 67`.
 *
 * The country comes from the number's own `+` prefix, so a call to another country still shows its
 * own calling code; the phone's locale only supplies a region for a number that has none. The
 * leading `+` is part of the number: the platform may drop it when it formats nationally. A number
 * the platform cannot parse, or a service short number with no country, is returned as typed.
 *
 * Some platforms (Android under a Japanese or Korean locale) format an already international number
 * in the national style, replacing its country code with a domestic prefix; a formatter may not
 * rewrite the number a person will dial, so the digits must survive or the input is returned.
 */
fun formatPhoneNumber(e164: String): String {
    val trimmed = e164.trim()
    if (!trimmed.startsWith("+")) return trimmed
    val formatted = runCatching {
        PhoneNumberUtils.formatNumber(trimmed, Locale.getDefault().country)
    }.getOrNull().orEmpty()
    if (formatted.isBlank()) return trimmed
    val value = if (formatted.startsWith("+")) formatted else "+$formatted"
    return if (value.digits() == trimmed.digits()) value else trimmed
}

private fun String.digits(): String = filter { it.isDigit() }

/**
 * A running call or recording timer: `MM:SS`, or `H:MM:SS` past an hour, from the platform
 * formatter so the digits match every other clock on the phone.
 */
fun formatElapsedTime(seconds: Long): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

/**
 * A duration read as prose: `45 s`, `4 min`, `2 h 05 min`. Units are joined with a no-break space
 * through the resources so they never wrap away from the number.
 */
fun formatDuration(resources: Resources, seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return when {
        total < SECONDS_PER_MINUTE -> resources.getString(R.string.designsystem_duration_seconds, total)

        total < SECONDS_PER_HOUR -> resources.getQuantityString(
            R.plurals.designsystem_duration_minutes,
            (total / SECONDS_PER_MINUTE).toInt(),
            (total / SECONDS_PER_MINUTE).toInt()
        )

        else -> resources.getString(
            R.string.designsystem_duration_hours_minutes,
            total / SECONDS_PER_HOUR,
            (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        )
    }
}

/** The composable form of [formatDuration]; re-reads the resources on a locale change. */
@Composable
fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return when {
        total < SECONDS_PER_MINUTE -> stringResource(R.string.designsystem_duration_seconds, total)

        total < SECONDS_PER_HOUR -> pluralStringResource(
            R.plurals.designsystem_duration_minutes,
            (total / SECONDS_PER_MINUTE).toInt(),
            (total / SECONDS_PER_MINUTE).toInt()
        )

        else -> stringResource(
            R.string.designsystem_duration_hours_minutes,
            total / SECONDS_PER_HOUR,
            (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        )
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
