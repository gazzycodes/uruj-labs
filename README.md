<div align="center">

# URUJ LABS

**Endurance cycling computer + biohacker training lab for Android**

*Native Kotlin · Jetpack Compose · Health Connect · Open-Meteo*

**عروج** — *ascent, rising, the act of climbing*

</div>

---

## What this is

URUJ Labs is a **sovereign-data Android app for serious endurance cyclists** who want more than a passive recorder.

- Live HUD with **physics-model power estimation**, **DEM-based elevation correction**, **real-time wind awareness**, lock-screen-visible during 4+ hour rides.
- **Biohacker-grade pre-ride readiness scoring** — Whoop / Oura methodology — derived from Health Connect inputs (sleep, HRV, RHR, training load).
- **Three-tier hardware adaptive**: works standalone (phone only), unlocks more with a Health Connect wearable, unlocks even more with BLE chest strap / power meter.
- **Your raw NDJSON sample data stays on your device**, owned by you. No cloud lock-in.

Built solo over a 36-hour push from 2026-05-10 to 2026-05-12. Validated on a 101 km century ride and a 45 km Kolkata loop, cross-checked against Strava + Samsung Health for accuracy.

> **Full roadmap + version log + biohacker vision**: [`docs/PLAN.md`](docs/PLAN.md)
> — every shipped version with rationale, queued versions (workout BLE → continuous biometric service → CAR / orthostatic / postprandial → power meter → CGM → custom sensors), data-fusion architecture, hardware ladder with costs. This is the actual build log — public, unredacted, no marketing.

---

## Three-tier user model

URUJ scales gracefully with whatever hardware you have:

| Tier | Hardware | What you get |
|---|---|---|
| **0 — Phone only** | Just the Android device | Speed · estimated power · zones · grade · elevation · wind · route map · post-ride summary · ride history |
| **1 — + Wearable** *(Fit Band 3 / Apple Watch / Whoop → Health Connect)* | Tier 0 + **post-ride HR enrichment** + **pre-ride readiness scoring** (sleep / HRV / RHR / training load) |
| **2 — + BLE sensors** *(chest strap, power meter, cadence)* | Tier 1 + **live HR on HUD** during the ride + measured power if meter present |

Every metric checks data-source availability at runtime and hides gracefully when missing. Empty UI, never broken UI.

---

## Shipped in v0.1

### Recording infrastructure
- 1 Hz GPS sampling via `FusedLocationProviderClient` (HIGH_ACCURACY)
- **Crash-safe append-only NDJSON** — force-kill loses ≤1 sample
- Foreground service with lock-screen HUD takeover (`setShowWhenLocked`)
- Combined GPS + accelerometer **auto-pause** (5s threshold, matches Garmin/Wahoo)
- 1 Hz wall-clock ticker — decoupled from sparse indoor GPS
- **GPS-quality gating** (25m accuracy threshold) prevents indoor cell-tower-fused junk from corrupting metrics
- WAKE_LOCK during recording — survives OEM background killing
- Auto-recovery of orphan NDJSON files into history

### Sensor fusion (five inputs)
- GPS — position + Doppler speed + bearing
- **Barometer** (when device has one) — elevation + grade
- Linear accelerometer — vibration baseline for auto-pause
- Health Connect — HR, sleep, HRV, RHR (post-batch from any wearable)
- **Open-Meteo APIs** — DEM elevation (Strava-grade) + live weather/wind, no key needed

### Power model (physics-based)
- Standard Coggan-style formula: `P = rolling + aero + climbing + inertia`
- `Crr` from tire profile · `CdA` from riding position
- 3-second + 30-second rolling smoothed averages
- 5-zone training model with live color-coded zone bar
- Live VAM (vertical ascent meters/hour)
- Total work in kJ + calorie estimate

### Elevation tracker (3-source priority)
- **Barometer** when device has the sensor (sub-meter)
- **Open-Meteo DEM lookup** as the canonical fallback (what Strava uses)
- **Smoothed GPS altitude** as last resort
- Tighter grade clamps as source quality degrades (±15% / ±10% / ±8%)
- Physical-impossibility filter: reject single-sample altitude deltas >3m/sec

### Pre-ride readiness (the biohacker layer)
- Score 0–100 from sleep / HRV / RHR / training load
- Whoop / Oura methodology — weighted blend, confidence-aware
- **HR-proxy fallback** when wearable doesn't write HRV/RHR direct records (Garmin / Fitbit method): derives proxies from raw `HeartRateRecord` samples
- Manual SYNC button + visible diagnostics (record counts, freshness, permission state)
- Health Connect pipeline inventory screen showing all 16 data types

### Post-ride
- Summary card: distance, time, avg/max speed, avg/max power, total work kJ, IF, TSS, calories, elev gain/loss
- **Auto HR enrichment** — polls Health Connect for 5 min after STOP, merges HR records once wearable syncs
- 30-second periodic checkpoint during ride — service kill loses ≤30s, not the whole ride
- Persistent ride history with browsable list
- Share button → JPG export → system share chooser

### Athletic gamification
- Live PR detection: 1-min / 5-min / 20-min rolling best power
- DataStore-persisted PRs survive across rides
- HUD flash overlay + TTS announcement on new PRs (with 5-min cooldown to prevent spam)
- TTS audio coach — km callouts every kilometer

### URUJ Labs aesthetic
- Pure-black background (AMOLED-friendly)
- Material Green A400 accent (`UrujAccent`)
- Spinning orbit-arc logo (lab-instrument feel, not AI-generated gradient)
- Lock-screen-visible rich notification with `RemoteViews`

---

## Architecture

```
com.uruj
├── domain/              # data classes — RideSample, RiderProfile, Readiness*
├── sensor/              # source interfaces — Location, Barometer, Accelerometer, HealthConnectHr
│   └── android/         # Android implementations of the above
├── power/               # physics — PowerEstimator, ElevationTracker, RollingAverage,
│                        #          PrTracker, HrAnalyzer, ReadinessCalculator
├── weather/             # Open-Meteo clients — WeatherClient, ElevationClient
├── data/                # persistence — RiderProfileStore, RideHistoryRepository,
│                        #               ReadinessRepository, HealthConnectInventory
├── service/             # RideRecorderService (foreground) + RideStateHolder + RideNotifications
├── audio/               # TtsAnnouncer
├── ui/
│   ├── theme/           # URUJ palette + theme
│   ├── branding/        # UrujLogo (spinning orbit arc)
│   ├── hud/             # the futuristic ride HUD
│   ├── checklist/       # pre-ride checks + readiness card
│   ├── profile/         # rider profile editor
│   ├── history/         # past rides
│   ├── summary/         # post-ride summary + share
│   └── diagnostics/     # Health Connect pipeline inventory
└── util/                # Haversine, etc.
```

---

## Tech stack

- **Language**: 100% Kotlin (2.2.10)
- **UI**: Jetpack Compose (BOM 2026.02.01)
- **AGP / Gradle**: AGP 9.2.1, Kotlin DSL build scripts
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **Concurrency**: kotlinx.coroutines + structured concurrency throughout
- **Persistence**: DataStore (Preferences) + per-ride NDJSON/JSON files
- **Sensors**: FusedLocationProviderClient + SensorManager
- **Health**: `androidx.health.connect:connect-client:1.1.0-alpha10`
- **Serialization**: kotlinx.serialization JSON
- **Networking**: HttpURLConnection (no third-party HTTP lib — keep the APK small)
- **External APIs**: Open-Meteo (weather + DEM elevation — free, no key)

---

## Build & run

```bash
git clone https://github.com/gazzycodes/uruj-labs.git
cd uruj-labs
# Open in Android Studio (Iguana+ recommended)
# Sync Gradle
# Run on a real device (API 26+) — emulator works but no GPS / barometer
```

You'll be prompted to:
1. Grant location permission (fine + background)
2. Grant notification permission (Android 13+)
3. Grant Health Connect permissions (HR, HRV, sleep, RHR, steps, distance, calories, VO2 Max, weight, etc. — 16 types in one bundle)
4. Disable battery optimization for the app (or OxygenOS will kill the foreground service mid-ride)

---

## Roadmap

### Session 2 — Visualization + share
- OSMDroid route map on summary (color-coded by speed / power)
- Time-series charts (power / speed / HR over distance)
- **Proper 9:16 Strava-style branded share card** (not a UI screenshot — dedicated design)
- VO2 Max display (Uth-Sørensen and power-based formulas)

### Session 3 — Bio Lab
- Dedicated dashboard for all 16 Health Connect data types
- 7 / 30 / 90-day trend graphs per metric
- Weight loss trajectory · VO2 trend · SpO2 nightly · RHR baseline drift
- "Stress proxy" derived from HRV inverse

### Session 4 — Training rigor
- Best-effort tables (all-time 5s / 30s / 1min / 5min / 20min / 60min peak power)
- HR drift / aerobic decoupling analysis post-ride
- Training load chart (CTL / ATL / TSB weekly)
- Structured workouts (intervals: "5×5min Z4 / 5min Z2")

### v1.5 — Hardware
- BLE chest strap support (`BleHrSource` implements existing `Flow<HrSample>`)
- BLE cadence sensor
- BLE power meter (when user is ready to buy one)

### Later
- Strava OAuth + historical activity import
- Ghost-rider live deltas on known routes
- Telemetry overlay on chest-cam clips (desktop Python tool)
- Public Play Store release polish

---

## Hardware roadmap (what gets unlocked)

| Add | Cost | What unlocks |
|---|---|---|
| **BLE chest strap** *(Coospo H6 / Polar H10 / Wahoo TICKR)* | ₹2k–6k | Live HR + HR zones on HUD during ride · Tier 2 |
| **Smart scale** *(any Health Connect-compatible)* | ₹3k–8k | Weight trend graph · body fat % trend · BMI |
| **BLE cadence sensor** | ₹2k | Measured cadence (vs the speed-grade-estimate) |
| **BLE power meter pedals/crank** *(Favero / Stages / Quarq)* | ₹25k–70k | Measured power ±1% (replaces estimation) · Cat-3-racer-grade |

---

## Acknowledgments

- **Open-Meteo** — free weather + DEM elevation APIs without an API key (the unsung hero of this project)
- **Health Connect** team — the standardized health data layer Android needed
- **Andy Coggan & Hunter Allen** — *Training and Racing with a Power Meter*, the source of all the IF/TSS/NP math
- **OpenTracks** — the open-source reference that proved Android cycling apps don't need to be subscriptions

---

## License

TBD — currently personal/closed during active development. Will move to a permissive license (MIT or Apache 2.0) once v1.0 ships.

---

<div align="center">

**صعود**

Built solo. Used daily. The lab grows with the rider.

</div>
