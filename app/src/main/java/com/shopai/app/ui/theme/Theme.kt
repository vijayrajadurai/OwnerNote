package com.shopai.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.shopai.app.data.local.AppThemeMode

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    secondary = Accent,
    background = Background,
    surface = Surface,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Background,
    surfaceContainer = Background,
    surfaceContainerHigh = Background,
    surfaceContainerHighest = Background,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = Danger,
)

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    secondary = Accent,
    background = Color(0xFF121412),
    surface = Color(0xFF1C1F1D),
    surfaceContainerLowest = Color(0xFF121412),
    surfaceContainerLow = Color(0xFF121412),
    surfaceContainer = Color(0xFF121412),
    surfaceContainerHigh = Color(0xFF121412),
    surfaceContainerHighest = Color(0xFF121412),
    onBackground = Color(0xFFE8E6E0),
    onSurface = Color(0xFFE8E6E0),
    onSurfaceVariant = Color(0xFF9A968C),
    outline = Color(0xFF3A3834),
    error = Danger,
)

@Composable
fun ShopAiTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShopAiTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
            content()
        }
    }
}
