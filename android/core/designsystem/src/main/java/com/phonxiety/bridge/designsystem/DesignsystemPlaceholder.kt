package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TODO(designsystem): replace with the real design-system surfaces and
 * components.
 */
@Composable
fun DesignsystemPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(160.dp)
            .background(Color(0xFF1B5E20)),
        contentAlignment = Alignment.Center
    ) {
        Text("phonxiety")
    }
}
