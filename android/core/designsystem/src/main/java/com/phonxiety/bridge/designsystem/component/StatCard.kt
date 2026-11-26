package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalStatusColors
import java.text.NumberFormat
import java.util.Locale

/** Emphasis of a [StatTile] value; colour is semantic, never decorative. */
enum class StatTone { NEUTRAL, POSITIVE, WARNING, NEGATIVE }

/**
 * The font scale at which a value leaves the title's side and drops under it. Beside a title at
 * this scale a pill or badge leaves a few characters per line, so Compose breaks the title
 * mid-word. A project heuristic, named once here: the same rule holds for every card and row in
 * the app.
 */
const val STACK_AT_FONT_SCALE = 1.5f

/**
 * One dashboard card: a heading row that may carry a status pill, an optional subtitle at full
 * width so a long pill cannot squeeze it, and content that is usually a row of [StatTile]s. At
 * [STACK_AT_FONT_SCALE] and above the title and subtitle take the full width, the status drops
 * under them, and the status slot is still invoked exactly once.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (stacked) {
                    CardTitle(title = title, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CardTitle(title = title)
                        }
                        if (status != null) {
                            Spacer(Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = status)
                        }
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (stacked && status != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = status)
            }
            content()
        }
    }
}

@Composable
private fun CardTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() }
    )
}

/**
 * A labelled value: the numbers a user checks before trusting the link. [unit] is separate so a
 * caller never concatenates "78" and "%" and the no-break space stays consistent.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    mono: Boolean = false,
    tone: StatTone = StatTone.NEUTRAL,
    unit: String? = null
) {
    val status = LocalStatusColors.current
    val locale = LocalConfiguration.current.locales[0]
    val valueStyle = MaterialTheme.typography.titleMedium.let { style ->
        if (mono) style.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum") else style
    }
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatMeasure(value, unit, locale),
            style = valueStyle,
            color = when (tone) {
                StatTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                StatTone.POSITIVE -> status.positiveIndicator
                StatTone.WARNING -> status.warning.content
                StatTone.NEGATIVE -> status.negativeIndicator
            },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Label/value row with the value right-aligned, tabular in monospace, and at least 96 dp wide so a
 * changing number never shifts the label. At 150 % font scale and above the pair stacks, because a
 * two-column row would split a label or a duration mid-word.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueMaxLines: Int = Int.MAX_VALUE
) {
    val valueStyle = MaterialTheme.typography.bodyMedium.let { style ->
        if (mono) style.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum") else style
    }
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    if (stacked) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = valueStyle,
                textAlign = TextAlign.End,
                maxLines = valueMaxLines,
                softWrap = valueMaxLines != 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = valueStyle,
                textAlign = TextAlign.End,
                maxLines = valueMaxLines,
                softWrap = valueMaxLines != 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .widthIn(min = 96.dp)
            )
        }
    }
}

/**
 * Joins a value and its unit the way the writing rules ask. A percent goes through the locale's own
 * percent format ("78%" in English, "78 %" in French and German); every other unit is joined with a
 * no-break space. A value that is not a number is shown as it is: a missing value never grows a
 * unit.
 */
internal fun formatMeasure(value: String, unit: String?, locale: Locale = Locale.getDefault()): String = when {
    unit == null -> value
    unit == "%" -> formatPercent(value, locale)
    else -> "$value\u00A0$unit"
}

private fun formatPercent(value: String, locale: Locale): String {
    val number = value.toDoubleOrNull() ?: return value
    val format = NumberFormat.getPercentInstance(locale).apply {
        maximumFractionDigits = value.substringAfter('.', "").length
    }
    return format.format(number / 100)
}
