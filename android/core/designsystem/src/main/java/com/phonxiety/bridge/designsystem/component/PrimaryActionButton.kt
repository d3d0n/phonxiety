package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.R

/** Visual weight of an action. One primary action per screen; everything else steps down. */
enum class ActionEmphasis { PRIMARY, TONAL, OUTLINED, TEXT }

/**
 * The one primary action of a screen, or a lower-emphasis action when [emphasis] says so. [busy]
 * swaps the label for a spinner and disables the button; [progress] (0..1) adds a determinate bar
 * under it for installs and link tests.
 */
@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    progress: Float? = null,
    supportingText: String? = null,
    emphasis: ActionEmphasis = ActionEmphasis.PRIMARY
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val buttonModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
        val clickable = enabled && !busy
        val workingLabel = stringResource(R.string.designsystem_action_working)
        val busyState = if (busy) {
            Modifier.semantics { stateDescription = workingLabel }
        } else {
            Modifier
        }
        val content: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        val scheme = MaterialTheme.colorScheme
        val disabledContainer = scheme.surfaceContainerHighest
        val disabledContent = scheme.onSurfaceVariant
        when (emphasis) {
            ActionEmphasis.PRIMARY -> Button(
                onClick = onClick,
                enabled = clickable,
                modifier = buttonModifier.then(busyState),
                colors = if (busy) {
                    ButtonDefaults.buttonColors(
                        disabledContainerColor = scheme.secondaryContainer,
                        disabledContentColor = scheme.onSecondaryContainer
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        disabledContainerColor = disabledContainer,
                        disabledContentColor = disabledContent
                    )
                },
                content = { content() }
            )

            ActionEmphasis.TONAL -> FilledTonalButton(
                onClick = onClick,
                enabled = clickable,
                modifier = buttonModifier.then(busyState),
                colors = ButtonDefaults.filledTonalButtonColors(
                    disabledContainerColor = disabledContainer,
                    disabledContentColor = disabledContent
                ),
                content = { content() }
            )

            ActionEmphasis.OUTLINED -> OutlinedButton(
                onClick = onClick,
                enabled = clickable,
                modifier = buttonModifier.then(busyState),
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContentColor = disabledContent
                ),
                content = { content() }
            )

            ActionEmphasis.TEXT -> TextButton(
                onClick = onClick,
                enabled = clickable,
                modifier = buttonModifier.then(busyState),
                colors = ButtonDefaults.textButtonColors(
                    disabledContentColor = disabledContent
                ),
                content = { content() }
            )
        }
        if (progress != null) {
            val fraction = progress.coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            )
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
