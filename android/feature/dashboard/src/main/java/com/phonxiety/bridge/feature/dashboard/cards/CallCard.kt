package com.phonxiety.bridge.feature.dashboard.cards

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.util.Date

/**
 * The call in progress or the last one, then one primary action: open it. An ended call keeps its
 * numbers for review but carries no filled button.
 */
@Composable
internal fun CallCard(call: CallState?, nowEpochMs: Long, onOpenLiveCall: (String) -> Unit) {
    val context = LocalContext.current
    // While a call is live the header pill already names its phase; the card does not repeat it.
    val status: (@Composable RowScope.() -> Unit)? = when {
        call == null -> {
            {
                StatusPill(
                    label = stringResource(R.string.dashboard_call_idle),
                    tone = StatusTone.NEUTRAL
                )
            }
        }

        !call.isLive() -> {
            {
                StatusPill(
                    label = phaseLabel(call.phase),
                    tone = phaseTone(call.phase)
                )
            }
        }

        else -> null
    }
    SectionCard(
        title = stringResource(R.string.dashboard_call_title),
        status = status
    ) {
        if (call == null) {
            Text(
                text = stringResource(R.string.dashboard_call_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val timing = callTiming(call)
            TileRow {
                StatTile(
                    label = stringResource(R.string.dashboard_destination),
                    value = formatPhoneNumber(call.destinationMasked),
                    mono = true
                )
                StatTile(
                    label = stringResource(timing.labelRes),
                    value = timing.elapsedStartEpochMs
                        ?.let { formatElapsedTime(secondsBetween(it, timing.elapsedEndEpochMs ?: nowEpochMs)) }
                        ?: MISSING_VALUE,
                    mono = true
                )
                StatTile(
                    label = stringResource(R.string.dashboard_connected_at),
                    value = timing.connectedAtEpochMs?.let { timeOf(context, it) } ?: MISSING_VALUE,
                    mono = true
                )
            }
            if (call.isLive()) {
                PrimaryActionButton(
                    label = stringResource(R.string.dashboard_open_live_call),
                    onClick = { onOpenLiveCall(call.callId) }
                )
            }
        }
    }
}

/**
 * What the call card's timing tile counts and how it names itself. The ring before the line
 * connects counts from placement and is labelled Dialing, the live screen's own word, so it never
 * reads as a call duration; the system's call chip also starts at the answer. From the connect the
 * tile is the conversation's Duration and counts from the bridge's connect stamp, and "Connected
 * at" takes the same stamp, not the placement time. Once the call has ended the count stops at the
 * bridge's end stamp, so the last call keeps the duration it ended with.
 */
internal data class CallTiming(
    val labelRes: Int,
    val elapsedStartEpochMs: Long?,
    /** Where the count stops; null while the call is still running and the card uses its clock. */
    val elapsedEndEpochMs: Long?,
    val connectedAtEpochMs: Long?
)

internal fun callTiming(call: CallState): CallTiming {
    val connected = call.connectedAtEpochMs
    return when {
        connected != null -> CallTiming(
            labelRes = R.string.dashboard_duration,
            elapsedStartEpochMs = connected,
            elapsedEndEpochMs = call.endedAtEpochMs.takeIf { call.phase == CallPhase.ENDED },
            connectedAtEpochMs = connected
        )

        call.phase == CallPhase.DIALING -> CallTiming(
            labelRes = R.string.dashboard_phase_dialing,
            elapsedStartEpochMs = call.startedAtEpochMs,
            elapsedEndEpochMs = null,
            connectedAtEpochMs = null
        )

        // Ending or ended without a connect: the line never carried a conversation, so there is
        // nothing to time and no connect to report.
        else -> CallTiming(
            labelRes = R.string.dashboard_duration,
            elapsedStartEpochMs = null,
            elapsedEndEpochMs = null,
            connectedAtEpochMs = null
        )
    }
}

@Composable
internal fun phaseLabel(phase: CallPhase): String = stringResource(
    when (phase) {
        CallPhase.DIALING -> R.string.dashboard_phase_dialing
        CallPhase.ACTIVE -> R.string.dashboard_phase_live
        CallPhase.ENDING -> R.string.dashboard_phase_ending
        CallPhase.ENDED -> R.string.dashboard_phase_ended
    }
)

internal fun phaseTone(phase: CallPhase): StatusTone = when (phase) {
    CallPhase.DIALING -> StatusTone.WARNING
    CallPhase.ACTIVE -> StatusTone.POSITIVE
    CallPhase.ENDING -> StatusTone.WARNING
    CallPhase.ENDED -> StatusTone.NEUTRAL
}

/** The device's own clock format, so 12- and 24-hour phones both read correctly. */
private fun timeOf(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))
