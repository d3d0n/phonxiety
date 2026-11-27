package com.phonxiety.bridge

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.PathKind

/** The strip's node: the host tests and the strip tests find it by this tag. */
internal const val LINK_STRIP_TAG = "phonxiety:link-strip"

/**
 * What the strip prints for a summary. A connected link shows its path pill and round trip; a
 * link that is not up shows a labelled pill in the tone of the problem.
 */
internal data class LinkStripCopy(
    @StringRes val label: Int?,
    val tone: StatusTone,
    val pulse: Boolean = false,
    val path: LinkPath? = null
)

/** The one copy decision the strip makes, kept out of the composable so it can be asserted. */
internal fun linkStripCopy(summary: LinkSummary): LinkStripCopy = when (summary.level) {
    LinkLevel.OFFLINE -> LinkStripCopy(
        label = R.string.link_strip_offline,
        tone = StatusTone.NEGATIVE
    )

    LinkLevel.CONNECTING -> LinkStripCopy(
        label = R.string.link_strip_connecting,
        tone = StatusTone.WARNING,
        pulse = true
    )

    LinkLevel.UP -> LinkStripCopy(
        label = null,
        tone = StatusTone.NEUTRAL,
        path = summary.path.linkPath()
    )
}

/**
 * The thin strip the host keeps above the navigation suite while the link is not what it should
 * be. It reports, and never acts: the dashboard and the wizard own every fix.
 *
 * [topInset] is the status bar's height. The host consumes the status bar for the whole window
 * while the strip is visible, so the strip pays it here and the screens below are not pushed down
 * a second time.
 */
@Composable
internal fun LinkStatusStrip(
    state: LinkStripState.Visible,
    topInset: Dp,
    modifier: Modifier = Modifier
) {
    val summary = state.summary
    val copy = linkStripCopy(summary)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(LINK_STRIP_TAG),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = topInset + 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The indicator is the live region, and the round trip beside it is not part of it:
            // a status change is announced, a round-trip sample is only redrawn.
            val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            val path = copy.path
            if (path != null) {
                LinkPathPill(path = path, modifier = live)
            } else {
                copy.label?.let { label ->
                    StatusPill(
                        label = stringResource(label),
                        tone = copy.tone,
                        modifier = live,
                        pulse = copy.pulse
                    )
                }
            }
            summary.rttMs?.let { millis ->
                Text(
                    text = stringResource(R.string.link_strip_rtt, millis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun PathKind?.linkPath(): LinkPath = when (this) {
    PathKind.DIRECT -> LinkPath.DIRECT
    PathKind.RELAY -> LinkPath.RELAY
    PathKind.UNKNOWN, null -> LinkPath.UNKNOWN
}
