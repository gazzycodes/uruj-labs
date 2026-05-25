#!/usr/bin/env python3
"""
Ground-truth RMSSD computation — no library wrappers, no median aggregation,
no per-window filtering. Just Task Force 1996 standard:

  RMSSD = sqrt(mean(diff(RR)^2))

This is the definition every reference (Kubios, Polar, neurokit2, URUJ)
ultimately computes. If three different implementations disagree, run this
to find the actual truth.

Usage:
  python tools/raw_rmssd_check.py tools/sleep-window.ndjson
"""

import json
import sys
from pathlib import Path

import numpy as np


def main(path: str) -> int:
    p = Path(path)
    if not p.exists():
        print(f"ERROR: {p} not found")
        return 1

    # Load NDJSON and extract ALL RR intervals (preserving order)
    rr_all: list[int] = []
    sample_count = 0
    with p.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                s = json.loads(line)
            except json.JSONDecodeError:
                continue
            sample_count += 1
            rr_list = s.get('rrIntervalsMs') or []
            rr_all.extend(int(r) for r in rr_list if r)

    print(f"Loaded {sample_count} samples → {len(rr_all)} raw RR intervals")

    # ----------------------------------------------------------------
    # Method 1: TRULY raw — every RR, no filtering at all
    # ----------------------------------------------------------------
    if len(rr_all) >= 2:
        rr_arr = np.array(rr_all, dtype=float)
        diffs = np.diff(rr_arr)
        rmssd = float(np.sqrt(np.mean(diffs ** 2)))
        print(f"\n[1] RMSSD on ALL raw RR (no filter):")
        print(f"    n = {len(rr_arr)} RR, {len(diffs)} diffs")
        print(f"    RMSSD = {rmssd:.2f} ms")
        print(f"    mean RR = {np.mean(rr_arr):.0f} ms  →  HR = {60000 / np.mean(rr_arr):.1f} bpm")

    # ----------------------------------------------------------------
    # Method 2: Physiological filter only (300–2000 ms)
    # ----------------------------------------------------------------
    rr_phys = [r for r in rr_all if 300 <= r <= 2000]
    rr_arr = np.array(rr_phys, dtype=float)
    diffs = np.diff(rr_arr)
    rmssd = float(np.sqrt(np.mean(diffs ** 2)))
    print(f"\n[2] RMSSD with physiological filter (300-2000ms):")
    print(f"    n = {len(rr_arr)} RR, {len(diffs)} diffs")
    print(f"    RMSSD = {rmssd:.2f} ms")
    print(f"    mean RR = {np.mean(rr_arr):.0f} ms  →  HR = {60000 / np.mean(rr_arr):.1f} bpm")

    # ----------------------------------------------------------------
    # Method 3: Physiological + ectopic 20% delta filter
    # (this is URUJ's two-stage filter, MINUS timestamp consecutiveness)
    # ----------------------------------------------------------------
    diffs_eligible = []
    for i in range(1, len(rr_phys)):
        prev = rr_phys[i - 1]
        curr = rr_phys[i]
        delta_pct = abs(curr - prev) / prev
        if delta_pct > 0.20:
            continue
        diffs_eligible.append(curr - prev)
    if diffs_eligible:
        diffs_arr = np.array(diffs_eligible, dtype=float)
        rmssd = float(np.sqrt(np.mean(diffs_arr ** 2)))
        print(f"\n[3] RMSSD with physiological + ectopic 20% filter:")
        print(f"    n diffs = {len(diffs_arr)} (rejected {len(rr_phys) - 1 - len(diffs_arr)})")
        print(f"    RMSSD = {rmssd:.2f} ms")

    # ----------------------------------------------------------------
    # Method 4: Median RMSSD per 5-min CHUNK (mirrors Task Force 1996
    # standard for long captures — what URUJ + Kubios do)
    # ----------------------------------------------------------------
    # Reconstruct beat timestamps (last-RR-at-sample-timestampMs convention)
    beats: list[tuple[int, int]] = []
    with p.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                s = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = int(s['timestampMs'])
            rr_list = s.get('rrIntervalsMs') or []
            for rr in reversed(rr_list):
                if rr:
                    beats.append((t, int(rr)))
                    t -= int(rr)
    beats.sort()

    # Bucket beats into 5-min windows
    if not beats:
        return 0
    first_ts = beats[0][0]
    WIN_MS = 5 * 60 * 1000
    windows: dict[int, list[tuple[int, int]]] = {}
    for t, rr in beats:
        idx = (t - first_ts) // WIN_MS
        windows.setdefault(idx, []).append((t, rr))

    rmssd_per_window: list[float] = []
    for idx in sorted(windows.keys()):
        win = windows[idx]
        if len(win) < 30:
            continue
        # Physiological + ectopic, NO timestamp consecutiveness check
        rr_in_win = [rr for _, rr in win if 300 <= rr <= 2000]
        if len(rr_in_win) < 30:
            continue
        diffs_win = []
        for i in range(1, len(rr_in_win)):
            prev = rr_in_win[i - 1]
            curr = rr_in_win[i]
            if abs(curr - prev) / prev > 0.20:
                continue
            diffs_win.append(curr - prev)
        if len(diffs_win) < 25:
            continue
        diffs_arr = np.array(diffs_win, dtype=float)
        rmssd_per_window.append(float(np.sqrt(np.mean(diffs_arr ** 2))))

    if rmssd_per_window:
        print(f"\n[4] Per-window RMSSD, median aggregation (Task Force 1996):")
        print(f"    {len(rmssd_per_window)} valid 5-min windows")
        print(f"    median RMSSD = {np.median(rmssd_per_window):.2f} ms")
        print(f"    mean RMSSD   = {np.mean(rmssd_per_window):.2f} ms")
        print(f"    min          = {np.min(rmssd_per_window):.2f} ms")
        print(f"    max          = {np.max(rmssd_per_window):.2f} ms")
        print(f"    p25          = {np.percentile(rmssd_per_window, 25):.2f} ms")
        print(f"    p75          = {np.percentile(rmssd_per_window, 75):.2f} ms")

    return 0


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python raw_rmssd_check.py <ndjson_file>")
        sys.exit(1)
    sys.exit(main(sys.argv[1]))
