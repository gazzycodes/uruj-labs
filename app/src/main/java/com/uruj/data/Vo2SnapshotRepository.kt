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
 * v0.9.1 — disk-persisted daily VO2 max readings.
 *
 * ## Why
 *
 * VO2 max evolves slowly (weeks-to-months) but the trend matters enormously:
 * a steady decline indicates over-training or detraining; a steady climb
 * confirms adaptation. Without disk persistence, the rider's VO2 history
 * caps at HC's 30-day window for Samsung's recorded values + URUJ's daily
 * Uth-Sørensen computes are recomputed every BioLab open from inputs
 * (RHR + maxHR + weight + FTP) that also age out at 30d.
 *
 * Per the snapshot architecture, a daily disk snapshot of URUJ's
 * Uth-Sørensen estimate + Samsung's value (when available) lets the rider
 * see months/years of evolution.
 *
 * ## How
 *
 * One file per calendar day at `/files/snapshots/vo2/YYYY-MM-DD.json`.
 * Stores BOTH URUJ's consensus value AND Samsung's HC-reported value
 * (when present) so future trend charts can show the URUJ vs Samsung
 * side-by-side over time — same transparency moat as the BioLab card.
 *
 * ## File schema
 *
 * ```json
 * {
 *   "dateIsoLocal": "2026-05-19",
 *   "urujConsensusMlKgMin": 66.1,    // URUJ Uth-Sørensen number
 *   "urujHrBasedMlKgMin": 66.1,      // breakdown — HR-based path
 *   "urujPowerBasedMlKgMin": null,   // breakdown — power-based path (null when FTP untested)
 *   "samsungMlKgMin": null,          // Samsung's value if HC has one
 *   "classification": "Elite (top 5%)",
 *   "methodologyVersion": "v0.4.0-uth-sorensen",
 *   "computedAtMs": 1779120000000
 * }
 * ```
 *
 * @see [[reference_snapshot_persistence_architecture]]
 */
class Vo2SnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/vo2").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun save(snapshot: Vo2Snapshot, date: LocalDate = LocalDate.now()): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${date}.json")
            // v0.9.7 — today's snapshot is MUTABLE during the day. VO2 refines
            // when Athletic RHR refines (RHR uses sleep + HC HR samples which
            // can arrive late). Past dates immutable for trend integrity.
            val today = LocalDate.now()
            if (date != today && file.exists()) {
                Log.d(TAG, "skipping save: $date is historical (immutable)")
                return@withContext false
            }
            runCatching {
                file.writeText(json.encodeToString(Vo2Snapshot.serializer(), snapshot))
                Log.d(
                    TAG,
                    "saved VO2 snapshot $date: URUJ ${snapshot.urujConsensusMlKgMin} mL/kg/min " +
                        (snapshot.samsungMlKgMin?.let { "(Samsung $it)" } ?: ""),
                )
                true
            }.getOrElse { e ->
                Log.w(TAG, "failed to save VO2 snapshot $date", e)
                false
            }
        }

    suspend fun load(date: LocalDate): Vo2Snapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$date.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(Vo2Snapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt VO2 snapshot $date — skipping", e)
            null
        }
    }

    suspend fun listAll(): List<Vo2Snapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(Vo2Snapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt VO2 snapshot ${file.name} — skipping", e)
                null
            }
        }.sortedByDescending { it.dateIsoLocal }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    /**
     * v0.9.11 — backfill historical VO2 snapshots from the RHR snapshots
     * already on disk. Pure compute, NO HC reads. Uses the current
     * profile's MaxHR (the rider's MaxHR doesn't change much month-to-month
     * for trained athletes; if they re-test, future Bio Lab opens
     * overwrite-today with the new value via past-immutable rule). Cooper
     * classification re-applied per historical date.
     *
     * For each RhrSnapshot on disk: VO2 ≈ 15.3 × (maxHr / rhrBpm).
     * Skips today (forward-save owns it). Skips dates already on disk.
     * Idempotent.
     *
     * Returns count of new snapshots created.
     */
    suspend fun backfillFromRhr(
        rhrSnapshots: RhrSnapshotRepository,
        maxHr: Int,
    ): Int = withContext(Dispatchers.IO) {
        val rhrEntries = rhrSnapshots.listAll()
        if (rhrEntries.isEmpty() || maxHr < 100) {
            Log.d(TAG, "[v0.9.11] backfill: no RHR entries or invalid maxHr=$maxHr")
            return@withContext 0
        }
        val today = LocalDate.now()
        var written = 0
        for (rhr in rhrEntries) {
            val date = runCatching { LocalDate.parse(rhr.dateIsoLocal) }.getOrNull() ?: continue
            if (date == today) continue  // forward-save owns today
            if (load(date) != null) continue  // already backfilled
            if (rhr.medianBpm < 30 || rhr.medianBpm > 100) continue  // sanity
            val vo2HrBased = 15.3f * (maxHr.toFloat() / rhr.medianBpm.toFloat())
            val classification = classifyCooper(vo2HrBased)
            val saved = save(
                Vo2Snapshot(
                    dateIsoLocal = date.toString(),
                    urujConsensusMlKgMin = vo2HrBased,
                    urujHrBasedMlKgMin = vo2HrBased,
                    urujPowerBasedMlKgMin = null,  // historical: no FTP context
                    samsungMlKgMin = null,
                    classification = classification,
                    methodologyVersion = "v0.9.11-backfill-uth-sorensen-from-disk-rhr",
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = date,
            )
            if (saved) written++
        }
        Log.d(TAG, "[v0.9.11] backfill: $written new VO2 snapshots from disk RHR (${rhrEntries.size} RHR nights)")
        written
    }

    /** Cooper classification (male, 20-29). Adjust if rider profile age expands later. */
    private fun classifyCooper(vo2: Float): String = when {
        vo2 >= 60f -> "Elite (top 5%)"
        vo2 >= 52f -> "Excellent"
        vo2 >= 47f -> "Good"
        vo2 >= 42f -> "Above average"
        else -> "Average or below"
    }

    companion object {
        private const val TAG = "URUJ-Vo2Snap"
        const val METHODOLOGY_VERSION = "v0.4.0-uth-sorensen"
    }
}

@Serializable
data class Vo2Snapshot(
    val dateIsoLocal: String,
    /** URUJ's consensus value (what the BioLab card hero displays). */
    val urujConsensusMlKgMin: Float,
    /** URUJ's HR-based component (Uth-Sørensen formula). */
    val urujHrBasedMlKgMin: Float? = null,
    /** URUJ's power-based component (null when FTP is at default/untested). */
    val urujPowerBasedMlKgMin: Float? = null,
    /** Samsung's HC-reported VO2 max if present on this day's compute. */
    val samsungMlKgMin: Float? = null,
    /** Cooper classification: "Elite (top 5%)", "Excellent", etc. */
    val classification: String,
    // v0.9.49.1 — default "legacy" per [[reference_lab_grade_architecture_rules]]
    // Rule 2 so pre-versioning snapshots deserialize gracefully + future
    // methodology bumps invalidate via mismatch instead of accidental match.
    val methodologyVersion: String = "legacy",
    val computedAtMs: Long,
)
