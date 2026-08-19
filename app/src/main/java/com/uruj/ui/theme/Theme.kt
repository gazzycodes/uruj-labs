package com.uruj.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

/**
 * URUJ stays neon and high-contrast in both modes — no dynamic Material You
 * theming, and deliberately NOT a follower of the system light/dark setting.
 *
 * The rider's phone lives in a frame bag. Dark is the default because AMOLED
 * black costs almost nothing over a multi-hour ride; light exists because in
 * direct sun at low brightness the neon-on-black HUD washes out to unreadable.
 * That is a decision about sunlight, not about what the OS thinks the time is,
 * so it is a rider-owned toggle persisted in [com.uruj.data.ThemeSettingsStore].
 */
@Composable
fun URUJTheme(
    lightMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (lightMode) UrujLightPalette else UrujDarkPalette

    val colorScheme = remember(palette) {
        if (palette.isLight) {
            lightColorScheme(
                background = palette.black,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textDim,
                primary = palette.accent,
                onPrimary = Color.White,
                secondary = palette.neonMagenta,
                onSecondary = Color.White,
                tertiary = palette.zone2,
                error = palette.zone5,
                outline = palette.divider,
            )
        } else {
            darkColorScheme(
                background = palette.black,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textDim,
                primary = palette.accent,
                onPrimary = palette.black,
                secondary = palette.neonMagenta,
                onSecondary = palette.black,
                tertiary = palette.zone2,
                error = palette.zone5,
                outline = palette.divider,
            )
        }
    }

    // Without this the status-bar icons stay white and vanish against the light
    // background — the exact readability problem light mode exists to fix.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = palette.isLight
        }
    }

    CompositionLocalProvider(LocalUrujPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
