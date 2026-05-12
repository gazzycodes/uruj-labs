"""Quick sanity check for an OpenTracks KMZ/KML export.

Usage: python inspect_ride.py <path-to-kmz-or-kml>
Reports duration, distance, speed, elevation gain, sample cadence.
"""
import sys
import math
import zipfile
import statistics
from pathlib import Path
from xml.etree import ElementTree as ET
from datetime import datetime


KML_NS = "{http://www.opengis.net/kml/2.3}"
GX_NS = "{http://www.google.com/kml/ext/2.2}"


def load_kml_text(path: Path) -> str:
    if path.suffix.lower() == ".kmz":
        with zipfile.ZipFile(path) as zf:
            kml_name = next(n for n in zf.namelist() if n.lower().endswith(".kml"))
            return zf.read(kml_name).decode("utf-8")
    return path.read_text(encoding="utf-8")


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    rl1, rl2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(rl1) * math.cos(rl2) * math.sin(dlon / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def parse_track(kml_text: str):
    root = ET.fromstring(kml_text)
    samples = []
    # OpenTracks uses MultiTrack > Track with <when> + <coord> pairs
    for track in root.iter(f"{KML_NS}Track"):
        whens = [el.text for el in track.findall(f"{KML_NS}when")]
        coords = [el.text for el in track.findall(f"{KML_NS}coord")]
        for w, c in zip(whens, coords):
            if not c or not c.strip():
                continue
            parts = c.strip().split()
            if len(parts) < 3:
                continue
            lon, lat, alt = float(parts[0]), float(parts[1]), float(parts[2])
            ts = datetime.fromisoformat(w.replace("Z", "+00:00"))
            samples.append((ts, lat, lon, alt))
    return samples


def summarize(samples):
    if not samples:
        print("No samples found.")
        return

    duration = samples[-1][0] - samples[0][0]
    total_m = 0.0
    elev_gain = 0.0
    elev_loss = 0.0
    intervals = []
    speeds_kph = []  # instantaneous speed per segment
    altitudes = [s[3] for s in samples]

    # Smooth altitude with a 5-sample moving average for gain calc (GPS alt is noisy)
    win = 5
    smoothed_alt = []
    for i in range(len(altitudes)):
        lo = max(0, i - win // 2)
        hi = min(len(altitudes), i + win // 2 + 1)
        smoothed_alt.append(sum(altitudes[lo:hi]) / (hi - lo))

    for i in range(1, len(samples)):
        t0, la0, lo0, _ = samples[i - 1]
        t1, la1, lo1, _ = samples[i]
        dt = (t1 - t0).total_seconds()
        d = haversine_m(la0, lo0, la1, lo1)
        total_m += d
        if dt > 0:
            intervals.append(dt)
            speeds_kph.append((d / dt) * 3.6)
        da = smoothed_alt[i] - smoothed_alt[i - 1]
        if da > 0:
            elev_gain += da
        else:
            elev_loss += -da

    moving_speeds = [s for s in speeds_kph if s > 1.0]  # >1 kph = moving

    print(f"Samples:              {len(samples):,}")
    print(f"Start:                {samples[0][0]}")
    print(f"End:                  {samples[-1][0]}")
    print(f"Total duration:       {duration}")
    print(f"Distance:             {total_m / 1000:.2f} km")
    print(f"Avg speed (all):      {(total_m / duration.total_seconds()) * 3.6:.2f} kph")
    if moving_speeds:
        print(f"Avg speed (moving):   {statistics.mean(moving_speeds):.2f} kph")
        print(f"Max speed:            {max(moving_speeds):.2f} kph")
        print(f"Median speed:         {statistics.median(moving_speeds):.2f} kph")
    print(f"Elevation gain (raw): {elev_gain:.0f} m")
    print(f"Elevation loss (raw): {elev_loss:.0f} m")
    print(f"Altitude range:       {min(altitudes):.1f} to {max(altitudes):.1f} m (raw GPS, ellipsoidal)")
    print(f"Sample interval:      median {statistics.median(intervals):.2f}s, "
          f"min {min(intervals):.2f}s, max {max(intervals):.2f}s, "
          f"p95 {statistics.quantiles(intervals, n=20)[18]:.2f}s")
    print(f"Bounding box:         lat [{min(s[1] for s in samples):.4f}, "
          f"{max(s[1] for s in samples):.4f}] "
          f"lon [{min(s[2] for s in samples):.4f}, {max(s[2] for s in samples):.4f}]")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = Path(sys.argv[1])
    kml_text = load_kml_text(path)
    samples = parse_track(kml_text)
    summarize(samples)


if __name__ == "__main__":
    main()
