package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.feature.lab.R

/** How many lines a captured block shows before it offers the rest. */
internal const val PREVIEW_LINES = 12
internal const val MAX_PREVIEW_LINES = 500

/** The code face token: a captured value never picks a font family of its own. */
@Composable
internal fun codeStyle(): TextStyle = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall)

/** Chips and buttons wrap instead of scrolling: a clipped label reads as a broken control, not as content. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WrapRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
internal fun CodecChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { SelectedCheck(selected) }
    )
}

@Composable
internal fun SelectedCheck(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(FilterChipDefaults.IconSize)
        )
    }
}

/**
 * A monospace block with a disclosure instead of its own scroll box: a nested scroll swallows the
 * page's drag, and the page is what a reader needs to move.
 */
@Composable
internal fun PreviewText(
    lines: List<String>,
    expanded: Boolean,
    more: Boolean,
    onToggle: () -> Unit
) {
    Text(
        text = lines.joinToString("\n"),
        style = codeStyle()
    )
    if (more) {
        TextButton(onClick = onToggle) {
            Text(stringResource(if (expanded) R.string.lab_show_less else R.string.lab_show_all))
        }
    }
}
