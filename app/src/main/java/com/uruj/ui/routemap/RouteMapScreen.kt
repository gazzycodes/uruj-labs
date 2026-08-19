package com.uruj.ui.routemap

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Full-screen route map for a recorded ride. Wraps OSMDroid MapView via
 * AndroidView. Polyline is colored per-segment by HR-zone (%max). Markers
 * pin start + end. Tap anywhere on the map → finds the nearest GPS sample
 * and surfaces a bottom panel with that point's HR/speed/elevation/time.
 *
 * Compose-AndroidView interop notes:
 *   - MapView is created once (remember) and reused across recompositions
 *   - update{} runs every recomposition, so we clear overlays first
 *   - DisposableEffect calls onDetach() to release tile cache + threads
 */
@Composable
fun RouteMapScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: RouteMapViewModel = viewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // One MapView instance, kept across recompositions. OSMDroid requires
    // Configuration setup BEFORE construction — the userAgentValue is
    // mandatory under the OSM tile usage policy.
    val mapView = remember {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences(OSMDROID_PREFS, Context.MODE_PRIVATE),
        )
        Configuration.getInstance().userAgentValue = "com.uruj.urujlabs"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isHorizontalMapRepetitionEnabled = false
            // Initial center on world center — overlay update zooms to ride bounds.
            controller.setZoom(2.0)
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    var selectedPoint by remember { mutableStateOf<ZonedPoint?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val ready = state as? RouteMapState.Ready ?: return@AndroidView
                view.overlays.clear()

                // 1. Tap handler — installed FIRST so it's at the bottom of the
                //    overlay stack and gets events not consumed by markers/lines.
                view.overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p == null) return false
                            val nearest = findNearestPoint(ready.points, p)
                            selectedPoint = nearest
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    })
                )

                // 2. HR-zone-colored polyline segments. Splitting into per-segment
                //    Polyline objects (one per consecutive-same-color run) keeps
                //    the renderer fast vs setting per-point colors via gradient.
                buildSegmentedPolylines(ready.points).forEach { view.overlays.add(it) }

                // 3. Start + end markers (green/red themed circles)
                if (ready.points.isNotEmpty()) {
                    view.overlays.add(buildMarker(view, ready.points.first(), isStart = true))
                    view.overlays.add(buildMarker(view, ready.points.last(), isStart = false))
                }

                // 4. Fit map to ride bounds with edge padding. post{} defers
                //    until MapView has measured its size, otherwise zoomTo gets
                //    a 0x0 box and crashes.
                val bbox = computeBoundingBox(ready.points)
                if (bbox != null) {
                    view.post { view.zoomToBoundingBox(bbox, true, 80) }
                }
                view.invalidate()
            },
        )

        // Top app-bar — SOLID dark background extending all the way under the
        // system status bar (status-bar icons need contrast). Apply solid
        // background at the outer Column level, then statusBarsPadding on the
        // inner content. v0.3.3 version made the status bar disappear because
        // background was alpha 0.85 and didn't cover the top inset.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text(
                            "← BACK",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "ROUTE MAP",
                        color = UrujText,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(64.dp)) // balances the back button width
                }
                // Ride identification — date, distance, duration. Without this
                // the rider has no idea which ride they're looking at.
                val ready = state as? RouteMapState.Ready
                if (ready?.summary != null) {
                    RideIdentificationRow(ready.summary)
                }
                if (ready != null) {
                    ZoneLegend(maxHrBpm = ready.maxHrBpm, hasHrData = ready.hasHrData)
                }
            }
        }

        // Center loading / empty states
        when (val s = state) {
            RouteMapState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = UrujAccent)
                }
            }
            is RouteMapState.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.reason,
                        color = UrujMuted,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            is RouteMapState.Ready -> Unit
        }

        // Selected point detail panel — pinned to bottom, dismissible
        val selected = selectedPoint
        if (selected != null && state is RouteMapState.Ready) {
            PointDetailPanel(
                point = selected,
                rideStartMs = (state as RouteMapState.Ready).rideStartMs,
                onDismiss = { selectedPoint = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RideIdentificationRow(summary: com.uruj.data.StoredRideSummary) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    val distanceKm = "%.2f".format(summary.totalDistanceMeters / 1000.0)
    val durationMin = summary.movingTimeMs / 60_000L
    val durationStr = if (durationMin >= 60L) {
        "${durationMin / 60}h ${"%02d".format(durationMin % 60)}m"
    } else {
        "${durationMin}m"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$distanceKm km · $durationStr",
            color = UrujText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            dateFmt.format(Date(summary.startedAtMs)),
            color = UrujMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ZoneLegend(maxHrBpm: Int, hasHrData: Boolean) {
    val zones = listOf(
        HrZone.Z1 to UrujZone1,
        HrZone.Z2 to UrujZone2,
        HrZone.Z3 to UrujZone3,
        HrZone.Z4 to UrujZone4,
        HrZone.Z5 to UrujZone5,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (hasHrData) "HR ZONES (%max $maxHrBpm)" else "NO HR DATA",
                color = if (hasHrData) UrujMuted else UrujZone3,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
            if (hasHrData) {
                zones.forEach { (zone, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "Z${zone.ordinal + 1}",
                            color = UrujText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        Text(
            if (hasHrData) "route is colored by your effort intensity — blue = recovery, red = sprint"
            else "Fit Band 3 didn't push HR samples during this ride. Route shown in green; future rides will color by zone.",
            color = UrujMuted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PointDetailPanel(
    point: ZonedPoint,
    rideStartMs: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsedSec = ((point.sample.timestampMs - rideStartMs) / 1000L).coerceAtLeast(0L)
    val elapsedMin = elapsedSec / 60
    val elapsedSecRem = elapsedSec % 60
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = modifier
            .padding(12.dp)
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                point.zone?.let { "${it.name} · ${it.label}" } ?: "POINT DETAILS",
                color = point.zone?.let { zoneColor(it) } ?: UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("✕", color = UrujMuted, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailRow("AT", "${elapsedMin}:${"%02d".format(elapsedSecRem)} into ride")
        DetailRow("CLOCK", timeFmt.format(Date(point.sample.timestampMs)))
        if (point.sample.hrBpm != null) {
            DetailRow("HEART RATE", "${point.sample.hrBpm} bpm")
        } else {
            // Explicit "not recorded" row instead of hiding silently — tells the
            // rider whether HR was zero (huh?) vs absent from the NDJSON (which
            // is the actual case for rides predating our HC HR pipeline).
            DetailRow("HEART RATE", "not recorded")
        }
        DetailRow("SPEED", "%.1f kph".format(point.sample.speedMetersPerSecond * 3.6f))
        DetailRow("ELEVATION", "%.0f m".format(point.sample.altitudeMeters))
        DetailRow("GPS ACCURACY", "±%.0f m".format(point.sample.horizontalAccuracyMeters))
        if (point.sample.isPaused) {
            Spacer(Modifier.height(4.dp))
            Text(
                "PAUSED",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            color = UrujText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

// ---- Helpers ----

private const val OSMDROID_PREFS = "osmdroid_prefs"

/**
 * Group consecutive same-zone points into Polyline objects with that zone's
 * color. One Polyline per zone-run is faster than coloring per-segment and
 * still produces a visually-gradient route.
 */
private fun buildSegmentedPolylines(points: List<ZonedPoint>): List<Polyline> {
    if (points.size < 2) return emptyList()
    val polylines = mutableListOf<Polyline>()
    var currentZone: HrZone? = points.first().zone
    var currentRun = mutableListOf<GeoPoint>(points.first().sample.let { GeoPoint(it.latitude, it.longitude) })

    fun flush() {
        if (currentRun.size < 2) return
        val polyline = Polyline().apply {
            setPoints(currentRun)
            outlinePaint.color = androidColorForZone(currentZone)
            // 16f stroke + anti-aliasing — chunky enough to stand out from
            // OSM street rendering, especially when zone is null and the
            // line is solid green rather than mixed-color.
            outlinePaint.strokeWidth = 16f
            outlinePaint.isAntiAlias = true
        }
        polylines += polyline
    }

    for (i in 1 until points.size) {
        val p = points[i]
        if (p.zone != currentZone) {
            // Include the transition point in both runs for visual continuity
            currentRun.add(GeoPoint(p.sample.latitude, p.sample.longitude))
            flush()
            currentRun = mutableListOf(GeoPoint(p.sample.latitude, p.sample.longitude))
            currentZone = p.zone
        } else {
            currentRun.add(GeoPoint(p.sample.latitude, p.sample.longitude))
        }
    }
    flush()
    return polylines
}

private fun androidColorForZone(zone: HrZone?): Int = when (zone) {
    HrZone.Z1 -> AndroidColor.parseColor("#42A5F5")  // matches UrujZone1 blue
    HrZone.Z2 -> AndroidColor.parseColor("#00E676")  // green
    HrZone.Z3 -> AndroidColor.parseColor("#FFC107")  // amber
    HrZone.Z4 -> AndroidColor.parseColor("#FF7043")  // orange
    HrZone.Z5 -> AndroidColor.parseColor("#FF1744")  // red
    // No HR data → use URUJ accent green so the route is HIGHLY visible
    // against OSM tiles. Previous muted grey blended into street rendering
    // and made the polyline invisible — major UX bug on older rides whose
    // NDJSON has no per-second HR samples (Fit Band 3 batches HR post-ride).
    null -> AndroidColor.parseColor("#00E676")
}

@Composable
@ReadOnlyComposable
private fun zoneColor(zone: HrZone): Color = when (zone) {
    HrZone.Z1 -> UrujZone1
    HrZone.Z2 -> UrujZone2
    HrZone.Z3 -> UrujZone3
    HrZone.Z4 -> UrujZone4
    HrZone.Z5 -> UrujZone5
}

private fun buildMarker(map: MapView, point: ZonedPoint, isStart: Boolean): Marker {
    return Marker(map).apply {
        position = GeoPoint(point.sample.latitude, point.sample.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = if (isStart) "START" else "END"
        // OSMDroid renders a default marker icon if we don't supply one — fine
        // for MVP. Future: themed pin drawable matching URUJ aesthetic.
    }
}

private fun computeBoundingBox(points: List<ZonedPoint>): BoundingBox? {
    if (points.isEmpty()) return null
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    points.forEach {
        val lat = it.sample.latitude
        val lon = it.sample.longitude
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }
    if (!minLat.isFinite() || !maxLat.isFinite()) return null
    return BoundingBox(maxLat, maxLon, minLat, minLon)
}

/**
 * Find the point on the route closest to a tapped GeoPoint. Simple linear
 * scan — at <10k points and one-shot per tap, the O(n) is fine. Future
 * optimization (spatial index) only matters if we render 100k+ point rides.
 */
private fun findNearestPoint(points: List<ZonedPoint>, target: GeoPoint): ZonedPoint? {
    if (points.isEmpty()) return null
    var bestDist = Double.MAX_VALUE
    var best: ZonedPoint? = null
    for (p in points) {
        val dLat = p.sample.latitude - target.latitude
        val dLon = p.sample.longitude - target.longitude
        // Squared planar distance — fine for "nearest" comparison at city scale.
        val d = dLat.pow(2) + dLon.pow(2)
        if (d < bestDist) {
            bestDist = d
            best = p
        }
    }
    return best
}

// Suppress unused warning — sqrt isn't called but imported for the planar
// distance comment above to be self-explanatory.
@Suppress("unused") private val unused = ::sqrt
