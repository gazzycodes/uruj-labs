#!/usr/bin/env python3
"""
v0.9.29 — Independent lab-grade cross-validation of URUJ's HRV math via the
neurokit2 research library.

PURPOSE
=======
URUJ's freq-domain math (v0.9.28 Lomb-Scargle + v0.9.27 filter alignment)
has been verified via:
  - Mathematical invariants (SD1 == RMSSD/sqrt2)
  - Synthetic test signals (FFT peak detection)
  - Internal cross-method comparison (Lomb-Scargle vs Welch)

This script adds the THIRD lab-grade gate: independent cross-validation
against neurokit2 (https://neurokit2.readthedocs.io) — a peer-reviewed
open-source HRV library used in published clinical research.

If neurokit2's numbers ~= URUJ's numbers (within +/-20%), URUJ is genuinely
Kubios-equivalent on REAL noisy data — not just on synthetic test signals.

USAGE
=====
1. Install dependencies (one-time):
     pip install neurokit2 numpy

2. Pull URUJ's overnight NDJSON from device via adb:
     adb pull /sdcard/Android/data/com.uruj/files/continuous/2026-05-22.ndjson .

3. Run validation:
     python tools/validate_hrv_neurokit2.py 2026-05-22.ndjson

4. Compare output to URUJ's HrvSnapshot for the same date:
     adb pull /data/data/com.uruj/files/snapshots/hrv/2026-05-22.json .

   (Note: snapshot path may require root or run-as access. Easier: just
   open Bio Lab on device + tap Autonomic Frequency card to read the
   numbers visually.)

EXPECTED AGREEMENT
==================
With Lomb-Scargle PSD (matching URUJ v0.9.28 method):
  RMSSD            — should match within +/-5%
  SDNN             — should match within +/-5%
  SD1 = RMSSD/sqrt2   — invariant, must match
  SD2              — should match within +/-10%
  LF/HF ratio      — should match within +/-20% (noisier metric)
  DFA alpha1           — should match within +/-15%
  Sample entropy   — should match within +/-25% (most noisy)

If URUJ deviates >30% from neurokit2 on any metric, investigate.

CITATIONS
=========
  - Makowski et al. 2021. NeuroKit2: A Python toolbox for neurophysiological
    signal processing. Behavior Research Methods 53, 1689-1696.
  - Task Force 1996. HRV: standards of measurement, physiological
    interpretation, and clinical use.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np

try:
    import neurokit2 as nk
except ImportError:
    print("ERROR: neurokit2 not installed. Run: pip install neurokit2 numpy")
    sys.exit(1)


# ========================================================================
# Constants — MUST MATCH URUJ's FrequencyDomainCalculator / HrvCalculator
# ========================================================================

PHYSIOLOGICAL_MIN = 300     # ms (30 bpm)
PHYSIOLOGICAL_MAX = 2000    # ms (200 bpm)
MIN_TOLERANCE_MS = 150      # Timestamp consecutiveness floor
TIMESTAMP_TOLERANCE_PCT = 0.30  # 30% of expected RR
ECTOPIC_THRESHOLD = 0.20    # 20% delta cap
WINDOW_MS = 5 * 60 * 1000   # 5-min windows (Task Force 1996)
MIN_BEATS_PER_WINDOW = 30
MIN_DIFFS_PER_WINDOW = 30
MIN_VALID_WINDOWS = 3


# ========================================================================
# NDJSON loading + beat reconstruction
# ========================================================================

def load_ndjson_samples(path: Path) -> list[dict[str, Any]]:
    """Load URUJ's continuous biometric NDJSON. Each line is one sample
    with optional rrIntervalsMs[] array."""
    samples = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                samples.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"Skipping invalid JSON line: {e}", file=sys.stderr)
    return samples


def reconstruct_beats(samples: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Reconstruct individual beat timestamps from sample.timestampMs +
    rrIntervalsMs[] array.

    Each RR interval ends at the sample's timestamp (Magene H613 convention).
    Mirrors URUJ's ContinuousBiometricRepository.samplesToBeats logic.
    """
    beats = []
    for s in samples:
        rr_list = s.get('rrIntervalsMs') or []
        if not rr_list:
            continue
        t = s['timestampMs']
        # Walk RR intervals backwards from sample timestamp
        for rr in reversed(rr_list):
            beats.append({'timestampMs': t, 'rrMs': int(rr)})
            t -= int(rr)
    beats.sort(key=lambda b: b['timestampMs'])
    return beats


# ========================================================================
# Per-pair validation (EXACT mirror of HrvCalculator.consecutiveDiffsMs +
# FrequencyDomainCalculator.consecutiveDiffsMs)
# ========================================================================

def physiological_filter(beats: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Drop RR intervals outside physiological range (300-2000 ms)."""
    return [b for b in beats if PHYSIOLOGICAL_MIN <= b['rrMs'] <= PHYSIOLOGICAL_MAX]


def filter_by_local_time(
    beats: list[dict[str, Any]],
    from_time: str | None,
    to_time: str | None,
) -> list[dict[str, Any]]:
    """v0.9.30 — Filter beats to a local-time HH:MM window.

    Supports overnight windows (from > to crosses midnight). All
    timestamps are interpreted in the host's local timezone (matches
    URUJ's LocalDate.now() / LocalDateTime semantics).
    """
    from datetime import datetime

    def parse_hhmm(s: str) -> tuple[int, int]:
        h, m = s.split(':')
        return int(h), int(m)

    if not from_time and not to_time:
        return beats

    fh, fm = parse_hhmm(from_time) if from_time else (0, 0)
    th, tm = parse_hhmm(to_time) if to_time else (23, 59)

    overnight = (fh, fm) > (th, tm)

    out = []
    for b in beats:
        dt = datetime.fromtimestamp(b['timestampMs'] / 1000.0)
        beat_hm = (dt.hour, dt.minute)
        in_range = (
            ((fh, fm) <= beat_hm <= (th, tm))
            if not overnight
            else (beat_hm >= (fh, fm) or beat_hm <= (th, tm))
        )
        if in_range:
            out.append(b)
    return out


def validated_rr_series(beats: list[dict[str, Any]]) -> list[int]:
    """Return RR series for windows that pass per-pair validation.

    Two-stage filter, exact mirror of URUJ:
      1. Timestamp consecutiveness: |actualGap − expectedGap| ≤ tolerance
         where tolerance = max(150ms, 30% of expectedGap)
      2. Ectopic delta cap: |curr.rrMs − prev.rrMs| / prev.rrMs ≤ 0.20

    Returns RR values (in ms) for beats whose PRIOR pair was valid,
    suitable for feeding into neurokit2 functions.
    """
    if len(beats) < 2:
        return []
    rr_series = [beats[0]['rrMs']]
    for i in range(1, len(beats)):
        prev = beats[i - 1]
        curr = beats[i]
        expected_gap = curr['rrMs']
        actual_gap = curr['timestampMs'] - prev['timestampMs']
        tolerance = max(MIN_TOLERANCE_MS, expected_gap * TIMESTAMP_TOLERANCE_PCT)
        if abs(actual_gap - expected_gap) > tolerance:
            continue  # cross-gap pair rejected
        delta_pct = abs(curr['rrMs'] - prev['rrMs']) / prev['rrMs']
        if delta_pct > ECTOPIC_THRESHOLD:
            continue  # ectopic pair rejected
        rr_series.append(curr['rrMs'])
    return rr_series


# ========================================================================
# 5-min windowing + per-window neurokit2 metrics
# ========================================================================

def split_into_windows(beats: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Group beats into 5-min buckets by timestamp."""
    if not beats:
        return []
    first_ts = beats[0]['timestampMs']
    windows: dict[int, list[dict[str, Any]]] = {}
    for b in beats:
        idx = (b['timestampMs'] - first_ts) // WINDOW_MS
        windows.setdefault(idx, []).append(b)
    return list(windows.values())


def compute_window_metrics(
    win_beats: list[dict[str, Any]],
) -> dict[str, float] | None:
    """Run neurokit2 on a single 5-min window. Returns None if window
    has insufficient validated diffs.

    Uses Lomb-Scargle PSD (psd_method='lomb') to match URUJ v0.9.28
    primary frequency-domain method.
    """
    if len(win_beats) < MIN_BEATS_PER_WINDOW:
        return None
    rr_series = validated_rr_series(win_beats)
    if len(rr_series) < MIN_DIFFS_PER_WINDOW:
        return None

    # neurokit2 expects PEAK indices (cumulative beat positions in samples),
    # not raw RR intervals. Convert: peaks[i] = sum(rr[0..i]) in ms,
    # then converted to "samples" at 1000Hz (1 sample per ms).
    rri_ms = np.array(rr_series, dtype=float)
    peaks = np.cumsum(rri_ms).astype(int)  # cumulative ms → "samples" at 1000Hz
    SAMPLING_RATE = 1000  # 1 sample per ms

    out: dict[str, float] = {'beat_count': len(rr_series)}

    # -- Time-domain --
    try:
        hrv_time = nk.hrv_time(peaks, sampling_rate=SAMPLING_RATE, show=False)
        out['rmssd'] = float(hrv_time['HRV_RMSSD'].iloc[0])
        out['sdnn'] = float(hrv_time['HRV_SDNN'].iloc[0])
        if 'HRV_pNN50' in hrv_time.columns:
            out['pnn50'] = float(hrv_time['HRV_pNN50'].iloc[0])
    except Exception as e:
        print(f"  time-domain failed: {e}", file=sys.stderr)
        return None

    # -- Frequency-domain (Lomb-Scargle, matches URUJ v0.9.28) --
    try:
        hrv_freq = nk.hrv_frequency(
            peaks,
            sampling_rate=SAMPLING_RATE,
            psd_method='lomb',
            show=False,
        )
        if 'HRV_LF' in hrv_freq.columns:
            out['lf'] = float(hrv_freq['HRV_LF'].iloc[0])
        if 'HRV_HF' in hrv_freq.columns:
            out['hf'] = float(hrv_freq['HRV_HF'].iloc[0])
        if 'HRV_VLF' in hrv_freq.columns:
            out['vlf'] = float(hrv_freq['HRV_VLF'].iloc[0])
        if 'HRV_LFHF' in hrv_freq.columns:
            out['lf_hf'] = float(hrv_freq['HRV_LFHF'].iloc[0])
    except Exception as e:
        print(f"  freq-domain failed: {e}", file=sys.stderr)

    # -- Non-linear --
    try:
        hrv_nl = nk.hrv_nonlinear(peaks, sampling_rate=SAMPLING_RATE, show=False)
        if 'HRV_SD1' in hrv_nl.columns:
            out['sd1'] = float(hrv_nl['HRV_SD1'].iloc[0])
        if 'HRV_SD2' in hrv_nl.columns:
            out['sd2'] = float(hrv_nl['HRV_SD2'].iloc[0])
        # neurokit2 field name varies by version
        for k in ('HRV_DFA_alpha1', 'HRV_DFA_alpha_1', 'HRV_DFA1'):
            if k in hrv_nl.columns:
                out['dfa_alpha1'] = float(hrv_nl[k].iloc[0])
                break
        for k in ('HRV_SampEn', 'HRV_SampleEn', 'HRV_SE'):
            if k in hrv_nl.columns:
                out['sample_entropy'] = float(hrv_nl[k].iloc[0])
                break
    except Exception as e:
        print(f"  non-linear failed: {e}", file=sys.stderr)

    return out


def median_aggregate(window_results: list[dict[str, float]]) -> dict[str, float | int]:
    """Median across valid windows (matches URUJ's aggregation)."""
    aggregated: dict[str, float | int] = {'window_count': len(window_results)}
    keys = ['rmssd', 'sdnn', 'pnn50', 'lf', 'hf', 'vlf', 'lf_hf',
            'sd1', 'sd2', 'dfa_alpha1', 'sample_entropy']
    for k in keys:
        values = [w[k] for w in window_results if k in w and not np.isnan(w[k])]
        aggregated[k] = float(np.median(values)) if values else float('nan')
    aggregated['total_beats'] = int(sum(w.get('beat_count', 0) for w in window_results))
    return aggregated


# ========================================================================
# Main
# ========================================================================

def main() -> int:
    parser = argparse.ArgumentParser(
        description='Cross-validate URUJ HRV math against neurokit2',
    )
    parser.add_argument(
        'ndjson_path',
        type=Path,
        help='Path to URUJ continuous biometric NDJSON (e.g. 2026-05-22.ndjson)',
    )
    parser.add_argument(
        '--output',
        type=Path,
        default=None,
        help='Optional path to write JSON result for diff against URUJ snapshot',
    )
    parser.add_argument(
        '--from-time',
        type=str,
        default=None,
        help='v0.9.30 — Filter beats to time range (HH:MM, local). Combined with '
             '--to-time, restricts analysis to a specific window (e.g. sleep '
             'only: --from-time 02:28 --to-time 11:12). Match URUJ Bio Lab '
             '"last sleep" window for apples-to-apples comparison.',
    )
    parser.add_argument(
        '--to-time',
        type=str,
        default=None,
        help='See --from-time. HH:MM local. If --to-time < --from-time, '
             'the window crosses midnight (e.g. --from-time 22:00 --to-time 07:00).',
    )
    args = parser.parse_args()

    if not args.ndjson_path.exists():
        print(f"ERROR: file not found: {args.ndjson_path}", file=sys.stderr)
        return 1

    # -- Load + reconstruct --
    print(f"Loading {args.ndjson_path}...")
    samples = load_ndjson_samples(args.ndjson_path)
    print(f"  {len(samples)} continuous samples")

    beats = reconstruct_beats(samples)
    print(f"  {len(beats)} reconstructed beats")

    physiological = physiological_filter(beats)
    print(f"  {len(physiological)} after physiological filter (300-2000 ms)")

    # v0.9.30 — Optional time-range filter for apples-to-apples comparison
    # with URUJ's "last sleep" window (or any specific period of interest).
    if args.from_time or args.to_time:
        physiological = filter_by_local_time(
            physiological, args.from_time, args.to_time,
        )
        rng = f"{args.from_time or '00:00'}-{args.to_time or '23:59'}"
        print(f"  {len(physiological)} after time filter [{rng} local]")

    # -- Window + compute per-window --
    windows = split_into_windows(physiological)
    print(f"  {len(windows)} candidate 5-min windows")

    valid_results = []
    for i, win in enumerate(windows):
        result = compute_window_metrics(win)
        if result is not None:
            valid_results.append(result)

    if len(valid_results) < MIN_VALID_WINDOWS:
        print(f"\nNot enough valid windows ({len(valid_results)}/{MIN_VALID_WINDOWS} required)")
        return 1

    print(f"\nOK {len(valid_results)} valid windows aggregated\n")

    # -- Aggregate + report --
    final = median_aggregate(valid_results)

    print("=" * 60)
    print("NEUROKIT2 RESULTS (cross-validation against URUJ)")
    print("=" * 60)
    print(f"  Windows:           {final['window_count']}")
    print(f"  Total beats:       {final['total_beats']}")
    print(f"  RMSSD:             {final['rmssd']:.2f} ms")
    print(f"  SDNN:              {final['sdnn']:.2f} ms")
    print(f"  pNN50:             {final['pnn50']:.2f} %")
    print(f"  SD1:               {final['sd1']:.2f} ms     (must ~= RMSSD/sqrt2)")
    print(f"  SD2:               {final['sd2']:.2f} ms")
    print(f"  LF power:          {final['lf']:.2f} ms^2")
    print(f"  HF power:          {final['hf']:.2f} ms^2")
    print(f"  VLF power:         {final['vlf']:.2f} ms^2")
    print(f"  LF/HF ratio:       {final['lf_hf']:.3f}")
    print(f"  DFA alpha1:            {final['dfa_alpha1']:.3f}")
    print(f"  Sample entropy:    {final['sample_entropy']:.3f}")
    print()

    # Self-check the invariant
    expected_sd1 = final['rmssd'] / np.sqrt(2)
    sd1_delta_pct = abs(final['sd1'] - expected_sd1) / expected_sd1 * 100
    print(f"  Invariant SD1 == RMSSD/sqrt2:")
    print(f"    RMSSD/sqrt2 = {expected_sd1:.2f} ms")
    print(f"    SD1      = {final['sd1']:.2f} ms")
    print(f"    delta        = {sd1_delta_pct:.1f}%  (must be <5%)")
    print()

    print("Compare against URUJ Bio Lab Autonomic Frequency card for the")
    print("same date. Expected agreement: +/-20% on LF/HF, +/-15% on DFA alpha1.")

    if args.output:
        args.output.write_text(json.dumps(final, indent=2))
        print(f"\nWrote result JSON to {args.output}")

    return 0


if __name__ == '__main__':
    sys.exit(main())
