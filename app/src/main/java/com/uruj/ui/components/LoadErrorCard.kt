package com.uruj.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone3

/**
 * v0.9.24 — shared error-state card. Sister composable to
 * [LoadingCardSkeleton] (v0.9.23) — covers the case where a compute
 * pipeline threw a non-cancellation exception and the loading skeleton
 * would otherwise pulse forever.
 *
 * Pattern:
 *   - Card outline matches the real card (same UrujSurface + border + radius)
 *   - Header: section label + "ERROR" tag in amber (UrujZone3 — same
 *     "warning, not catastrophic" semantic as the gray-zone color)
 *   - Body: short message explaining what went wrong (caller passes
 *     human-readable text — usually "Couldn't compute X — tap to retry")
 *   - Retry button: prominent, calls back to the caller's refresh path
 *
 * Why amber not red: this is a recoverable computation failure, not a
 * crash or data corruption. Red would suggest "URUJ broke" — amber says
 * "try again." The retry button is the most important pixel.
 *
 * Why not a snackbar/toast: snackbar disappears; the card stays until
 * resolved. Critical info like "your readiness didn't compute" deserves
 * persistent visibility.
 */
@Composable
fun LoadErrorCard(
    label: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryButtonText: String = "RETRY",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "ERROR",
                color = UrujZone3,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = UrujText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetry) {
                Text(
                    text = "↻ $retryButtonText",
                    color = UrujAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}
