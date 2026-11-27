package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.PathKind
import java.util.concurrent.TimeUnit

/** Unit symbols are not translated; the designsystem joins them to the value with a no-break space. */
internal const val UNIT_MILLISECONDS = "ms"
internal const val UNIT_KILOBITS = "kbit/s"

/** A missing measurement is a dash, never a fabricated zero. */
internal const val MISSING_VALUE = "—"

/** Tiles reflow instead of squeezing: three across at 100 %, as many rows as the font needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TileRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

/** Action buttons wrap at their own width so no label is ever split or clipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

/** An ended call stays on the card for review; only a call still in progress blocks the actions. */
internal fun CallState?.isLive(): Boolean = this != null && phase != CallPhase.ENDED

internal fun CallState?.takeIfLive(): CallState? = takeIf { it.isLive() }

internal fun secondsBetween(fromEpochMs: Long, toEpochMs: Long): Long = TimeUnit.MILLISECONDS.toSeconds((toEpochMs - fromEpochMs).coerceAtLeast(0))

/** The link path as the pill names it; an unmeasured path stays "Unknown". */
internal fun PathKind.toDesignPath(): LinkPath = when (this) {
    PathKind.DIRECT -> LinkPath.DIRECT
    PathKind.RELAY -> LinkPath.RELAY
    PathKind.UNKNOWN -> LinkPath.UNKNOWN
}
