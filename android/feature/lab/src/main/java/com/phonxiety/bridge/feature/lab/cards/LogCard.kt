package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.LogUi
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.logLevelLabel

/** The filter narrows what is already read; Done applies it and puts the keyboard away. */
internal val LOG_FILTER_KEYBOARD = KeyboardOptions(imeAction = ImeAction.Done)

private const val LOG_MAX_LINES = 500

@Composable
internal fun LogCard(logs: LogUi, callbacks: LabCallbacks) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    SectionCard(
        title = stringResource(R.string.lab_log_title),
        subtitle = stringResource(R.string.lab_log_subtitle)
    ) {
        OutlinedTextField(
            value = logs.filter,
            onValueChange = callbacks.onLogFilter,
            label = { Text(stringResource(R.string.lab_log_filter)) },
            singleLine = true,
            keyboardOptions = LOG_FILTER_KEYBOARD,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        WrapRow {
            PhoneSettings.LOG_LEVELS.forEach { level ->
                val selected = level == logs.level
                FilterChip(
                    selected = selected,
                    onClick = { callbacks.onLogLevel(level) },
                    label = { Text(logLevelLabel(level)) },
                    leadingIcon = { SelectedCheck(selected) }
                )
            }
        }
        WrapRow {
            TextButton(onClick = callbacks.onCopyLogs, enabled = logs.lines.isNotEmpty()) {
                Text(stringResource(R.string.lab_log_copy))
            }
            TextButton(onClick = callbacks.onExportLogs, enabled = logs.lines.isNotEmpty() && !logs.exporting) {
                Text(stringResource(if (logs.exporting) R.string.lab_log_writing else R.string.lab_log_export))
            }
            Text(
                text = pluralStringResource(R.plurals.lab_log_lines, logs.lines.size, logs.lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val lines = logs.lines.takeLast(if (expanded) LOG_MAX_LINES else PREVIEW_LINES)
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.lab_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            PreviewText(
                lines = lines,
                expanded = expanded,
                more = logs.lines.size > PREVIEW_LINES,
                onToggle = { expanded = !expanded }
            )
        }
    }
}
