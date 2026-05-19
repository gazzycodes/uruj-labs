<div align="center">

# URUJ LABS

**Personal physiology lab + cycling-training brain · Android**

*Native Kotlin · Jetpack Compose · Health Connect · BLE chest strap · Open-Meteo*

**عروج** — *ascent, rising, the act of climbing*

`v0.9.16` · Android 8.0+

</div>

---

## What this is

URUJ Labs is one app doing two jobs that no consumer product does together:

1. **A live cycling-training brain on the bike** — futuristic HUD with physics-model power estimate, Karvonen HR zones, grade %, wind awareness, route map, polarized 80/20 compliance, time-in-zone, FTP auto-update, audio coach with pre-ride session intent. Lock-screen visible during 4+ hour rides.
2. **A 24/7 biohacker physiology lab** — real RMSSD HRV from BLE chest strap RR intervals (continuous service writes ~5 MB/day NDJSON), CAR (Cortisol Awakening Response) detector, 4-min orthostatic test, snapshot-persisted trends for HRR1 / RHR / VO2 / TSB / Sleep / HRV — every metric data-loss-safe forever, none capped by Health Connect's 30-day retention.

The bet: **integration + transparency + sovereignty**. Whoop/Oura/Garmin show opaque "Recovery 67" scores; URUJ shows the formulas, the source sensors, the methodology version, and the per-component breakdown. Your raw NDJSON stays on your device. Free forever.

> Built solo by a non-credentialed polymath cyclist + Claude as coding partner, incrementally validated on real rides (101 km century, 37.78 km evening polarized session, ongoing). No medical degree, no team, no cloud backend. Documenting the build journey publicly is part of the point.

---

## Why this exists

Each consumer biohacker / cycling app does 20–50% of what URUJ targets, in a closed ecosystem with a subscription:

| App | Does well | Doesn't (vs URUJ) |
|---|---|---|
| **Whoop** | 24/7 PPG HRV, sleep, "Strain" + "Recovery" | Closed strap, opaque scores, subscription, no cycling power |
| **Oura** | Sleep stages, HRV trend, skin temp | Proprietary "Readiness", no cycling integration, ring-only |
| **EliteHRV / HRV4Training** | Morning RMSSD readings, tagging | No 24/7, no rides, no training load, no integration |
| **Kubios HRV** | Best frequency-domain HRV consumer-side | Research tool, no rides, no athlete framing |
| **Polar Flow / Garmin Connect** | Recovery Pro / Body Battery (genuinely good) | Locked to their ecosystem, opaque scores, closed math |
| **Magene Utility** | Strap firmware + workout recording | No training context, no HRV trends, no biohacker tests |

URUJ's edges:

- **One integrated brain** — cycling + HRV + training load + biohacker tests in one model, not five apps.
- **Transparency first** — every reading shows source label (`STRAP / BAND / MIXED`), capture timestamp, methodology version, math. ⓘ tooltips on every card explain ELI10 + caveats.
- **Hardware-agnostic** — drop-in priority registry. Magene H613 today, any standards-compliant ECG strap tomorrow. Power meters, smart scales, CGM, lactate strips slot in cleanly.
- **Sovereign data** — NDJSON on your device. Export anytime. No cloud account, no subscription, no lock-in.
- **Honest about limits** — refuses to fake HRV from PPG, refuses to invent cadence from frame-bag accelerometer, refuses "lab grade" claims without disambiguating.

---

## Three-tier hardware model

URUJ scales gracefully with whatever sensors you have. Each metric reports its data source and disappears cleanly when prerequisites are missing.

| Tier | Hardware | What unlocks |
|---|---|---|
| **0 — Phone only** | OnePlus / any Android 8.0+ device with barometer | Recording engine, GPS, physics-model power, Karvonen zones (estimated from formula), grade + wind + DEM elevation, route map, polarized compliance, FTP auto-update from best 20-min effort |
| **1 — + HC wearable** *(Samsung Fit Band 3, Apple Watch via HC, Whoop via export, etc.)* | Tier 0 + post-ride HR enrichment + Athletic RHR (sleep-window median) + sleep tracking + multi-sport TSB (Samsung-tracked runs feed via hrTSS) + readiness scoring |
| **2 — + BLE chest strap** *(Magene H613 validated, Polar H10 / Wahoo TICKR / Coospo H6 compatible)* | Tier 1 + live HR on HUD (sub-100ms latency) + **real RMSSD HRV** from RR intervals + true continuous HRR1 + 24/7 BiometricService (~5 MB/day) + CAR detector + 4-min orthostatic test + zone-discipline audio coach |
| **3+ — Power meter / CGM / lactate / smart scale** *(roadmap)* | True watts (replaces physics model) · continuous glucose curve · real LT1/LT2 anchored zones · body composition |

---

## What's shipped (v0.9.16, 2026-05-19)

### Recording engine (production-quality from v0.1)
- 1 Hz GPS via `FusedLocationProviderClient`, **crash-safe append-only NDJSON** (force-kill loses ≤ 1 sample), 30-sec checkpoint sidecar, foreground service with lock-screen HUD takeover
- Combined GPS + accelerometer **auto-pause** (5 s threshold), 1 Hz wall-clock ticker decoupled from sparse indoor GPS, **GPS-quality gating** (25 m accuracy) prevents indoor cell-tower-fused junk
- `WAKE_LOCK` during recording (survives OEM background killing on OxygenOS/MIUI), **true ride resume** from `.active` marker after process kill, orphan-NDJSON auto-recovery into history
- Service-health REC indicator on HUD (green pulsing / amber degraded / red stale) based on checkpoint age — visible lie-detector for "is the service actually alive"

### Live HUD (v0.9.16 twin-hero polish)
- **TwinHero**: HR (Karvonen zone-colored, subtle pulse at Z3+) + Speed side-by-side at the top — never scrolls offscreen
- Live waveform (last 30 s of beats, tier-colored) when strap paired
- Physics-model power: `P = rolling + aero + climbing + inertia` (Coggan-style). 3 s + 30 s rolling avgs. 5-zone bar.
- Grade + wind component (headwind ↓ / tailwind ↑ / crosswind →) from Open-Meteo + GPS heading
- Session-intent bar with mid-ride CHANGE (Recovery / Endurance / Tempo / Threshold / VO2 / Exploratory)
- STOP button fixed at BottomCenter (v0.8.5 fix — never gets pushed offscreen)

### Biohacker lab (the moat)
- **Real RMSSD HRV** — 24/7 BLE service captures RR intervals to daily NDJSON; `HrvCalculator` runs windowed 5-min RMSSD (Task Force 1996 standard) with timestamp-aware consecutive-beat filter + Kubios-style physiological range + ectopic delta filter. Validated against EliteHRV.
- **CAR (Cortisol Awakening Response)** — auto-fires on wake detection, scans first 30 min HR/HRV pattern (Pruessner 1997, Clow 2010 methodology). Saved on disk forever.
- **Orthostatic test** — guided 4-min sit→stand ritual, HR delta + RMSSD ratio, history persisted.
- **HRR1** — Cole NEJM 1999 + URUJ closest-to-60s adaptation, dual-sensor (strap-primary, band-fallback, MIXED label), per-ride snapshot.
- **Athletic RHR** — sleeping-window median (not lowest 24h reading; not Samsung's "RestingHR" definition).
- **TSB / CTL / ATL** — Coggan calendar-day EWMA. Multi-sport: cycling rides use power-based TSS, Samsung-tracked runs use HR-Reserve hrTSS (`(HRR fraction / 0.87)² × hours × 100`).
- **Snapshot persistence** — every trend metric writes to `/files/snapshots/<metric>/YYYY-MM-DD.json` at compute time with methodology version + source label. Trend charts read disk only — HC's 30-day retention no longer caps history.
- **HC backfill** — one-time harvest of HC 30 d history into disk snapshots so the trend doesn't start at "today".

### Readiness recommendation engine (v0.9.4 ReadinessContext signal pack)
- Multi-signal tiering (`FullRest` / `ActiveRecovery` / `EasyAerobic` / `Moderate` / `HardGreenLight`) — composite score AND severe-flag count (TSB ≤ −25, sleep < 5 h, HRV crashed, RHR ≥ +5, exaggerated/blunted CAR). 2+ severe flags → `FullRest` regardless of score.
- Cross-metric insight bullets ("HRV trending down 2 nights running", "TSB underwater N consecutive days", "VO2 trend rising").
- Multi-day rest enforcement via persisted recommendation snapshots (consecutive days in red escalate copy firmness).
- **AI plug seam** — `ReadinessReasoner` interface, `RuleBasedReasoner` is the default impl, `GroqAiReasoner` swaps in via `CompositeReasoner` one-line change at instantiation site. AI HOOK comments mark every plug point.
- ⓘ tooltips on every Readiness row + every Bio Lab card with methodology disclosure.

### Audio coach (v0.8.0 / v0.9.15)
- Pre-ride session-intent picker drives target zone (Z1–Z5 or silent exploratory).
- `SessionCoach` fires TTS cues when HR drifts from target ≥ 30 s; mid-ride override via HUD CHANGE button with 60 s grace period.
- 11 vocab pools with rotating cue index — no cue repeats consecutively. Direction-aware lines per session type.
- **Samsung-style audio ducking** — `AudioFocusRequest` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so music drops in volume during coach speech.

### Other shipped
- **Karvonen zone unification (v0.9.14)** — single classifier (`KarvonenZonesCalculator.classifyKarvonenZone`) used by TIZ + Route map + HUD + Audio coach. Pre-fix used %max-HR; now uses HR-Reserve (personalized by sleep-window RHR).
- **Polarized 80/20 compliance** on every ride summary (Seiler/Stöggl Norwegian-method discipline).
- **Route map** — OSMDroid + free OpenStreetMap tiles, polyline colored by Karvonen zone, tap-to-inspect bottom panel.
- **HC rate-limit budget** — sticky-cache pattern, post-ride 30 s quiet window, throttled multi-reads. ~150–250 reads/hr (well under HC's ~1000–2000/hr foreground ceiling).
- **Profile auto-sync** — weight from `HC WeightRecord`, max HR from in-ride observed peak, FTP from best 20-min sliding-window power × 0.95, Athletic RHR from sleep-window median.

---

## Architecture rules (REQUIRED reading before any new metric / trend PR)

1. **Snapshot persistence** — every trend metric persists to disk at compute time. Trend charts read disk only. HC is never queried for history. CAR + Orthostatic are reference impls; HRR1 / RHR / VO2 / TSB / Sleep / HRV all conform.
2. **ReadinessContext signal pack** — every new biomarker MUST plug into `ReadinessContext` from PR 1. Engine + AI coach + trend charts + future biomarkers all read the same struct. Plug-and-play AI seam.
3. **HC rate-limit budget** — NO background HC polling. Throttle multi-reads with `delay(150L)`. Sticky cache fallback (never overwrite GOOD with BAD on HC blip). Surface freshness via "synced X ago" timestamp.
4. **Lab-level honesty rules** — every metric must declare: source label · capture timestamp · methodology version · no fake numbers (refuse fabricated proxies) · deep-view trend chart · hardware-additive resurrection path · cycling-relevance test.

---

## Tech stack

- **Language**: 100% Kotlin
- **UI**: Jetpack Compose
- **Min SDK**: 26 (Android 8.0) · **Target SDK**: 36
- **Concurrency**: kotlinx.coroutines + structured concurrency, supervisorScope around recording loop
- **Persistence**: DataStore (Preferences) + per-ride NDJSON + per-snapshot JSON files
- **Sensors**: `FusedLocationProviderClient` + `SensorManager` (barometer + accelerometer) + BLE GATT (`BluetoothGatt` with one-op-at-a-time state machine for OEM compatibility)
- **Health**: `androidx.health.connect:connect-client`
- **Maps**: OSMDroid + free OpenStreetMap tiles
- **Serialization**: kotlinx.serialization JSON
- **Networking**: `HttpURLConnection` (no third-party HTTP lib — keep APK small)
- **External APIs**: Open-Meteo (free weather + DEM elevation, no key)

No paid services. No cloud backend. No accounts. Health Connect + Open-Meteo are the only external surfaces.

---

## Build & run

```bash
git clone https://github.com/gazzycodes/uruj-labs.git
cd uruj-labs
# Open in Android Studio
# Run on a real device (API 26+) — emulator works for UI but has no GPS / barometer / BLE
```

You'll be prompted to grant:
1. Location (fine + background) — for GPS recording
2. Notifications (Android 13+) — for foreground service + lock-screen HUD
3. Health Connect — HR, HRV, sleep, RHR, VO2, weight, steps, distance, calories, exercise sessions, body fat, height (~16 types in one bundle)
4. Bluetooth scan + connect — for BLE chest strap (Tier 2)
5. Disable battery optimization — or the foreground service will die mid-ride on aggressive OEM ROMs (OxygenOS, MIUI, OneUI)

---

## Roadmap (next sessions)

### Short-term polish
- **TSB rounding consistency** between Readiness card and Bio Lab card (cosmetic — same float displayed differently)
- **Weekly polarized compliance chart** on Rides screen (Z1–Z5 stacked bar per ride, weekly distribution pattern)
- **Visual polish for source labels** (bigger pills, card badges)

### Biology / methodology
- **HRV frequency-domain + non-linear** — FFT on RR series for LF/HF/VLF power, SDNN, pNN50/pNN20, Poincaré SD1/SD2, DFA α1, sample entropy
- **Sleep stage detection** from HRV trends
- **Tier B tests** — postprandial response, caffeine, alcohol, cold/heat exposure, meditation, breath-work biofeedback (each = small computation on existing 24/7 RR data)
- **In-app subjective tracker** — mood/energy 1–10, soreness map, hydration, caffeine timing, supplements, cold/sun exposure, meditation log, meal photo

### AI layer
- **v0.5 Groq AI coach** — pre-ride narrative + post-ride debrief + free-form Q&A. Math stays rule-based and deterministic; AI is narrative on top. Must cite the data points it reasoned from (no untraceable claims). Plugs into existing `ReadinessReasoner` seam — one-line change at instantiation site.

### Hardware ladder
- **BLE cadence sensor** — measured cadence (currently estimated from speed × grade)
- **Power meter pedals/crank** *(Favero Assioma / Stages)* — replaces physics-model power estimate with real watts ±1%
- **CGM** *(Abbott Libre / Stelo)* — glucose curve during ride + postprandial response + dawn phenomenon
- **Lactate meter** *(Lactate Plus + strips)* — real LT1/LT2 anchored zones
- **Smart scale** *(Health Connect-compatible)* — body fat %, muscle, water, visceral fat
- **v∞ custom sensors** — when off-the-shelf hardware caps out, design domain-specific sensors for the gaps

---

## Medical disclaimer

URUJ Labs is a personal fitness / physiology tracking tool, not a medical device. It does not diagnose, treat, cure, or prevent any disease. Every metric is a personal-fitness signal, not a clinical reading. Heart rate variability, cortisol-response proxies, training load, and recovery scores are decision aids for self-coached training — not substitutes for medical advice.

If you experience chest pain, severe shortness of breath, syncope, or any concerning symptom, stop using the app and contact a qualified physician.

---

## Acknowledgments

- **Anthropic Claude** — coding partner across every session. The build journey would not exist at this pace solo.
- **Open-Meteo** — free weather + DEM elevation APIs without a key (the unsung hero of this project)
- **Health Connect team** — the standardized health-data layer Android needed
- **Andy Coggan & Hunter Allen** — *Training and Racing with a Power Meter* (IF / TSS / NP / CTL / ATL / TSB math)
- **Cole et al. NEJM 1999** — HRR1 methodology + Cole zones
- **Pruessner 1997, Clow 2010, Stalder 2016** — CAR (Cortisol Awakening Response) framework
- **Plews / Buchheit / Shaffer & Ginsberg** — HRV reference ranges for athletes
- **Seiler / Stöggl / Blummenfelt** — polarized training discipline
- **OpenTracks** — the open-source reference proving Android cycling apps don't need to be subscriptions
- **Magene** — standards-compliant BLE chest strap (H613) at consumer pricing

---

## License

Currently **personal / closed during active development**. Will move to a permissive license (MIT or Apache 2.0) once v1.0 ships and the public-release polish layer is complete.

---

<div align="center">

**صعود**

Built solo. Validated on real rides. The lab grows with the rider.

</div>
