package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.RecommendationSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * v0.9.4 — disk-persisted daily recommendation snapshots.
 *
 * # Why persist
 *
 * 1. **Multi-day rest tracking** — the reasoner reads this repository
 *    backwards from today to count consecutive `tier == FullRest` (or any
 *    other tier). Without persistence, "you've rested 3 days in a row"
 *    can't be detected.
 * 2. **AI feedback loop** — when v0.5 Groq AI ships, we compare what rules
 *    said vs what AI said vs what the rider actually did vs the outcome.
 *    Requires both rules + AI to write their daily output to disk.
 * 3. **Audit trail** — rider asks "why did URUJ say FullRest on the 12th?"
 *    → load `/snapshots/recommendations/2026-05-12.json` and read the
 *    severe flag list + rationale at that moment.
 * 4. **Pattern detection** — TSB has been underwater 5 days running,
 *    recommendations have escalated. Read the snapshot history to surface
 *    that pattern in the UI.
 *
 * # Storage
 *
 * `/Android/data/com.uruj/files/snapshots/recommendations/YYYY-MM-DD.json`,
 * one file per local calendar day. Idempotent: re-saving same date
 * overwrites (recommendations recompute every Bio Lab open; latest wins).
 *
 * Same shape as the other v0.9.0/v0.9.1 snapshot repos
 * ([HrrSnapshotRepository], [RhrSnapshotRepository], [Vo2SnapshotRepository],
 * [TsbSnapshotRepository]). Per
 * [[reference_snapshot_persistence_architecture]] all training-signal data
 * persists daily.
 *
 * # AI HOOK
 *
 * Future Groq AI reasoner writes its output to the same file shape (with
 * `methodologyVersion` tagged as `groq-claude4-yyyy-mm-dd` or similar).
 * History stays comparable across reasoner generations.
 */
class RecommendationSnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/recommendations").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Save (overwrite OK — recommendations recompute per Bio Lab open;
     * latest of the day wins). Idempotent.
     */
    suspend fun save(snapshot: RecommendationSnapshot, date: LocalDate = LocalDate.now()): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${date}.json")
            runCatching {
                file.writeText(json.encodeToString(RecommendationSnapshot.serializer(), snapshot))
                Log.d(
                    TAG,
                    "saved recommendation $date: ${snapshot.tier} (score ${snapshot.score})",
                )
                true
            }.getOrElse { e ->
                Log.w(TAG, "failed to save recommendation $date", e)
                false
            }
        }

    suspend fun load(date: LocalDate): RecommendationSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$date.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(RecommendationSnapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt recommendation $date — skipping", e)
            null
        }
    }

    /**
     * All snapshots, newest first. Used by the reasoner for multi-day
     * pattern detection (consecutive rest days, low-readiness streaks).
     */
    suspend fun listAll(): List<RecommendationSnapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(RecommendationSnapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt recommendation ${file.name} — skipping", e)
                null
            }
        }.sortedByDescending { it.dateIsoLocal }
    }

    /**
     * Walk backwards from today counting consecutive days where the given
     * predicate holds. Returns 0 if today's snapshot doesn't match. Used
     * for "you've been on FullRest 3 days running" style detection.
     */
    suspend fun consecutiveDaysMatching(
        from: LocalDate = LocalDate.now(),
        predicate: (RecommendationSnapshot) -> Boolean,
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        var date = from
        while (true) {
            val snap = load(date) ?: break
            if (!predicate(snap)) break
            count++
            date = date.minusDays(1)
        }
        count
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-RecSnap"

        /**
         * Bumped when the engine's tier-selection logic, severe-flag rules,
         * or rationale-construction methodology changes meaningfully. Old
         * snapshots stay tagged with the version that produced them so
         * future analysis can compare reasoner generations honestly.
         */
        const val METHODOLOGY_VERSION = "v0.9.4-rule-based-multi-signal"
    }
}
