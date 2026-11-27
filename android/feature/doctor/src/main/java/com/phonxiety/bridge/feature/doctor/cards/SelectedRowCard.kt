package com.phonxiety.bridge.feature.doctor.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.doctor.DoctorCallbacks
import com.phonxiety.bridge.feature.doctor.DoctorPolicy
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.FixClass
import com.phonxiety.bridge.feature.doctor.R

@Composable
internal fun SelectedRowCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    val row = state.row(state.selected)
    if (row == null) {
        SectionCard(title = stringResource(R.string.doctor_card_summary)) {
            EmptyState(
                title = stringResource(R.string.doctor_select_check_title),
                message = stringResource(R.string.doctor_select_check_message)
            )
        }
        return
    }
    val risk = row.plan?.let { DoctorPolicy.effectiveRisk(it, state.callActive) }
    val checkName = row.title.asString()
    SectionCard(
        title = checkName,
        status = { SeverityBadge(row.severity) }
    ) {
        SummaryText(row)
        RowDetails(row)
        val plan = row.plan
        if (plan != null) {
            Text(text = plan.effect.asString(), style = MaterialTheme.typography.bodyMedium)
            if (plan.needsReboot) {
                RebootNotice()
            }
            Text(
                text = stringResource(R.string.doctor_rollback_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = plan.rollback.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val label = stringResource(plan.fix.labelRes)
            TextButton(
                onClick = { callbacks.onFix(row.id) },
                enabled = state.running == null && risk != FixClass.NEVER,
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
            if (risk == FixClass.NEVER) {
                Text(
                    text = stringResource(R.string.doctor_blocked_in_call),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DoctorPolicy.recoveryFor(row.kind)?.let { recovery ->
            val label = stringResource(R.string.doctor_open_recovery)
            TextButton(
                onClick = { callbacks.onOpenRecovery(recovery) },
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
        }
    }
}

@Composable
internal fun RebootNotice() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = LocalStatusColors.current.warning.content,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = stringResource(R.string.doctor_reboot_notice),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
