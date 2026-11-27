package com.phonxiety.bridge.feature.doctor.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.phonxiety.bridge.designsystem.LocalPhonxietyType

/** The code face token: a command or a captured value never picks a font family of its own. */
@Composable
internal fun codeStyle(): TextStyle = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall)
