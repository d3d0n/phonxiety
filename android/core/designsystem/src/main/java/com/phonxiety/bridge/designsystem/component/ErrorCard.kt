package com.phonxiety.bridge.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An error with the evidence attached: what failed, what to do, and the captured shell/daemon
 * output behind a disclosure, because a failure without its output cannot be diagnosed from a
 * screenshot.
 */
@Composable
fun ErrorCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    output: String? = null,
    initiallyExpanded: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    var expanded by rememberSaveable(output) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (output != null) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "Hide output" else "Show output",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        SelectionContainer {
                            Text(
                                text = output,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
