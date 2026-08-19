package com.uruj.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.ThemeSettingsStore
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.urujIsLightTheme
import kotlinx.coroutines.launch

/**
 * v0.9.79 — daylight switch. Sun glyph while dark (tap to go light), moon while
 * light (tap to go back).
 *
 * Owns its own store rather than taking a callback, so it can be dropped into any
 * screen as a bare `ThemeToggleButton()`. The write is a single boolean to
 * DataStore; the whole app re-themes because MainActivity collects that flow.
 *
 * Sized 34.dp — small enough not to crowd the HUD's top strip, still above the
 * 32.dp floor that a gloved thumb can reliably hit on a moving bike.
 */
@Composable
fun ThemeToggleButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ThemeSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val light by store.lightMode.collectAsState(initial = false)
    val isLight = urujIsLightTheme

    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(10.dp))
            .clickable { scope.launch { store.setLightMode(!light) } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isLight) "\u263E" else "\u2600",
            color = if (isLight) UrujText else UrujZone3,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
        )
    }
}
