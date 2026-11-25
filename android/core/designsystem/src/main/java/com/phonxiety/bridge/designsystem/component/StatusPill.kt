package com.phonxiety.bridge.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyColors

/** Semantic tone of a [StatusPill]; the theme decides the actual colours. */
enum class StatusTone { POSITIVE, WARNING, NEGATIVE, NEUTRAL, ACCENT }

/** The link path as the dashboard and the wizard label it. */
enum class LinkPath { DIRECT, RELAY, OFFLINE }

/**
 * Small labelled capsule for a state that must never be mistaken for another: link path, call
 * phase, permission verdict. [pulse] is for transitional states (`connecting`) only.
 */
@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    pulse: Boolean = false
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val container = when (tone) {
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.WARNING -> if (dark) PhonxietyColors.WarnDark.copy(alpha = 0.24f) else PhonxietyColors.Warn.copy(alpha = 0.14f)
        StatusTone.NEGATIVE -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        StatusTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (tone) {
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.WARNING -> if (dark) PhonxietyColors.WarnDark else PhonxietyColors.Warn
        StatusTone.NEGATIVE -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val dotAlpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "status-pill")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "status-pill-alpha"
        )
        alpha
    } else {
        1f
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "$label ${tone.name.lowercase()}" },
        shape = CircleShape,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            val dotColor = if (tone == StatusTone.NEUTRAL) MaterialTheme.colorScheme.outline else content
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor.copy(alpha = dotAlpha), CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The link path pill: Direct is good, Relay is a warning, Offline is neutral. */
@Composable
fun LinkPathPill(path: LinkPath, modifier: Modifier = Modifier) {
    val (tone, label) = when (path) {
        LinkPath.DIRECT -> StatusTone.POSITIVE to "Direct"
        LinkPath.RELAY -> StatusTone.WARNING to "Relay"
        LinkPath.OFFLINE -> StatusTone.NEUTRAL to "Offline"
    }
    StatusPill(label = label, tone = tone, modifier = modifier)
}
