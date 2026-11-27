package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.dashboard.R

@Composable
internal fun PermissionCard(missing: List<String>, onOpenSetup: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.dashboard_permissions_title),
        status = {
            if (missing.isEmpty()) {
                StatusPill(
                    label = stringResource(R.string.dashboard_permissions_all_granted),
                    tone = StatusTone.POSITIVE
                )
            } else {
                StatusPill(
                    label = pluralStringResource(
                        R.plurals.dashboard_permissions_missing,
                        missing.size,
                        missing.size
                    ),
                    tone = StatusTone.NEGATIVE
                )
            }
        }
    ) {
        if (missing.isEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_permissions_all_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            missing.forEach { permission ->
                val known = knownPermission(permission)
                if (known != null) {
                    KeyValueRow(
                        label = stringResource(known),
                        value = stringResource(R.string.dashboard_permission_not_granted)
                    )
                } else {
                    Column {
                        KeyValueRow(
                            label = stringResource(R.string.dashboard_permission_other),
                            value = stringResource(R.string.dashboard_permission_not_granted)
                        )
                        Text(
                            text = permission,
                            style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.dashboard_fix_in_setup))
            }
        }
    }
}

internal fun knownPermission(permission: String): Int? = when (permission) {
    "android.permission.RECORD_AUDIO" -> R.string.dashboard_permission_microphone
    "android.permission.CALL_PHONE" -> R.string.dashboard_permission_calls
    "android.permission.READ_PHONE_STATE" -> R.string.dashboard_permission_phone_state
    "android.permission.MODIFY_AUDIO_SETTINGS" -> R.string.dashboard_permission_audio_settings
    "android.permission.POST_NOTIFICATIONS" -> R.string.dashboard_permission_notifications
    "android.permission.ACCESS_LOCAL_NETWORK" -> R.string.dashboard_permission_local_network
    else -> null
}
