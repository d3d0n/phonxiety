package com.phonxiety.bridge.designsystem

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

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
