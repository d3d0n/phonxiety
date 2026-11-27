package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.R

/**
 * The frame record: the transport can note the size and kind of every message it sends and
 * receives, never their contents. It is off by default, and the switch is the only way to turn it
 * on.
 */
@Composable
internal fun FrameTapCard(frameTap: Boolean, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_frame_tap_title),
        subtitle = stringResource(R.string.lab_frame_tap_subtitle)
    ) {
        ToggleRow(
            title = stringResource(R.string.lab_frame_tap_toggle),
            detail = stringResource(R.string.lab_frame_tap_detail),
            checked = frameTap,
            onCheckedChange = callbacks.onFrameTap
        )
    }
}
