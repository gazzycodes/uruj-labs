## 📦 GitHub — live as of 2026-05-12

**Repo**: https://github.com/gazzycodes/uruj-labs
**v0.1 tagged**: commit `105c8a6` on `main`
**Identity**: `Ghazanfar Uruj <ghazanfar.uruj.cool@gmail.com>`

**Workflow going forward** (Session 2+):
1. Pull latest main
2. `git checkout -b feat/<name>` per feature
3. Logical commits per change with conventional-commit messages
4. Push branch → open PR → merge to main
5. Tag minor milestones (`v0.2`, `v0.3`...)

**Never**: `--force` to main, skip hooks, commit `fixtures/*`, commit `local.properties`.

---

## ⚡ Session ship log — 2026-05-11/12 night session (URUJ Labs v0.1)

**Shipped in one all-night session**, validated via indoor walking tests, ready for first real outdoor ride:

- ✅ Full v1 recording infrastructure (GPS, NDJSON, foreground service, auto-pause, lock-screen overlay, rich notification)
- ✅ Pre-ride checklist with FIX flows for every permission
- ✅ Profile screen (DataStore — weight, FTP, max HR, tire, position, age)
- ✅ Power model (physics-based watts, Crr+CdA+grade+inertia terms) with 3s/30s smoothing
- ✅ Power zones Z1–Z5 on HUD with color bar
- ✅ Barometer-fused elevation + live grade % + VAM
- ✅ Post-ride summary screen (IF, TSS, work kJ, calorie est)
- ✅ Persistent ride history (`.summary.json` sidecar + browsable list)
- ✅ TTS audio coach (km callouts + PR announcements)
- ✅ Weather + wind via Open-Meteo (free, no API key) + headwind/tailwind/crosswind display
- ✅ Live PR detection (1min/5min/20min best power) with HUD flash + TTS + DataStore persistence
- ✅ URUJ Labs v0.1 branding — spinning orbit-arc logo, monospace version subtitle
- ✅ Health Connect HR confirmed as post-batch only (Samsung limit, not solvable without BLE strap)

**Resolved bugs** during the session:
- Foreground service `foregroundServiceType="health"` rejected without BODY_SENSORS — dropped to `location` only
- Notification icon: launcher mipmap not valid as small-icon, created proper white silhouette vector
- Notification visibility: bumped channel to IMPORTANCE_DEFAULT (was LOW, OxygenOS buried it under "Silent")
- 1Hz timer lag: added wall-clock ticker decoupled from sparse indoor GPS
- Distance GPS jitter: 1m minimum delta filter on indoor stationary phone
- Auto-pause too slow: 10s → 5s (matches Garmin/Strava/Wahoo)
- Ride history saved as "unknown" stub: race between cancel + state-clear; fixed by saving in stopRecording before cancel
- PR persist canceled with recordingJob: launched on long-lived service scope instead
- **GPS-quality gating (industry-grade)**: FusedLocationProvider falls back to cell/Wi-Fi indoors → reports false speed → false distance/power/PRs. Fixed by gating all bike metrics on `horizontalAccuracyMeters ≤ 25m` (matches Wahoo Roam). Weather uses a looser any-location gate since wind/temp don't change within a km.
- Wind display made GPS-quality-aware: shows component (HEADWIND/TAILWIND) when moving with lock, raw "WIND · X KPH from NW" cardinal-direction when stationary or pre-lock.
- HUD now shows live GPS status badge: `LOCKED ±5m` / `POOR ±42m` / `UNUSABLE ±200m` color-coded.
- CancellationException no longer logged as a warning — re-thrown to respect structured concurrency.
- Color refactor: `UrujNeonCyan` → `UrujAccent` (semantic name), value swapped to Material Green A400 (#00E676). One-line change in `Color.kt` to re-tint the whole app.

**Deferred for next session** (the user is going on a 4hr night ride right now):
- Pre-ride readiness score (sleep + HRV + RHR via Health Connect — needs new HC permissions, manifest, checklist row)
- Time-series graphs on summary screen (Compose Canvas — power/speed/HR overlay over time)
- OSMDroid route map per ride
- Climb/sprint auto-detection (calibrate from tonight's NDJSON data)
- Animations on/off toggle + battery-saver mode
- BLE chest strap support (`BleHrSource` — same `Flow<HrSample>` interface)
- Strava OAuth + historical activity import
- Ghost-rider live deltas on known routes
- Audio coach customization (volume, frequency, voice)
- `.gpx` / `.fit` export for sharing
- Telemetry video overlay on chest-cam clips (Python desktop tool)

**Tomorrow's first move**: review the user's ride NDJSON. The power model's CdA/Crr defaults are educated guesses — real ride data will tell us what to tune. PR thresholds, auto-pause feel, wind correction accuracy all calibrate from real numbers.

---

# Cyclometer — Endurance Training-Lab Sidekick (Android)

## Context

You're an endurance cyclist with triathlon-style training ambitions (modelling on Blummenfelt / Sanders), riding 50–100+ km on a OnePlus Android phone in a top-tube frame-bag with clear phone window, a ₹200 handlebar odometer, chest-mount action camera (clips, not full sessions), and a Samsung Fit Band 3 → Samsung Health → Strava passive sync.

**Scope reset after the 2026-05-10 century ride** — you tested OpenTracks for 101 km and it covers GPS recording, .gpx export, voice announcements, post-ride map. Strava covers history, segments, post-ride estimated power, achievements. Samsung Health covers HR logging. **Rebuilding any of that is wasted effort.**

This app is the **live realtime layer that nothing free currently provides**:
- Live HR on the bike screen (via Health Connect from Fit Band 3 — only path because Samsung doesn't broadcast standard BLE HR profile)
- Live estimated power + power zones (Strava only shows post-ride; Strava Premium would be ₹600/mo)
- Live ghost-rider delta vs past best on the current route (Strava Live Segments is Premium-paywalled)
- A **futuristic, athlete-grade HUD** designed for the through-plastic frame-bag view, lock-screen visible, AOD-aware
- Pre-ride checklist + crash-safe recording — production quality bar from v1, intended for eventual public release

OpenTracks keeps running alongside as the canonical .gpx recorder; our app does the realtime overlays + everything in one HUD. Two apps, one GPS chip — Android handles it cleanly.

---

## Test fixture (2026-05-10 century ride — captured, in `fixtures/`)

`2026-05-11_23_46_46.161_2026-05-10T23_46+0530.kmz` — OpenTracks export.

Parsed via `tools/inspect_ride.py` (already exists):
- 100.59 km, 8h 36min, 8,247 GPS samples, median 2s sampling
- Moving avg 19.84 kph, max 39.97 kph
- Raw GPS elevation gain 1,042 m — **bogus** (Kolkata is flat ~9m above sea level; this is pure GPS altitude noise)
- 33-min gap somewhere = mix of long break + brief GPS dropouts (user away from phone, Bluetooth lost twice)
- 43% of total trip was stopped time → confirms auto-pause is mandatory

This fixture drives every test in v1: power model, auto-pause threshold tuning, elevation barometer fusion, route matching for ghost rider.

Strava activity for this ride exists via Samsung Health → Strava auto-push. Need URL / screenshot to cross-validate distance, moving time, Strava's elevation correction, Strava's estimated power.

---

## Stack

- **Native Android, Kotlin** (your choice — best battery efficiency, sensor access, background recording)
- **Jetpack Compose** for UI (modern, lets us iterate on HUD layouts fast)
- `minSdk = 26` (Android 8.0+, covers every reasonable phone)
- **Location**: `FusedLocationProviderClient` at 1 Hz, foreground service
- **Sensors**: `SensorManager` for barometer (elevation refinement), accelerometer (auto-pause detection)
- **Heart rate**: `androidx.health.connect:connect-client` — read-only HR from Samsung Health
- **Maps** (later versions): OSMDroid + OpenStreetMap tiles (free)
- **Charts** (post-ride): MPAndroidChart (free)
- **`.gpx` export**: small custom XML writer (no library needed)
- **`.fit` export** (later): `garmin/fit-sdk` (free, official) or `polyline-labs/fit-tool` Kotlin port
- **Strava integration** (later): Strava API v3 OAuth, free tier (100 req / 15 min, 1000/day — plenty)

No paid services, no cloud backend in v1–v3. All on-device.

---

## Feature roadmap — ordered by speed × accuracy

The order is "what we can ship fast AND get right." Power estimation comes before gamification because the math is short and the accuracy is good enough; gamification needs route-matching algorithms and a good UX, which is more work.

### v1 — Realtime HUD foundation (the core daily-use experience)
**Goal: a single screen that's worth opening every ride. Live HR + live speed + lock-screen-visible + crash-safe recording.**

- **One-time profile screen**: rider weight (kg), bike weight (kg), tire type (road / gravel / MTB → drives `Crr`), max HR (for HR zones), estimated FTP (optional, will refine in v2)
- **Pre-ride checklist screen** — must pass before "Start Ride" enables:
  - [x] Location permission (FINE) granted
  - [x] Health Connect HEART_RATE read permission granted
  - [x] Battery optimization disabled for the app
  - [x] GPS fix acquired (±10m accuracy or better)
  - [x] Health Connect reporting recent HR from Fit Band 3 (last sample within last 2 min)
  - [x] OpenTracks installed (optional but recommended — surfaces a tip if missing)
- **Foreground service** sampling every 1s: lat, lon, altitude, GPS speed, accuracy, barometer pressure, accelerometer (auto-pause), HR from Health Connect (best-effort, with freshness)
- **Futuristic HUD** — Compose UI, dark/neon aesthetic:
  - Pure-black background (AMOLED-friendly = lower battery draw on OnePlus)
  - Single huge **current speed** number, top-center, glowing neon-cyan
  - **HR + HR zone** circular gauge with zone-color glow (Z1 blue → Z5 red)
  - **Distance + elapsed moving time** below
  - **Grade %** indicator
  - **Pause indicator** when auto-paused
  - All numbers use a single condensed-geometric typeface (planned: Orbitron or JetBrains Mono Bold; final pick at first build)
- **Lock-screen visibility** — rich foreground service notification:
  - Custom `RemoteViews` showing speed / HR / distance in big numerals
  - Persistent (`setOngoing(true)`) so it stays on the lock screen
  - On OnePlus OxygenOS, this surfaces on AOD automatically
  - Tap power button → screen wakes → numbers visible → no unlock needed
- **Stay-awake on HUD activity**: `FLAG_KEEP_SCREEN_ON` when HUD is foregrounded
- **Auto-pause** triggers when GPS speed <1 kph AND accelerometer magnitude <0.2g for >10s. Tunable threshold (we tune against the 33-min gap in the 2026-05-10 fixture).
- **Battery-saver mode** toggle: drops GPS to 2s sampling, dims HUD to 50%, reduces HUD to 3 big numbers only. Saves ~40% battery in long rides.
- **Crash-safe recording**: every GPS sample appended to a local file immediately (newline-delimited JSON in v1, will add .gpx in v2). Force-kill mid-ride → partial recording is valid and readable.
- Permissions: `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, Health Connect read for `HEART_RATE`
- **Acceptance**: complete a 50+ km ride, no crash, distance within 1% of OpenTracks recording the same ride, lock-screen notification visible and accurate, battery drain <30% over 3 hr with HUD on intermittently

### v2 — Estimated power, .gpx export, audio callouts
**Goal: real power numbers, athlete-grade pacing feedback.**

- **Power estimator** — apply per GPS sample:
  ```
  P = (Crr · m · g · v) + (0.5 · ρ · CdA · v³) + (m · g · sin(θ) · v) + (m · a · v)
  ```
  - Constants: `Crr` from tire profile, `CdA = 0.408` (typical upright), `ρ = 1.225 kg/m³`, `g = 9.81`
  - `θ` from **barometer-corrected** elevation delta (raw GPS alt is garbage on flat terrain — confirmed by 1042m bogus gain in 2026-05-10 fixture). Smoothed across 5 samples to kill jitter. Reject deltas < 0.5m as noise.
  - `a` from speed delta
  - Display in HUD with `≈` prefix to keep honesty (per `feedback_honest_estimates.md`)
  - Skip negative values (coasting → 0W, not negative)
- **Power zones** based on FTP — color-coded zone indicator in HUD (Z1 grey → Z5 red), matching HR zone visual language
- **`.gpx` export** at ride end (v1 records to NDJSON for crash safety; v2 adds full GPX writer for Strava compatibility / sharing)
- **TTS audio callouts** via `TextToSpeech`: every km — "12 km, 27 kph average, 165 watts estimated, HR Zone 3". Plays through phone speaker or BT audio. Toggle on/off + frequency in settings.
- **Acceptance**: power estimates correlate within ±15% of Strava's estimated power on the 2026-05-10 ride and 5 other historical Strava rides

### v3 — Ghost rider + Strava history + lap structure
**Goal: training-lab stickiness. Race your past self, structured intervals.**

- **Strava OAuth** + bulk import past 3 months of activities into a local Room database
- **Common-route detection**: cluster historical activities by start point (within 100m) and overall path overlap (Hausdorff distance on Ramer-Douglas-Peucker simplified polylines)
- **Ghost rider mode**: at ride start, app detects "this looks like Route X" from top 5 common routes, asks "race your best (1:47:13)?". During ride: live `+12s ahead` / `-8s behind` in HUD as a tachometer-style indicator.
- **Interval / lap structure**: manual lap button + preset workouts ("4 × 8min Z4 with 4min recovery" — Sanders-style threshold sessions). HUD shows current interval target zone + time remaining.
- **Personal records page**: longest ride, highest avg speed, biggest single-day km, best 1km/5km/10km/20km, longest streak, biggest weekly volume
- **Streak tracker**: consecutive days/weeks with rides above thresholds
- **Acceptance**: ghost-rider delta accurate within ±2s vs manual post-ride calculation; route detection correctly clusters at least 3 distinct common routes from your 3-month history

### v4 — Telemetry overlay on cam clips, weather, polish for public release
**Goal: shareable, public-release-ready.**

- **Telemetry video overlay** desktop tool (Python + ffmpeg + gpxpy):
  - Drag a chest-cam clip + your ride `.gpx` onto a window
  - Tool time-aligns the clip's start to a GPS timestamp (you confirm one sync point, then auto-aligns the rest)
  - Renders the clip with speed / HR / power / mini-map / route progress overlaid
  - Same neon-glow theme as the Android HUD for brand consistency
  - Designed for short clips (your actual recording pattern), not full sessions
- **Wind correction** for power model: pull wind speed/direction at ride start from OpenWeather free tier, apply to drag term
- **Route heatmap** (Android): aggregate all rides into personal heatmap of where you've ridden
- **Public release polish**: externalize all strings to `strings.xml`, theme system review, app icon, store screenshots, privacy policy, basic Play Store listing copy
- **Training load** estimate (Banister TRIMP-style or Coggan TSS-style using HR + estimated power) — weekly/monthly trend

### Explicitly NOT in plan
- **Rebuilding what OpenTracks already does well**: post-ride history browser, detailed activity sharing, voice announcements as the primary feature. OpenTracks runs alongside for canonical `.gpx`.
- **Cadence**: refused to fake it. If wanted, ₹500 of hardware (reed switch + magnet + BLE module) is the honest path. Phone accelerometer FFT won't work from frame bag.
- **Auto-push to Strava**: Samsung Health already pushes via the Fit Band 3 workout. Avoid duplicates. Our `.gpx` export is for manual sharing only.
- **iOS support**: not requested, would double the work.
- **Cloud backend / accounts**: all on-device + Strava (which you already use).
- **In-app purchases / paywall**: free forever, even after public release.

---

## Critical files / structure (greenfield project)

```
D:\Codes\Personal Projects\Cyclometer\
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cyclometer/
│       │   ├── MainActivity.kt
│       │   ├── service/
│       │   │   └── RideRecorderService.kt          # foreground service, the heart of v1
│       │   ├── sensor/
│       │   │   ├── LocationSource.kt               # FusedLocationProviderClient wrapper
│       │   │   ├── BarometerSource.kt
│       │   │   └── HealthConnectHrSource.kt        # HR + freshness timestamp
│       │   ├── power/
│       │   │   └── PowerEstimator.kt               # v2 — physics model
│       │   ├── data/
│       │   │   ├── RideSample.kt                   # one row per second
│       │   │   ├── RideRepository.kt
│       │   │   └── db/                             # Room DB for past rides
│       │   ├── export/
│       │   │   ├── GpxWriter.kt                    # incremental, crash-safe
│       │   │   └── FitWriter.kt                    # v2+
│       │   ├── route/
│       │   │   └── RouteMatcher.kt                 # v3 — common-route clustering
│       │   ├── strava/
│       │   │   └── StravaClient.kt                 # v3 — OAuth + history pull
│       │   ├── tts/
│       │   │   └── MilestoneAnnouncer.kt           # v2
│       │   └── ui/
│       │       ├── hud/HudScreen.kt
│       │       ├── profile/ProfileScreen.kt
│       │       ├── history/HistoryScreen.kt
│       │       └── theme/
├── fixtures/
│   ├── opentracks_2026-05-10.gpx                   # today's ride lands here
│   └── strava/                                     # past 3 months bulk export
├── tools/
│   └── video_overlay/                              # v4 — Python + ffmpeg desktop tool
│       └── overlay.py
└── README.md
```

---

## Open questions to resolve as we build (not blockers for today)

1. **HR sync latency in practice** — Health Connect docs say HR is batched; we won't know how stale "live" HR feels until a real ride. May need to start a workout on the band itself to force more frequent syncs. Test with v1.
2. **Power model calibration** — after 5–10 rides we can plot our estimate vs Strava's estimate and tune `Crr` / `CdA` to your bike. No theoretical work needed yet.
3. **`.fit` vs `.gpx`** — Strava accepts both, but `.fit` carries power/HR cleanly. v1 sticks to `.gpx` for simplicity; v2 adds `.fit`.
4. **Phone visibility through plastic** — if the bag's window is glare-prone, we may need to invert HUD colors or add a high-contrast "outdoor mode." Confirm after first ride.

---

## Quality bar — production from v1

Public-release-intent quality from the start, but without over-engineering:
- **No defects in the ride-critical path**: pre-ride checklist must catch every known failure mode before "Start Ride". Crash-safe append-on-every-sample recording so the worst-case loses ≤2s of data.
- **Unit tests on the data pipeline**: power model, auto-pause detection, route matching, .gpx writing — all tested against `fixtures/2026-05-11_23_46_46.161...kmz` (the 2026-05-10 century) before considered done
- **Battery profile measured, not guessed**: every release profiles a 3hr ride and reports drain %
- **Strings externalized to `strings.xml`** from day 1 — translation later is free
- **Centralized Compose theme** — single source of truth for colors, typography, glow effects (so the "futuristic" feel is consistent, not per-screen)
- **No hardcoded device assumptions** — works on any modern Android 8.0+, not just OnePlus

## Verification

### v1
- [ ] Pre-ride checklist correctly fails when any prerequisite is missing (test by toggling each off)
- [ ] Side-by-side ride: OpenTracks + Cyclometer running simultaneously. Distance, max speed match within 1%.
- [ ] Lock-screen notification shows live speed/HR/distance — verified by pressing power button mid-ride without unlocking
- [ ] OnePlus AOD shows the persistent notification (visual check on OxygenOS)
- [ ] Stay-awake holds the HUD screen on indefinitely without dimming when active
- [ ] 3 hr battery test with HUD on intermittently — phone uses <30% battery; battery-saver mode test uses <20%
- [ ] Force-kill app mid-ride; partial recording file is valid and parseable
- [ ] Auto-pause triggers within 12s of a real stop (tested with traffic-light style stops)
- [ ] Verify HR appears in HUD when band is being worn during a Samsung Health workout

### v2
- [ ] Run power estimator over 5 historical Strava rides; mean error vs Strava's estimated power within ±15%
- [ ] TTS announces correctly every km, audible over wind at 30 kph

### v3
- [ ] Ghost rider time-delta on a known route matches a manual stopwatch comparison within ±2s
- [ ] Common-route detection correctly clusters at least 3 distinct routes from your 3-month history

### v4
- [ ] Video overlay aligns within ±1s on a known sync point (e.g. you wave at the camera at a specific GPS coordinate)

---

## 📌 Deferred for later session (2026-05-13 09:00 update)

After v0.2.11 ships, the following are explicitly deferred (not forgotten):

- **Push notifications for HR re-sync arrival**: when the background re-pull on
  a ride summary finds fresh data from Samsung's late batch sync, fire an
  Android notification ("Tonight's ride HR updated: max 156 → 173") instead
  of requiring user to re-open the summary screen. v0.3+ feature.
- **Per-window PR reset** (just 1min, just 5min, etc.) — currently the reset is
  all-or-nothing in Profile. Granular reset is a small UI addition if needed.
- **Time-based PR auto-purge** (PRs older than N days) — heuristic alternative
  to manual reset. Risky if a real PR is older than the threshold.
- **Strava-style post-ride elevation reprocess** — re-compute gain/loss with
  smoother DEM+GPS blend after the ride ends. v0.2.11 captures most of it
  live, but post-processing could catch missed bridges.
- **BLE chest strap support (v1.5 plan, unchanged)** — `BleHrSource` plugs into
  the same `HrSample` interface. Unlocks: live HR on HUD during ride, true
  RMSSD HRV, continuous-sample HRR1, post-ride enrichment becomes instant.

## ✅ Validated 2026-05-13 09:00 — v0.2.11 ride-summary refresh works

User tested manually: opened 31.5km ride summary from earlier today, magenta
spinner appeared with "↻ checking Samsung..." label, background fetch
completed, **HR data updated 136 samples / max 156 → 724 samples / max 173**.
Confirms Samsung's late batch sync arrives 15-30min post-ride, and v0.2.11's
re-pull-on-view correctly catches it.

---

## 🗓️ v0.3.0 SHIPPED 2026-05-13 — Route Map per ride

Polyline + HR-zone coloring (Z1 blue → Z5 red) + tap-to-inspect bottom
panel + start/end markers + fit-to-bounds zoom. OSMDroid 6.1.20 + free
OpenStreetMap tiles. Wired from RideSummaryScreen (just-finished AND
historical). Foundation for: heatmap, climb segments, branded share
card, route matching/ghost-rider.

## 📌 Deferred: HRR1 Deeper-View (v0.3+ feature)

Current Bio Lab card: hero median (30d window) + 3 latest readings.
This is good for at-a-glance "where am I now."

Add on top:
  - **Sparkline inline on the card** — tiny ~40dp trend chart under the
    hero number, shows last 12-30 readings as dots/line. Visual answer
    to "am I improving?"
  - **"See trend →" button** → full-screen deeper view

Deeper view spec:
  - X = ride date, Y = HRR1 bpm
  - Color-coded by Cole zone (green/amber/red)
  - Granularity adapts to time range:
      30d  → individual rides as dots
      6mo  → weekly medians
      1yr  → monthly medians
      All  → monthly + 90d rolling-avg overlay
  - Time-range filter (30d / 90d / 1yr / All)
  - Stat bar: best ever, current, 30d delta, 90d delta
  - Optional overlays (toggle): RHR trend, sleep hours, TSB — see
    cross-correlation with sleep/recovery

Data retention requirement:
  - HC retains HR samples for ~30 days → can't compute old HRR1 from
    scratch indefinitely.
  - **Add `hrr1Bpm: Int?` to `StoredRideSummary`** and persist at ride
    end. Deeper view reads from RideHistoryRepository (works forever).
  - Bio Lab card still re-computes from raw HR data for accuracy on
    recent rides.

Same pattern applies to other metrics that should have history-of-fitness
views: VO2 max trend, RHR trend, sleep hours trend, TSB-over-time. All
become possible once we persist computed values per-ride.

## ✍️ Sync convention (started 2026-05-13)

Memory + plan files should be updated as part of any major commit/PR,
not after-the-fact when the user reminds. See feedback memory
[[feedback_keep_memory_synced]] for the rule.

---

## 🗓️ v0.3.1 SHIPPED 2026-05-13 — Sleeping HRV proxy

Closes the activity-confound bug: old proxy used std-dev of all 24h HR
samples, which produced "low HRV" on rest days (because low activity =
low HR variance) and "high HRV" on workout days (because HR ranged
widely). Backwards signal. SleepingHrvProxyCalculator filters HR samples
to sleep windows only — same protocol every day, ratio reflects
autonomic state not activity level.

Validation case (2026-05-13): 9.2h sleep + recovery day. Old proxy =
1.7 vs 7d baseline 3.8 = -55% (catastrophic, scored 25/100 for HRV
weight = 7.5 of 30 possible points → total score 65/100). With sleep-
window filter, today and baseline both use deep-sleep std-dev → ratio
near 1.0 → score 95+ → total score 90+. Matches real biology.

Same pattern as SleepingRhrCalculator: shared HC read function loads
HR + sleep windows once, two calculators consume it. Diagnostics label
"HRV(sleep) ✓" when sleeping proxy fires.

---

## 🗓️ v0.3.2 SHIPPED 2026-05-14 — TSB calendar fix + verbose readiness + HRV bucket retune

THREE fixes shipped together because device-testing v0.3.1 surfaced:

**TSB stuck-at-fatigued bug**
  `computeTsb` was using `(nowMs - rideStartMs) / 86_400_000` to bucket
  rides into daily TSS arrays. Truncating to 24-hour chunks meant
  yesterday's 04:00 ride viewed at 00:32 next day = 20.5h elapsed =
  daysAgo 0 = bucketed as TODAY → no decay applied → TSB stayed -14
  for the full 24h after a ride before suddenly jumping.

  Fixed using `LocalDate` + `ChronoUnit.DAYS.between(rideDate, todayDate)`
  so the bucket aligns with calendar midnight. TSB now updates
  predictably overnight, not 24h-from-ride-start.

**HRV score buckets retuned**
  Original `scoreHrv` thresholds were designed for real RMSSD HRV
  which has narrow ~5-15% day-to-day variance. The sleeping HR-proxy
  has 20-25% natural night-over-night variance. -23% deltas got
  penalized as "very poor" (score 25) when actually within normal
  proxy noise.

  Widened buckets: 0.80→75, 0.70→55, 0.60→35. User's -23% case
  jumps 25 → 55. Total readiness score: 65 → ~74 on the same data.

**Verbose ReadinessCard**
  Per-component rows now show: METRIC value → score/100 + one-line
  "why" tagline. Examples:
    SLEEP    9.2h → 90/100  slightly over 7-9h optimal
    HRV      -23%  → 55/100  near baseline — normal variance
    RHR      -5    → 100/100 RHR below baseline → strong recovery
    LOAD     -14   → 55/100  fatigued from recent hard rides

  Methodology surfaced inline. User sees which component drags the
  score AND why, without a separate detail screen. User's specific
  ask: "we just need it to be verbose man very detailed so it doesn't
  confuse at all even with minor updates thats how it becomes kristian
  blummenfelt level right?"


---

## 🗓️ v0.3.3 SHIPPED 2026-05-14 — Readiness UI polish + final calendar consistency

User asked: "make sure entire app follows calendar protocol like Strava
and Samsung Health" + "title shouldn't be above readiness card" + "add
visual indicators / ticker so I know what data is from when."

App-wide calendar-day audit completed: every "today" path uses
LocalDate.now().atStartOfDay() — BioLabRepository ✓, ReadinessRepository
TSB ✓, readHrProxies (last fix in v0.3.3) ✓. Rolling N-day windows
correctly use Duration.ofDays(N) (rolling is the right semantic for
baselines/EWMAs).

UI: moved title below readiness card. Live ticker on diagnostics (was
static, updates every second). New "Windows" footer line on diagnostics:
"today = local-calendar midnight → now · HRV/RHR baseline = rolling 7
days · Training load = rolling 42d EWMA". Rider always knows what time
frame each input covers — pre-empts the future time-series chart's
"what range am I seeing" question.


---

## 🗓️ v0.3.4 SHIPPED 2026-05-14 — Route map visual polish

User device-tested v0.3.0 route map. Five issues uncovered:
  1. Status bar disappeared (alpha 0.85 header didn't cover top inset)
  2. Polyline invisible on older rides (grey-on-grey blended into OSM streets)
  3. No ride identification — "ROUTE MAP" title told them nothing
  4. Zone legend opaque — "Z1 Z2 Z3 Z4 Z5" with no explanation
  5. Point details hid HR row when null — confused absence with zero

Fixes:
  - Solid MaterialTheme.background extending under status bar
  - Polyline stroke 12f → 16f, null-HR color grey → bright accent green
  - StoredRideSummary loaded in ViewModel, header shows "X km · duration · date"
  - Zone legend: one-line "colored by effort intensity" caption + "NO HR DATA"
    indicator with explanation when ride has no HR samples
  - Point details: explicit "HEART RATE: not recorded" when null

**Architectural gap discovered (fix queued for v0.3.5)**:
  NDJSON has hrBpm=null on every sample for rides older than full HC HR pipeline
  integration. Fit Band 3 batches HR — Samsung pushes after ride end, our
  NDJSON never gets per-second HR. Real fix:

    RouteMapViewModel.load() should ALSO pull HC HR samples for ride time
    range, time-align them to GPS points (nearest-timestamp join), classify
    zones from the joined data. Then older rides will show colored polylines
    (their HR is in HC, just not in NDJSON).

  This is the right place to do this work — read-time join, no NDJSON mutation,
  works for past rides retroactively.


---

## 🗓️ v0.3.5 SHIPPED 2026-05-14 — Route map HC HR time-align

Closes the Fit Band 3 batching gap for visualization. RouteMapViewModel
now reads HeartRateRecord from Health Connect for the ride's time window
and time-aligns to GPS sample timestamps via O(n+m) merge join (±60s
tolerance). NDJSON's null hrBpm gets backfilled from HC at render time.

Pattern is read-time join, no NDJSON mutation. Works retroactively for
all rides because Samsung's post-workout batch sync has been populating
HC for months. The 2026-05-13 31.5km ride (which previously showed all-
green for "no HR data") now renders Z1-Z5 colored polyline.

For future rides — same fix path applies. During-ride NDJSON still has
null HR (Fit Band 3 limitation), but at render time we pull HC's
post-sync data and time-align. Live HR in NDJSON requires chest strap
(v1.5).


---

## 🗓️ v0.3.6 SHIPPED 2026-05-14 — Post-ride time-in-zone + polarized compliance

User initially asked for live HUD time-in-zone counter for today's ride.
Mid-implementation, he flagged the architectural reality I should have
caught: Fit Band 3 batches HR, doesn't push to HC during workout, so a
LIVE counter would never increment.

Pivoted to honest post-ride analysis — same time-align pattern as the
route map (v0.3.5). Single HC query feeds both summary HR stats and the
zone breakdown.

  - TimeInZoneCalculator: %max-HR thresholds matching route map
  - Card on ride summary: stacked bar + per-zone minutes/% + polarized
    compliance line + Blummenfelt-style feedback
  - Tier 0 (no HR data) gracefully hides the card
  - HR cap audio alert deferred to v1.5 (chest strap unlock)

The lesson worth keeping: if I'd shipped live HUD without his catch, the
counters would have stayed at zero through the entire ride and we'd
have wasted his ride day. The user's "I don't know technical terms but
I know how my band works" was load-bearing — listen to it.


---

## 🗓️ v0.4.3 SHIPPED 2026-05-15 — Multi-sport TSB via Banister HR-Reserve + "Training Lab" rename

Triathlete-focused upgrade. Closes the gap where runs didn't feed TSB
(URUJ records only cycling, so non-cycling load was invisible).

**Math validated on real data 2026-05-15** — rider did a 30:46 run
(avg HR 133) + a 33.78km cycling ride (TSS 87) on the same day.
TSB tracked:
  Morning:   -13 (from prior days)
  After run: -16 (predicted -3 via hrTSS 23.7) ✓ matched
  After ride: -26 (predicted -10 via TSS 87)  ✓ matched

Both running hrTSS and cycling power-TSS feed the same calendar-day
EWMA correctly. Cycling sessions in Samsung overlapping URUJ rides
are deduped within ±2 min. Sessions <5 min or with no HR samples
are skipped. RHR baseline used as HR-Reserve anchor.

**TSB -26 also correctly triggered "over-trained — rest before pushing"**
band warning when the rider crossed the v0.4.1 -25 threshold. The
calibration of TSB band labels (v0.4.1) is validating in practice.

Plus identity rename: BioLabScreen header "Cycling Lab" → "Training Lab"
with multi-sport description.

Polarized 80/20 compliance line on ride summary correctly flagged the
2026-05-15 cycling ride as "Mixed — context-dependent" (59% easy /
35% gray / 4% hard). Useful diagnostic, but currently only post-ride.
Live discipline guidance queued for v0.4.4.

---

## 🗓️ v0.4.2 SHIPPED 2026-05-15 — Per-row info dialogs + dim disabled rows

Each Readiness component now has a tap-to-expand ⓘ icon opening an
ELI10 explanation dialog: what the metric is, why cyclists care, where
to live as an athlete, honest caveats, "For YOU right now" footer.

Training Load dialog specifically addresses the "42 days I have to
wait to recover" confusion — 42d is the FITNESS-build timescale, NOT
recovery. Fatigue recovers in 1-7 days. Plus the productive-fatigue
vs significant-fatigue band breakdown with TSB ranges.

HRV row rendered at 45% opacity when null (Samsung Fit Band 3 case)
— looks like a disabled state, clean for screenshots shared on social.

Tap target on ⓘ icon is 20dp box around 12sp visible glyph (finger-
size hit area).

---

## 🗓️ v0.4.1 SHIPPED 2026-05-15 — Readiness label fixes (device-test catch)

After v0.4.0 device test, rider caught: 11.1h sleep showed "under-slept
— recovery limited" label. Root cause: ReadinessCard.reasonFor mapped
score → label, and 5-6h (score 60) collided with >10h (score 70) in
the "under-slept" bucket. Fixed by branching on hours directly.

Also: HRV null label changed from "needs 7 days of data" (misleading —
Fit Band 3 will never write HRV) to "chest strap unlocks (v1.5)".

TSB bucket split: -25..-10 was too wide. Added -15..-10 = 65/100
"productive fatigue — adaptation territory". TSB -12 (productive)
no longer reads as "fatigued from recent hard rides" (alarmist).

Pure label correctness. No data layer touched. Validated same day
on real ride.

---

## 🗓️ v0.4.0 SHIPPED 2026-05-14 — Identity reset + Samsung-mirror cleanup

After v0.3.8 stress card shipped, rider audited honestly and pushed back:
*"this proxy bullshit is actually confusing rather than value... uruj labs
right now is useless with samsung fit 3 and samsung health... this has
become overly complicated useless piece of junk just with a beautiful UI...
my original idea was biohacker triathlete lab app which would keep growing
as we grow."*

Correct diagnosis. Many Bio Lab cards were re-deriving from Health Connect
what Samsung Health already shows from raw band firmware data (RR intervals,
continuous batched sync) that HC doesn't expose to us. v0.4.0 reset the
identity and cut the noise.

### Identity reset
URUJ Labs is the **cycling-training brain**. Not a wellness dashboard.
Samsung Health owns general wellness. URUJ owns:
1. Live cyclometer on the bike (HUD, power-estimate, grade, wind, PRs)
2. Cycling-specific post-ride analytics (zones, polarized 80/20, route map)
3. Cycling-specific training load (TSB, FTP auto-update, HRR1 over time)
4. Athlete-tier framing of every metric
5. Time-series deep-view history of cycling fitness (v0.4.x)

### What was cut (Bio Lab)
- StressLoadCard + `StressScoreCalculator` (the v0.3.8 cortisol proxy —
  Samsung does live stress with raw RR intervals, we proxied with worse
  inputs → confusing rather than valuable)
- RecoveryCard (sleep + SpO2 — Samsung shows full sleep staging)
- BodyCompositionCard (weight/BMI/height — Samsung's scale writes this)
- ActivityCard (steps/distance/calories today — pure Samsung mirror)
- CardiovascularAgeCard / Fitness Age (pure derivative of RHR + VO2)
- HeartRateCard's today min/max + Samsung Direct RHR row + HRV proxy row
  (all Samsung mirrors or misleading proxies — kept Max HR, 30d peak,
  HR Reserve, Athletic RHR)
- `SleepingHrvProxyCalculator.kt` (deleted — proxy std-dev of HR samples,
  NOT real RMSSD; Readiness now uses direct HRV record only when Samsung
  writes it, no fallback to fake)
- `CardiovascularAgeCalculator.kt` (deleted — pure derivative)

### What was reframed
- VO2 Max card — shows URUJ's Uth-Sørensen number + Samsung's VO2 (if HC
  has it) side-by-side with the formula. Transparency is the moat, not
  the number.

### What was added
- Samsung Health deep-link footer card on Bio Lab — buttons to open
  Samsung Health for sleep / activity / body comp / stress
- Last-refresh timestamp on Bio Lab (rule #2 of lab-level URUJ —
  see [[reference_lab_level_uruj]])

### Readiness slimming
- Cut HRV proxy fallback path entirely
- Cut proxyRestingHr last-resort fallback
- Kept: Sleep hours, RHR(sleep) delta, TSB, direct HRV when Samsung writes
  RmssdRecord (rare on Fit Band 3 but real when present)

### Memory + plan capture
- New `reference_cut_features_v0_4.md` — full audit log: what was cut,
  why it was noise on Fit Band 3, what hardware unlocks resurrection, git
  refs to find the original implementations
- New `reference_lab_level_uruj.md` — the 7 rules every metric must meet
  going forward (source label, timestamp, methodology, no fake numbers,
  deep-view, hardware-additive, cycling-relevance test)
- New `feedback_no_samsung_proxy.md` — the rule that drove the audit

### What stays untouched (the cycling brain)
Recording engine, HUD, power model, GPS quality gating, auto-pause, lock-
screen overlay, WAKE_LOCK, NDJSON, true ride resume (v0.3.8), orphan
recovery (v0.3.7), service-health REC indicator (v0.3.8), stop confirmation,
weather + wind, route map with HR-zone coloring, time-in-zone polarized
80/20 card, HR re-pull on summary view, TTS coach, PR detection — ALL kept.

---

## 🆚 Competitive landscape (2026-05-17 — why URUJ exists)

Honest answer to "isn't this what Whoop/Oura/EliteHRV/Garmin already do?"

Each consumer app shows 20-50% of what URUJ targets, but each is a slice in a
black box with subscriptions and ecosystem lock-in. URUJ's edge is
**integration + transparency + hardware-agnostic + free + sovereign data**.

### What each consumer app does and doesn't do

| App | Does well | Doesn't (vs URUJ) |
|---|---|---|
| **Magene Utility** | Strap firmware, basic HR, workout recording, battery | No training context, no HRV trends, no cycling-specific zones, no biohacker tests |
| **EliteHRV** (free) | Morning HRV reading, RMSSD trends, tagging, CSV export | No continuous, no cycling integration, no orthostatic/CAR/postprandial, no training load |
| **HRV4Training** (~₹2k/yr) | Best research-flavored HRV app, has orthostatic mode, camera-based no-strap | No 24/7, no cycling power/zones, no rides, no time-in-zone |
| **Kubios HRV** (subscription) | Best frequency-domain HRV (LF/HF, DFA) available consumer-side | No training context, research-tool not athlete-tool, no rides |
| **Welltory** | Continuous HRV + stress, daily readiness | Opaque proprietary scores, iOS-stronger, no cycling, no transparency |
| **Whoop** (~₹2k/month) | Continuous PPG HRV 24/7, sleep, "Strain" + "Recovery" | PPG not ECG, closed ecosystem, opaque scores, subscription, locked to their strap, no cycling power |
| **Oura ring** (~₹4k/yr) | Sleep stages, HRV trend, skin temp, polished UX | PPG-based, proprietary "Readiness", no cycling integration, ring-only |
| **Polar Flow** | Recovery Pro, Nightly Recharge, ANS Recovery, Training Load Pro | Polar ecosystem only, needs Polar watch for some features, no biohacker stuff |
| **Garmin Connect** | Body Battery (genuinely good), Stress, HRV Status, sleep | Garmin ecosystem only, needs Garmin watch, closed scores |
| **Bryan Johnson's setup** | Aggregates 20+ apps + lab tests + custom dashboard | Costs ~$2M/year, requires research team, not reproducible solo |

### URUJ's defensible edges

| Edge | Why it matters |
|---|---|
| **One integrated brain** | Each app above is a slice. URUJ pulls cycling + HRV + training load + biohacker tests into one model. |
| **Cycling-specific + biohacker depth in same app** | HRV apps don't care about cycling. Cycling apps don't care about HRV. URUJ marries them. |
| **Source provenance + verbose math** (lab-level rule 8) | Every other app is a black box. URUJ shows you formulas + sources. Whoop's "Recovery 67" tells you nothing. URUJ's "Readiness 67/100 = Sleep 70 + RHR 100 + TSB 50 + (HRV missing)" tells you everything. |
| **Hardware-agnostic** | Any future sensor slots in via priority registry. Whoop locks you to Whoop. URUJ doesn't lock you to anything. |
| **Free + your data stays yours** | NDJSON on your device. No subscription. No cloud lock-in. Export anytime. |
| **Honest about limitations** | We refuse to fake HRV from PPG. Whoop fakes it. We say "chest strap unlocks" until the chest strap is connected. |
| **Tier B tests integrated** | Some apps have orthostatic (HRV4Training). Some have CAR. NONE have CAR + orthostatic + postprandial + caffeine + alcohol + meditation all in one place correlated with your training data. |
| **Athletic + biohacker dual identity** | The other apps pick one. URUJ does both. |
| **Custom personal regressions** (v1.6+) | As baseline data accumulates → patterns specific to YOUR body. No black-box "Recovery 67"-style scores. |
| **Hardware-additive v∞ path** | Custom sensors when consumer hardware caps out. Impossible with closed ecosystems. |

### Honest gaps URUJ has (what they have we don't)

- **Whoop's longitudinal cohort data** (millions of users → normalized scores). URUJ has population of 1.
- **Oura's UX polish** — they have a real design team. URUJ is a solo build.
- **Continuous PPG when off-strap** (Whoop, Oura) — we lose data when Magene is off. Wrist-worn devices keep tracking.
- **Polar's clinical research validation** — Polar H10 has hundreds of published papers. Magene H613 is consumer-grade (still excellent, less peer-reviewed).
- **Cohort comparison** ("how does my VO2 compare to other 26yo cyclists?") — these apps have answers, we don't yet.

### The synthesis

If you only wanted morning HRV + a recovery score → EliteHRV does that, free, simpler.
If you only wanted cycling training → Strava + power meter does that, paywall.
If you want **one thing** that does both, with verbose math, your data, hardware-agnostic, free, growing toward Bryan-Johnson-tier biohacker depth → **that doesn't exist**. That's URUJ's niche.

Most of what URUJ targets exists piecemeal. URUJ's bet is on **integration + transparency + sovereignty + hardware-additive growth**. That's a real and defensible position. Plus the v∞ custom-sensors path means future-URUJ can do things current consumer ecosystems literally cannot.

URUJ isn't duplicating Whoop. URUJ is the thing Whoop would be if it were open, ECG-based, cycling-aware, biohacker-deep, and let you see the math.

---

## 🗺️ Lab-level URUJ roadmap forward

### v0.4.4 — Resume state-clobber fix (CRITICAL, shipped 2026-05-17)

`recordLoop()` initialized local accumulators (distance/work/maxPower/powerSum/
elev) to zero regardless of resume state. First GPS sample post-resume
overwrote the seeded `RideStateHolder` state with zeros via `current.copy()`,
and the 30s checkpoint cemented the loss to disk. Caught when user's
55.57km Samsung-tracked ride with 2 resumes logged only 6.04km in URUJ
(the post-final-resume segment). Fix: seed locals from holder + new
`ElevationTracker.seed()` method. See [[project_cyclometer]] validation
events table for full trace.

---

### v0.4.x — Verbose logging + debug overlay (lab-level rule 8)

User-requested 2026-05-17: every UI number tappable → source + timestamp
+ methodology + raw value. Color-coded source labels on every chart.
Long-press developer overlay showing live source feeds + fusion decisions.
24h ring-buffer of structured logs exportable for bug diagnosis. Provenance
metadata `(source, capturedAt, processedAt, pipeline)` on every sample.
The v0.3.8 resume bug stayed hidden 9 days because no log surfaced the
state clobber — this prevents that class of bug from hiding again.

---

### v0.4.5 — Live HUD zone-discipline TTS alert (rule-based, no AI)

Why: 2026-05-15 ride was 35% in gray zone (Z3 tempo) — pyramidal/threshold-
heavy distribution. Pure polarized (Seiler/Stöggl) wants ≤5% gray. The
fitness cost of gray-zone work without proportional gain is the discipline
gap to close.

Spec:
  - Pre-ride checklist gains a session-type picker:
      [ ] Endurance (target: stay <Z3, 60-180min)
      [ ] Hard intervals (intentional Z4-Z5 work)
      [ ] Recovery (Z1 only, <134 bpm)
      [ ] Exploratory (no discipline target)
  - During ride, if session type ≠ Exploratory:
      Track HR-zone duration via existing TimeInZoneCalculator
      When HR sustains in gray zone (Z3) >5 min on an Endurance session
      → TTS coach: "ease off — gray zone"
      Same for Z2 spillover during Recovery session
  - Visual: HUD adds a small "DISCIPLINE: ENDURANCE" tag next to REC

No AI needed. Rule-based on existing HR data + zone math.

### v0.4.5 — Weekly polarized compliance chart on Rides screen

Why: rider needs to see distribution PATTERN across the week, not just
per-ride. Pyramidal-leaning across 3 rides in a row = early warning.

Spec:
  - New "Week View" tab on RidesScreen
  - Stacked bar chart of last 7 days zone distribution per ride
    (Z1-Z2 easy / Z3 gray / Z4-Z5 hard, color-coded)
  - Weekly summary: % easy / % gray / % hard across all rides
  - Tag: Polarized / Pyramidal / Threshold-heavy
  - Tap a day to drill into that ride's summary

Uses existing TimeInZoneCalculator output per-ride. No new data layer.

### v0.4.x — Time-series deep-view template (MetricTrendScreen)

Already in v0.4.x roadmap. Wire to HRR1 first, then FTP / VO2 / TSB
curve. See [[reference_lab_level_uruj]] rule #5.

### v0.5 — Groq AI coach (the narrative layer)

Three use cases where AI genuinely earns its API call:

**1. Pre-ride coach**
   Inputs: TSB, sleep last night, last 7 rides distribution, recent HRR1
   trend, current goal (e.g. "improve VO2"), today's session type.
   Output: 2-3 sentence narrative recommendation with concrete HR target.
   Example: "TSB -16 (productive fatigue), last week 30% gray → today
   prioritize easy. 75 min Z2 at HR 134-145. Don't chase if road-bike-man
   shows up."

**2. Post-ride debrief**
   Inputs: ride summary, TIZ, polarized compliance, readiness pre/post,
   recent week pattern.
   Output: coaching narrative + tomorrow recommendation.
   Example: "Today 35% gray — not polarized. Pattern across week is
   pyramidal-leaning. Tomorrow REST, day-after do 4×4min intervals at
   HR 175+."

**3. Free-form Q&A on Bio Lab tap**
   Rider asks: "should I race Sunday?"
   AI reasons over: TSB curve, HRR1 trend, recent distribution, race
   recovery requirements. Returns data-grounded answer.

Implementation notes:
  - Math stays rule-based. TSB, hrTSS, HRR1, zones all deterministic.
    AI is NARRATIVE LAYER on top.
  - Honesty floor: AI must cite the specific data points it reasoned
    from in its response. No untraceable claims.
  - Cost: Groq is cheap — full coach inference < ₹1/call. Budget for
    ~50 calls/month per rider.
  - Privacy: ride data + biometrics are sent to Groq. Rider gets a
    one-time consent prompt. All raw data stays on-device; only
    aggregated summaries get sent.
  - Fallback: if Groq unavailable or rider declines, rule-based
    recommendations from v0.4.4 still work.

### v0.4.1+ — Time-series deep-view template (next PR after v0.4.0 device test)
Build one reusable `MetricTrendScreen` composable. Tap any Bio Lab card →
opens the deep-view chart for that metric.

Spec:
- X = time, Y = metric value
- Colored threshold bands (Cole zones for HRR1, intensity bands for power)
- Event dots overlaid (ride markers, hard-day markers)
- Time range filter (30d / 90d / 1y / all)
- Source filter (URUJ only / Samsung only / all)
- Stat row (best ever, current, 7d delta, 30d delta)
- Methodology footnote
- Every datapoint tagged with source label

First wire-up: HRR1 (data already persisted per-ride via
[[reference_hrr1_methodology]]). Then FTP, VO2, TSB curve, athletic RHR,
Max HR.

This is the **actual moat** — Samsung doesn't chart YOUR cycling fitness
across months/years with athlete framing and source provenance.

### v0.5 — Guided lab-grade test protocols (no chest strap needed)
Biohacker-grade self-testing protocols that no consumer app guides:
1. **20-min FTP test** — guided warmup → all-out 20min → cooldown, captures,
   computes FTP from 0.95 × 20-min best avg
2. **Aerobic decoupling** on long rides — Pa:HR ratio (power vs HR drift
   over 2nd half) → predicts pace sustainability
3. **Cooper test (12-min all-out)** — measured VO2, not formula
4. **Submaximal step test** — HR at fixed effort, tracked weekly
5. **HR recovery full curve** — HRR1, HRR2, HRR3, not just one point
6. **Repeatability index** — consistency of best 20s/1min/5min across rides

Fit Band 3 + phone is enough hardware. URUJ becomes the "personal lab" by
guiding the protocol + capturing + analyzing — not by adding sensors.

### v1.5.0 — Workout BLE (Magene H613) — strap arrived 2026-05-17

User purchased Magene H613 (₹4.8k vs ₹5.5k MSRP). Specs unlock:
- 3-channel BLE simultaneous + ANT+ (multi-device pairing)
- Rechargeable USB-C (~50h continuous → 24/7 wear feasible)
- 17h offline storage on strap (backup if phone dies)
- ECG signal + proprietary noise reduction (clean even during heavy sweat)
- Onboard HR threshold alerts
- Real RR intervals (real RMSSD HRV)

v1.5.0 scope (workout-only first, validates plumbing):
- BLUETOOTH_SCAN + BLUETOOTH_CONNECT permissions
- `BleHrSource` implementing `HrSample` flow interface
- Parse Heart Rate Measurement characteristic (0x2A37) + extract RR
  intervals when flag bit 4 is set
- Register as priority-1 HR source above HC HR during recording
- Live HR + zones on HUD (no batch lag)
- Real RMSSD HRV during ride
- Battery state visible on HUD top bar
- Graceful fallback to HC HR on disconnect

### v1.5.1 — Continuous biometric service (24/7 RR data layer)

Persistent BLE foreground service (separate from RideRecorderService).
24/7 RR-interval capture and storage. HRV calculator running every 5 min
on rolling window. Daily HRV chart on Bio Lab. Stress event detection.
Battery + connection state on HUD. ~5MB/day NDJSON storage. 1-2 weeks
focused work. THIS is the foundational layer for tiers below.

### v1.5.2 — Tier B time-anchored tests

Once continuous RR exists, each test = small computation on top:
- **Cortisol Awakening Response** — first 30min post-wake HR + HRV pattern
- **Orthostatic test** — sit/stand HR delta + HRV recovery
- **Postprandial response** — meal-mark + 45min window HRV drop
- **Caffeine response** — drink-mark + 0-180min trace
- **Alcohol HRV debt** — next-night sleep HRV vs baseline
- **Cold/heat exposure response** — pre/during/post pattern
- **Meditation effectiveness** — pre/during/post trace
- **Breath-work biofeedback** — live HF HRV during slow breathing

Each ~1-3 days focused.

### v1.5.3 — HRV frequency-domain + non-linear measures

- FFT on RR series → LF / HF / VLF power
- LF/HF ratio (legitimate autonomic balance / "stress index")
- SDNN, pNN50, pNN20
- Poincaré SD1 / SD2
- DFA α1 (autonomic age marker — published norms)
- Sample entropy

New "Autonomic" section on Bio Lab. Replaces the dimmed HRV row.

### v1.6 — In-app tracking layer (NO new hardware, just code)

Mood / energy 1-10, soreness map, Bristol stool, hydration, caffeine
timing, supplement tracker, cold/sun exposure, meditation log with HRV
trace, cognitive reaction-time gamified, dream recall, meal photo log,
symptom journal. All correlatable with HRV/load/sleep for personal
regression patterns. Bryan Johnson Tier D — most of what he tracks is
subjective + correlatable, doable purely in software.

### v2.0 — Power meter integration (when purchased, ~₹25-35k)

Favero Assioma single-side or similar. Replaces physics-based power
estimate. Same priority pattern as HR.

### v2.5 — External lab test data ingestion

User imports manual entries or PDF parse of:
- Blood panels (lipids, HbA1c, hsCRP, ferritin, vitamins, hormones)
- DEXA scan results (body comp)
- Speed-of-aging tests (TruDiagnostic / similar epigenetic methylation)
- VO2 max lab CPET

Correlate with continuous URUJ data for personal regression dashboard.
Track epigenetic age trend across yearly tests — Bryan Johnson's flagship
metric, now available to anyone with ₹15-25k per test.

### v3.0+ — Biohacker hardware ladder (each as gear purchased)

| Hardware | Cost (₹) | Unlocks |
|---|---|---|
| Smart scale (Withings/Garmin Index) | 8-15k | Body fat %, muscle, water, visceral fat, bone — daily |
| CGM (Abbott Libre / Stelo) | ~2k/14d | Glucose curve, postprandial, dawn, food ledger |
| Lactate meter (Lactate Plus / Edge) | 15k + strips | Real LT1/LT2 → anchored zones |
| BP cuff (Omron / Withings) | 3-5k | Resting BP, stress response, post-exercise recovery |
| Sleep EEG headband (Dreem 2 / Frenz) | 30-50k | True sleep staging from brain waves |
| Oura ring (skin temp) | 25k + 4k/yr | Illness early warning, ovulation, redundant HRV |

### v∞ — Custom-built sensors (user's polymath aspiration)

"im a polymath builder man and with you we can do impossible stuff" —
once enough off-the-shelf data is captured + patterns identified, design
custom sensors for gaps (e.g. sweat composition analyzer, continuous
saliva cortisol, breath VOC analyzer, in-helmet EEG for live cognitive
load during rides). Aspirational, but documented to keep the long arc
visible.
When user buys a Polar H9 / CooSpo H6 / Magene H64. `BleHrSource` plugs in
as priority-1 HR source. Unlocks:
- **Live HR on HUD during ride** (Fit Band 3 batches; chest strap streams)
- **Real RMSSD HRV** via beat-to-beat RR intervals → rebuild Autonomic
  Stress card with real signal (the stress score cut in v0.4.0 can come
  back as a genuine measurement, not a proxy)
- **Continuous HRR1** every 1s during recovery, not Fit Band 3 spot-checks
- **HR drift / aerobic decoupling alerts** mid-ride ("you're cracking, slow
  down")
- **True HR-zone time tracking** live during ride

See [[reference_cut_features_v0_4]] for resurrection refs of features
removed in v0.4.0 that v1.5 unlocks.

### v2+ — Power meter unlock (₹25k–35k)
Favero Assioma single-side or similar. Replaces physics-based power
estimate with real watts. Unlocks:
- True FTP test (not 0.95 × 20-min estimate)
- Real W/kg
- Proper TSS (not IF² approximation)
- Power-balance (per-leg) metric

### v3+ — Biohacker layer (CGM + lactate strips)
- **CGM** (Abbott Libre / Stelo, ~₹2k per 14d strip): glucose-during-ride
  curve, glycemic response to fueling strategies, post-ride glucose
  recovery rate. Even Pogačar uses CGM in elite endurance training.
- **Lactate meter** (Lactate Plus, ~₹15k + ₹100/strip): home lactate
  threshold test → genuine LT1/LT2 anchored zones replacing Karvonen
  estimate.

This is the realistic gear ladder toward the "Blummenfelt-tier biohacker
triathlete lab" the rider originally envisioned. URUJ Labs grows in
capability with each hardware addition.

---

## 🗓️ v0.3.8 SHIPPED 2026-05-14 — True ride resume + service-health REC + stress load

Three enterprise-grade hardenings shipped in one PR. User scope statement:
"true ride resume, derived stress score and service health indicator is
nice lets do that enterprise lab grade failproof. ... skip meal logging UI
(Samsung already has water+meals)."

**1. True ride resume**
  - `.active` marker file written by RideRecorderService on session start,
    deleted on clean STOP. Persistent marker = process was killed while
    actively recording.
  - On cold-start, MainActivity scans `findActiveOrphans()` BEFORE running
    the v0.3.7 passive `recoverOrphanRides()`. When an active orphan is
    found, surfaces `ActiveOrphanDialog`:
      - **RESUME** → service relaunches via `ACTION_RESUME` +
        `EXTRA_RESUME_SESSION_ID`. `startRecording(resumeSessionId)` loads
        the existing summary and seeds initial RideState accumulators
        (totalDistance, movingTime, totalElapsed, avgPower, maxPower,
        totalWorkKj, elev gain/loss, maxSpeed, FTP) so new samples extend
        the totals instead of starting from zero. NDJSON append=true.
      - **END & SAVE** → `finalizeActiveOrphan(sessionId)` deletes the
        marker and rebuilds summary from NDJSON. Surfaces the recovered
        ride via the existing v0.3.7 OrphanRecoveryDialog so the rider
        can VIEW RIDE immediately.
      - **DISCARD** → `discardActiveOrphan(sessionId)` deletes marker +
        NDJSON + summary together.

**2. Service-health REC indicator on HUD**
  - HudTopBar's pulsing dot was a constant 900ms RepeatMode.Reverse tween
    regardless of whether the recording loop was alive — visual lie.
  - Now color-coded by `RideState.lastCheckpointAtMs` age:
      <40s → HEALTHY (green, pulsing)
      40-90s → DEGRADED (amber, static)
      >90s → STALE (red, static)
      null pre-first-checkpoint → STARTING (amber, pulsing)
      isPaused → PAUSED (muted, dim)
  - Label text mirrors the state ("REC", "REC · DEGRADED", "REC · STALE"
    etc.) so the rider can identify the cause without remembering the
    color code.

**3. Derived stress load — cortisol-axis proxy**
  - New `StressScoreCalculator` (`com.uruj.power`). Pure function over
    StressScoreCalculator.Inputs → StressScoreCalculator.Result.
  - Output: 0-100 score where HIGHER = MORE STRESS (inverse of readiness).
  - Weighting:
      HRV trend vs 7d baseline   — 30%
      RHR delta vs 7d baseline   — 20%
      Sleep deficit vs 7-9h tgt  — 20%
      TSB (Coggan CTL−ATL)       — 20%
      Consecutive hard days      — 10%
  - Bands: CALM 0-25 / MODERATE 26-50 / ELEVATED 51-75 / HIGH 76-100.
  - BioLabRepository wires it in alongside existing sleeping HRV/RHR +
    new local `computeTsbFromRides()` (mirrors ReadinessRepository's
    calendar-day fixed v0.3.2 algorithm) + new `countConsecutiveHardDays()`
    (IF ≥ 0.80 OR moving ≥ 90min, walks backwards from today/yesterday).
  - BioLabSnapshot gains `stressLoad: StressScoreCalculator.Result?` +
    `consecutiveHardDays: Int` fields.
  - New `StressLoadCard` on BioLabScreen, slotted under Recovery section.
    Shows hero score + band + tagline + per-component breakdown rows
    (HRV/RHR/SLEEP/LOAD/STREAK with score/100 and detail) + a confidence
    line ("Confidence 80% — based on what's available in HC + ride
    history. ≈ proxy, NOT blood cortisol. Real cortisol = blood/saliva
    test.").

**What's queued NEXT after v0.3.8 lands:**
  - True chest-strap BLE source (v1.5 unlock) — live HR on HUD + real
    RMSSD HRV + continuous-sample HRR1 + instantaneous post-ride HR.
  - Push notification when re-sync arrives with fresh HR data.
  - HRR1 history view (deferred from earlier in plan).
  - Time-series HR/speed/power chart on summary screen.

---

## 🗓️ v0.3.7 SHIPPED 2026-05-14 — Enterprise hardening (5 fixes)

User came back from a rough ride with stacked grievances: pocket-touched
stop ended a ride, OS-killed service produced blank-screen-on-reopen
silently losing data, readiness sleep showed 5.3h while Bio Lab showed
9.2h on same data, HRR1 showed 65 bpm drop after 3 back-to-back rides,
elevation gain never visibly moved during ride.

All five addressed in one PR:

1. **Stop ride confirmation** — AlertDialog with ride stats + warning
   for sub-500m rides. OpenTracks-style guard against pocket touch.
2. **Orphan ride auto-recovery on cold-start** — MainActivity now runs
   recoverOrphanRides() on launch and surfaces a dialog ("Previous
   ride recovered: X.Y km — added to RIDES"). Killed-mid-ride data is
   never silently lost.
3. **Unified LastSleepReader** — single source of truth, used by both
   ReadinessRepository AND BioLabRepository. Returns MOST RECENT sleep
   session block (not a sum). Matches Samsung's semantic for night-shift
   users.
4. **HRR1 session-gap filter** — sessions with another workout starting
   within 3 min are skipped from HRR1 calc (recovery sample would land
   in next workout's warmup). Fixes the 65 bpm inflation.
5. **HUD altitude + heartbeat** — new ALT row shows live current altitude
   ticking every sample; new LAST SAVE row shows seconds since 30s
   checkpoint so rider sees service is alive when phone is backgrounded.

What's queued NEXT after this lands:
  - True ride resume capability (continue an interrupted ride from where
    it stopped, not just recover the partial data)
  - Derived "stress score" combining HRV trend + RHR baseline + sleep
    deficit + TSB (Whoop-style "strain/recovery" — cortisol proxy)
  - HUD service-health indicator (current notification status visible)
  - Cortisol/meal/caffeine logging UI

