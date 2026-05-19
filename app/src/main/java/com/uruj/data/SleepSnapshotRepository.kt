package com.uruj.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * v0.9.8 — disk-persisted daily sleep-hours snapshots.
 *
 * # Why
 *
 * Sleep is the SINGLE LARGEST input to Readiness scoring (35% weight) yet
 * pre-v0.9.8 it lived ONLY in Health Connect — which Samsung retains for
 * ~30 days. Beyond that window URUJ couldn't show the rider their sleep
 * pattern. RHR/VO2/TSB derivatives WERE persisted, but the raw underlying
 * sleep-hours per night that drove them were lost. Asymmetric persistence.
 *
 * v0.9.8 closes the gap: every time Readiness or Bio Lab computes today's
 * sleep via [LastSleepReader], the resulting hours + session window + source
 * are snapshotted to disk. Sleep-hours trend chart unlocked, future Bio Lab
 * sleep card has data to populate, AI coach (Task #105) has audit trail of
 * what sleep duration drove each day's recommendation.
 *
 * # Storage
 *
 * `/Android/data/com.uruj/files/snapshots/sleep/YYYY-MM-DD.json`, one file
 * per local calendar day. Today's snapshot is MUTABLE (Samsung's HC sync
 * can refine the sleep session boundary mid-morning as more data arrives).
 * Past dates are IMMUTABLE so the trend chart never drifts retroactively.
 *
 * Same shape as v0.9.0/v0.9.1 snapshot repos
 * ([HrrSnapshotRepository], [RhrSnapshotRepository], [Vo2SnapshotRepository],
 * [TsbSnapshotRepository], [RecommendationSnapshotRepository]). Per
 * [[reference_snapshot_persistence_architecture]] all training-signal data
 * persists daily. Per [[reference_readiness_context_architecture]] sleep
 * field is already in `ReadinessContext.TodaySnapshot`; this repo gives
 * trends.sleep a real history to compute slope/direction from.
 *
 * # Note on sleep STAGES
 *
 * URUJ deliberately does NOT persist sleep stages (REM / Deep / Light /
 * Awake durations) — those live in Samsung Health (cut from URUJ in v0.4.0
 * per [[reference_cut_features_v0_4]]). URUJ's value-add is the HR/HRV
 * derivative metrics; stages are Samsung's domain.
 *
 * @see [[reference_snapshot_persistence_architecture]]
 */
class SleepSnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/sleep").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persist today's sleep snapshot. Idempotent: today overwrites
     * (Samsung's mid-morning HC sync can extend the night), past dates
     * immutable.
     */
    suspend fun save(snapshot: SleepSnapshot, date: LocalDate = LocalDate.now()): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${date}.json")
            val today = LocalDate.now()
            if (date != today && file.exists()) {
                Log.d(TAG, "skipping save: $date is historical (immutable)")
                return@withContext false
            }
            runCatching {
                file.writeText(json.encodeToString(SleepSnapshot.serializer(), snapshot))
                Log.d(
                    TAG,
                    "saved sleep snapshot $date: ${"%.2f".format(snapshot.hoursTotal)}h " +
                        "(source ${snapshot.source})",
                )
                true
            }.getOrElse { e ->
                Log.w(TAG, "failed to save sleep snapshot $date", e)
                false
            }
        }

    suspend fun load(date: LocalDate): SleepSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$date.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(SleepSnapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt sleep snapshot $date — skipping", e)
            null
        }
    }

    suspend fun listAll(): List<SleepSnapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(SleepSnapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt sleep snapshot ${file.name} — skipping", e)
                null
            }
        }.sortedByDescending { it.dateIsoLocal }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-SleepSnap"

        /**
         * Bump when the sleep-hours computation methodology changes
         * meaningfully. Currently we use Samsung's SleepSessionRecord
         * `endTime - startTime` directly (matches Samsung's "Sleep Time"
         * display). If we ever switch to inferring actual sleep duration
         * by filtering AWAKE stages, bump this tag.
         */
        const val METHODOLOGY_VERSION = "v0.9.8-samsung-session-duration"
    }
}

/**
 * v0.9.8 — disk schema for one night's sleep.
 *
 * Hours, session window, source. No stages — those belong to Samsung
 * Health per the v0.4.0 cut decision.
 */
@Serializable
data class SleepSnapshot(
    /** ISO-8601 local date the sleep session ENDED. So a night that starts
     *  at 23:30 on May 18 and ends at 07:00 on May 19 saves as 2026-05-19. */
    val dateIsoLocal: String,
    /** Total hours from session.startTime to session.endTime. Samsung
     *  exposes this as "Sleep Time" — matches their display semantics. */
    val hoursTotal: Float,
    /** Session start epoch ms (24h clock; can be previous local day). */
    val sessionStartMs: Long,
    /** Session end epoch ms. The "wake" timestamp CAR + Readiness anchor to. */
    val sessionEndMs: Long,
    /** "samsung-hc" / "manual" / "imputed". For now only samsung-hc populates. */
    val source: String,
    val methodologyVersion: String,
    val computedAtMs: Long,
)
