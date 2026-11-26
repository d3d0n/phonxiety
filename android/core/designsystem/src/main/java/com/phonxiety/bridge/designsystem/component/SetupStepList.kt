package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.R

/** Status of one wizard step in a [SetupStepList]. */
enum class SetupStepState { DONE, ACTIVE, PENDING, FAILED }

/** One line of the setup story, e.g. "Detect root", "Install module", "Reboot". */
data class SetupStep(
    val title: String,
    val state: SetupStepState,
    val detail: String? = null
)

/**
 * The vertical step list the wizard shows above its single action: what already happened, what is
 * happening, and what is left. Each row carries its state as text, not only as a coloured shape.
 */
@Composable
fun SetupStepList(steps: List<SetupStep>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, step ->
            val stateLabel = stringResource(
                when (step.state) {
                    SetupStepState.DONE -> R.string.designsystem_step_done
                    SetupStepState.ACTIVE -> R.string.designsystem_step_in_progress
                    SetupStepState.FAILED -> R.string.designsystem_step_failed
                    SetupStepState.PENDING -> R.string.designsystem_step_not_started
                }
            )
            Row(
                verticalAlignment = Alignment.Top,
                // The row is exactly as tall as its text, so the connector spans it: a two-line
                // title or detail at 200 % no longer ends the line above the next indicator.
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) {
                        stateDescription = stateLabel
                    }
            ) {
                StepIndicator(
                    state = step.state,
                    showConnector = index < steps.lastIndex,
                    modifier = Modifier.fillMaxHeight()
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when (step.state) {
                            SetupStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            SetupStepState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (step.detail != null) {
                        Text(
                            text = step.detail,
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
private fun StepIndicator(state: SetupStepState, showConnector: Boolean, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            SetupStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )

            SetupStepState.DONE -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(status.positive.container, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = status.positive.content,
                    modifier = Modifier.size(12.dp)
                )
            }

            SetupStepState.FAILED -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(status.negative.container, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = status.negative.content,
                    modifier = Modifier.size(12.dp)
                )
            }

            SetupStepState.PENDING -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
        if (showConnector) {
            // The row's remaining height, so the line always ends at the next indicator. The
            // 2 dp foot is the breathing room the old between-rows spacer gave; weighting cannot
            // draw through space outside the row, so the gap moves inside the line.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(2.dp)
                    .padding(bottom = 2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}
