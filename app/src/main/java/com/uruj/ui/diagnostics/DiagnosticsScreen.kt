package com.uruj.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.data.HcCategory
import com.uruj.data.HcDataTypeStatus
import com.uruj.data.HcDataTypes
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lastRefreshAt by viewModel.lastRefreshAtMs.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refresh() }

    val byCategory = inventory.groupBy { it.type.category }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PIPELINE",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) {
                    Text(
                        "CLOSE",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                    )
                }
            }
            Text(
                "Health Connect Inventory",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Every data type URUJ can pull. Green = flowing. Grey = no data yet. Red = no permission.",
                color = UrujMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { permLauncher.launch(HcDataTypes.allReadPermissions) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujAccent,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        "GRANT ALL",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.refresh() },
                    enabled = !isRefreshing,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujSurface,
                        contentColor = UrujAccent,
                    ),
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = UrujAccent,
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (isRefreshing) "REFRESHING" else "↻ REFRESH",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }

            if (lastRefreshAt != null) {
                val ageSec = ((System.currentTimeMillis() - lastRefreshAt!!) / 1000)
                    .coerceAtLeast(0)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last refresh ${if (ageSec < 5) "just now" else "${ageSec}s ago"}",
                    color = UrujMuted,
                    fontSize = 10.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            val totalGreen = inventory.count { it.health == HcDataTypeStatus.Health.Working }
            val totalRed = inventory.count { it.health == HcDataTypeStatus.Health.NoPermission }
            val totalGrey = inventory.count { it.health == HcDataTypeStatus.Health.NoData }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(UrujZone2)
                Spacer(Modifier.width(4.dp))
                Text(
                    "$totalGreen flowing",
                    color = UrujText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(12.dp))
                LegendDot(UrujMuted)
                Spacer(Modifier.width(4.dp))
                Text(
                    "$totalGrey no data",
                    color = UrujText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(12.dp))
                LegendDot(UrujZone5)
                Spacer(Modifier.width(4.dp))
                Text(
                    "$totalRed blocked",
                    color = UrujText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v0.5.0 — BLE chest strap test panel at top so it's the first
                // thing the rider sees in Diagnostics. Self-contained: holds
                // its own BleHrSource lifecycle scoped to composition.
                item(key = "ble_strap_test") {
                    StrapTestCard()
                    Spacer(Modifier.height(8.dp))
                }
                HcCategory.entries.forEach { cat ->
                    val items = byCategory[cat].orEmpty()
                    if (items.isNotEmpty()) {
                        item(key = "header_${cat.name}") {
                            Text(
                                text = cat.displayName.uppercase(),
                                color = UrujMuted,
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 2.sp,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(items, key = { it.type.key }) { status ->
                            DataTypeRow(status)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataTypeRow(status: HcDataTypeStatus) {
    val (color, label) = when (status.health) {
        HcDataTypeStatus.Health.Working -> UrujZone2 to "${status.recordCount7d}"
        HcDataTypeStatus.Health.NoData -> UrujMuted to "0"
        HcDataTypeStatus.Health.NoPermission -> UrujZone5 to "—"
        HcDataTypeStatus.Health.Error -> UrujZone3 to "ERR"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(10.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                status.type.displayName,
                color = UrujText,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                "from ${status.type.expectedSource.displayName}" +
                    if (status.errorMessage != null) " · ${status.errorMessage}" else "",
                color = UrujMuted,
                fontSize = 10.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                label,
                color = color,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
            Text(
                "records · 7d",
                color = UrujMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
