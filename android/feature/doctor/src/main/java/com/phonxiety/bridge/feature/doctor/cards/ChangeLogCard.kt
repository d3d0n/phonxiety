package com.phonxiety.bridge.feature.doctor.cards

import android.text.format.DateFormat
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.doctor.DoctorChangeEntry
import com.phonxiety.bridge.feature.doctor.DoctorLogEvent
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.R
import java.util.Date

private const val CHANGE_LOG_ROWS = 6

@Composable
internal fun ChangeLogCard(state: DoctorUiState) {
    SectionCard(
        title = stringResource(R.string.doctor_card_changes),
        subtitle = stringResource(R.string.doctor_changes_subtitle)
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_loading_report))

            state.changeLog.isEmpty() -> Text(
                text = stringResource(R.string.doctor_changes_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> {
                val context = LocalContext.current
                val time = remember(context) { DateFormat.getTimeFormat(context) }
                state.changeLog.takeLast(CHANGE_LOG_ROWS).reversed().forEach { entry ->
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.doctor_change_line,
                                time.format(Date(entry.atEpochMs)),
                                entry.text()
                            ),
                            style = codeStyle()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorChangeEntry.text(): String = when (event) {
    DoctorLogEvent.BRIDGE_RESTARTED -> stringResource(R.string.doctor_log_bridge_restarted)

    DoctorLogEvent.PERMISSION_ASKED -> stringResource(R.string.doctor_log_permission_asked, *args.toTypedArray())

    DoctorLogEvent.NOTIFICATIONS_ASKED -> stringResource(R.string.doctor_log_notifications_asked)

    DoctorLogEvent.BACKGROUND_ASKED -> stringResource(R.string.doctor_log_background_asked)

    DoctorLogEvent.MODULE_ENABLED -> stringResource(R.string.doctor_log_module_enabled)

    DoctorLogEvent.MODULE_ENABLED_OUTPUT ->
        stringResource(R.string.doctor_log_module_enabled_output, *args.toTypedArray())

    DoctorLogEvent.MODULE_REINSTALLED -> stringResource(R.string.doctor_log_module_reinstalled)

    DoctorLogEvent.MODULE_REINSTALLED_OUTPUT ->
        stringResource(R.string.doctor_log_module_reinstalled_output, *args.toTypedArray())

    DoctorLogEvent.REBOOT_REQUESTED -> stringResource(R.string.doctor_log_reboot_requested)

    DoctorLogEvent.REBOOTED -> stringResource(R.string.doctor_log_rebooted)

    DoctorLogEvent.FIX_FAILED -> if (args.firstOrNull().isNullOrBlank()) {
        stringResource(R.string.doctor_log_fix_failed_no_output)
    } else {
        stringResource(R.string.doctor_log_fix_failed, args.first())
    }

    // A reboot request can fail before the phone prints anything: say that in words rather than
    // rendering a sentence with a hole in it.
    DoctorLogEvent.REBOOT_FAILED -> if (args.firstOrNull().isNullOrBlank()) {
        stringResource(R.string.doctor_log_reboot_failed_no_output)
    } else {
        stringResource(R.string.doctor_log_reboot_failed, args.first())
    }

    DoctorLogEvent.PAIRING_RECOVERY_OPENED -> stringResource(R.string.doctor_log_pairing_opened)

    DoctorLogEvent.DAEMON_FIX_APPLIED -> stringResource(R.string.doctor_log_daemon_fix, *args.toTypedArray())

    DoctorLogEvent.DAEMON_FIX_APPLIED_MORE ->
        stringResource(R.string.doctor_log_daemon_fix_more, *args.toTypedArray())
}
