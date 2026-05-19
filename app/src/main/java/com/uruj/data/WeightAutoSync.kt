package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * v0.9.12 — one-way Samsung Health → URUJ Profile weight sync.
 *
 * # Why
 *
 * Pre-v0.9.12: rider updated naked weight on the Samsung scale (synced to
 * Samsung Health → Health Connect) but URUJ's [RiderProfile.riderWeightKg]
 * stayed at whatever was manually entered. Power-model calculations
 * (Watts = mass × terms) used stale weight. VO2 estimates that depend on
 * weight (future power-based) also drifted.
 *
 * # Architecture (respects [[reference_hc_rate_limit_architecture]])
 *
 * - One HC read per app open (or per Profile screen open). NOT background.
 * - 5-minute in-memory cooldown so rapid Profile re-opens don't spam HC.
 * - HcReadGuard.recordRead("profile.weight-sync") for telemetry.
 * - Skips during post-ride quiet window.
 * - Writes to RiderProfileStore.saveWeightFromHc — preserves all other
 *   profile fields. UI surfaces "synced from Samsung X ago" via
 *   RiderProfileStore.lastWeightSyncMs flow.
 *
 * # Limits
 *
 * - HC retains WeightRecord ~30 days. Older scale weights stay in Samsung
 *   Health proper but not URUJ. That's fine — we only need the LATEST.
 * - Manual edits in URUJ Profile take precedence on the same open
 *   (user-entered weight wins until the next sync).
 * - First-time-no-HC-data path: returns null, UI shows manual-edit field
 *   only. No false sync.
 */
class WeightAutoSync(context: Context) {

    private val appContext = context.applicationContext
    private val profileStore = RiderProfileStore(appContext)

    @Volatile private var lastSyncMs: Long = 0L

    /**
     * Try to sync today's weight from HC. Returns the new weight if synced,
     * null otherwise (cooldown active / HC unavailable / no records /
     * post-ride quiet window / failure — all safe-default).
     *
     * Idempotent: if HC's most-recent WeightRecord matches what's already
     * in RiderProfile, this is a no-op write (DataStore handles dedup
     * internally — same kg → same value → no listener fires).
     */
    suspend fun trySync(): Float? = withContext(Dispatchers.IO) {
        if (HcReadGuard.isPostRideQuietWindow()) {
            Log.d(TAG, "[v0.9.12] sync skipped — post-ride quiet window")
            return@withContext null
        }
        val now = System.currentTimeMillis()
        if (lastSyncMs != 0L && now - lastSyncMs < COOLDOWN_MS) {
            val ageS = (now - lastSyncMs) / 1000L
            Log.d(TAG, "[v0.9.12] sync skipped — last sync ${ageS}s ago (<300s cooldown)")
            return@withContext null
        }
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) ==
            HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext null
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .getOrNull() ?: return@withContext null
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(WeightRecord::class) !in granted) {
            Log.d(TAG, "[v0.9.12] sync skipped — WeightRecord perm missing")
            return@withContext null
        }
        lastSyncMs = now
        HcReadGuard.recordRead("profile.weight-sync")
        val records = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.now().minus(Duration.ofDays(30)),
                        Instant.now(),
                    ),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records
        }.onFailure { Log.w(TAG, "[v0.9.12] WeightRecord read failed", it) }
            .getOrDefault(emptyList())
        val latest = records.firstOrNull() ?: run {
            Log.d(TAG, "[v0.9.12] no WeightRecord in last 30d")
            return@withContext null
        }
        val weightKg = latest.weight.inKilograms.toFloat()
        if (weightKg !in 30f..250f) {
            Log.w(TAG, "[v0.9.12] WeightRecord out-of-range: $weightKg kg — skipping")
            return@withContext null
        }
        profileStore.saveWeightFromHc(weightKg, now)
        Log.d(TAG, "[v0.9.12] synced weight: ${"%.2f".format(weightKg)} kg from Samsung")
        weightKg
    }

    companion object {
        private const val TAG = "URUJ-WeightSync"
        private const val COOLDOWN_MS = 5L * 60L * 1000L
    }
}
