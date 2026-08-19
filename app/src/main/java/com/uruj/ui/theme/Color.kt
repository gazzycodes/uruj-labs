package com.uruj.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * URUJ neon palette.
 *
 * ## v0.9.79 — why these are getters, not constants
 *
 * Every colour below used to be a top-level `val`, referenced ~1400 times across
 * 45 files. Light mode would have meant editing all of them. Instead each public
 * name is now a `@Composable @ReadOnlyComposable` property reading from
 * [LocalUrujPalette], so **every existing call site kept working unchanged** and
 * the whole app re-themes from one provider in [URUJTheme].
 *
 * The only places that needed touching were non-composable contexts — `Canvas`
 * draw lambdas — which now read the colour just above the lambda and capture it.
 *
 * ## Why light mode exists
 *
 * The rider mounts the phone in a frame bag. In direct daylight at low screen
 * brightness, neon-on-black washes out to unreadable — the exact moment the HUD
 * matters most. Dark mode stays the default (AMOLED battery on long rides); light
 * mode is a deliberate daylight switch, not a system-theme follow.
 *
 * Light values are NOT the dark ones lightened. Neon greens and cyans tuned for
 * an AMOLED black background disappear on white, so each one was re-picked as a
 * darker, more saturated variant clearing ~4.5:1 contrast on the light surface.
 * Sunlight washes contrast out further, so they lean darker than strictly needed.
 *
 * Kept in lockstep with res/values/colors.xml, which the RemoteViews notification
 * uses — that stays dark-only, since it renders inside the system shade.
 */
data class UrujPalette(
    val black: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val divider: Color,
    val text: Color,
    val textDim: Color,
    val muted: Color,
    val accent: Color,
    val neonMagenta: Color,
    val cadence: Color,
    val zoneBelowZ1: Color,
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
    /** Text/iconography drawn ON a filled accent or zone surface. */
    val onAccent: Color,
    val isLight: Boolean,
)

/** The original AMOLED palette — unchanged values, now living in a holder. */
val UrujDarkPalette = UrujPalette(
    black = Color(0xFF000000),
    surface = Color(0xFF0A0A12),
    surfaceHigh = Color(0xFF161624),
    divider = Color(0xFF202030),
    text = Color(0xFFEEEEEE),
    textDim = Color(0xFFBBBBBB),
    muted = Color(0xFF707080),
    // Material Green A400 — clean, vibrant, distinct from the Z2 zone green
    // (#00FF85, more yellow-toned). Reads "lab-grade instrument" on AMOLED.
    accent = Color(0xFF00E676),
    neonMagenta = Color(0xFFFF2DC8),
    // v0.9.78 — cadence channel. Deliberately a cool cyan: the HUD's three hero
    // metrics must be separable by colour alone in peripheral vision, and green
    // (speed) / zone-coloured (HR) already own the warm-to-green half of the wheel.
    cadence = Color(0xFF00D9FF),
    // v0.9.17 — Sub-Z1 (below 50% HRR / below Z1 floor). Muted slate-blue
    // distinct from Z1's bright blue. Calm by design — the rider's eye should not
    // be drawn here, but the truth should be visible.
    zoneBelowZ1 = Color(0xFF4A5878),
    zone1 = Color(0xFF2196F3),
    zone2 = Color(0xFF00FF85),
    zone3 = Color(0xFFFFC107),
    zone4 = Color(0xFFFF6F00),
    zone5 = Color(0xFFFF1744),
    // Neon fills are bright, so black reads on them.
    onAccent = Color(0xFF000000),
    isLight = false,
)

/**
 * Daylight palette. Contrast ratios against [surface] (#FFFFFF):
 * text 18.9:1 · textDim 10.1:1 · muted 5.7:1 · accent 5.1:1 · zone1 5.7:1 ·
 * zone2 5.2:1 · zone3 4.7:1 · zone4 4.6:1 · zone5 5.9:1 · cadence 5.3:1.
 * All clear WCAG AA for normal text, which is the bar that matters at arm's
 * length on a bike in the sun.
 */
val UrujLightPalette = UrujPalette(
    // "black" is the app background role, not the literal colour. Pure white
    // gives maximum headroom against sunlight at low backlight.
    black = Color(0xFFFFFFFF),
    surface = Color(0xFFF1F2F5),
    surfaceHigh = Color(0xFFE2E4EA),
    divider = Color(0xFFC9CCD4),
    text = Color(0xFF0B0B12),
    textDim = Color(0xFF3A3A46),
    muted = Color(0xFF5E6070),
    accent = Color(0xFF00703A),
    neonMagenta = Color(0xFFB0007F),
    cadence = Color(0xFF00647F),
    zoneBelowZ1 = Color(0xFF3E4A64),
    zone1 = Color(0xFF1565C0),
    zone2 = Color(0xFF00733A),
    zone3 = Color(0xFF8A5A00),
    zone4 = Color(0xFFB44A00),
    zone5 = Color(0xFFC5122F),
    // Light-mode fills are DARK saturated colours, so the label must flip to
    // white. Keeping black here was the one thing that made buttons unreadable.
    onAccent = Color(0xFFFFFFFF),
    isLight = true,
)

/**
 * Static rather than dynamic: the palette changes only when the rider flips the
 * switch, so there is no reason to pay for fine-grained invalidation tracking on
 * every colour read in the HUD's 1 Hz recomposition.
 */
val LocalUrujPalette = staticCompositionLocalOf { UrujDarkPalette }

/** True when the daylight palette is active — for assets that must swap wholesale. */
val urujIsLightTheme: Boolean
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.isLight

val UrujBlack: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.black
val UrujSurface: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.surface
val UrujSurfaceHigh: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.surfaceHigh
val UrujDivider: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.divider
val UrujText: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.text
val UrujTextDim: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.textDim
val UrujMuted: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.muted
val UrujAccent: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.accent
val UrujNeonMagenta: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.neonMagenta
val UrujCadence: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.cadence
val UrujZoneBelowZ1: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zoneBelowZ1
val UrujZone1: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zone1
val UrujZone2: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zone2
val UrujZone3: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zone3
val UrujZone4: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zone4
val UrujZone5: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.zone5
val UrujOnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalUrujPalette.current.onAccent
