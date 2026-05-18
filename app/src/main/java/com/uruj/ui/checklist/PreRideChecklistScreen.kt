package com.uruj.ui.checklist

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.BuildConfig
import com.uruj.sensor.android.HealthConnectPermissions
import com.uruj.ui.branding.UrujLogo
import com.uruj.ui.theme.URUJTheme
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5

@Composable
fun PreRideChecklistScreen(
    onStartRide: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenBioLab: () -> Unit = {},
    onOpenLive: () -> Unit = {},
    viewModel: ChecklistViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val readinessSnapshot by viewModel.readinessSnapshot.collectAsStateWithLifecycle()
    val readinessSyncing by viewModel.readinessSyncing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* state refresh happens on next poll tick */ }

    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { /* state refresh happens on next poll tick */ }

    fun launchFix(id: CheckId) {
        when (id) {
            CheckId.LocationPermission -> locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            CheckId.NotificationPermission -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    locationPermissionLauncher.launch(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    )
                }
            }
            CheckId.LocationServices -> safeStart(
                context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )
            CheckId.HealthConnectInstalled -> {
                val direct = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                )
                try {
                    context.startActivity(direct)
                } catch (_: ActivityNotFoundException) {
                    safeStart(context, fallback)
                }
            }
            CheckId.HealthConnectPermission -> healthConnectPermissionLauncher.launch(
                // Request HR + HRV + Sleep + Resting HR in one flow — unlocks readiness
                // scoring + post-ride HR enrichment with a single user grant.
                HealthConnectPermissions.allReadPermissions,
            )
            CheckId.HealthConnectRecentHr -> {
                val samsungHealth = context.packageManager
                    .getLaunchIntentForPackage("com.sec.android.app.shealth")
                if (samsungHealth != null) context.startActivity(samsungHealth)
            }
            CheckId.BatteryOptimization -> {
                val pkg = context.packageName
                // Try direct system dialog first; fall back to the global list, then to app
                // settings. OEMs disable some of these intents (looking at you, OxygenOS).
                val candidates = listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                    },
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$pkg")
                    },
                )
                candidates.firstNotNullOfOrNull { intent ->
                    runCatching { context.startActivity(intent) }.getOrNull()
                }
            }
            CheckId.OpenTracksInstalled -> safeStart(
                context,
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/de.dennisguse.opentracks/"),
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Single LazyColumn for the entire screen — header, readiness card, every
        // checklist row, AND the start button are all items. No nested scroll
        // viewports, no glued/fixed regions, the whole UX flows as one page.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("header") {
                UrujHeader(
                    onOpenProfile = onOpenProfile,
                    onOpenHistory = onOpenHistory,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenBioLab = onOpenBioLab,
                    onOpenLive = onOpenLive,
                )
            }
            // Readiness card is the primary status indicator on this screen,
            // so it sits ABOVE the checklist title. The title introduces the
            // pre-ride checks section that follows, not the readiness panel.
            item("readiness") {
                Spacer(Modifier.height(8.dp))
                ReadinessCard(
                    result = readiness,
                    snapshot = readinessSnapshot,
                    syncing = readinessSyncing,
                    onRefresh = { viewModel.refreshReadiness() },
                )
            }
            item("title") {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    Text(
                        text = "PRE-RIDE",
                        color = UrujMuted,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "Checklist",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                    )
                }
            }
            item("checks_spacer") { Spacer(Modifier.height(4.dp)) }
            items(state.items, key = { it.id }) { item ->
                CheckRow(item = item, onFix = { launchFix(item.id) })
            }
            // v0.8.0 — session intent picker just above START RIDE. Default
            // last-picked (sticks across rides) so user doesn't pick again
            // if they ride twice in a day. Can also override mid-ride from
            // the HUD.
            item("session_intent_picker") {
                SessionIntentPicker()
            }
            item("start_button") {
                Button(
                    onClick = onStartRide,
                    enabled = state.canStartRide,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujAccent,
                        contentColor = Color.Black,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = UrujMuted,
                    ),
                ) {
                    Text(
                        text = "START RIDE",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 4.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun UrujHeader(
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBioLab: () -> Unit,
    onOpenLive: () -> Unit,
) {
    // Two-row header: brand on top, navigation underneath. Four nav buttons in
    // a single row was overflowing on ~360dp phones (PROFILE got pushed off the
    // right edge). Splitting gives every action equal space and breathing room.
    val navPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UrujLogo(size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "URUJ LABS",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = UrujAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton(label = "LAB", onClick = onOpenBioLab, contentPadding = navPadding)
            NavButton(label = "LIVE", onClick = onOpenLive, contentPadding = navPadding)
            NavButton(label = "PIPELINE", onClick = onOpenDiagnostics, contentPadding = navPadding)
            NavButton(label = "RIDES", onClick = onOpenHistory, contentPadding = navPadding)
            NavButton(label = "PROFILE", onClick = onOpenProfile, contentPadding = navPadding)
        }
    }
}

@Composable
private fun NavButton(label: String, onClick: () -> Unit, contentPadding: PaddingValues) {
    TextButton(onClick = onClick, contentPadding = contentPadding) {
        Text(
            text = label,
            color = UrujAccent,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CheckRow(item: CheckItem, onFix: () -> Unit) {
    val (statusColor, statusGlyph) = when (item.status) {
        CheckStatus.Pass -> UrujZone2 to "●"
        CheckStatus.Warning -> UrujZone3 to "▲"
        CheckStatus.Fail -> UrujZone5 to "✕"
        CheckStatus.Pending -> UrujMuted to "·"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusGlyph,
                color = statusColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    text = item.description,
                    color = UrujMuted,
                    fontSize = 13.sp,
                )
            }
            if (item.canFix) {
                TextButton(onClick = onFix) {
                    Text(
                        text = "FIX",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                    )
                }
            }
        }
    }
}

private fun safeStart(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No-op — no app installed to handle this intent. Future: surface a snackbar.
    }
}

@Preview
@Composable
private fun PreRideChecklistPreview() {
    URUJTheme {
        PreRideChecklistScreen(onStartRide = {})
    }
}
