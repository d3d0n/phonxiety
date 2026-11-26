package com.phonxiety.bridge.designsystem.component

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.R
import com.phonxiety.bridge.designsystem.StatusColorPair

/** Semantic tone of a [StatusPill]; the theme decides the actual colours. */
enum class StatusTone { POSITIVE, WARNING, NEGATIVE, NEUTRAL, ACCENT }

/** The link path as the dashboard and the wizard label it. */
enum class LinkPath { DIRECT, RELAY, OFFLINE, UNKNOWN }

/**
 * Small labelled capsule for a state that must never be mistaken for another: link path, call
 * phase, permission verdict. [pulse] is for transitional states (`connecting`) only; it runs at
 * most three cycles and stops when the system animator scale is off.
 *
 * The visible label is the whole accessible name: the tone is a colour, not something to announce.
 */
@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    pulse: Boolean = false
) {
    val colors = LocalStatusColors.current
    val pair: StatusColorPair = when (tone) {
        StatusTone.POSITIVE -> colors.positive
        StatusTone.WARNING -> colors.warning
        StatusTone.NEGATIVE -> colors.negative
        StatusTone.NEUTRAL -> colors.neutral
        StatusTone.ACCENT -> colors.accent
    }
    val dotColor = if (tone == StatusTone.NEUTRAL) colors.neutralIndicator else pair.content
    val pulseAlpha = if (pulse && ValueAnimator.areAnimatorsEnabled()) {
        val alpha = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            repeat(PULSE_CYCLES) {
                alpha.animateTo(PULSE_LOW_ALPHA, tween(PULSE_HALF_CYCLE_MS))
                alpha.animateTo(1f, tween(PULSE_HALF_CYCLE_MS))
            }
        }
        alpha.value
    } else {
        1f
    }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = CircleShape,
        color = pair.container,
        contentColor = pair.content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor.copy(alpha = pulseAlpha), CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = pair.content
            )
        }
    }
}

/** The link path pill. Only a measured path gets a name; an unmeasured one stays "Unknown". */
@Composable
fun LinkPathPill(path: LinkPath, modifier: Modifier = Modifier) {
    val (tone, labelId) = when (path) {
        LinkPath.DIRECT -> StatusTone.POSITIVE to R.string.designsystem_path_direct
        LinkPath.RELAY -> StatusTone.WARNING to R.string.designsystem_path_relay
        LinkPath.OFFLINE -> StatusTone.NEUTRAL to R.string.designsystem_path_offline
        LinkPath.UNKNOWN -> StatusTone.NEUTRAL to R.string.designsystem_path_unknown
    }
    StatusPill(label = stringResource(labelId), tone = tone, modifier = modifier)
}

private const val PULSE_CYCLES = 3
private const val PULSE_HALF_CYCLE_MS = 400
private const val PULSE_LOW_ALPHA = 0.35f
