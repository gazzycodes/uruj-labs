package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import com.uruj.domain.CarResult
import com.uruj.power.CarDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * v0.7.2 — Cortisol Awakening Response repository.
 *
 * Computes CAR for the most-recent overnight sleep window from 24/7 NDJSON
 * data, caches per-day result on disk so the morning Bio Lab open doesn't
 * recompute every refresh.
 *
 * Storage: `/Android/data/com.uruj/files/tests/car/YYYY-MM-DD.json`.
 * Keyed by date (one CAR per night). Re-running on the same day overwrites
 * (e.g. after Samsung's post-wake sleep-window correction lands).
 *
 * Compute is on-demand from BioLabRepository.snapshot(). If the most recent
 * SleepSessionRecord ended <45 min ago, we don't have a full post-wake
 * window yet — return null and try again on next refresh.
 */
class CarRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File = File(
        appContext.getExternalFilesDir(null),
        "tests/car",
    ).apply { mkdirs() }
    private val json = Json { encodeDefaults = false; prettyPrint = true }
    private val continuousRepo = ContinuousBiometricRepository(appContext)
    private val lastSleepReader = LastSleepReader()
    private val detector = CarDetector()

    /**
     * Compute CAR for the most-recent completed sleep window, OR return the
     * cached result if already computed today.
     *
     * Returns null when:
     *   - No Samsung SleepSessionRecord available
     *   - Last sleep ended <45 min ago (window incomplete)
     *   - Not enough 24/7 samples in baseline or post-wake window
     */
    suspend fun computeForLastWake(): CarResult? = withContext(Dispatchers.IO) {
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) ==
            HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext null
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .getOrNull() ?: return@withContext null
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
            return@withContext null
        }

        val sleep = lastSleepReader.read(client, granted) ?: return@withContext null
        val sleepEnd = sleep.endedAt
        val now = Instant.now()
        // Need full 45-min post-wake window to compute reliably.
        if (Duration.between(sleepEnd, now).toMinutes() < 45) return@withContext null

        // Check cache first — same day same wake = same result.
        val dateKey = sleepEnd.atZone(ZoneId.systemDefault())
            .format(FILE_DATE_FORMAT)
        val cacheFile = File(baseDir, "$dateKey.json")
        if (cacheFile.exists()) {
            val cached = runCatching {
                json.decodeFromString(CarResult.serializer(), cacheFile.readText())
            }.getOrNull()
            // Use cache only if it corresponds to this wake event (sleepEnd
            // matches within a minute).
            if (cached != null &&
                kotlin.math.abs(cached.sleepEndMs - sleepEnd.toEpochMilli()) < 60_000L
            ) {
                return@withContext cached
            }
        }

        // Slice samples for both windows.
        val preWakeStart = sleepEnd.minus(Duration.ofMinutes(10))
        val postWakeEnd = sleepEnd.plus(Duration.ofMinutes(45))
        val preWake = continuousRepo.samplesForWindow(preWakeStart, sleepEnd)
        val postWake = continuousRepo.samplesForWindow(sleepEnd, postWakeEnd)
        Log.d(
            TAG,
            "[car] wake=$sleepEnd preWake=${preWake.size} postWake=${postWake.size}",
        )

        val result = detector.compute(
            sleepEndMs = sleepEnd.toEpochMilli(),
            preWakeSamples = preWake,
            postWakeSamples = postWake,
        ) ?: return@withContext null

        // Cache.
        runCatching {
            cacheFile.writeText(json.encodeToString(CarResult.serializer(), result))
        }.onFailure { Log.w(TAG, "[car] cache write failed", it) }
        result
    }

    /** Latest cached result on disk (for fast Bio Lab render before refresh). */
    fun cachedLatest(): CarResult? {
        if (!baseDir.exists()) return null
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?: return null
        return files
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(CarResult.serializer(), f.readText())
                }.getOrNull()
            }
            .maxByOrNull { it.sleepEndMs }
    }

    /** All cached results, newest first (for v0.7.3 trend chart). */
    fun listAll(): List<CarResult> {
        if (!baseDir.exists()) return emptyList()
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(CarResult.serializer(), f.readText())
                }.getOrNull()
            }
            .sortedByDescending { it.sleepEndMs }
    }

    companion object {
        private const val TAG = "URUJ-CAR-Repo"
        private val FILE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
