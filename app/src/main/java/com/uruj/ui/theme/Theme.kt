package com.uruj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val UrujDarkColors = darkColorScheme(
    background = UrujBlack,
    onBackground = UrujText,
    surface = UrujSurface,
    onSurface = UrujText,
    surfaceVariant = UrujSurfaceHigh,
    onSurfaceVariant = UrujTextDim,
    primary = UrujAccent,
    onPrimary = UrujBlack,
    secondary = UrujNeonMagenta,
    onSecondary = UrujBlack,
    tertiary = UrujZone2,
    error = UrujZone5,
    outline = UrujDivider,
)

/**
 * URUJ is always dark, always neon — no dynamic Material You theming. The athlete's HUD
 * needs a stable, high-contrast palette regardless of system theme.
 */
@Composable
fun URUJTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UrujDarkColors,
        typography = Typography,
        content = content,
    )
}
