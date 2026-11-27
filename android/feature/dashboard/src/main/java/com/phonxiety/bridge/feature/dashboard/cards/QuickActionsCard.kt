package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.model.CallState

/**
 * The actions that are not the call: prove the link, repair it, update it. A call in progress
 * explains why a second one is not offered instead of disabling a button without a reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickActionsCard(
    call: CallState?,
    showLinkTest: Boolean,
    onNewCall: () -> Unit,
    onRunLinkTest: () -> Unit,
    onOpenDoctor: () -> Unit,
    onOpenUpdates: () -> Unit
) {
    SectionCard(title = stringResource(R.string.dashboard_actions_title)) {
        if (call.isLive()) {
            Text(
                text = stringResource(R.string.dashboard_actions_call_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionFlow {
                if (showLinkTest) {
                    OutlinedButton(onClick = onRunLinkTest) {
                        Text(stringResource(R.string.dashboard_link_test))
                    }
                }
                OutlinedButton(onClick = onOpenDoctor) {
                    Text(stringResource(R.string.dashboard_doctor))
                }
                OutlinedButton(onClick = onOpenUpdates) {
                    Text(stringResource(R.string.dashboard_updates))
                }
            }
        } else {
            ActionFlow {
                Button(onClick = onNewCall) {
                    Text(stringResource(R.string.dashboard_new_call))
                }
                if (showLinkTest) {
                    OutlinedButton(onClick = onRunLinkTest) {
                        Text(stringResource(R.string.dashboard_link_test))
                    }
                }
                OutlinedButton(onClick = onOpenDoctor) {
                    Text(stringResource(R.string.dashboard_doctor))
                }
                OutlinedButton(onClick = onOpenUpdates) {
                    Text(stringResource(R.string.dashboard_updates))
                }
            }
        }
    }
}
