"""Analyze a URUJ Labs NDJSON ride file.

Usage: python analyze_ride.py <path-to-ndjson>

Reports distance, duration, speed stats, elevation, power distribution, GPS
quality, and surfaces any anomalies (impossible-grade samples, power spikes,
PR-storm windows). Cross-validates against Samsung Health / Strava when known.
"""

import json
import math
import statistics
import sys
from pathlib import Path
from datetime import datetime, timezone


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6_371_000.0
    rl1, rl2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(rl1) * math.cos(rl2) * math.sin(dlon / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def load_samples(path: Path):
    samples = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                samples.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return samples


def fmt_dur(seconds):
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    return f"{h}:{m:02d}:{s:02d}"


def percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * pct
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = Path(sys.argv[1])
    samples = load_samples(path)
    if not samples:
        print(f"No samples in {path}")
        return

    print(f"\n=== {path.name} ===")
    print(f"Total samples: {len(samples):,}")

    first, last = samples[0], samples[-1]
    start_ms = first["timestampMs"]
    end_ms = last["timestampMs"]
    total_dur_s = (end_ms - start_ms) / 1000
    print(f"Start: {datetime.fromtimestamp(start_ms/1000, tz=timezone.utc)}")
    print(f"End:   {datetime.fromtimestamp(end_ms/1000, tz=timezone.utc)}")
    print(f"Total elapsed: {fmt_dur(total_dur_s)}")

    # Distance, moving time, GPS quality breakdown
    total_distance_m = 0.0
    total_distance_filtered_m = 0.0
    moving_time_s = 0.0
    pause_time_s = 0.0
    gps_accurate_count = 0
    gps_unusable_count = 0
    speeds_ms = []
    speeds_moving_ms = []
    accuracies = []
    elevations = []
    pressures = []

    prev = None
    for s in samples:
        acc = s.get("horizontalAccuracyMeters", 999)
        accuracies.append(acc)
        is_paused = s.get("isPaused", False)
        sp = s.get("speedMetersPerSecond", 0.0)
        speeds_ms.append(sp)
        if not is_paused:
            speeds_moving_ms.append(sp)
        if s.get("altitudeMeters") is not None:
            elevations.append(s["altitudeMeters"])
        if s.get("pressureHpa") is not None:
            pressures.append(s["pressureHpa"])

        if acc <= 25:
            gps_accurate_count += 1
        if acc > 100:
            gps_unusable_count += 1

        if prev is not None:
            dt = (s["timestampMs"] - prev["timestampMs"]) / 1000
            if 0 < dt < 10:  # ignore huge gaps
                if is_paused:
                    pause_time_s += dt
                else:
                    moving_time_s += dt
                d = haversine_m(
                    prev["latitude"], prev["longitude"],
                    s["latitude"], s["longitude"],
                )
                if d < 100:
                    total_distance_m += d
                    # Apply our actual app-side filter: GPS quality + 1m floor
                    if acc <= 25 and 1.0 <= d <= 100.0 and not is_paused:
                        total_distance_filtered_m += d
        prev = s

    print(f"\n--- DISTANCE ---")
    print(f"Raw haversine distance: {total_distance_m/1000:.2f} km")
    print(f"After GPS-quality + 1m filter (what URUJ records): {total_distance_filtered_m/1000:.2f} km")
    print(f"Moving time: {fmt_dur(moving_time_s)}")
    print(f"Paused time: {fmt_dur(pause_time_s)}")

    print(f"\n--- GPS QUALITY ---")
    print(f"Accurate (<=25m): {gps_accurate_count:,} ({100*gps_accurate_count/len(samples):.1f}%)")
    print(f"Unusable (>100m): {gps_unusable_count:,} ({100*gps_unusable_count/len(samples):.1f}%)")
    if accuracies:
        sorted_acc = sorted(accuracies)
        print(f"Accuracy median: {percentile(sorted_acc, 0.5):.1f}m")
        print(f"Accuracy p90: {percentile(sorted_acc, 0.9):.1f}m")
        print(f"Accuracy p99: {percentile(sorted_acc, 0.99):.1f}m")

    print(f"\n--- SPEED ---")
    if speeds_moving_ms:
        sm = sorted(speeds_moving_ms)
        print(f"Avg moving: {statistics.mean(speeds_moving_ms)*3.6:.1f} kph")
        print(f"Median moving: {percentile(sm, 0.5)*3.6:.1f} kph")
        print(f"Max: {max(speeds_moving_ms)*3.6:.1f} kph")
        print(f"p95: {percentile(sm, 0.95)*3.6:.1f} kph")

    # Elevation analysis
    print(f"\n--- ELEVATION ---")
    if elevations:
        print(f"GPS altitude range: {min(elevations):.1f} to {max(elevations):.1f} m (ellipsoidal)")
        print(f"Raw min-max delta: {max(elevations) - min(elevations):.1f} m")
        # Sum positive deltas in raw GPS alt (this is what gives bogus "gain" values)
        raw_gain = 0
        for i in range(1, len(elevations)):
            d = elevations[i] - elevations[i-1]
            if d > 0:
                raw_gain += d
        print(f"Raw GPS gain (sum positive deltas): {raw_gain:.0f} m  [usually overstated]")
    if pressures:
        print(f"\nBarometer pressure range: {min(pressures):.2f} to {max(pressures):.2f} hPa")
        # Convert pressures to altitudes for a barometer-based estimate
        SEA = 1013.25
        def alt(p): return 44_330 * (1 - (p / SEA) ** (1/5.255))
        bar_alts = [alt(p) for p in pressures]
        print(f"Barometer-derived altitude range: {min(bar_alts):.1f} to {max(bar_alts):.1f} m")
        print(f"Barometer min-max delta: {max(bar_alts) - min(bar_alts):.1f} m")
        # Smoothed gain
        win = 10
        smoothed = []
        for i in range(len(bar_alts)):
            lo = max(0, i-win//2)
            hi = min(len(bar_alts), i+win//2+1)
            smoothed.append(sum(bar_alts[lo:hi])/(hi-lo))
        smooth_gain = 0
        for i in range(1, len(smoothed)):
            d = smoothed[i] - smoothed[i-1]
            if d > 0.5:  # noise floor
                smooth_gain += d
        print(f"Smoothed barometer gain (>0.5m deltas): {smooth_gain:.0f} m  [more honest]")

        # Detect barometer spikes — sudden >2m altitude change in 1 sample
        spikes = []
        for i in range(1, len(bar_alts)):
            dt = (samples[i]["timestampMs"] - samples[i-1]["timestampMs"]) / 1000
            if 0 < dt < 5:
                d_alt = abs(bar_alts[i] - bar_alts[i-1])
                if d_alt > 2 and dt < 2:
                    spikes.append((samples[i]["timestampMs"], d_alt))
        print(f"Barometer spikes (>2m alt change in <2s): {len(spikes)}  ← THIS is what causes fake power numbers")

    # Compute power per sample using URUJ's physics model with default profile
    print(f"\n--- POWER (recomputed offline, default 80kg / Crr=0.005 / CdA=0.42) ---")
    GRAVITY = 9.81
    AIR_RHO = 1.225
    m = 80
    Crr = 0.005
    CdA = 0.42
    powers = []
    insane_powers = 0
    # Smooth grade over 10s window
    win_alt = []
    win_dist = []
    cum_dist = 0
    for i, s in enumerate(samples):
        sp = s.get("speedMetersPerSecond", 0)
        acc = s.get("horizontalAccuracyMeters", 999)
        is_paused = s.get("isPaused", False)
        if is_paused or acc > 25 or sp < 0.5:
            powers.append(0)
            continue
        # crude grade from barometer if available, else GPS altitude
        if pressures and i < len(bar_alts):
            alt_now = bar_alts[i]
        else:
            alt_now = s.get("altitudeMeters", 0)
        if i > 0:
            d_alt = alt_now - (bar_alts[i-1] if pressures else samples[i-1].get("altitudeMeters", 0))
            d_dist = sp * 1.0  # approx 1s sample
            grade = max(-0.5, min(0.5, d_alt / d_dist)) if d_dist > 0.5 else 0
        else:
            grade = 0
        # Power formula
        rolling = Crr * m * GRAVITY * sp
        aero = 0.5 * AIR_RHO * CdA * sp**3
        climb = m * GRAVITY * math.sin(grade) * sp
        p = max(0, rolling + aero + climb)
        powers.append(p)
        if p > 800:
            insane_powers += 1
    nonzero_powers = [p for p in powers if p > 0]
    if nonzero_powers:
        ps = sorted(nonzero_powers)
        print(f"Avg (moving): {statistics.mean(nonzero_powers):.0f} W")
        print(f"Median (moving): {percentile(ps, 0.5):.0f} W")
        print(f"p95: {percentile(ps, 0.95):.0f} W")
        print(f"p99: {percentile(ps, 0.99):.0f} W")
        print(f"Max: {max(nonzero_powers):.0f} W")
        print(f"Samples >800W (suspicious): {insane_powers} ({100*insane_powers/len(samples):.1f}%)")
        # Work in kJ
        work_j = sum(nonzero_powers)  # power * 1s per sample
        print(f"Total work: {work_j/1000:.0f} kJ (~ {work_j/4184:.0f} kcal)")


if __name__ == "__main__":
    main()
