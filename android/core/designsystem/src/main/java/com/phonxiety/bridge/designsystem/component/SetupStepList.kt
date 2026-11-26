package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
                modifier = Modifier.semantics(mergeDescendants = true) {
                    stateDescription = stateLabel
                }
            ) {
                StepIndicator(state = step.state, showConnector = index < steps.lastIndex)
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
            if (index < steps.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun StepIndicator(state: SetupStepState, showConnector: Boolean) {
    val status = LocalStatusColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}
