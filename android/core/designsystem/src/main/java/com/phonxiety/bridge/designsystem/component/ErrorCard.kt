package com.phonxiety.bridge.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.R
import com.phonxiety.bridge.model.FailureKind

/**
 * A failure with its evidence attached: what failed, what to do, and the captured shell/daemon
 * output behind a disclosure, because a failure without its output cannot be diagnosed from a
 * screenshot. The card sits on a neutral surface; the icon and the error semantics carry the
 * failure, so a long error page does not read as a wall of red.
 *
 * [kind] decides the action. A transport failure offers a retry; a daemon failure offers the
 * Doctor; a refused request only explains itself, because repeating it would be refused again.
 * A caller with a more specific recovery (an install retry, the phone's own call screen) passes
 * [actionLabel] and [onAction], and that action wins.
 */
@Composable
fun ErrorCard(
    title: String,
    message: String,
    kind: FailureKind,
    modifier: Modifier = Modifier,
    output: String? = null,
    initiallyExpanded: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onOpenDoctor: (() -> Unit)? = null
) {
    ErrorCardContent(
        title = title,
        message = message,
        kind = kind,
        modifier = modifier,
        output = output,
        initiallyExpanded = initiallyExpanded,
        actionLabel = actionLabel,
        onAction = onAction,
        onRetry = onRetry,
        onOpenDoctor = onOpenDoctor,
        dismissLabel = null,
        onDismiss = null
    )
}

/**
 * The extended shape with a secondary dismiss. [dismissLabel] has no default, so this shape never
 * competes with the action overload for a plain trailing lambda.
 */
@Composable
fun ErrorCard(
    title: String,
    message: String,
    kind: FailureKind,
    modifier: Modifier = Modifier,
    output: String? = null,
    initiallyExpanded: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onOpenDoctor: (() -> Unit)? = null,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    ErrorCardContent(
        title = title,
        message = message,
        kind = kind,
        modifier = modifier,
        output = output,
        initiallyExpanded = initiallyExpanded,
        actionLabel = actionLabel,
        onAction = onAction,
        onRetry = onRetry,
        onOpenDoctor = onOpenDoctor,
        dismissLabel = dismissLabel,
        onDismiss = onDismiss
    )
}

/** The action a card shows: an explicit recovery first, then the one [kind] itself implies. */
internal data class ErrorCardAction(val label: String, val action: () -> Unit)

@Composable
internal fun errorCardAction(
    kind: FailureKind,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onOpenDoctor: (() -> Unit)?
): ErrorCardAction? {
    // A caller's own recovery (the phone's call screen, an install retry) is never a retry of a
    // refused request, so it survives the refused kind. The implied actions do not.
    if (actionLabel != null && onAction != null) return ErrorCardAction(actionLabel, onAction)
    if (kind == FailureKind.REFUSED) return null
    return when (kind) {
        FailureKind.TRANSPORT -> onRetry?.let {
            ErrorCardAction(stringResource(R.string.designsystem_action_retry), it)
        }

        FailureKind.DAEMON -> onOpenDoctor?.let {
            ErrorCardAction(stringResource(R.string.designsystem_action_open_doctor), it)
        }

        FailureKind.REFUSED -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorCardContent(
    title: String,
    message: String,
    kind: FailureKind,
    modifier: Modifier,
    output: String?,
    initiallyExpanded: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onOpenDoctor: (() -> Unit)?,
    dismissLabel: String?,
    onDismiss: (() -> Unit)?
) {
    var expanded by rememberSaveable(output) { mutableStateOf(initiallyExpanded) }
    val status = LocalStatusColors.current
    val resolved = errorCardAction(kind, actionLabel, onAction, onRetry, onOpenDoctor)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { error(message) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = status.negative.content,
                    modifier = Modifier.size(22.dp)
                )
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (output != null) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = stringResource(
                            if (expanded) {
                                R.string.designsystem_hide_details
                            } else {
                                R.string.designsystem_show_details
                            }
                        )
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        SelectionContainer {
                            Text(
                                text = output,
                                modifier = Modifier.padding(10.dp),
                                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall)
                            )
                        }
                    }
                }
            }
            if (resolved != null || (dismissLabel != null && onDismiss != null)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (dismissLabel != null && onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    }
                    if (resolved != null) {
                        TextButton(onClick = resolved.action) { Text(resolved.label) }
                    }
                }
            }
        }
    }
}
