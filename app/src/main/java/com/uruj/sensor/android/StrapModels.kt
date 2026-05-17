package com.uruj.sensor.android

/**
 * v0.6.0 — Friendly-name resolver for BLE chest straps. Manufacturers often
 * report cryptic internal SKU codes in the standard Device Information
 * Service Model Number String characteristic (0x2A24) instead of marketing
 * names. e.g. Magene H613 reports "340". This lookup converts to the
 * consumer-facing model so URUJ's UI doesn't show meaningless integers.
 *
 * Extensible: add an entry when new straps are tested. Falls back to
 * "Manufacturer + Model" if not in the table.
 */
object StrapModels {

    /** (manufacturer, model-code) → friendly marketing name. */
    private val KNOWN = mapOf(
        "Magene" to mapOf(
            "340" to "H613",
            "337" to "H603",
            "335" to "H303",
        ),
        // Add Polar / Wahoo / CooSpo as we test them.
    )

    /**
     * Returns the consumer-facing name. Priority:
     *   1. Mapped friendly name (e.g. "H613") if we know this manufacturer+code
     *   2. "Manufacturer Model" concat (e.g. "Magene 340") if both fields present
     *   3. Just the model code if only that's known
     *   4. Just the manufacturer if only that's known
     *   5. null if neither known
     */
    fun friendlyName(manufacturer: String?, model: String?): String? {
        val mfr = manufacturer?.trim()
        val mdl = model?.trim()
        if (mfr != null && mdl != null) {
            val mapped = KNOWN[mfr]?.get(mdl)
            return when {
                mapped != null -> "$mapped ($mfr)"
                else -> "$mfr $mdl"
            }
        }
        return mfr ?: mdl
    }
}
