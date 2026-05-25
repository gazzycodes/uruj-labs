#!/usr/bin/env python3
"""
Independent cross-validation of URUJ's frequency-domain + non-linear HRV
metrics using scipy (peer-reviewed scientific Python library).

Companion to raw_rmssd_check.py — same approach, freq-domain side.

Validates:
  - Lomb-Scargle LF/HF/VLF powers + ratio
  - DFA α1 (via manual implementation matching Peng 1994)
  - SD1 (Poincaré short-term variability — must satisfy SD1 = RMSSD/√2)
  - SD2 (Poincaré long-term variability)

Usage:
  python tools/verify_freqdomain_scipy.py tools/sleep-window.ndjson

Expected to MATCH URUJ Bio Lab Autonomic Frequency card within:
  - LF/HF ratio: ±20% (normalization-invariant)
  - DFA α1: ±15%
  - SD1: exact match to RMSSD/√2 (math invariant)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from scipy.signal import lombscargle


# ---- URUJ filter constants ----
PHYSIOLOGICAL_MIN = 300
PHYSIOLOGICAL_MAX = 2000
ECTOPIC_THRESHOLD = 0.20
WIN_MS = 5 * 60 * 1000
MIN_BEATS_PER_WIN = 30


def load_beats(path):
    """Reconstruct beats from URUJ NDJSON: each sample's
    rrIntervalsMs[] walked backwards from sample.timestampMs."""
    beats = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                s = json.loads(line)
            except json.JSONDecodeError:
                continue
            rr_list = s.get('rrIntervalsMs') or []
            t = int(s['timestampMs'])
            for rr in reversed(rr_list):
                if rr:
                    beats.append((t, int(rr)))
                    t -= int(rr)
    beats.sort()
    return beats


def physiological_filter(beats):
    return [b for b in beats if PHYSIOLOGICAL_MIN <= b[1] <= PHYSIOLOGICAL_MAX]


def split_windows(beats):
    """Bucket beats into 5-min windows by timestamp (matches URUJ semantics)."""
    if not beats:
        return []
    first = beats[0][0]
    windows = {}
    for t, rr in beats:
        idx = (t - first) // WIN_MS
        windows.setdefault(idx, []).append((t, rr))
    return list(windows.values())


# ---- Lomb-Scargle band integration (mirrors URUJ algorithm) ----

def lomb_scargle_bands(win_beats):
    """Compute VLF/LF/HF band powers via Lomb-Scargle, mirroring URUJ
    methodology. Returns (vlf, lf, hf, total_var)."""
    if len(win_beats) < 30:
        return None, None, None, None

    # Build (t_sec, rr_ms) — cumulative beat time
    t_sec = np.zeros(len(win_beats))
    rr_ms = np.array([rr for _, rr in win_beats], dtype=float)
    t = 0.0
    for i in range(len(win_beats)):
        t_sec[i] = t
        t += rr_ms[i] / 1000.0

    if t_sec[-1] < 60.0:
        return None, None, None, None

    # Mean-center RR
    y = rr_ms - rr_ms.mean()
    variance = float(np.var(y))
    if variance <= 0:
        return None, None, None, None

    # Frequency grid: 200 linear bins from 0.003 to 0.4 Hz
    fLo, fHi, nFreqs = 0.003, 0.4, 200
    freqs = np.linspace(fLo, fHi, nFreqs)

    # scipy.signal.lombscargle expects ANGULAR frequencies
    omegas = 2 * np.pi * freqs

    # Compute Lomb-Scargle periodogram
    psd = lombscargle(t_sec, y, omegas, normalize=False)

    # Normalize so integral ≈ variance (Parseval-like, matches URUJ)
    df = (fHi - fLo) / (nFreqs - 1)
    total_integral = psd.sum() * df
    if total_integral <= 0:
        return None, None, None, None
    scale = variance / total_integral

    def band_power(f_lo_b, f_hi_b):
        k_low = max(0, min(nFreqs - 1, int((f_lo_b - fLo) / df)))
        k_high = max(0, min(nFreqs - 1, int((f_hi_b - fLo) / df)))
        if k_high <= k_low:
            return None
        # Trapezoidal integration (np.trapezoid in numpy >= 2.0)
        band = psd[k_low:k_high + 1]
        trapz = getattr(np, 'trapezoid', None) or getattr(np, 'trapz')
        return float(trapz(band, dx=df) * scale)

    vlf = band_power(0.0033, 0.04)
    lf = band_power(0.04, 0.15)
    hf = band_power(0.15, 0.4)
    return vlf, lf, hf, variance


# ---- DFA α1 (Peng 1994 / Rogero 2021, scales 4-16) ----

def dfa_alpha1(rr_values):
    """Detrended Fluctuation Analysis at scales 4-16 beats."""
    if len(rr_values) < 64:
        return None
    rr = np.array(rr_values, dtype=float)
    mean = rr.mean()
    y = np.cumsum(rr - mean)

    scales = list(range(4, 17))
    log_n = []
    log_f = []
    for n in scales:
        n_windows = len(y) // n
        if n_windows < 2:
            return None
        ss_resid = 0.0
        total = 0
        for w in range(n_windows):
            start = w * n
            x = np.arange(n)
            y_seg = y[start:start + n]
            # Linear regression
            xm, ym = x.mean(), y_seg.mean()
            num = ((x - xm) * (y_seg - ym)).sum()
            den = ((x - xm) ** 2).sum()
            if den == 0:
                continue
            slope = num / den
            intercept = ym - slope * xm
            pred = slope * x + intercept
            ss_resid += ((y_seg - pred) ** 2).sum()
            total += n
        if total == 0:
            return None
        f = np.sqrt(ss_resid / total)
        if f <= 0:
            return None
        log_n.append(np.log(n))
        log_f.append(np.log(f))

    # Slope of log-log fit
    log_n_a = np.array(log_n)
    log_f_a = np.array(log_f)
    nfit = len(log_n_a)
    xm, ym = log_n_a.mean(), log_f_a.mean()
    num = ((log_n_a - xm) * (log_f_a - ym)).sum()
    den = ((log_n_a - xm) ** 2).sum()
    if den == 0:
        return None
    return float(num / den)


# ---- SD1/SD2 Poincaré ----

def poincare_sd1_sd2(rr_values):
    """SD1 = sqrt(variance of diff(rr)) / sqrt(2)  → ≡ RMSSD/√2
       SD2 = sqrt(2·SDNN² - SD1²)"""
    rr = np.array(rr_values, dtype=float)
    if len(rr) < 2:
        return None, None
    diffs = np.diff(rr)
    sd1 = float(np.std(diffs, ddof=0) / np.sqrt(2))
    sdnn = float(np.std(rr, ddof=0))
    sd2_sq = 2 * sdnn ** 2 - sd1 ** 2
    sd2 = float(np.sqrt(sd2_sq)) if sd2_sq > 0 else 0.0
    return sd1, sd2


def main():
    if len(sys.argv) < 2:
        print("Usage: verify_freqdomain_scipy.py <ndjson>")
        return 1
    p = Path(sys.argv[1])
    if not p.exists():
        print(f"ERROR: {p} not found")
        return 1

    beats = load_beats(p)
    print(f"Loaded {len(beats)} beats")
    beats = physiological_filter(beats)
    print(f"After physiological filter: {len(beats)}")

    windows = split_windows(beats)
    print(f"Candidate windows (5-min, non-overlap): {len(windows)}")

    per_win = []
    for w in windows:
        if len(w) < MIN_BEATS_PER_WIN:
            continue
        rr_values = [rr for _, rr in w]

        # Lomb-Scargle bands
        vlf, lf, hf, variance = lomb_scargle_bands(w)
        if vlf is None:
            continue

        # SD1, SD2
        sd1, sd2 = poincare_sd1_sd2(rr_values)

        # DFA
        dfa = dfa_alpha1(rr_values)

        per_win.append({
            'vlf': vlf, 'lf': lf, 'hf': hf,
            'lf_hf': lf / hf if hf and hf > 0.001 else None,
            'sd1': sd1, 'sd2': sd2,
            'dfa': dfa,
        })

    if len(per_win) < 3:
        print("Too few valid windows")
        return 1

    print(f"\n{len(per_win)} valid windows aggregated\n")

    def med(key):
        vals = [w[key] for w in per_win if w.get(key) is not None]
        return float(np.median(vals)) if vals else None

    print("=" * 60)
    print("SCIPY FREQUENCY-DOMAIN + NON-LINEAR RESULTS")
    print("=" * 60)
    print(f"  Windows aggregated: {len(per_win)}")
    print(f"  VLF power:    {med('vlf'):.1f} ms²")
    print(f"  LF power:     {med('lf'):.1f} ms²")
    print(f"  HF power:     {med('hf'):.1f} ms²")
    lh = med('lf_hf')
    if lh:
        print(f"  LF/HF ratio:  {lh:.2f}")
    print(f"  SD1:          {med('sd1'):.2f} ms")
    print(f"  SD2:          {med('sd2'):.2f} ms")
    dfa = med('dfa')
    if dfa:
        print(f"  DFA α1:       {dfa:.2f}")

    print()
    print("Compare against URUJ Bio Lab Autonomic Frequency card:")
    print("  URUJ LF/HF = 5.84 — expect within ±20% on this method")
    print("  URUJ DFA α1 = 1.78 — expect within ±15%")
    print("  URUJ SD1 = 7.0 — currently INVARIANT-BROKEN (should be ~9.83)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
