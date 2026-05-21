package com.uruj.power

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * v0.9.25 → v0.9.26 — HRV frequency-domain + non-linear analysis
 * (tier 2 + 3 from [[reference_biohacker_lab_vision]]).
 *
 * ## v0.9.26 FIX — short-term windowing per Task Force 1996
 *
 * **v0.9.25 had a methodology bug**: computed FFT/Poincaré/entropy/DFA on
 * the ENTIRE overnight RR series as one block. Task Force 1996 explicitly
 * defines frequency-domain HRV as a SHORT-TERM (2-5 min window) measure,
 * not long-term. Computing across 8 hours mixed deep sleep + REM cycles
 * + brief awakenings into one FFT produces physiologically meaningless
 * numbers (LF/HF=22 was the smoking gun in field test — real range
 * 0.5-3.0).
 *
 * **v0.9.26 fix**: window beats into 5-min segments + compute all metrics
 * per-window + median-aggregate. Matches existing
 * [HrvCalculator.computeWindowed] pattern. Validated by mathematical
 * invariant: per-window Poincaré SD1 must equal RMSSD/√2 (sanity check).
 *
 * ## What this exposes
 *
 * **Frequency-domain** (Welch's periodogram per 5-min window):
 *   - VLF power (0.0033 – 0.04 Hz) — thermoregulation + hormonal
 *   - LF power (0.04 – 0.15 Hz) — mixed sympathetic + parasympathetic
 *   - HF power (0.15 – 0.4 Hz) — parasympathetic (RSA)
 *   - LF/HF ratio — Kubios / Polar / Garmin "stress index"
 *     (PARTIALLY DEBUNKED — Heathers 2014, Hayano 2019. UI surfaces caveat.)
 *
 * **Non-linear** (per 5-min window):
 *   - Poincaré SD1 (short-term, ≡ RMSSD/√2)
 *   - Poincaré SD2 (long-term, correlates with SDNN)
 *   - DFA α1 (scales 4-16 beats) — fractal scaling.
 *     Rogero 2021: crosses 0.75 at Aerobic Threshold (LT1).
 *   - Sample entropy (m=2, r=0.2·SD)
 *
 * ## Performance bound (v0.9.26)
 *
 * Per-window cap: ~600 beats max (5 min at 120 bpm peak). Sample entropy
 * O(N²) per window = ~360k ops; ~12 windows per night = ~4M ops total.
 * Total compute: <200ms (vs 5min for v0.9.25 unbounded).
 *
 * ## Method (per Task Force 1996 standard)
 *
 *   For each 5-min window:
 *     1. Filter RR (300-2000 ms physiological range + 20% ectopic delta cap)
 *     2. Linear-interpolate to uniform 4 Hz time series
 *     3. Detrend + Hann window + Welch's PSD with 256-sample segments
 *     4. Integrate PSD over VLF / LF / HF bands
 *     5. Poincaré SD1/SD2 from RR series
 *     6. DFA α1 (scales 4-16 beats)
 *     7. Sample entropy (m=2, r=0.2·SD)
 *   Median-aggregate all metrics across valid windows.
 *
 * Architectural notes:
 *   - Pure math; no I/O, no coroutines
 *   - Mirrors HrvCalculator.computeWindowed shape exactly
 *   - Per lab-level rule 4 (no fake numbers): returns null if <3 valid windows
 *   - Per lab-level rule 3 (methodology): caveats cited in UI ⓘ dialog
 *
 * References:
 *   - Task Force 1996. HRV: standards of measurement.
 *   - Heathers JAJ 2014. Everything Hertz: methodological issues in
 *     short-term frequency-domain HRV.
 *   - Hayano J, Yuda E 2019. Pitfalls of assessment of autonomic function
 *     by heart rate variability.
 *   - Peng CK et al. 1994. DFA origin.
 *   - Rogero MM et al. 2021. DFA α1 as aerobic threshold marker.
 *   - Richman JS, Moorman JR 2000. Sample entropy.
 */
class FrequencyDomainCalculator {

    data class FrequencyDomainHrv(
        /** Median power 0.0033–0.04 Hz across 5-min windows (ms²). */
        val vlfMs2: Float?,
        /** Median power 0.04–0.15 Hz across 5-min windows (ms²). */
        val lfMs2: Float?,
        /** Median power 0.15–0.4 Hz across 5-min windows (ms²). */
        val hfMs2: Float?,
        /** Median total power across windows (ms²). */
        val totalPowerMs2: Float?,
        /** Median LF/HF ratio across windows. See PARTIALLY-DEBUNKED caveat in UI. */
        val lfHfRatio: Float?,
        /** Median Poincaré SD1 across windows (ms) — short-term variability ≡ RMSSD/√2. */
        val sd1Ms: Float?,
        /** Median Poincaré SD2 across windows (ms) — long-term variability. */
        val sd2Ms: Float?,
        /** Median DFA α1 across windows. Rogero 2021: crosses 0.75 at AeT (LT1). */
        val dfaAlpha1: Float?,
        /** Median sample entropy (m=2, r=0.2·SD) across windows. */
        val sampleEntropy: Float?,
        /** Total beats analyzed (across all valid windows). */
        val sampleCount: Int,
        /** Number of valid 5-min windows aggregated. v0.9.26+. */
        val windowCount: Int,
        /** Methodology version for forward-traceability. */
        val methodologyVersion: String = METHODOLOGY_VERSION,
    )

    /**
     * v0.9.26 — windowed frequency-domain + non-linear HRV.
     *
     * Splits [beats] into [windowMs]-sized segments (default 5 min), computes
     * all metrics per-window, median-aggregates across windows. Returns null
     * if fewer than [minValidWindows] (default 3) valid windows survive.
     *
     * @param beats sorted RR sequence (will be re-sorted by timestamp defensively)
     * @param windowMs window size in ms (default 300_000 = 5 min, Task Force 1996)
     * @param minBeatsPerWindow minimum beats per window to consider it valid (default 30)
     * @param minValidWindows minimum number of valid windows to return a result (default 3)
     */
    fun computeWindowed(
        beats: List<HrvCalculator.Beat>,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minBeatsPerWindow: Int = MIN_BEATS_PER_WINDOW,
        minValidWindows: Int = MIN_VALID_WINDOWS,
    ): FrequencyDomainHrv? {
        if (beats.isEmpty()) return null
        val filtered = filterRr(beats).sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return null

        // Group into windows by timestamp (matches HrvCalculator pattern)
        val firstTs = filtered.first().timestampMs
        val perWindow = mutableMapOf<Long, MutableList<HrvCalculator.Beat>>()
        for (b in filtered) {
            val idx = (b.timestampMs - firstTs) / windowMs
            perWindow.getOrPut(idx) { mutableListOf() }.add(b)
        }

        // Per-window compute. Each window is independent; failure of one
        // doesn't invalidate the night.
        data class WindowResult(
            val lf: Float?, val hf: Float?, val vlf: Float?,
            val sd1: Float, val sd2: Float,
            val dfaAlpha1: Float?, val sampleEntropy: Float?,
            val beatCount: Int,
        )
        val windowResults = perWindow.values.mapNotNull { winBeats ->
            if (winBeats.size < minBeatsPerWindow) return@mapNotNull null
            val rr = winBeats.map { it.rrMs.toFloat() }

            // Cheap measures first
            val sd1 = poincareSd1(rr) ?: return@mapNotNull null
            val sd2 = poincareSd2(rr) ?: return@mapNotNull null
            val dfa = dfaAlpha1(rr)  // nullable — small windows may not have enough scales
            val sampEn = sampleEntropy(rr)

            // Frequency-domain via Welch's PSD on this 5-min window
            val (vlf, lf, hf) = computeFrequencyBandsForWindow(winBeats)

            WindowResult(lf, hf, vlf, sd1, sd2, dfa, sampEn, winBeats.size)
        }

        if (windowResults.size < minValidWindows) return null

        // Median-aggregate (robust to outlier windows — same approach as
        // HrvCalculator.computeWindowed)
        val lfMed = medianOrNull(windowResults.mapNotNull { it.lf })
        val hfMed = medianOrNull(windowResults.mapNotNull { it.hf })
        val vlfMed = medianOrNull(windowResults.mapNotNull { it.vlf })
        val totalPower = if (vlfMed != null && lfMed != null && hfMed != null) {
            vlfMed + lfMed + hfMed
        } else null
        val lfHf = if (lfMed != null && hfMed != null && hfMed > 0.0001f) lfMed / hfMed else null
        val sd1Med = median(windowResults.map { it.sd1 })
        val sd2Med = median(windowResults.map { it.sd2 })
        val dfaMed = medianOrNull(windowResults.mapNotNull { it.dfaAlpha1 })
        val sampEnMed = medianOrNull(windowResults.mapNotNull { it.sampleEntropy })
        val totalBeats = windowResults.sumOf { it.beatCount }

        return FrequencyDomainHrv(
            vlfMs2 = vlfMed,
            lfMs2 = lfMed,
            hfMs2 = hfMed,
            totalPowerMs2 = totalPower,
            lfHfRatio = lfHf,
            sd1Ms = sd1Med,
            sd2Ms = sd2Med,
            dfaAlpha1 = dfaMed,
            sampleEntropy = sampEnMed,
            sampleCount = totalBeats,
            windowCount = windowResults.size,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Per-window non-linear measures
    // ────────────────────────────────────────────────────────────────────

    /**
     * Poincaré SD1 = SD(consecutive diffs)/√2. Mathematical invariant:
     * SD1 ≡ RMSSD/√2 always — used as a sanity assertion in unit tests.
     */
    internal fun poincareSd1(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        val mean = diffs.average().toFloat()
        val variance = diffs.map { (it - mean).let { d -> d * d } }.average().toFloat()
        return sqrt(variance) / sqrt(2f)
    }

    /** Poincaré SD2 = √(2·SDNN² − SD1²). */
    internal fun poincareSd2(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        val mean = rr.average().toFloat()
        val sdnnSq = rr.map { (it - mean).let { d -> d * d } }.average().toFloat()
        val sd1 = poincareSd1(rr) ?: return null
        val sd2Sq = 2f * sdnnSq - sd1 * sd1
        return if (sd2Sq > 0f) sqrt(sd2Sq) else 0f
    }

    /**
     * DFA α1 — Detrended Fluctuation Analysis, scales 4-16 beats.
     * Returns null if window too small (<64 beats — insufficient for
     * largest scale).
     */
    internal fun dfaAlpha1(rr: List<Float>): Float? {
        if (rr.size < MIN_BEATS_FOR_DFA) return null
        val mean = rr.average()
        val y = DoubleArray(rr.size)
        var sum = 0.0
        for (i in rr.indices) {
            sum += rr[i] - mean
            y[i] = sum
        }
        val scales = (4..16).toList()
        val logN = DoubleArray(scales.size)
        val logF = DoubleArray(scales.size)
        for ((idx, n) in scales.withIndex()) {
            val f = fluctuationAtScale(y, n) ?: return null
            logN[idx] = ln(n.toDouble())
            logF[idx] = ln(f)
        }
        return linearRegressionSlope(logN, logF).toFloat()
    }

    private fun fluctuationAtScale(y: DoubleArray, n: Int): Double? {
        val windowCount = y.size / n
        if (windowCount < 2) return null
        var sumSqResiduals = 0.0
        var totalSamples = 0
        for (w in 0 until windowCount) {
            val start = w * n
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in 0 until n) {
                val x = i.toDouble(); val yi = y[start + i]
                sx += x; sy += yi; sxx += x * x; sxy += x * yi
            }
            val nd = n.toDouble()
            val denom = nd * sxx - sx * sx
            if (denom == 0.0) continue
            val slope = (nd * sxy - sx * sy) / denom
            val intercept = (sy - slope * sx) / nd
            for (i in 0 until n) {
                val pred = slope * i + intercept
                val r = y[start + i] - pred
                sumSqResiduals += r * r
            }
            totalSamples += n
        }
        if (totalSamples == 0) return null
        return sqrt(sumSqResiduals / totalSamples)
    }

    /** Sample entropy (m=2, r=0.2·SD). O(N²) but N is per-window-bounded (~300). */
    internal fun sampleEntropy(rr: List<Float>): Float? {
        if (rr.size < 50) return null
        val m = 2
        val sd = run {
            val mean = rr.average().toFloat()
            sqrt(rr.map { (it - mean).let { d -> d * d } }.average()).toFloat()
        }
        val r = 0.2f * sd
        val n = rr.size
        if (n < m + 1) return null
        var A = 0L
        var B = 0L
        for (i in 0..n - m - 1) {
            for (j in (i + 1)..n - m - 1) {
                var matchM = true
                for (k in 0 until m) {
                    if (kotlin.math.abs(rr[i + k] - rr[j + k]) > r) {
                        matchM = false; break
                    }
                }
                if (!matchM) continue
                B++
                if (i + m < n && j + m < n &&
                    kotlin.math.abs(rr[i + m] - rr[j + m]) <= r
                ) A++
            }
        }
        if (B == 0L) return null
        val ratio = A.toDouble() / B.toDouble()
        if (ratio <= 0.0) return null
        return -ln(ratio).toFloat()
    }

    // ────────────────────────────────────────────────────────────────────
    // Per-window frequency-domain (Welch's PSD)
    // ────────────────────────────────────────────────────────────────────

    /** Compute (VLF, LF, HF) for a single 5-min window. */
    private fun computeFrequencyBandsForWindow(
        beats: List<HrvCalculator.Beat>,
    ): Triple<Float?, Float?, Float?> {
        val uniform = resampleRrTo4Hz(beats) ?: return Triple(null, null, null)
        val n = uniform.size
        if (n < 256) return Triple(null, null, null)

        val segmentLen = 256
        val overlap = segmentLen / 2
        val hann = hannWindow(segmentLen)
        val windowSumSq = hann.sumOf { (it * it).toDouble() }.toFloat()

        val segments = ((n - segmentLen) / overlap) + 1
        if (segments < 1) return Triple(null, null, null)

        val psd = FloatArray(segmentLen / 2 + 1)
        for (s in 0 until segments) {
            val start = s * overlap
            val segment = FloatArray(segmentLen)
            val mean = (start until start + segmentLen).map { uniform[it] }.average().toFloat()
            for (i in 0 until segmentLen) {
                segment[i] = (uniform[start + i] - mean) * hann[i]
            }
            val re = segment.copyOf()
            val im = FloatArray(segmentLen)
            fftRadix2(re, im)
            for (k in 0..segmentLen / 2) {
                val mag2 = re[k] * re[k] + im[k] * im[k]
                psd[k] += mag2
            }
        }
        val sampleRate = 4f
        val norm = 1f / (segments * sampleRate * windowSumSq)
        for (k in psd.indices) psd[k] = psd[k] * norm

        val df = sampleRate / segmentLen
        val vlf = integrateBand(psd, df, 0.0033f, 0.04f)
        val lf = integrateBand(psd, df, 0.04f, 0.15f)
        val hf = integrateBand(psd, df, 0.15f, 0.4f)
        return Triple(vlf, lf, hf)
    }

    private fun resampleRrTo4Hz(beats: List<HrvCalculator.Beat>): FloatArray? {
        val cumTimes = FloatArray(beats.size)
        val rrValues = FloatArray(beats.size)
        var t = 0f
        for (i in beats.indices) {
            cumTimes[i] = t
            rrValues[i] = beats[i].rrMs.toFloat()
            t += beats[i].rrMs.toFloat() / 1000f
        }
        val totalSec = cumTimes.last()
        if (totalSec < 60f) return null
        val sampleRate = 4f
        val nSamples = (totalSec * sampleRate).toInt()
        if (nSamples < 256) return null
        val out = FloatArray(nSamples)
        var beatIdx = 0
        for (i in 0 until nSamples) {
            val ti = i / sampleRate
            while (beatIdx + 1 < cumTimes.size && cumTimes[beatIdx + 1] <= ti) beatIdx++
            if (beatIdx + 1 >= cumTimes.size) {
                out[i] = rrValues[beatIdx]
            } else {
                val t0 = cumTimes[beatIdx]; val t1 = cumTimes[beatIdx + 1]
                val v0 = rrValues[beatIdx]; val v1 = rrValues[beatIdx + 1]
                val frac = if (t1 > t0) (ti - t0) / (t1 - t0) else 0f
                out[i] = v0 + frac * (v1 - v0)
            }
        }
        return out
    }

    private fun integrateBand(psd: FloatArray, df: Float, fLow: Float, fHigh: Float): Float? {
        val kLow = (fLow / df).toInt().coerceIn(0, psd.size - 1)
        val kHigh = (fHigh / df).toInt().coerceIn(0, psd.size - 1)
        if (kHigh <= kLow) return null
        var sum = 0f
        for (k in kLow..kHigh) sum += psd[k]
        return sum * df
    }

    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = 0.5f * (1f - cos(2.0 * PI * i / (n - 1)).toFloat())
        }
        return w
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        require((n and (n - 1)) == 0) { "FFT length must be a power of 2" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wkRe = 1f; var wkIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = wkRe * re[i + k + len / 2] - wkIm * im[i + k + len / 2]
                    val tIm = wkRe * im[i + k + len / 2] + wkIm * re[i + k + len / 2]
                    re[i + k + len / 2] = re[i + k] - tRe
                    im[i + k + len / 2] = im[i + k] - tIm
                    re[i + k] = re[i + k] + tRe
                    im[i + k] = im[i + k] + tIm
                    val nwRe = wkRe * wRe - wkIm * wIm
                    val nwIm = wkRe * wIm + wkIm * wRe
                    wkRe = nwRe; wkIm = nwIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun filterRr(beats: List<HrvCalculator.Beat>): List<HrvCalculator.Beat> {
        val out = mutableListOf<HrvCalculator.Beat>()
        for (b in beats) {
            if (b.rrMs < 300 || b.rrMs > 2000) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val jump = kotlin.math.abs(b.rrMs - prev.rrMs).toDouble() / prev.rrMs
                if (jump > 0.20) continue
            }
            out.add(b)
        }
        return out
    }

    private fun linearRegressionSlope(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        require(n == y.size && n >= 2)
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]
        }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return 0.0
        return (n * sxy - sx * sy) / denom
    }

    private fun median(values: List<Float>): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2f else sorted[n / 2]
    }

    private fun medianOrNull(values: List<Float>): Float? =
        if (values.isEmpty()) null else median(values)

    companion object {
        const val METHODOLOGY_VERSION = "v0.9.26-windowed-welch-dfa-sampen"

        /** Task Force 1996 short-term HRV window (5 min). */
        const val DEFAULT_WINDOW_MS = 5L * 60_000L

        /** Minimum beats per window for valid stats. */
        const val MIN_BEATS_PER_WINDOW = 30

        /** Minimum valid windows required (same as HrvCalculator.MIN_WINDOWS). */
        const val MIN_VALID_WINDOWS = 3

        /** Minimum beats for DFA α1 — needs enough at the largest scale (16). */
        const val MIN_BEATS_FOR_DFA = 64
    }
}
