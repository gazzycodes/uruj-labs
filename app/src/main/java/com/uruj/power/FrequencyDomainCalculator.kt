package com.uruj.power

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * v0.9.25 — HRV frequency-domain + non-linear analysis (tier 2 + 3 from
 * [[reference_biohacker_lab_vision]]). Computes on top of the same RR-interval
 * series that [HrvCalculator] uses for time-domain RMSSD/SDNN/pNN50.
 *
 * ## What this exposes
 *
 * **Frequency-domain** (Welch's periodogram on uniformly-resampled RR):
 *   - VLF power (0.0033 – 0.04 Hz) — thermoregulation + hormonal axis
 *   - LF power (0.04 – 0.15 Hz) — mixed sympathetic + parasympathetic
 *     (NOT cleanly sympathetic — see methodology caveat)
 *   - HF power (0.15 – 0.4 Hz) — parasympathetic (respiratory sinus arrhythmia)
 *   - LF/HF ratio — used by Kubios / Polar / Garmin as "autonomic balance"
 *     index; correlates with stress in practice even though the original
 *     mechanistic interpretation is partially debunked (Heathers 2014,
 *     Hayano 2019). UI shows this caveat in the ⓘ dialog.
 *
 * **Non-linear**:
 *   - Poincaré SD1 (short-term variability, parasympathetic) — mathematically
 *     equivalent to RMSSD/√2
 *   - Poincaré SD2 (long-term variability) — correlates with SDNN
 *   - DFA α1 (Detrended Fluctuation Analysis, scales 4-16 beats) — fractal
 *     scaling of RR. Healthy ~1.0; drops below 0.75 above LT1 (Aerobic
 *     Threshold) per Rogero et al. 2021 — enables future LT1-from-ramp-test
 *     feature (separate v0.5+ guided protocol).
 *   - Sample entropy (m=2, r=0.2·SDNN) — complexity/irregularity (Richman &
 *     Moorman 2000). Lower = more regular (could mean deep rest OR
 *     pathological rigidity), higher = healthy variability.
 *
 * ## Method (per Task Force 1996 standard)
 *
 *   1. Reconstruct beat timestamps from RR intervals (already done by caller)
 *   2. Compute uniform-time-series at 4 Hz via linear interpolation between
 *      cumulative-time RR samples
 *   3. Detrend (subtract mean)
 *   4. Apply Hann window (reduces spectral leakage)
 *   5. Radix-2 FFT (zero-pad to next power of 2)
 *   6. Compute Power Spectral Density (PSD)
 *   7. Integrate PSD over VLF / LF / HF bands
 *
 * Minimum beats required: 240 (~5 min of healthy 60 bpm beats). Returns
 * null below the minimum so the UI can show "baseline building" instead
 * of unreliable numbers.
 *
 * Architectural notes:
 *   - Pure math; no I/O, no coroutines, no side effects
 *   - Inputs: [HrvCalculator.Beat] list (same struct used by time-domain)
 *   - Outputs: [FrequencyDomainHrv] data class with all 8 metrics
 *   - Per lab-level rule 4 (no fake numbers), all metrics return null on
 *     insufficient data — UI hides those rows
 *   - Per lab-level rule 3 (methodology), ⓘ dialog cites all references
 *
 * References:
 *   - Task Force 1996. Heart rate variability: standards of measurement,
 *     physiological interpretation, and clinical use.
 *   - Heathers JAJ 2014. Everything Hertz: methodological issues in short-
 *     term frequency-domain HRV.
 *   - Hayano J, Yuda E 2019. Pitfalls of assessment of autonomic function
 *     by heart rate variability.
 *   - Peng CK et al. 1994. Mosaic organization of DNA nucleotides
 *     (origin of DFA).
 *   - Rogero MM et al. 2021. DFA α1 as a marker of aerobic threshold in
 *     cycling.
 *   - Richman JS, Moorman JR 2000. Physiological time-series analysis using
 *     approximate entropy and sample entropy.
 */
class FrequencyDomainCalculator {

    data class FrequencyDomainHrv(
        /** Power 0.0033–0.04 Hz (ms²). Slow oscillations — thermoregulation, hormones. */
        val vlfMs2: Float?,
        /** Power 0.04–0.15 Hz (ms²). Mixed sympathetic+parasympathetic via baroreflex. */
        val lfMs2: Float?,
        /** Power 0.15–0.4 Hz (ms²). Parasympathetic (respiratory sinus arrhythmia). */
        val hfMs2: Float?,
        /** Total power across VLF+LF+HF (ms²). */
        val totalPowerMs2: Float?,
        /**
         * LF/HF ratio. Classical interpretation: high = sympathetic dominant,
         * low = parasympathetic dominant. PARTIALLY DEBUNKED — LF contains
         * both sympathetic and parasympathetic activity (Heathers 2014).
         * Still useful as an empirical stress-index that correlates with
         * autonomic state in practice. UI shows the caveat.
         */
        val lfHfRatio: Float?,
        /** Poincaré SD1 (ms) — short-term variability. ≈ RMSSD/√2 mathematically. */
        val sd1Ms: Float?,
        /** Poincaré SD2 (ms) — long-term variability. Correlates with SDNN. */
        val sd2Ms: Float?,
        /**
         * DFA α1 (dimensionless, ~0.5–1.5). Fractal scaling exponent over
         * 4–16 beat scales. Healthy ~1.0; drops below 0.75 above the rider's
         * Aerobic Threshold (Rogero 2021). Trend up over weeks = overtraining
         * / autonomic dysfunction.
         */
        val dfaAlpha1: Float?,
        /**
         * Sample entropy (m=2, r=0.2·SDNN). Complexity/irregularity measure.
         * Higher = more complex (healthy variability), lower = more regular
         * (deep rest OR pathological rigidity). Richman & Moorman 2000.
         */
        val sampleEntropy: Float?,
        /** Number of beats analyzed (after filtering). For traceability. */
        val sampleCount: Int,
        /** Method version for future calc-change traceability. */
        val methodologyVersion: String = METHODOLOGY_VERSION,
    )

    /**
     * Compute frequency-domain + non-linear HRV for the given beat sequence.
     * Returns null if fewer than [MIN_BEATS] beats — values would be
     * unreliable below this threshold.
     */
    fun compute(beats: List<HrvCalculator.Beat>): FrequencyDomainHrv? {
        if (beats.size < MIN_BEATS) return null

        // Filter to physiological range + reject ectopic jumps. Reuses the
        // same defensive filter HrvCalculator applies for time-domain.
        val filtered = filterRr(beats)
        if (filtered.size < MIN_BEATS) return null

        val rr = filtered.map { it.rrMs.toFloat() }

        // ── Non-linear (cheap, compute first) ──
        val sd1 = poincareSd1(rr)
        val sd2 = poincareSd2(rr)
        val dfa = dfaAlpha1(rr)
        val sampEn = sampleEntropy(rr)

        // ── Frequency-domain (FFT path) ──
        val (vlf, lf, hf) = computeFrequencyBands(filtered)
        val totalPower = if (vlf != null && lf != null && hf != null) vlf + lf + hf else null
        val lfHf = if (lf != null && hf != null && hf > 0.0001f) lf / hf else null

        return FrequencyDomainHrv(
            vlfMs2 = vlf,
            lfMs2 = lf,
            hfMs2 = hf,
            totalPowerMs2 = totalPower,
            lfHfRatio = lfHf,
            sd1Ms = sd1,
            sd2Ms = sd2,
            dfaAlpha1 = dfa,
            sampleEntropy = sampEn,
            sampleCount = filtered.size,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Non-linear measures
    // ────────────────────────────────────────────────────────────────────

    /**
     * Poincaré SD1 — standard deviation perpendicular to the identity line
     * in the RR(n) vs RR(n+1) plot. Mathematically equivalent to
     * RMSSD/√2. Measures short-term (beat-to-beat) variability ≈
     * parasympathetic.
     */
    internal fun poincareSd1(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        // SD of successive differences / sqrt(2)
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        val mean = diffs.average().toFloat()
        val variance = diffs.map { (it - mean).let { d -> d * d } }.average().toFloat()
        return sqrt(variance) / sqrt(2f)
    }

    /**
     * Poincaré SD2 — standard deviation along the identity line in the
     * RR(n) vs RR(n+1) plot. Long-term variability. Derived from total
     * variance: SD2² = 2·SDNN² − SD1².
     */
    internal fun poincareSd2(rr: List<Float>): Float? {
        if (rr.size < 2) return null
        val mean = rr.average().toFloat()
        val sdnnSq = rr.map { (it - mean).let { d -> d * d } }.average().toFloat()
        val sd1 = poincareSd1(rr) ?: return null
        val sd2Sq = 2f * sdnnSq - sd1 * sd1
        return if (sd2Sq > 0f) sqrt(sd2Sq) else 0f
    }

    /**
     * DFA α1 — Detrended Fluctuation Analysis at scales 4–16 beats.
     * Standard "short-scale" α1 (Rogero 2021). The slope of log F(n)
     * vs log n over these scales = α1.
     *
     * Steps:
     *   1. Integrate the centered RR series → Y(k)
     *   2. For each scale n in [4..16]:
     *      a. Split Y into non-overlapping windows of length n
     *      b. Per window, fit a linear trend, compute residual variance F²(n)
     *   3. log F(n) vs log n: slope = α1
     */
    internal fun dfaAlpha1(rr: List<Float>): Float? {
        if (rr.size < MIN_BEATS_FOR_DFA) return null

        // Step 1: integrate centered RR
        val mean = rr.average()
        val y = DoubleArray(rr.size)
        var sum = 0.0
        for (i in rr.indices) {
            sum += rr[i] - mean
            y[i] = sum
        }

        // Step 2 + 3: F(n) for each scale, log-log slope
        val scales = (4..16).toList()
        val logN = DoubleArray(scales.size)
        val logF = DoubleArray(scales.size)
        for ((idx, n) in scales.withIndex()) {
            val f = fluctuationAtScale(y, n) ?: return null
            logN[idx] = ln(n.toDouble())
            logF[idx] = ln(f)
        }

        // Linear regression slope
        return linearRegressionSlope(logN, logF).toFloat()
    }

    /** F(n) — root-mean-square of local linear-detrending residuals at scale n. */
    private fun fluctuationAtScale(y: DoubleArray, n: Int): Double? {
        val windowCount = y.size / n
        if (windowCount < 2) return null
        var sumSqResiduals = 0.0
        var totalSamples = 0
        for (w in 0 until windowCount) {
            val start = w * n
            // Fit a linear trend to y[start..start+n-1]
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val x = i.toDouble()
                val yi = y[start + i]
                sx += x; sy += yi; sxx += x * x; sxy += x * yi
            }
            val nd = n.toDouble()
            val denom = nd * sxx - sx * sx
            if (denom == 0.0) continue
            val slope = (nd * sxy - sx * sy) / denom
            val intercept = (sy - slope * sx) / nd
            // Residuals
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

    /** Sample entropy (m=2, r=0.2·SD). Richman & Moorman 2000. */
    internal fun sampleEntropy(rr: List<Float>): Float? {
        if (rr.size < 50) return null  // sample entropy is noisy below this
        val m = 2
        val sd = run {
            val mean = rr.average().toFloat()
            sqrt(rr.map { (it - mean).let { d -> d * d } }.average()).toFloat()
        }
        val r = 0.2f * sd
        val n = rr.size
        if (n < m + 1) return null

        // Count template matches of length m and m+1
        var A = 0L  // pairs matching at length m+1
        var B = 0L  // pairs matching at length m
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
                // Extend to m+1
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
    // Frequency-domain (Welch's PSD)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Compute (VLF, LF, HF) band powers via Welch's PSD on the uniformly-
     * resampled RR series.
     */
    private fun computeFrequencyBands(beats: List<HrvCalculator.Beat>): Triple<Float?, Float?, Float?> {
        if (beats.size < MIN_BEATS) return Triple(null, null, null)

        // Step 1: build cumulative-time RR series + uniform-resample at 4 Hz
        val uniform = resampleRrTo4Hz(beats) ?: return Triple(null, null, null)
        val n = uniform.size
        if (n < 256) return Triple(null, null, null)  // need enough samples for FFT

        // Step 2: Welch's method — split into 50%-overlapping segments
        // of length 256 (64 seconds at 4 Hz), Hann-window each, FFT,
        // average the squared magnitudes.
        val segmentLen = 256
        val overlap = segmentLen / 2
        val hann = hannWindow(segmentLen)
        val windowSumSq = hann.sumOf { (it * it).toDouble() }.toFloat()

        // Number of segments
        val segments = ((n - segmentLen) / overlap) + 1
        if (segments < 1) return Triple(null, null, null)

        // Accumulate PSD across segments
        val psd = FloatArray(segmentLen / 2 + 1)
        for (s in 0 until segments) {
            val start = s * overlap
            // Detrend (subtract mean) + window
            val segment = FloatArray(segmentLen)
            val mean = (start until start + segmentLen).map { uniform[it] }.average().toFloat()
            for (i in 0 until segmentLen) {
                segment[i] = (uniform[start + i] - mean) * hann[i]
            }
            // FFT (in-place, radix-2)
            val re = segment.copyOf()
            val im = FloatArray(segmentLen)
            fftRadix2(re, im)
            // Add |X(k)|² to PSD bins
            for (k in 0..segmentLen / 2) {
                val mag2 = re[k] * re[k] + im[k] * im[k]
                psd[k] += mag2
            }
        }

        // Normalize: PSD = (1 / (segments * sampleRate * sum(window²))) · sum |X|²
        val sampleRate = 4f  // Hz
        val norm = 1f / (segments * sampleRate * windowSumSq)
        for (k in psd.indices) psd[k] = psd[k] * norm

        // Step 3: integrate over bands
        val df = sampleRate / segmentLen  // bin width in Hz
        val vlf = integrateBand(psd, df, 0.0033f, 0.04f)
        val lf = integrateBand(psd, df, 0.04f, 0.15f)
        val hf = integrateBand(psd, df, 0.15f, 0.4f)
        return Triple(vlf, lf, hf)
    }

    /**
     * Resample RR series to uniform 4 Hz via linear interpolation between
     * cumulative-time RR samples. Returns the resampled RR series in ms,
     * or null if input is invalid (e.g., total duration < 60 sec).
     */
    private fun resampleRrTo4Hz(beats: List<HrvCalculator.Beat>): FloatArray? {
        // Cumulative time at each beat (start at 0)
        val cumTimes = FloatArray(beats.size)
        val rrValues = FloatArray(beats.size)
        var t = 0f
        for (i in beats.indices) {
            cumTimes[i] = t
            rrValues[i] = beats[i].rrMs.toFloat()
            t += beats[i].rrMs.toFloat() / 1000f  // seconds
        }
        val totalSec = cumTimes.last()
        if (totalSec < 60f) return null  // too short

        val sampleRate = 4f
        val nSamples = (totalSec * sampleRate).toInt()
        if (nSamples < 256) return null

        val out = FloatArray(nSamples)
        var beatIdx = 0
        for (i in 0 until nSamples) {
            val ti = i / sampleRate
            // Advance beatIdx so cumTimes[beatIdx] <= ti < cumTimes[beatIdx+1]
            while (beatIdx + 1 < cumTimes.size && cumTimes[beatIdx + 1] <= ti) beatIdx++
            if (beatIdx + 1 >= cumTimes.size) {
                out[i] = rrValues[beatIdx]
            } else {
                // Linear interpolation
                val t0 = cumTimes[beatIdx]; val t1 = cumTimes[beatIdx + 1]
                val v0 = rrValues[beatIdx]; val v1 = rrValues[beatIdx + 1]
                val frac = if (t1 > t0) (ti - t0) / (t1 - t0) else 0f
                out[i] = v0 + frac * (v1 - v0)
            }
        }
        return out
    }

    /** Integrate PSD over a frequency band. */
    private fun integrateBand(psd: FloatArray, df: Float, fLow: Float, fHigh: Float): Float? {
        val kLow = (fLow / df).toInt().coerceIn(0, psd.size - 1)
        val kHigh = (fHigh / df).toInt().coerceIn(0, psd.size - 1)
        if (kHigh <= kLow) return null
        var sum = 0f
        for (k in kLow..kHigh) sum += psd[k]
        return sum * df  // trapezoidal area
    }

    /** Hann window of length n. */
    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = 0.5f * (1f - cos(2.0 * PI * i / (n - 1)).toFloat())
        }
        return w
    }

    /**
     * In-place radix-2 Cooley-Tukey FFT. n must be a power of 2 (segmentLen
     * is 256 = 2^8 by design). Modifies re[] and im[] in place.
     */
    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        require((n and (n - 1)) == 0) { "FFT length must be a power of 2" }

        // Bit reversal permutation
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

        // Cooley-Tukey
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

    /**
     * Filter RR intervals to physiological range (300-2000 ms) + reject
     * ectopic beats (>20% jump from prior). Same defensive filter as
     * HrvCalculator time-domain.
     */
    private fun filterRr(beats: List<HrvCalculator.Beat>): List<HrvCalculator.Beat> {
        val out = mutableListOf<HrvCalculator.Beat>()
        for (b in beats) {
            if (b.rrMs < 300 || b.rrMs > 2000) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val jump = kotlin.math.abs(b.rrMs - prev.rrMs).toDouble() / prev.rrMs
                if (jump > 0.20) continue  // ectopic delta cap
            }
            out.add(b)
        }
        return out
    }

    /** Linear regression slope. Returns m in y = m·x + c. */
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

    companion object {
        const val METHODOLOGY_VERSION = "v0.9.25-welch-dfa-sampen"

        /** Minimum RR beats required for frequency-domain analysis (~4 min at 60 bpm). */
        const val MIN_BEATS = 240

        /** Minimum beats for DFA α1 — needs enough at the larger scales. */
        const val MIN_BEATS_FOR_DFA = 64
    }
}
