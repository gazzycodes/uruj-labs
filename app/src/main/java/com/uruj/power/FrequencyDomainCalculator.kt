package com.uruj.power

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * v0.9.25 → v0.9.27 — HRV frequency-domain + non-linear analysis
 * (tier 2 + 3 from [[reference_biohacker_lab_vision]]).
 *
 * ## v0.9.27 — LAB-GRADE math fixes
 *
 * Field test 2026-05-21 revealed SD1 = 9.7 ms vs mathematically-expected
 * 7.07 ms (37% inflation). Investigation traced this to a filter mismatch
 * with [HrvCalculator] — my filter dropped individual beats based on
 * RR-jump from previous KEPT beat, missing the timestamp-consecutiveness
 * check that HrvCalculator applies per-pair. Cross-ectopic pairs that
 * HrvCalculator rejects were being accepted into my diff list, inflating
 * variance.
 *
 * v0.9.27 brings all four lab-grade corrections:
 *
 *   1. **Filter aligned with HrvCalculator** — port the per-pair
 *      validation from `consecutiveDiffsMs` (timestamp gap within 30%
 *      or 150ms floor + 20% ectopic delta cap). SD1 will now exactly
 *      reconcile with RMSSD/√2 per-window per the mathematical invariant.
 *   2. **Linear detrending before FFT** — slow HR drift across a 5-min
 *      window (e.g. deep sleep onset) leaks into VLF/LF spectrum. Subtract
 *      best-fit linear trend before Hann windowing.
 *   3. **SDNN/SD2 use all physiological RR** (not just validated pairs)
 *      to match [HrvCalculator.buildHrv] convention. Mixing diff-validated
 *      vs all-RR variance was a category error.
 *   4. **Trapezoidal integration** of PSD over frequency bands (minor
 *      accuracy bump over rectangular).
 *
 * Methodology version: `v0.9.27-aligned-filter-detrended-trapz`.
 *
 * ## Known limitations (lab-level honest disclosure)
 *
 *   - **VLF resolution at 5-min windows is inherently limited.** Bin width
 *     0.0156 Hz × 5-min FFT only gives 2-3 bins inside the VLF band
 *     (0.0033-0.04 Hz). True VLF requires longer windows (10+ min).
 *     We compute it for completeness but the value is noisier than LF/HF.
 *   - **LF/HF "sympathovagal balance" mechanistic claim is partially
 *     debunked** (Heathers 2014, Hayano 2019). Number is still useful as
 *     empirical stress index; UI caveat in ⓘ dialog.
 *   - **DFA α1 LT1 = 0.75 crossing is research-grade** (Rogero 2021) —
 *     not clinical-validated. Promising for the future ramp-test feature
 *     but should not replace lactate-anchored zones without further
 *     personal validation.
 *
 * ## What this exposes
 *
 * **Frequency-domain** (Welch's periodogram, per 5-min window):
 *   - VLF power 0.0033–0.04 Hz (caveat: limited resolution)
 *   - LF power 0.04–0.15 Hz
 *   - HF power 0.15–0.4 Hz (parasympathetic, RSA)
 *   - LF/HF ratio (Kubios "stress index" — with caveat)
 *
 * **Non-linear** (per 5-min window):
 *   - Poincaré SD1 (≡ RMSSD/√2 per-window — math invariant)
 *   - Poincaré SD2 (≡ √(2·SDNN² − SD1²))
 *   - DFA α1 (scales 4-16 beats, Rogero 2021)
 *   - Sample entropy (m=2, r=0.2·SD, Richman & Moorman 2000)
 *
 * ## Performance bound
 *
 * Per-window: ≤600 beats max. Sample entropy O(N²) per window
 * ≈ 360k ops × ~12 windows ≈ 4M ops total. Plus FFTs ~256-pt × 12
 * × 7 segments-each = small. Total ~200ms-1s on Android.
 *
 * References:
 *   - Task Force 1996. HRV standards.
 *   - Heathers JAJ 2014. LF/HF caveats.
 *   - Hayano J, Yuda E 2019. HRV pitfalls.
 *   - Peng CK et al. 1994. DFA.
 *   - Rogero MM et al. 2021. DFA α1 LT1 marker.
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
        /** Median total power (VLF+LF+HF) across windows (ms²). */
        val totalPowerMs2: Float?,
        /** Median LF/HF ratio across windows. See PARTIALLY-DEBUNKED caveat. */
        val lfHfRatio: Float?,
        /** Median Poincaré SD1 across windows (ms) — short-term variability ≡ RMSSD/√2. */
        val sd1Ms: Float?,
        /** Median Poincaré SD2 across windows (ms) — long-term variability. */
        val sd2Ms: Float?,
        /** Median DFA α1 across windows. Rogero 2021: crosses 0.75 at AeT (LT1). */
        val dfaAlpha1: Float?,
        /** Median sample entropy across windows. */
        val sampleEntropy: Float?,
        /** Total beats analyzed across all valid windows. */
        val sampleCount: Int,
        /** Number of valid 5-min windows aggregated. */
        val windowCount: Int,
        /** Methodology version for forward-traceability. */
        val methodologyVersion: String = METHODOLOGY_VERSION,
    )

    /**
     * v0.9.27 — windowed frequency-domain + non-linear HRV with
     * HrvCalculator-aligned filter + linear detrending + correct
     * SDNN scope.
     */
    fun computeWindowed(
        beats: List<HrvCalculator.Beat>,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minBeatsPerWindow: Int = MIN_BEATS_PER_WINDOW,
        minValidWindows: Int = MIN_VALID_WINDOWS,
    ): FrequencyDomainHrv? {
        if (beats.isEmpty()) return null

        // Step 1: Physiological filter (300-2000 ms) and sort by timestamp.
        // This is the BASE filter — drops impossible RR values but does NOT
        // drop ectopic-suspect pairs (that happens per-pair below, matching
        // HrvCalculator.consecutiveDiffsMs semantics).
        val physiological = beats
            .filter { it.rrMs in HrvCalculator.PHYSIOLOGICAL_MIN..HrvCalculator.PHYSIOLOGICAL_MAX }
            .sortedBy { it.timestampMs }
        if (physiological.size < minBeatsPerWindow) return null

        // Step 2: Group into 5-min windows by timestamp.
        val firstTs = physiological.first().timestampMs
        val perWindow = mutableMapOf<Long, MutableList<HrvCalculator.Beat>>()
        for (b in physiological) {
            val idx = (b.timestampMs - firstTs) / windowMs
            perWindow.getOrPut(idx) { mutableListOf() }.add(b)
        }

        // Step 3: Per-window compute.
        data class WindowResult(
            val lf: Float?, val hf: Float?, val vlf: Float?,
            val sd1: Float, val sd2: Float,
            val dfaAlpha1: Float?, val sampleEntropy: Float?,
            val beatCount: Int,
        )
        val windowResults = perWindow.values.mapNotNull { winBeats ->
            if (winBeats.size < minBeatsPerWindow) return@mapNotNull null

            // Validated diffs — matches HrvCalculator.consecutiveDiffsMs.
            // This is THE critical alignment fix vs v0.9.26.
            val validatedDiffs = consecutiveDiffsMs(winBeats)
            if (validatedDiffs.size < HrvCalculator.MIN_DIFFS) return@mapNotNull null

            // SD1 ≡ RMSSD/√2 — uses validated diffs (mathematical invariant)
            val sd1 = sdSqrtVariance(validatedDiffs.map { it.toFloat() }) / sqrt(2f)

            // SD2 — derived from SDNN (all physiological RR in window) + SD1
            val rrValues = winBeats.map { it.rrMs.toFloat() }
            val sdnn = sdSqrtVariance(rrValues)
            val sd2Sq = 2f * sdnn * sdnn - sd1 * sd1
            val sd2 = if (sd2Sq > 0f) sqrt(sd2Sq) else 0f

            // DFA — uses all physiological RR (DFA is scale-based, not pair-based)
            val dfa = dfaAlpha1(rrValues)

            // Sample entropy — uses all physiological RR
            val sampEn = sampleEntropy(rrValues)

            // FFT — uses all physiological beats (resampled), with linear detrending
            val (vlf, lf, hf) = computeFrequencyBandsForWindow(winBeats)

            WindowResult(lf, hf, vlf, sd1, sd2, dfa, sampEn, winBeats.size)
        }

        if (windowResults.size < minValidWindows) return null

        // Step 4: Median-aggregate.
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
    // v0.9.27 — Aligned filter (mirrors HrvCalculator.consecutiveDiffsMs)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Validated consecutive-pair diffs. For each pair (prev, curr):
     *   - actualGap = curr.timestampMs - prev.timestampMs
     *   - expectedGap = curr.rrMs
     *   - tolerance = max(150ms, 30% of expectedGap)
     *   - Accept only if |actualGap - expectedGap| ≤ tolerance
     *     AND ectopic-delta |curr.rrMs - prev.rrMs| / prev.rrMs ≤ 20%
     *
     * EXACT mirror of [HrvCalculator.consecutiveDiffsMs]. Same constants.
     * If they ever diverge, the SD1 ≡ RMSSD/√2 invariant test fails.
     */
    internal fun consecutiveDiffsMs(sortedBeats: List<HrvCalculator.Beat>): List<Int> {
        if (sortedBeats.size < 2) return emptyList()
        val diffs = mutableListOf<Int>()
        for (i in 1 until sortedBeats.size) {
            val prev = sortedBeats[i - 1]
            val curr = sortedBeats[i]
            val expectedGap = curr.rrMs.toLong()
            val actualGap = curr.timestampMs - prev.timestampMs
            val tolerance = maxOf(
                HrvCalculator.MIN_TOLERANCE_MS,
                (expectedGap * 0.30).toLong(),
            )
            if (kotlin.math.abs(actualGap - expectedGap) > tolerance) continue
            val deltaPct = kotlin.math.abs(curr.rrMs - prev.rrMs).toFloat() / prev.rrMs
            if (deltaPct > HrvCalculator.ECTOPIC_THRESHOLD) continue
            diffs.add(curr.rrMs - prev.rrMs)
        }
        return diffs
    }

    /** Standard deviation = sqrt(variance with mean subtracted). */
    internal fun sdSqrtVariance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).let { d -> d * d } }.average().toFloat()
        return sqrt(variance)
    }

    // ────────────────────────────────────────────────────────────────────
    // Per-window non-linear measures
    // ────────────────────────────────────────────────────────────────────

    /**
     * Poincaré SD1 helper for testing (legacy API). Uses simple diff
     * series — internal computeWindowed uses [consecutiveDiffsMs] for
     * the production-correct math invariant.
     */
    internal fun poincareSd1(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        return sdSqrtVariance(diffs) / sqrt(2f)
    }

    /** Poincaré SD2 helper for testing. */
    internal fun poincareSd2(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        val sd1 = poincareSd1(rr) ?: return null
        val sdnn = sdSqrtVariance(rr)
        val sd2Sq = 2f * sdnn * sdnn - sd1 * sd1
        return if (sd2Sq > 0f) sqrt(sd2Sq) else 0f
    }

    /** DFA α1 — Detrended Fluctuation Analysis, scales 4-16 beats. */
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

    /** Sample entropy (m=2, r=0.2·SD). O(N²) but bounded per-window. */
    internal fun sampleEntropy(rr: List<Float>): Float? {
        if (rr.size < 50) return null
        val m = 2
        val sd = sdSqrtVariance(rr)
        if (sd <= 0f) return null
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
    // Per-window frequency-domain (Welch's PSD with linear detrending)
    // ────────────────────────────────────────────────────────────────────

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

            // v0.9.27 — Linear detrending before windowing.
            // Subtract best-fit linear trend (not just mean) to suppress
            // slow drift leakage into VLF/LF bands.
            val (slope, intercept) = linearTrend(uniform, start, segmentLen)
            for (i in 0 until segmentLen) {
                val detrended = uniform[start + i] - (slope * i + intercept)
                segment[i] = detrended * hann[i]
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

    /** Best-fit linear trend over a slice of a uniformly-sampled series. */
    private fun linearTrend(arr: FloatArray, start: Int, len: Int): Pair<Float, Float> {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until len) {
            val x = i.toDouble(); val yi = arr[start + i].toDouble()
            sx += x; sy += yi; sxx += x * x; sxy += x * yi
        }
        val nd = len.toDouble()
        val denom = nd * sxx - sx * sx
        if (denom == 0.0) return 0f to (sy / nd).toFloat()
        val slope = (nd * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / nd
        return slope.toFloat() to intercept.toFloat()
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

    /**
     * v0.9.27 — Trapezoidal integration of PSD over [fLow, fHigh].
     * Minor accuracy bump over rectangular integration.
     */
    private fun integrateBand(psd: FloatArray, df: Float, fLow: Float, fHigh: Float): Float? {
        val kLow = (fLow / df).toInt().coerceIn(0, psd.size - 1)
        val kHigh = (fHigh / df).toInt().coerceIn(0, psd.size - 1)
        if (kHigh <= kLow) return null
        var sum = 0f
        for (k in kLow until kHigh) {
            sum += (psd[k] + psd[k + 1]) * 0.5f
        }
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
        const val METHODOLOGY_VERSION = "v0.9.27-aligned-filter-detrended-trapz"
        const val DEFAULT_WINDOW_MS = 5L * 60_000L
        const val MIN_BEATS_PER_WINDOW = 30
        const val MIN_VALID_WINDOWS = 3
        const val MIN_BEATS_FOR_DFA = 64
    }
}
