package com.uruj.domain

/**
 * v0.9.39 — Registry of supported tracker types for the in-app subjective
 * + behavioral logging layer (#111).
 *
 * Each enum entry knows its persistence key (used in JSON + directory name),
 * display name (card title), and unit (for chart axis + ⓘ dialog).
 *
 * **Adding new trackers** (future phases): just add an enum value here +
 * wire a card + trend screen + ⓘ dialog. Data layer, repository, and
 * ReadinessContext signal pack accommodate new types automatically.
 *
 * Phase 1 (v0.9.39): MOOD, ENERGY, HYDRATION_ML, CAFFEINE_MG.
 * Phase 2 (v0.9.40): SUPPLEMENTS, BRISTOL, SLEEP_QUALITY, SORENESS.
 * Phase 3 (v0.9.41): COLD_EXPOSURE, MEDITATION, MORNING_ERECTION.
 * Phase 4 (v0.9.42): DREAM_RECALL, SYMPTOM_JOURNAL.
 */
enum class TrackerType(
    val key: String,
    val displayName: String,
    val unit: String,
) {
    // Phase 1 (v0.9.39)
    MOOD("mood", "Mood", "1-10"),
    ENERGY("energy", "Energy", "1-10"),
    HYDRATION_ML("hydration_ml", "Hydration", "ml"),
    CAFFEINE_MG("caffeine_mg", "Caffeine", "mg"),

    // Phase 2 (v0.9.40) — recovery-period biohacker essentials
    /** Supplement intake (textValue = name, numericValue = optional dose mg). */
    SUPPLEMENTS("supplements", "Supplements", "name + mg"),
    /** Bristol stool scale 1-7 (classic gut-health biohacker metric). */
    BRISTOL("bristol", "Bristol Stool", "1-7"),
    /** Subjective sleep quality 1-10 — cross-check against Samsung's score. */
    SLEEP_QUALITY("sleep_quality", "Sleep Quality", "1-10"),
    /** Subjective soreness 1-10 (textValue = optional body location). */
    SORENESS("soreness", "Soreness", "1-10"),
    ;

    companion object {
        fun fromKey(key: String): TrackerType? = entries.firstOrNull { it.key == key }
    }
}
