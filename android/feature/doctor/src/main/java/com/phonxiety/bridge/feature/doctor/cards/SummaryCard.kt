package com.phonxiety.bridge.feature.doctor.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.doctor.DoctorCallbacks
import com.phonxiety.bridge.feature.doctor.DoctorSeverity
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.R

@Composable
internal fun SummaryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(
        title = stringResource(R.string.doctor_card_summary),
        subtitle = summarySubtitle(state),
        status = { OverallPill(state) }
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_summary_reading))

            state.readError != null -> ErrorCard(
                title = stringResource(R.string.doctor_read_error_title),
                message = stringResource(R.string.doctor_read_error_message),
                output = requireNotNull(state.readError).asString(),
                actionLabel = stringResource(R.string.doctor_action_run_checks),
                onAction = callbacks.onRefresh
            )

            else -> {
                Text(
                    text = summaryLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = callbacks.onRefresh, enabled = state.running == null) {
                        Text(stringResource(R.string.doctor_action_run_checks))
                    }
                    if (state.running != null) {
                        Text(
                            text = stringResource(
                                R.string.doctor_running,
                                stringResource(state.running.labelRes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun OverallPill(state: DoctorUiState) {
    when {
        state.checking -> StatusPill(
            label = stringResource(R.string.doctor_status_reading),
            tone = StatusTone.NEUTRAL,
            pulse = true
        )

        state.readError != null -> StatusPill(
            label = stringResource(R.string.doctor_status_read_failed),
            tone = StatusTone.NEGATIVE
        )

        state.rows.isEmpty() || state.overall == DoctorSeverity.UNKNOWN -> StatusPill(
            label = stringResource(R.string.doctor_status_inconclusive),
            tone = StatusTone.NEUTRAL
        )

        state.overall == DoctorSeverity.OK -> StatusPill(
            label = stringResource(R.string.doctor_status_all_pass),
            tone = StatusTone.POSITIVE
        )

        else -> StatusPill(
            label = pluralStringResource(
                R.plurals.doctor_status_needs_fix,
                state.failing.size,
                state.failing.size
            ),
            tone = if (state.overall == DoctorSeverity.FAIL) StatusTone.NEGATIVE else StatusTone.WARNING
        )
    }
}

@Composable
private fun summarySubtitle(state: DoctorUiState): String = when {
    state.checking -> stringResource(R.string.doctor_status_reading)

    state.readError != null -> stringResource(R.string.doctor_status_read_failed)

    state.rows.isEmpty() -> stringResource(R.string.doctor_status_not_checked)

    state.failing.isEmpty() -> pluralStringResource(
        R.plurals.doctor_summary_all_pass,
        state.rows.size,
        state.rows.size
    )

    else -> pluralStringResource(
        R.plurals.doctor_summary_passed,
        state.passed,
        state.passed,
        state.rows.size
    )
}

@Composable
private fun summaryLine(state: DoctorUiState): String = when {
    state.failing.isEmpty() && state.overall == DoctorSeverity.OK ->
        stringResource(R.string.doctor_summary_line_all_pass)

    state.overall == DoctorSeverity.UNKNOWN -> stringResource(R.string.doctor_summary_line_unknown)

    state.callActive -> stringResource(R.string.doctor_summary_line_call)

    else -> pluralStringResource(
        R.plurals.doctor_summary_line_needs_fix,
        state.failing.size,
        state.failing.size
    )
}
