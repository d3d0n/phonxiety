package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.LocalLabClock
import com.phonxiety.bridge.feature.lab.ProbeRowUi
import com.phonxiety.bridge.feature.lab.ProbeUi
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.localTime
import com.phonxiety.bridge.feature.lab.pathLabel
import com.phonxiety.bridge.feature.lab.verdictLabel
import com.phonxiety.bridge.feature.lab.verdictTone

/** The paths a probe can ask about, in the order the daemon lists them. */
internal val PROBE_PATHS = listOf("iroh", "lan", "tailscale")

@Composable
internal fun ProbeCard(probe: ProbeUi, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_probe_title),
        subtitle = stringResource(R.string.lab_probe_subtitle)
    ) {
        Text(
            text = stringResource(R.string.lab_probe_paths),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WrapRow {
            PROBE_PATHS.forEach { name ->
                val selected = name in probe.selected
                FilterChip(
                    selected = selected,
                    onClick = { callbacks.onToggleProbeCandidate(name) },
                    label = { Text(pathLabel(name)) },
                    leadingIcon = { SelectedCheck(selected) }
                )
            }
        }
        PrimaryActionButton(
            label = stringResource(if (probe.running) R.string.lab_probe_running else R.string.lab_probe_run),
            onClick = callbacks.onRunProbe,
            enabled = !probe.running && probe.selected.isNotEmpty(),
            busy = probe.running
        )
        if (probe.running) {
            Text(
                text = stringResource(R.string.lab_probe_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        probe.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_probe_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        probe.rows.forEach { row -> ProbeRow(row) }
        probe.checkedAt?.let {
            Text(
                text = stringResource(R.string.lab_probe_checked, localTime(it, LocalLabClock.current()) ?: it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A path's own verdict, its endpoint, and — separately — why it was or was not checked. */
@Composable
private fun ProbeRow(row: ProbeRowUi) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pathLabel(row.key), style = MaterialTheme.typography.bodyMedium)
                row.endpoint?.let {
                    Text(
                        text = it,
                        style = codeStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(label = verdictLabel(row.verdict), tone = verdictTone(row.verdict))
        }
        row.note?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        row.listenerNote?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        row.details?.let { details ->
            var open by rememberSaveable(row.key) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.lab_probe_details_hide else R.string.lab_probe_details_show))
            }
            if (open) {
                Text(
                    text = details,
                    style = codeStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
