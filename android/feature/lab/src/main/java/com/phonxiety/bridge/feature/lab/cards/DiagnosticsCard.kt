package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.lab.DiagnosticsUi
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.R

@Composable
internal fun DiagnosticsCard(diagnostics: DiagnosticsUi, callbacks: LabCallbacks) {
    var expanded by rememberSaveable(diagnostics.payload) { mutableStateOf(false) }
    SectionCard(
        title = stringResource(R.string.lab_diagnostics_title),
        subtitle = stringResource(R.string.lab_diagnostics_subtitle)
    ) {
        WrapRow {
            TextButton(onClick = callbacks.onLoadDiagnostics, enabled = !diagnostics.loading) {
                Text(
                    stringResource(
                        if (diagnostics.loading) R.string.lab_diagnostics_loading else R.string.lab_diagnostics_load
                    )
                )
            }
            TextButton(onClick = callbacks.onExportBundle, enabled = !diagnostics.exporting) {
                Text(
                    stringResource(
                        if (diagnostics.exporting) R.string.lab_diagnostics_building else R.string.lab_diagnostics_export
                    )
                )
            }
        }
        diagnostics.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_diagnostics_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        diagnostics.bundleId?.let {
            Text(
                text = stringResource(R.string.lab_diagnostics_bundle, it),
                style = MaterialTheme.typography.bodySmall
            )
        }
        diagnostics.payload?.let { payload ->
            val lines = payload.lines()
            PreviewText(
                lines = if (expanded) lines.take(MAX_PREVIEW_LINES) else lines.take(PREVIEW_LINES),
                expanded = expanded,
                more = lines.size > PREVIEW_LINES,
                onToggle = { expanded = !expanded }
            )
        }
    }
}
