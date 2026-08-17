package com.uruj.ui.theme

import androidx.compose.ui.graphics.Color

// URUJ neon palette — kept in lockstep with res/values/colors.xml so the Compose UI
// and the RemoteViews notification render the same brand color.
val UrujBlack = Color(0xFF000000)
val UrujSurface = Color(0xFF0A0A12)
val UrujSurfaceHigh = Color(0xFF161624)
val UrujDivider = Color(0xFF202030)

val UrujText = Color(0xFFEEEEEE)
val UrujTextDim = Color(0xFFBBBBBB)
val UrujMuted = Color(0xFF707080)

// Primary URUJ accent. Material Green A400 — clean, vibrant, distinct from the Z2
// zone green (#00FF85, more yellow-toned). Reads "lab-grade instrument" on AMOLED.
val UrujAccent = Color(0xFF00E676)
val UrujNeonMagenta = Color(0xFFFF2DC8)

// v0.9.78 — cadence channel. Deliberately a cool cyan: the HUD's three hero
// metrics must be separable by colour alone in peripheral vision, and green
// (speed) / zone-coloured (HR) already own the warm-to-green half of the wheel.
val UrujCadence = Color(0xFF00D9FF)

// v0.9.17 — Sub-Z1 (below 50% HRR / below Z1 floor). Muted slate-blue
// distinct from Z1's bright blue. Surfaces sub-recovery time in TIZ bar
// + HUD HR readout. Calm by design — the rider's eye should not be drawn
// here, but the truth should be visible.
val UrujZoneBelowZ1 = Color(0xFF4A5878)
val UrujZone1 = Color(0xFF2196F3)
val UrujZone2 = Color(0xFF00FF85)
val UrujZone3 = Color(0xFFFFC107)
val UrujZone4 = Color(0xFFFF6F00)
val UrujZone5 = Color(0xFFFF1744)
