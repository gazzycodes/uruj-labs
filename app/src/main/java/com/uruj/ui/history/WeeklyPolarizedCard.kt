package com.uruj.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.power.WeeklyPolarizedAnalyzer
import com.uruj.power.shortLabel
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujNeonMagenta
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import com.uruj.ui.theme.UrujZoneBelowZ1

/**
 * v0.9.22 — weekly polarized compliance card at the top of the Rides screen.
 *
 * Two pieces:
 *   1. Weekly summary line — easy/gray/hard % weighted across all rides
 *      this week (Mon–Sun ISO), plus polarized verdict (reuses same
 *      thresholds as per-ride feedback for consistency).
 *   2. 7 daily stacked bars — Mon-Sun, each bar showing 6-zone distribution
 *      for that day's rides. Rest days show as empty muted bars so the
 *      pattern of when-rides-happen is also visible.
 *
 * Architectural notes:
 *   - Uses the same 6-zone palette (UrujZoneBelowZ1 + UrujZone1..Z5) as
 *     TIZ card, HUD twin-hero, Bio Lab Karvonen card. Single source of
 *     color truth.
 *   - Easy/gray/hard math is identical to per-ride TIZ (sub-Z1 + Z1 + Z2
 *     count as easy per v0.9.17 polarized math).
 *   - Verdict uses the same thresholds as RideSummaryScreen's
 *     polarizedFeedback() for one consistent compliance interpretation.
 *     Weekly version is duplicated here to keep RideSummary self-contained
 *     and to allow slight wording variation ("this week" vs "this ride").
 */
@Composable
fun WeeklyPolarizedCard(week: WeeklyPolarizedAnalyzer.WeekResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "POLARIZED — THIS WEEK",
                color = UrujNeonMagenta,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${week.rideCount} ride${if (week.rideCount == 1) "" else "s"}",
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }

        if (week.ridesWithHrCount == 0) {
            // No HR data yet this week — show empty state instead of bars.
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (week.rideCount == 0) {
                    "No rides yet this week."
                } else {
                    "${week.rideCount} ride${if (week.rideCount == 1) "" else "s"} this week, none with strap HR data yet — bars unlock once HR data syncs."
                },
                color = UrujMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            DailyBars(week = week, showEmptyBars = true)
            return@Column
        }

        // Weekly summary line — weighted easy/gray/hard
        Spacer(Modifier.height(8.dp))
        val easyPct = (week.weeklyEasyPct * 100).toInt()
        val grayPct = (week.weeklyGrayPct * 100).toInt()
        val hardPct = (week.weeklyHardPct * 100).toInt()
        Text(
            text = "$easyPct% easy (sub-Z1 + Z1 + Z2) · $grayPct% gray (Z3) · $hardPct% hard (Z4-Z5)",
            color = UrujText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = weeklyPolarizedVerdict(
                easy = week.weeklyEasyPct,
                gray = week.weeklyGrayPct,
                hard = week.weeklyHardPct,
            ),
            color = UrujMuted,
            fontSize = 11.sp,
        )

        // Daily bars
        Spacer(Modifier.height(10.dp))
        DailyBars(week = week, showEmptyBars = false)

        // Legend — sub-Z1 floor + zone names
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Karvonen zones · sub-Z1 floor < ${week.subRecoveryFloorBpm} bpm (your current RHR-based recovery threshold)",
            color = UrujMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun DailyBars(week: WeeklyPolarizedAnalyzer.WeekResult, showEmptyBars: Boolean) {
    // Find max daily total across the week so bars scale proportionally.
    // Empty days render as a thin muted strip so the day label still shows.
    val maxDayMs = week.days.maxOfOrNull { it.totalMs } ?: 0L
    val barHeightDp = 48.dp
    val emptyBarHeightDp = 6.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val buckets = day.timeInZoneMs
                if (buckets != null && day.totalMs > 0L && maxDayMs > 0L) {
                    val barFrac = day.totalMs.toFloat() / maxDayMs.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeightDp)
                            .padding(top = (barHeightDp.value * (1f - barFrac)).dp),
                    ) {
                        ZoneStack(buckets = buckets, totalMs = day.totalMs)
                    }
                } else if (showEmptyBars || day.rideCount > 0) {
                    // Rest day OR ride-without-HR — thin muted strip.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeightDp)
                            .padding(top = (barHeightDp.value - emptyBarHeightDp.value).dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .background(UrujSurfaceHigh, RoundedCornerShape(2.dp)),
                        )
                    }
                } else {
                    Spacer(Modifier.height(barHeightDp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = day.dayOfWeek.shortLabel(),
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = if (day.totalMs > 0L) "${day.totalMs / 60_000}m" else "",
                    color = UrujMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

/**
 * Vertical 6-zone stack (sub-Z1 at bottom → Z5 at top) — each segment
 * sized proportionally to its bucket's share of the day's total.
 */
@Composable
private fun ZoneStack(buckets: LongArray, totalMs: Long) {
    val total = totalMs.coerceAtLeast(1L)
    val colors = listOf(
        UrujZoneBelowZ1, UrujZone1, UrujZone2, UrujZone3, UrujZone4, UrujZone5,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(2.dp)),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
    ) {
        // Render Z5 → sub-Z1 from top to bottom so visual order matches
        // intensity (highest zone at top of bar).
        for (i in 5 downTo 0) {
            val fraction = buckets[i].toFloat() / total
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fraction)
                        .background(colors[i]),
                )
            }
        }
    }
}

/**
 * Same threshold logic as RideSummaryScreen.polarizedFeedback() but worded
 * for a week of rides. Kept duplicated (vs sharing) to allow per-context
 * wording without forcing identical text on both surfaces.
 *
 * Seiler/Stöggl polarized = ~80% easy / ≤5% gray / 15-20% hard. The
 * thresholds here are slightly relaxed from "strict polarized" to give
 * the rider useful weekly verdicts even when the distribution shifts
 * across rest weeks.
 */
private fun weeklyPolarizedVerdict(easy: Float, gray: Float, hard: Float): String = when {
    easy >= 0.75f && hard >= 0.10f && gray < 0.15f ->
        "Polarized ✓ — Blummenfelt/Seiler distribution. Aerobic base + threshold work both hit this week."
    easy >= 0.85f && hard < 0.05f ->
        "Endurance week — pure aerobic base, no threshold stimulus. Good for rest/recovery weeks."
    easy < 0.50f && gray >= 0.25f ->
        "Gray-zone trap — too much Z3 this week. Either drop to Z2 or commit to Z4+ blocks."
    hard >= 0.25f ->
        "Hard week — heavy threshold/VO2 stimulus. Make sure next week dials back."
    else ->
        "Mixed distribution — context-dependent. Watch the daily bars for the pattern."
}
