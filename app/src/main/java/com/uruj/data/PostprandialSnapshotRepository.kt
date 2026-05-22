package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.PostprandialSnapshot
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.9.31 — Disk-first persistence for [PostprandialSnapshot] results.
 *
 * Per [[reference_snapshot_persistence_architecture]] BULLETPROOF rule —
 * every trend metric persists at compute time, trend charts read disk
 * only. HC's 30-day retention can never cap postprandial test history
 * (relevant for tier-7 long-arc patterns: meal sensitivity trends over
 * months reveal metabolic adaptation).
 *
 * One file per snapshot at `/files/snapshots/postprandial/<mealMarkId>.json`.
 *
 * Past snapshots are immutable. Recomputing the same meal mark id is a
 * no-op (caller should check existing first via [load]).
 *
 * Methodology version is stamped per snapshot for forward calc-change
 * traceability (lab-level rule 3).
 */
class PostprandialSnapshotRepository(context: Context) {

    private val baseDir = File(context.applicationContext.filesDir, "snapshots/postprandial")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Save a postprandial snapshot. Idempotent — if a snapshot with the
     * same `mealMarkId` exists, it is overwritten (typically called only
     * once per meal mark anyway because BioLabRepository skips already-
     * processed marks).
     */
    suspend fun save(snapshot: PostprandialSnapshot): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "${snapshot.mealMarkId}.json")
        runCatching {
            file.writeText(json.encodeToString(PostprandialSnapshot.serializer(), snapshot))
            Log.d(
                TAG,
                "saved postprandial snapshot ${snapshot.mealMarkId} (meal at ${snapshot.mealMarkMs}): " +
                    "rmssd pre=${snapshot.preRmssdMs?.let { "%.1f".format(it) } ?: "—"} ms · " +
                    "post=${snapshot.postRmssdMs?.let { "%.1f".format(it) } ?: "—"} ms · " +
                    "Δ=${snapshot.rmssdDeltaPercent?.let { "%.1f%%".format(it) } ?: "—"} · " +
                    "lf/hf pre=${snapshot.preLfHfRatio?.let { "%.2f".format(it) } ?: "—"} · " +
                    "post=${snapshot.postLfHfRatio?.let { "%.2f".format(it) } ?: "—"} · " +
                    "source=${snapshot.source}",
            )
            true
        }.rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "save failed for ${snapshot.mealMarkId}", it)
                false
            }
    }

    /** Load snapshot for a specific meal-mark id. */
    suspend fun load(mealMarkId: String): PostprandialSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$mealMarkId.json")
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(PostprandialSnapshot.serializer(), file.readText()) }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "load failed for $mealMarkId", it)
                null
            }
    }

    /** All snapshots, newest meal-mark first. */
    suspend fun listAll(): List<PostprandialSnapshot> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(PostprandialSnapshot.serializer(), file.readText()) }
                    .rethrowCancellation()
                    .getOrNull()
            }
            ?.sortedByDescending { it.mealMarkMs }
            ?: emptyList()
    }

    /** True if a snapshot for the given meal mark id already exists. */
    suspend fun exists(mealMarkId: String): Boolean = withContext(Dispatchers.IO) {
        File(baseDir, "$mealMarkId.json").exists()
    }

    /** Delete a snapshot by meal-mark id. */
    suspend fun delete(mealMarkId: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$mealMarkId.json")
        if (!file.exists()) return@withContext false
        runCatching { file.delete() }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "delete failed for $mealMarkId", it)
                false
            }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-PostprandialSnap"

        /** v0.9.31 — Postprandial methodology constants (lab-level rule 3). */
        const val METHODOLOGY_VERSION = "v0.9.31-postprandial-25min-pre-30min-post"

        /** Pre-meal window: 30 min before mark to 5 min before mark (25-min span). */
        const val PRE_WINDOW_START_OFFSET_MS = -30L * 60_000L
        const val PRE_WINDOW_END_OFFSET_MS = -5L * 60_000L

        /** Post-meal window: 45 min after mark to 75 min after mark (30-min span). */
        const val POST_WINDOW_START_OFFSET_MS = 45L * 60_000L
        const val POST_WINDOW_END_OFFSET_MS = 75L * 60_000L

        /** Minimum elapsed time from meal mark before postprandial can be computed. */
        const val MIN_ELAPSED_FOR_COMPUTE_MS = 75L * 60_000L
    }
}
