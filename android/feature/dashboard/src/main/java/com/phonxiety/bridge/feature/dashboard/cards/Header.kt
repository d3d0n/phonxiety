package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.BrandLockup
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.LinkState

/** The title and the one pill that answers "what is happening now". */
@Composable
internal fun DashboardHeader(bridge: BridgeState, paired: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BrandLockup(height = 22.dp)
            Text(
                text = stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HeaderStatus(bridge, paired)
    }
}

@Composable
internal fun HeaderStatus(bridge: BridgeState, paired: Boolean) {
    val call = bridge.call.takeIfLive()
    when {
        call != null -> StatusPill(
            label = phaseLabel(call.phase),
            tone = phaseTone(call.phase),
            pulse = call.phase == CallPhase.DIALING
        )

        !paired -> StatusPill(
            label = stringResource(R.string.dashboard_status_not_paired),
            tone = StatusTone.NEUTRAL
        )

        else -> when (val link = bridge.link) {
            is LinkState.Up -> LinkPathPill(link.path.toDesignPath())

            is LinkState.Connecting -> StatusPill(
                label = stringResource(R.string.dashboard_status_connecting),
                tone = StatusTone.WARNING,
                pulse = true
            )

            LinkState.Offline -> StatusPill(
                label = stringResource(R.string.dashboard_status_offline),
                tone = StatusTone.NEUTRAL
            )
        }
    }
}
