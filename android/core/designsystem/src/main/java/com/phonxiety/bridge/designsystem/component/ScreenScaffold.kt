package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.R

/**
 * The frame every screen shares: one app bar with a labelled back arrow, safe drawing insets, and
 * 16 dp side margins. Screens put their own scroll container inside [content].
 *
 * The scaffold is the screen's only inset and keyboard owner: the status bar, the cutout and the
 * navigation bar come from its own content insets, and [imeInsets] is applied to the content
 * exactly once — a screen must not add an `imePadding` of its own. [imeInsets] is a parameter so a
 * test can pin the keyboard's height; every real screen keeps the platform's inset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    imeInsets: WindowInsets = WindowInsets.ime,
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.designsystem_action_back)
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background,
        // Everything safeDrawing covers except the keyboard: the bars and the cutout are paid
        // once here, and the keyboard is paid once by the content's [imeInsets] padding below.
        // Splitting the same union in two keeps the bottom at max(navigation bar, keyboard)
        // instead of their sum, so a screen with a field above the keyboard never gets a gap.
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(imeInsets)
        ) {
            content()
        }
    }
}
