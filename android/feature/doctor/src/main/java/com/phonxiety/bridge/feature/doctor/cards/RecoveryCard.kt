package com.phonxiety.bridge.feature.doctor.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.doctor.DoctorCallbacks
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.R
import com.phonxiety.bridge.feature.doctor.RecoveryBook
import com.phonxiety.bridge.feature.doctor.RecoveryId
import com.phonxiety.bridge.feature.doctor.RecoveryPage
import com.phonxiety.bridge.feature.doctor.RecoveryStep
import com.phonxiety.bridge.feature.doctor.doctorTitle

@Composable
internal fun GuidedRecoveryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    val provider = state.provider
    SectionCard(
        title = stringResource(R.string.doctor_card_recovery),
        subtitle = if (provider == null) {
            stringResource(R.string.doctor_recovery_subtitle_unknown)
        } else {
            stringResource(R.string.doctor_recovery_subtitle, provider.doctorTitle())
        }
    ) {
        RecoveryId.entries.forEach { id ->
            val page = remember(id, provider) { RecoveryBook.page(id, provider) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { callbacks.onOpenRecovery(id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = page.title.asString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RecoveryContent(page: RecoveryPage, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.doctor_recovery_what_happened)) {
            Text(
                text = page.happened.asString(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SectionCard(title = stringResource(R.string.doctor_recovery_on_phone)) {
            page.onPhone.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
        SectionCard(title = stringResource(R.string.doctor_recovery_from_computer)) {
            page.fromComputer.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun StepLine(index: Int, step: RecoveryStep, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$index. ${step.text.asString()}",
            style = MaterialTheme.typography.bodyMedium
        )
        step.command?.let { command ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command,
                        style = codeStyle()
                    )
                }
                val copyLabel = stringResource(R.string.doctor_recovery_copy)
                TextButton(
                    onClick = { onCopy(command) },
                    modifier = Modifier.semantics { contentDescription = "$copyLabel: $command" }
                ) {
                    Text(copyLabel)
                }
            }
        }
    }
}
