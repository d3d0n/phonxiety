package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.dashboard.R

/**
 * The pairing card and its confirmation. The confirmation is a value of the screen's state — the
 * caller's, not a `remember` here — so a rotation keeps the question open instead of dropping it.
 */
@Composable
internal fun PairingCard(
    inCall: Boolean,
    confirming: Boolean,
    onOpenSetup: () -> Unit,
    onRequestUnpair: () -> Unit,
    onDismissUnpair: () -> Unit,
    onConfirmUnpair: () -> Unit
) {
    SectionCard(title = stringResource(R.string.dashboard_pairing_title)) {
        Text(
            text = stringResource(R.string.dashboard_pairing_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.dashboard_pair_another))
            }
            TextButton(
                onClick = onRequestUnpair,
                enabled = !inCall,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.dashboard_unpair))
            }
        }
        if (inCall) {
            Text(
                text = stringResource(R.string.dashboard_unpair_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = onDismissUnpair,
            title = { Text(stringResource(R.string.dashboard_unpair_title)) },
            text = { Text(stringResource(R.string.dashboard_unpair_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmUnpair) {
                    Text(stringResource(R.string.dashboard_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUnpair) {
                    Text(stringResource(R.string.dashboard_cancel))
                }
            }
        )
    }
}
