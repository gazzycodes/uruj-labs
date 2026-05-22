package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.MealMark
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.9.31 — Disk-first persistence for [MealMark] events.
 *
 * Mirrors the snapshot-repository pattern (per
 * [[reference_snapshot_persistence_architecture]]):
 *   - One file per mark at `/files/meal_marks/<id>.json`
 *   - All suspending I/O on Dispatchers.IO
 *   - Cancellation rule respected (`.rethrowCancellation()` per
 *     [[reference_coroutine_cancellation_rule]])
 *
 * NOT subject to the today-mutable/past-immutable rule that applies to
 * snapshot repos — meal marks are user-triggered events captured at
 * exact moments. Once saved, they only get DELETED (via future
 * long-press UX) but never overwritten.
 */
class MealMarkRepository(context: Context) {

    private val baseDir = File(context.applicationContext.filesDir, "meal_marks")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Save a meal mark. Idempotent (overwriting same id is a no-op behavior-wise). */
    suspend fun save(mark: MealMark): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "${mark.id}.json")
        runCatching {
            file.writeText(json.encodeToString(MealMark.serializer(), mark))
            Log.d(
                TAG,
                "saved meal mark ${mark.id} at ${mark.timestampMs} " +
                    "(${mark.source}${mark.note?.let { " · note: $it" } ?: ""})",
            )
            true
        }.rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "save failed for mark ${mark.id}", it)
                false
            }
    }

    /** Load a single mark by id, or null if not found. */
    suspend fun load(id: String): MealMark? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(MealMark.serializer(), file.readText()) }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "load failed for $id", it)
                null
            }
    }

    /** All marks, newest first. */
    suspend fun listAll(): List<MealMark> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(MealMark.serializer(), file.readText()) }
                    .rethrowCancellation()
                    .getOrNull()
            }
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()
    }

    /**
     * Marks within the last [windowMs] from `nowMs`, newest first.
     * Default: last 7 days. Used by BioLabRepository to find marks that
     * may have unprocessed postprandial windows still computable from
     * 24/7 NDJSON history.
     */
    suspend fun listRecent(
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = 7L * 24L * 60L * 60L * 1000L,
    ): List<MealMark> = withContext(Dispatchers.IO) {
        val cutoff = nowMs - windowMs
        listAll().filter { it.timestampMs >= cutoff }
    }

    /** Delete a mark by id. Cascades cleanup of any PostprandialSnapshot is
     *  the caller's responsibility (caller should delete that file first). */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$id.json")
        if (!file.exists()) return@withContext false
        runCatching { file.delete() }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "delete failed for $id", it)
                false
            }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-MealMark"
    }
}
