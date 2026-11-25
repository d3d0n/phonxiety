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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
 * happening, and what is left. Connectors stay grey so only the current step draws attention.
 */
@Composable
fun SetupStepList(steps: List<SetupStep>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            SetupStepState.ACTIVE -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )

            else -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(color = indicatorColor(state), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (state == SetupStepState.DONE) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.onTertiary, CircleShape)
                    )
                }
            }
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

@Composable
private fun indicatorColor(state: SetupStepState): Color = when (state) {
    SetupStepState.DONE -> MaterialTheme.colorScheme.tertiary
    SetupStepState.FAILED -> MaterialTheme.colorScheme.error
    SetupStepState.ACTIVE -> MaterialTheme.colorScheme.primary
    SetupStepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
}
