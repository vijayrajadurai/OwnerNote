package com.shopai.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Theme-aware replacements for static light-mode color tokens. */
object ShopAiThemeColors {
    val primary: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    val onSurface: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    val onSurfaceVariant: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val primaryMutedBackground: Color
        @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
}
