"""
verify_car_trajectory.py — Cross-check CAR math + reveal activity confound.

Pulls today's NDJSON pre/post-wake window, recomputes baseline/peak/amplitude,
and plots the HR trajectory so we can see if the peak HR is a true autonomic
surge or a post-wake activity spike.

Reference methodology:
  - Pruessner 1997 / Clow 2010: healthy CAR = 10-25 bpm rise within 30 min
  - Stalder 2016: window = 0-45 min post-wake, baseline = pre-wake
  - URUJ's HR proxy of cortisol uses identical window structure

Run:
  python tools/verify_car_trajectory.py
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

# ---- config ----
TOOLS_DIR = Path(__file__).parent
PULL_DIR = TOOLS_DIR / "verify-pull"
CAR_DIR = PULL_DIR / "car-pull"
NDJSON_TODAY = PULL_DIR / "2026-05-26.ndjson"
NDJSON_YESTERDAY = PULL_DIR / "2026-05-25.ndjson"

PRE_WAKE_MIN = 10
POST_WAKE_MIN = 45


def load_ndjson(path: Path):
    """Yield each NDJSON sample dict from a daily file."""
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def slice_window(samples, start_ms: int, end_ms: int):
    """Return samples whose timestampMs is in [start_ms, end_ms]."""
    return [s for s in samples if start_ms <= s.get("timestampMs", 0) <= end_ms]


def verify_car(car_path: Path, ndjson_paths: list[Path]):
    """Verify one CAR cache file against raw NDJSON for the same window."""
    with car_path.open("r", encoding="utf-8") as f:
        car = json.load(f)

    sleep_end_ms = car["sleepEndMs"]
    pre_start_ms = sleep_end_ms - PRE_WAKE_MIN * 60_000
    post_end_ms = sleep_end_ms + POST_WAKE_MIN * 60_000

    # Load all candidate NDJSON (the window may straddle midnight).
    all_samples = []
    for p in ndjson_paths:
        if p.exists():
            all_samples.extend(load_ndjson(p))

    pre = slice_window(all_samples, pre_start_ms, sleep_end_ms)
    post = slice_window(all_samples, sleep_end_ms, post_end_ms)

    # Recompute baseline + peak (exact replica of CarDetector.kt).
    pre_bpm = [s.get("bpm", 0) for s in pre if s.get("bpm", 0) > 0]
    if not pre_bpm:
        print(f"  [no pre-wake samples — cannot verify {car_path.name}]")
        return
    py_baseline = sum(pre_bpm) / len(pre_bpm)

    py_peak = 0.0
    py_peak_at = sleep_end_ms
    for s in post:
        bpm = s.get("bpm", 0)
        if bpm > py_peak:
            py_peak = bpm
            py_peak_at = s.get("timestampMs", sleep_end_ms)
    py_amplitude = py_peak - py_baseline
    py_latency_min = max(0, (py_peak_at - sleep_end_ms) / 60_000)

    wake_dt = datetime.fromtimestamp(sleep_end_ms / 1000, tz=timezone.utc)
    print(f"\n=== {car_path.stem}  wake={wake_dt:%Y-%m-%d %H:%MZ} ===")
    print(f"  PRE-WAKE samples: {len(pre)}  ·  POST-WAKE samples: {len(post)}")
    print(f"  baseline:   URUJ={car['baselineHrBpm']:.1f}  Python={py_baseline:.1f}")
    print(f"  peak:       URUJ={car['peakHrBpm']:.1f}      Python={py_peak:.1f}")
    print(f"  amplitude:  URUJ={car['amplitudeBpm']:.1f}    Python={py_amplitude:.1f}")
    print(f"  latency:    URUJ={car['latencyMinutes']:.1f}m  Python={py_latency_min:.1f}m")
    print(f"  rmssd drop: {car['rmssdDropPercent']:.1f}%  (literature healthy = 30-60%)")

    # Confound diagnostic: where does HR climb past +20 bpm?  (Healthy CAR peaks 10-25 bpm.)
    healthy_ceiling = py_baseline + 25
    print(f"\n  Healthy CAR ceiling (+25 bpm from baseline) = {healthy_ceiling:.0f} bpm")
    print(f"  Trajectory by 5-min bin post-wake (mean HR):")
    bins = {}
    for s in post:
        bpm = s.get("bpm", 0)
        if bpm <= 0:
            continue
        bin_idx = int((s["timestampMs"] - sleep_end_ms) / 60_000 / 5)
        bins.setdefault(bin_idx, []).append(bpm)
    for idx in sorted(bins.keys()):
        vals = bins[idx]
        mean_bpm = sum(vals) / len(vals)
        max_bpm = max(vals)
        marker = "  <<<<< above healthy ceiling" if max_bpm > healthy_ceiling else ""
        bar = "█" * int((mean_bpm - 40) / 2)
        print(f"    +{idx*5:2d}-{(idx+1)*5:2d}min  mean={mean_bpm:5.1f}  max={max_bpm:5.0f}  n={len(vals):4d}  {bar}{marker}")

    # Interpretation:
    print()
    if py_amplitude > 30:
        print(f"  >> AMPLITUDE {py_amplitude:.0f} bpm  EXCEEDS healthy 10-25 bpm range.")
        if py_peak > healthy_ceiling:
            print(f"  >> Peak {py_peak:.0f} bpm is {py_peak - healthy_ceiling:.0f} above healthy ceiling.")
            print(f"  >> Likely a MIX of true CAR surge + post-wake activity (walking, stairs, etc.)")
            print(f"     True CAR signal sits in first 0-15 min when subject is still in bed.")
            # Refined estimate: peak HR in first 15 min (more likely true CAR window)
            quiet_window = [b for b in bins.get(0, []) + bins.get(1, []) + bins.get(2, [])]
            if quiet_window:
                quiet_peak = max(quiet_window)
                quiet_amp = quiet_peak - py_baseline
                print(f"     QUIET-WINDOW (0-15 min) peak = {quiet_peak:.0f} bpm  →  amplitude {quiet_amp:.0f} bpm")
    elif py_amplitude > 20:
        print(f"  >> AMPLITUDE {py_amplitude:.0f} bpm  at upper edge of healthy.")
    elif py_amplitude < 5:
        print(f"  >> AMPLITUDE {py_amplitude:.0f} bpm  BLUNTED — chronic-stress / burnout marker.")
    else:
        print(f"  >> AMPLITUDE {py_amplitude:.0f} bpm  HEALTHY range.")


def quiet_window_amplitude(car_path: Path, ndjson_paths: list[Path]):
    """Compute true CAR via mean-of-5-min-bins in first 30 min — rigorous Pruessner method."""
    with car_path.open("r", encoding="utf-8") as f:
        car = json.load(f)
    sleep_end_ms = car["sleepEndMs"]
    pre_start_ms = sleep_end_ms - PRE_WAKE_MIN * 60_000
    quiet_end_ms = sleep_end_ms + 30 * 60_000  # 30-min window per Clow 2010

    all_samples = []
    for p in ndjson_paths:
        if p.exists():
            all_samples.extend(load_ndjson(p))
    pre = slice_window(all_samples, pre_start_ms, sleep_end_ms)
    quiet = slice_window(all_samples, sleep_end_ms, quiet_end_ms)

    pre_bpm = [s.get("bpm", 0) for s in pre if s.get("bpm", 0) > 0]
    if not pre_bpm or not quiet:
        return None
    baseline = sum(pre_bpm) / len(pre_bpm)

    bins = {}
    for s in quiet:
        bpm = s.get("bpm", 0)
        if bpm <= 0:
            continue
        bin_idx = int((s["timestampMs"] - sleep_end_ms) / 60_000 / 5)
        bins.setdefault(bin_idx, []).append(bpm)
    bin_means = {idx: sum(v) / len(v) for idx, v in bins.items() if v}
    if not bin_means:
        return None
    peak_mean = max(bin_means.values())
    peak_bin = max(bin_means, key=lambda k: bin_means[k])
    return {
        "wake": datetime.fromtimestamp(sleep_end_ms / 1000, tz=timezone.utc),
        "wide_amp": car["amplitudeBpm"],
        "wide_peak": car["peakHrBpm"],
        "baseline": baseline,
        "quiet_peak_mean": peak_mean,
        "quiet_amplitude": peak_mean - baseline,
        "quiet_peak_bin_min": peak_bin * 5,
    }


def classify_quiet(amp):
    if amp < 5:
        return "BLUNTED (chronic stress / HPA-axis fatigue)"
    if amp < 10:
        return "SUPPRESSED (below healthy)"
    if amp < 20:
        return "HEALTHY"
    if amp < 30:
        return "ROBUST"
    return "EXAGGERATED (acute stress / anxiety)"


def main():
    car_files = sorted(CAR_DIR.glob("*.json"))
    if not car_files:
        print(f"No CAR files in {CAR_DIR}", file=sys.stderr)
        sys.exit(1)

    # Build NDJSON list for all available days.
    ndjson_paths = sorted((PULL_DIR).glob("2026-*.ndjson"))

    # Detailed verify on last 2 days first.
    for f in car_files[-2:]:
        verify_car(f, ndjson_paths)

    # Then summary: all 8 days WIDE vs QUIET.
    print("\n\n========================================")
    print("FULL 8-DAY COMPARISON — wide-window vs rigorous quiet-window")
    print("========================================")
    print(f"  {'date':<12} {'wide_amp':>10} {'wide_label':<14} {'quiet_amp':>10} {'quiet_label'}")
    for f in car_files:
        r = quiet_window_amplitude(f, ndjson_paths)
        if r is None:
            print(f"  {f.stem:<12}  [no data]")
            continue
        wide_label = "EXAGGERATED" if r["wide_amp"] > 30 else "HIGH"
        quiet_label = classify_quiet(r["quiet_amplitude"])
        print(f"  {f.stem:<12} {r['wide_amp']:>9.1f}  {wide_label:<14} {r['quiet_amplitude']:>9.1f}  {quiet_label}")
    print("\n--- math verification + methodology audit complete ---")


if __name__ == "__main__":
    main()
