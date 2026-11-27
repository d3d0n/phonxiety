package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The frame a bottom sheet's content shares: one title, an optional subtitle, the sheet's own
 * content, and the insets that keep the last row clear of the navigation bar. Place it inside the
 * sheet surface; the drag handle and the scrim belong to the sheet, not to the content.
 *
 * The title is the sheet's [paneTitle] as well as a heading, so a screen reader announces which
 * pane opened and a reader scanning the sheet sees the same words first. Pass a zero [WindowInsets]
 * when the sheet surface already consumes the navigation-bar insets, so the last row is not padded
 * twice.
 */
@Composable
fun SheetScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    navigationBarInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(navigationBarInsets)
            .padding(contentPadding)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}
