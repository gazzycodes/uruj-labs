package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.TrackerEntry
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.39 — Disk-first persistence for [TrackerEntry] records (#111
 * in-app tracker layer).
 *
 * Layout:
 *   /files/trackers/
 *     mood/<UUID>.json
 *     energy/<UUID>.json
 *     hydration_ml/<UUID>.json
 *     caffeine_mg/<UUID>.json
 *     ...
 *
 * One file per entry. Per [[reference_snapshot_persistence_architecture]]:
 * trend charts read disk only, never HC, never recomputed from cache.
 * Past entries immutable; deletion is the only edit operation.
 *
 * **Coroutine cancellation**: all suspend I/O chains `.rethrowCancellation()`
 * per [[reference_coroutine_cancellation_rule]].
 *
 * **Lab-level rule 3 (methodology version)**: tracker entries don't have a
 * methodology version because they're rider-self-reported, not computed.
 * The `source` field on TrackerEntry serves as provenance.
 */
class TrackerRepository(context: Context) {

    private val baseDir = File(context.applicationContext.filesDir, "trackers")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Save a tracker entry. Creates the type sub-directory if needed. */
    suspend fun save(entry: TrackerEntry): Boolean = withContext(Dispatchers.IO) {
        val typeDir = File(baseDir, entry.type)
        if (!typeDir.exists()) typeDir.mkdirs()
        val file = File(typeDir, "${entry.id}.json")
        runCatching {
            file.writeText(json.encodeToString(TrackerEntry.serializer(), entry))
            Log.d(
                TAG,
                "saved tracker ${entry.type}/${entry.id} @ ${entry.timestampMs}: " +
                    "num=${entry.numericValue ?: "—"} · text=${entry.textValue ?: "—"} · " +
                    "note=${entry.note ?: "—"} · source=${entry.source}",
            )
            true
        }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "save failed for ${entry.type}/${entry.id}", it)
                false
            }
    }

    /** All entries for a given tracker type, newest first. */
    suspend fun listForType(type: String): List<TrackerEntry> = withContext(Dispatchers.IO) {
        val typeDir = File(baseDir, type)
        if (!typeDir.exists()) return@withContext emptyList()
        typeDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(TrackerEntry.serializer(), file.readText()) }
                    .rethrowCancellation()
                    .getOrNull()
            }
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()
    }

    /**
     * Entries for a given type recorded today (local timezone).
     * Used by Bio Lab cards to display today's running totals + latest values.
     */
    suspend fun listForToday(
        type: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TrackerEntry> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        listForType(type).filter { entry ->
            Instant.ofEpochMilli(entry.timestampMs).atZone(zone).toLocalDate() == today
        }
    }

    /** Delete an entry by type + id. Used by long-press deletion UX. */
    suspend fun delete(type: String, id: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$type/$id.json")
        if (!file.exists()) return@withContext false
        runCatching { file.delete() }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "delete failed for $type/$id", it)
                false
            }
    }

    /** Count entries for a tracker type — useful for "X entries on disk" UX. */
    suspend fun count(type: String): Int = withContext(Dispatchers.IO) {
        val typeDir = File(baseDir, type)
        if (!typeDir.exists()) return@withContext 0
        typeDir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-Tracker"
    }
}
