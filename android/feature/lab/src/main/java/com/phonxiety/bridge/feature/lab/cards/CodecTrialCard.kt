package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.feature.lab.CodecTrialRun
import com.phonxiety.bridge.feature.lab.CodecTrialUi
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.codecLabel
import com.phonxiety.bridge.feature.lab.millisText
import com.phonxiety.bridge.feature.lab.percentText
import com.phonxiety.bridge.feature.lab.tone
import com.phonxiety.bridge.feature.lab.verdictLabel

@Composable
internal fun CodecTrialCard(trial: CodecTrialUi, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_trial_title),
        subtitle = stringResource(R.string.lab_trial_subtitle)
    ) {
        PrimaryActionButton(
            label = trial.step?.asString() ?: stringResource(R.string.lab_trial_run),
            onClick = callbacks.onRunCodecTrial,
            enabled = !trial.running,
            busy = trial.running
        )
        trial.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_trial_failed),
                message = failure.message.asString(),
                kind = failure.kind,
                output = failure.code,
                onRetry = callbacks.onRunCodecTrial,
                onOpenDoctor = callbacks.onOpenDoctor
            )
        }
        if (trial.runs.isNotEmpty()) {
            HorizontalDivider()
            trial.runs.forEach { run -> TrialRow(run) }
        }
        trial.recommendation?.let { best ->
            val label = codecLabel(best.codec, best.bitrate)
            Text(
                text = stringResource(R.string.lab_trial_recommendation, label),
                style = MaterialTheme.typography.titleSmall
            )
            PrimaryActionButton(
                label = stringResource(R.string.lab_trial_keep, label),
                onClick = callbacks.onApplyRecommendation,
                emphasis = ActionEmphasis.TONAL
            )
        }
    }
}

@Composable
private fun TrialRow(run: CodecTrialRun) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = codecLabel(run.codec, run.bitrate),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            StatusPill(label = verdictLabel(run.result.verdict, run.result.healthy), tone = run.result.tone())
        }
        Text(
            text = stringResource(
                R.string.lab_trial_row,
                run.result.p95?.let(::millisText) ?: stringResource(R.string.lab_none),
                run.result.loss?.let(::percentText) ?: stringResource(R.string.lab_none)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
