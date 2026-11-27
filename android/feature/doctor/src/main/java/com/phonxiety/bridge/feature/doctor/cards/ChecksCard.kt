package com.phonxiety.bridge.feature.doctor.cards

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.STACK_AT_FONT_SCALE
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.doctor.DoctorCallbacks
import com.phonxiety.bridge.feature.doctor.DoctorGroup
import com.phonxiety.bridge.feature.doctor.DoctorPolicy
import com.phonxiety.bridge.feature.doctor.DoctorRow
import com.phonxiety.bridge.feature.doctor.DoctorSeverity
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.FixClass
import com.phonxiety.bridge.feature.doctor.R

@Composable
internal fun CheckGroups(state: DoctorUiState, callbacks: DoctorCallbacks) {
    DoctorGroup.entries.forEach { group ->
        val rows = state.rowsOf(group)
        if (rows.isEmpty()) return@forEach
        SectionCard(title = stringResource(group.titleRes)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row -> CheckRow(row, state, callbacks) }
            }
        }
    }
}

@Composable
private fun CheckRow(row: DoctorRow, state: DoctorUiState, callbacks: DoctorCallbacks) {
    val selected = state.selected == row.id
    val risk = row.plan?.let { DoctorPolicy.effectiveRisk(it, state.callActive) }
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.Button,
                onClick = { callbacks.onSelect(row.id) }
            )
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title.asString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            // Beside a 200 % title the badge leaves so little width that the title breaks
            // mid-word ("reachab|le"), so at that scale the badge drops under the title instead.
            if (!stacked) {
                SeverityBadge(row.severity)
                RowChevron()
            }
        }
        if (stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityBadge(row.severity)
                RowChevron()
            }
        }
        SummaryText(row)
        RowDetails(row)
        if (row.needsFix && !selected) {
            RowActionButtons(row, risk, state, callbacks)
        }
    }
}

/** The row opens its detail card; the chevron says so, and carries no text of its own. */
@Composable
private fun RowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 4.dp)
            .size(20.dp)
    )
}

/** A row's actions carry the check name, so two identical labels never read the same. */
@Composable
private fun RowActionButtons(
    row: DoctorRow,
    risk: FixClass?,
    state: DoctorUiState,
    callbacks: DoctorCallbacks
) {
    val checkName = row.title.asString()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.plan != null && risk != null) {
            val label = stringResource(row.plan.fix.labelRes)
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
        val recovery = DoctorPolicy.recoveryFor(row.kind)
        if (recovery != null) {
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

/** The one plain line a row reads: authored words, never a raw daemon value. */
@Composable
internal fun SummaryText(row: DoctorRow) {
    if (row.summary.isEmpty() && row.summaryAtEpochMs == null) return
    val parts = ArrayList<String>(row.summary.size + 1)
    for (part in row.summary) parts += part.asString()
    val moment = row.summaryAtEpochMs?.let { formatMoment(it) }
    if (moment != null) parts += moment
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The daemon's own evidence, one line per record, in the code face and selectable, closed until
 * asked for: a raw code or path never sits in the reading flow.
 */
@Composable
internal fun RowDetails(row: DoctorRow) {
    if (row.details.isEmpty()) return
    var shown by rememberSaveable(row.id) { mutableStateOf(false) }
    val label = stringResource(if (shown) R.string.doctor_details_hide else R.string.doctor_details_show)
    // Twenty rows can offer Details: each one says which check it belongs to.
    val checkName = row.title.asString()
    TextButton(onClick = { shown = !shown }, modifier = Modifier.semantics { contentDescription = "$label: $checkName" }) {
        Text(label)
    }
    if (shown) {
        val lines = ArrayList<String>(row.details.size)
        for (part in row.details) lines += part.asString()
        SelectionContainer {
            Text(
                text = lines.joinToString("\n"),
                style = codeStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A moment in the phone's own locale and zone, never the daemon's ISO stamp. */
@Composable
private fun formatMoment(epochMs: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
)

/** A passing row shows a check, not a pill: twenty identical "OK" pills bury the one failure. */
@Composable
internal fun SeverityBadge(severity: DoctorSeverity) {
    when (severity) {
        DoctorSeverity.OK -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = LocalStatusColors.current.positiveIndicator,
            modifier = Modifier.size(20.dp)
        )

        DoctorSeverity.WARN -> StatusPill(
            label = stringResource(R.string.doctor_severity_warning),
            tone = StatusTone.WARNING
        )

        DoctorSeverity.FAIL -> StatusPill(
            label = stringResource(R.string.doctor_severity_failed),
            tone = StatusTone.NEGATIVE
        )

        DoctorSeverity.UNKNOWN -> StatusPill(
            label = stringResource(R.string.doctor_severity_unknown),
            tone = StatusTone.NEUTRAL
        )
    }
}
