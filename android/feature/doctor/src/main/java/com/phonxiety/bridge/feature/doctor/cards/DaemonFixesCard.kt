package com.phonxiety.bridge.feature.doctor.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.doctor.DoctorCallbacks
import com.phonxiety.bridge.feature.doctor.DoctorPolicy
import com.phonxiety.bridge.feature.doctor.DoctorUiState
import com.phonxiety.bridge.feature.doctor.FixClass
import com.phonxiety.bridge.feature.doctor.R

@Composable
internal fun DaemonFixesCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(
        title = stringResource(R.string.doctor_card_daemon_fixes),
        subtitle = stringResource(R.string.doctor_daemon_fixes_subtitle)
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_loading_report))

            state.daemonFixes.isEmpty() -> Text(
                text = stringResource(R.string.doctor_daemon_fixes_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> state.daemonFixes.forEach { offer ->
                val risk = DoctorPolicy.effectiveRisk(offer.plan, state.callActive)
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = offer.description?.replaceFirstChar { it.uppercase() } ?: offer.fix,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        ClassPill(risk)
                    }
                    Text(
                        text = offer.fix,
                        style = codeStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val runLabel = stringResource(R.string.doctor_fix_run)
                    TextButton(
                        onClick = { callbacks.onDaemonFix(offer.fix) },
                        enabled = state.running == null && risk != FixClass.NEVER,
                        modifier = Modifier.semantics {
                            contentDescription = "$runLabel: ${offer.description ?: offer.fix}"
                        }
                    ) {
                        Text(runLabel)
                    }
                    if (risk == FixClass.NEVER) {
                        Text(
                            text = stringResource(R.string.doctor_blocked_in_call),
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
internal fun ClassPill(risk: FixClass) {
    val (label, tone) = when (risk) {
        FixClass.AUTOMATIC -> R.string.doctor_risk_automatic to StatusTone.NEUTRAL

        // Asking first is how a careful fix behaves, not a warning: the tone says so.
        FixClass.CONFIRM -> R.string.doctor_risk_confirm to StatusTone.NEUTRAL

        FixClass.NEVER -> R.string.doctor_risk_never to StatusTone.NEGATIVE
    }
    StatusPill(label = stringResource(label), tone = tone)
}
