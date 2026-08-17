# v0.9.78 — Cadence sensor integration + HUD v2 + slide-to-end

**Date**: 2026-08-17
**Branch**: `feat/v0.9.78-cadence-hud`
**versionCode**: 168 · **versionName**: 0.9.78
**Hardware added**: Magene S314 dual-mode speed/cadence sensor, mounted on the **left crank** (cadence mode)

---

## 0. What the rider asked for

Three things, in one ride-facing change:

1. Add the new cadence sensor — "how is it gonna work? first check our existing
   architecture cuz we cannot have place for bugs"
2. Make the HUD more futuristic and more real-time, fit everything, with
   **speed / cadence / HR** as the biggest readouts — without eating battery
3. Stop rain from ending rides: the STOP confirmation was being triggered by
   water on the screen, so ending a ride needs a gesture, not a tap

Plus the standing requirement: edge cases (disconnection, dead battery, wrong
mode), how it links to everything already in the app, and how it gets tested.

---

## 1. The sensor, from the advertisement packet

nRF Connect scan of the S314 as mounted:

```
Complete Local Name : 57298-1
Address             : F2:4F:CD:5C:26:74
Appearance          : [1155] Cycling: Cadence Sensor
Flags               : LE Limited Discoverable, BR/EDR Not Supported
16-bit Service UUIDs: 0x1816          ← Cycling Speed and Cadence (CSC)
Manufacturer data   : Company 0xFFFF  (no assigned company ID)
```

`0x1816` is the standard Bluetooth SIG **Cycling Speed and Cadence** service, and
the appearance value confirms the sensor has self-identified as a *cadence*
sensor rather than a speed sensor. That is the whole integration surface — no
proprietary protocol, no vendor SDK, no Magene app dependency.

### The characteristics URUJ reads

| UUID | Name | Access | Used for |
|---|---|---|---|
| `0x2A5B` | CSC Measurement | NOTIFY | cumulative crank revolutions + last crank event time |
| `0x2A5C` | CSC Feature | READ | does this sensor claim crank-revolution support? |
| `0x2A5D` | Sensor Location | READ | "Left crank" — shown in Diagnostics |
| `0x2A19` | Battery Level (svc `0x180F`) | READ + NOTIFY | live battery, persisted per connection |
| `0x2A29/24/26` | Manufacturer / Model / Firmware (svc `0x180A`) | READ | Diagnostics provenance |

### The measurement packet

```
byte 0        flags
                bit 0: Wheel Revolution Data Present
                bit 1: Crank Revolution Data Present
if bit 0:     uint32 Cumulative Wheel Revolutions
              uint16 Last Wheel Event Time    (1/1024 s)
if bit 1:     uint16 Cumulative Crank Revolutions
              uint16 Last Crank Event Time    (1/1024 s)
```

**The sensor never sends rpm.** It sends "how many strokes so far" and "when the
last stroke happened, on my own 1024 Hz clock". Cadence is the slope between two
of those reports:

```
rpm = Δrevolutions ÷ (Δevent_ticks ÷ 1024) × 60
```

Both counters are **uint16**. The event-time clock wraps every 64 seconds; the
crank counter wraps every 65 536 strokes (~12 h at 90 rpm). A naive subtraction
produces a huge negative delta roughly once per hour of riding, and once per
chai stop. Every delta in `CadenceTracker` is therefore computed modulo 2¹⁶.

---

## 2. Architecture — where each piece landed

The existing BLE chest-strap pipeline (v0.5.x) was already the right shape, so
cadence was built as a **sibling of it**, not a generalisation of it. Nothing in
the HR path was touched.

```
BleCadenceSource            sensor/android/    GATT + chained one-op-at-a-time
  └─ owns CadenceTracker    power/             pure math, 16 unit tests
       ├─ CscParser                            packet decode
       └─ rpm slope over a 3.5 s window

RideRecorderService         service/           retry loop + accumulators
  ├─ cadence BLE collect  → live rpm, battery, stroke count
  └─ 1 Hz ticker          → staleness → 0 rpm, avg/max/pedalling accumulation

RideState                   service/           live + accumulators
RideSample.cadenceRpm       domain/            per-second NDJSON
StoredRideSummary           data/              avg / max / pedalling / strokes

HudScreen + HudMetrics      ui/hud/            three-hero row
CadenceTestCard             ui/diagnostics/    pair + audit
RideSummaryScreen           ui/summary/        post-ride cadence card
```

### Decisions worth recording

**`autoConnect = true` for cadence, `false` for the strap.** A chest strap is
worn continuously and is awake whenever the rider is, so a fast direct connect
is correct. A crank sensor is the opposite: it sleeps within minutes of the bike
being parked and stops advertising entirely. With `autoConnect = false`, every
reconnect burns a ~30 s GATT timeout against a device that is not listening, so
the sensor reads as dead for up to a minute after the rider starts pedalling
again. `autoConnect = true` parks the connection in the Bluetooth controller and
completes it the instant the sensor wakes — which is exactly the moment the
crank turns.

**Cadence is measured over a rolling window, not between adjacent packets.** At
low cadence a crank event is rarer than the ~1 Hz notification rate, so
adjacent-packet math alternates between a spike and a zero. A 3.5 s window gives
the number a head unit shows, with no EMA lag bolted on: the value is still an
exact revolutions-over-time measurement, just over a slightly longer base.

**Coasting is a measurement, not a gap.** No crank event inside the window ⇒
**0 rpm**, not "last known value" and not null. This is why the 1 Hz ticker
re-reads the tracker every second instead of only updating on packet arrival —
cadence sensors go silent while freewheeling, so a packet-driven readout would
freeze at 92 rpm all the way down a descent.

**Average cadence excludes coasting** (Strava / Garmin convention). An average
that counts freewheeling measures the terrain, not the rider: the same legs
"average" 85 rpm on a flat loop and 55 on a descent-heavy one. The coasting
story is carried honestly and separately by **PEDALLING %** — the share of
moving time the cranks were actually turning.

**`null` ≠ `0` in the ride NDJSON.** `cadenceRpm = null` means no sensor was
connected; `0` means the rider was freewheeling. Collapsing those would make
every pre-v0.9.78 ride look like a ride spent coasting.

---

## 3. Edge cases and what happens

| # | Situation | Behaviour | Where |
|---|---|---|---|
| 1 | **No cadence sensor paired** | No BLE loop starts, no scan, no GATT, no battery cost. HUD falls back to the two-up SPEED + HR layout; summary card is absent. | `RideRecorderService` early-return; `HeroRow` |
| 2 | **Sensor asleep at ride start** | `autoConnect` parks the connection; it completes on the first crank turn. Chip reads `CAD OFF` until then. | `BleCadenceSource` |
| 3 | **Mid-ride dropout** (out of range, knocked off) | Flow closes → chip goes `CAD OFF`, rpm → 0, retry with 5→60 s exponential back-off. Stroke count is preserved across the reconnect via a per-connection base. | cadence retry loop |
| 4 | **Battery dies mid-ride** | Same as a dropout — it looks like a permanent disconnect. Retries continue at the 60 s cap for the rest of the ride (cheap: a parked `autoConnect`). | cadence retry loop |
| 5 | **Battery low but alive** | Read at every connection, persisted to DataStore. HUD chip turns amber below 20%; Diagnostics shows "Battery at last connect: 8% ⚠ replace the CR2032 soon" **before** the next ride. | `BleSettingsStore.saveCadenceBattery` |
| 6 | **Dual-mode sensor in SPEED mode** (wheel data only) | Packets decode fine but carry no crank field. HUD reads `RPM · WHEEL MODE`, Diagnostics explains how to fix it. Never silently blank. | `CadenceSample.hasCrankData` |
| 7 | **Crank counter wraps** (65 536 strokes) | Modulo-2¹⁶ delta. Unit-tested. | `CadenceTracker` |
| 8 | **Event-time counter wraps** (every 64 s) | Modulo-2¹⁶ delta. Unit-tested. | `CadenceTracker` |
| 9 | **Stop longer than 64 s** (traffic, chai) | The tick delta becomes genuinely ambiguous (wrapped once? three times?). Tracker re-baselines instead of guessing, then picks straight back up. Unit-tested. | `AMBIGUOUS_GAP_MS` |
| 10 | **Sensor power-cycles and restarts its counters at 0** | Modular subtraction would read that as ~65 500 strokes in one second. Any delta above 300 strokes/packet is treated as a reset → re-baseline, ride stroke total preserved. Unit-tested. | `MAX_REVS_PER_PACKET` |
| 11 | **Malformed / truncated packet** | Dropped, logged, never half-decoded. Unit-tested. | `CscParser` |
| 12 | **Implausible rpm** (> 250) | Rejected; previous value held. | `CadenceTracker` |
| 13 | **Bluetooth turned off mid-ride** | Adapter check fails at flow entry → ERROR → back-off retry, same as the strap. | `BleCadenceSource` |
| 14 | **BT permission revoked mid-ride** | Flow closes immediately, retry loop continues, resumes when re-granted. | `hasPermissions()` |
| 15 | **Ride paused (manual or auto)** | Live rpm still displays honestly; avg / max / pedalling-time accumulation is gated off, same as every other stat. | 1 Hz ticker |
| 16 | **Ride resumed after a crash** | Cadence accumulators seed from the saved summary, **weighted by pedalling seconds** so a 2-minute post-resume spin can't dominate a 3-hour ride's average. | `startRecording` |
| 17 | **Interrupted ride rebuilt from NDJSON** | Avg / max / pedalling recomputed from the per-second samples. Stroke count deliberately left `null` — the NDJSON stores sampled rpm, not the sensor's counter, so any figure would be an integral dressed as a count. | `rebuildSummary` |
| 18 | **Three concurrent BLE links** (ride strap + 24/7 strap + cadence) | Within the phone's connection budget; all three are ~1 Hz notification streams. | — |
| 19 | **Rough road vibration** | The S314 is accelerometer-based, so vibration *could* register. Not filtered on the low side: 20–40 rpm is real when grinding up a ramp, and filtering it would delete a true signal to hide a rare artifact. The 250 rpm ceiling is the only physical filter. | `CadenceTracker` |

---

## 4. How it links to everything already in the app

**Training load / TSB / CTL / ATL — untouched, and that is deliberate.** Since
v0.9.72 the load model is HR-based (`hrTSS`). Cadence does not enter it. Adding
cadence to the load calculation would be inventing physiology; it changes
nothing about how hard the ride was, only about *how* the rider produced it.

**Power estimate — untouched.** The physics model runs off speed and grade.
Cadence without torque is not power, and pretending otherwise would be exactly
the kind of fabricated proxy the project's honesty rules forbid.

**Readiness / Bio Lab / HRV / CAR — untouched.** No cadence signal enters the
recovery engine.

**Ride NDJSON** gains one nullable field per sample. Old rides decode unchanged
(`ignoreUnknownKeys` + a default), and new rides omit the field entirely when no
sensor is present (`encodeDefaults = false`), so file size is unaffected for
strap-only rides.

**Ride summary** gains a card, which the existing share-image capture picks up
for free (it snapshots the whole summary column).

**Notification / lock screen** gains `· 87 rpm`, only when a sensor is paired.

**Route map, TIZ, polarized compliance** — unchanged. Colouring the route
polyline by cadence is a natural follow-up, deliberately deferred.

---

## 5. HUD v2

### The layout problem

Three hero metrics on a phone screen fight each other for width, and the rider
explicitly wants all three *biggest*. Rather than tune a font size on one
handset, each hero cell measures the width it was actually given and picks the
largest font that fits, weighting the speed decimal correctly (`"28" + ".4"`
costs 2.84 digit-widths, not 2 — sizing off string length alone overflowed the
cell in the first draft).

Speed renders its integer part large and its decimal small. That is a deliberate
readability trade: at 30 kph on a bumpy road, the "28" is the number being read
and the ".4" is texture.

### Why segmented bars instead of ring gauges

A ring gauge steals the space the digits need. A segmented bar sits *under* the
number in 7 dp, encodes the same information, and is legible without focusing —
the rider reads block count in peripheral vision the way a pilot reads a strip
gauge. It also carries the band map:

- **HR bar** — the rider's full Karvonen zone map lives in the unlit track, so
  "how far is Z2 from here" is visible without arithmetic. Boundaries come from
  the same `classifyKarvonenZone` every other surface uses, so the band map and
  the digit colour can never disagree.
- **Cadence bar** — the 80–95 rpm endurance band stays highlighted even at 0 rpm,
  so the target is visible before the rider is anywhere near it.
- **Speed bar** — plain 0–50 kph scale.

### The battery rule

**Motion only where it encodes data, and only when the data changes.**

The pre-v0.9.78 HUD ran two `rememberInfiniteTransition` loops — the REC dot
pulse and the HR glow — which animated continuously for the entire ride
regardless of what the sensors were doing. Both are gone. The REC dot now simply
prints its checkpoint age ("REC · 12s"), which is the honest number the blink
was standing in for; the elapsed clock ticking every second is all the liveness
proof a HUD needs.

What replaced them are `animateFloatAsState` transitions driven by values that
update about once a second, so each one settles and stops. Net expectation:
**neutral to better** than the previous HUD, in a screen-on context where the
display panel, GPS and two BLE links dominate anyway. This is an argued
expectation, not a measured one — see the open items below.

Secondary battery choices: true-black background retained (unlit AMOLED pixels
cost nothing), large filled surfaces avoided in favour of 1 dp outlines, and the
fill behind the swipe track is not drawn at all until a drag starts.

### Real-time speed

GPS stays at 1 Hz. Requesting faster fixes would cost power for data the chip
does not produce. What changed is the *display*: the segmented bar animates
between fixes over 700 ms, so the readout moves continuously instead of stepping
once per second. The digits remain the honest 1 Hz value — the animation is on
the bar, not on the number, so nothing invents precision the GPS did not supply.

---

## 6. Slide-to-end

### The bug

Riding in rain, water on the screen registers as touches, and rides were being
ended mid-session. A confirmation dialog does not fix this: the dialog's own
buttons are equally tappable by a droplet, and a wet screen produces touches in
sequence.

### The fix

A capacitive false-touch is a **point event**. It does not travel 85% of the
screen's width while maintaining contact. So ending a ride now requires a
sustained horizontal drag — physically unavailable to rain, achievable with one
gloved thumb, impossible from a jersey pocket.

- Track fills red behind the thumb as it travels.
- Crossing the threshold fires a **haptic thump**, so the confirm is verifiable
  by feel at 30 kph without looking down.
- Release past the threshold ends the ride; release short springs back.
- The track shows the ride's distance and moving time, so the rider sees what
  they are ending.

### What stayed a tap, and why

**PAUSE.** A phantom pause is visible (a full-width amber PAUSED banner) and
undone with one more tap. Friction belongs on the control you cannot take back,
not on the one you can.

**The sub-500 m dialog.** The one surviving tap-confirm. A ride that short is far
more likely to be an accidental swipe two minutes in than a deliberate finish, so
that specific case still asks.

**The notification's STOP action.** A deliberate press in the shade, not exposed
to rain.

---

## 7. Testing

### Automated (ran, green)

`CadenceCalculatorTest` — **16 tests, all passing**. Full suite: **55 tests, 0
failures** (first time the whole suite has been green *and* grown since the
v0.9.76 test-rot fix).

Every scenario that is not reproducible on a bike ride is covered here, because
the alternative is discovering it at km 60:

- crank-only, wheel+crank, and wheel-only packet decode
- truncated / empty / no-fields-declared packets rejected, never half-decoded
- CSC Feature bits and Sensor Location ("Left crank") decode
- steady 90 rpm reads 90 rpm; stroke total accumulates
- crank counter wrap does not spike cadence
- event-time counter wrap does not spike cadence
- coasting reads 0, not the last known cadence
- repeated identical crank events decay to 0
- sensor power-cycle re-baselines instead of reporting ~65 500 strokes
- a 5-minute stop re-baselines, then recovers to the correct rpm
- 24 rpm grinding is measured, not treated as coasting
- reset clears counters for a fresh connection

### On-device, before the ride (bench test, ~10 minutes)

1. **Pair** — Diagnostics → BLE CADENCE SENSOR → SCAN & PAIR. Spin the crank a
   few turns first to wake the sensor. Expect the card to show `57298-1`, MAC
   `F2:4F:CD:5C:26:74`, and — the key line — **`mounted at: Left crank`** and
   **`crank data supported: yes`**. If it says wheel instead, the sensor is in
   the wrong mode and nothing downstream will be right.
2. **Count strokes by hand.** Turn the crank exactly 20 times, slowly. `crank
   revolutions:` in the RAW CSC STREAM block must read exactly 20. This is the
   single most important check — it validates decode, rollover handling and the
   counter base in one move.
3. **Rate check.** Spin at a steady, countable pace for 30 s (e.g. one turn per
   second = 60 rpm). The readout should sit at that number ±2.
4. **Coasting.** Stop turning. The readout must fall to **0 within ~4 s**, not
   freeze at the last value.
5. **Battery.** Confirm a plausible battery % appears.
6. **Disconnect.** Walk 20 m away (or pop the sensor's cell out). The card should
   go DISCONNECTED. Walk back / re-seat: it should recover on its own.

### On-device, during the ride

7. **HUD.** Three heroes visible without scrolling. Cadence tracks the legs.
   Chip strip shows `GPS ±Xm`, `STRAP ✓ · N%`, `CAD ✓ · N%`.
8. **Freewheel down a hill.** Cadence → 0 while speed stays high. This is the
   check that the readout is honest rather than sticky.
9. **PAUSE / RESUME** still behaves as it did in v0.9.76 (validated on the
   2026-06-13 ride).
10. **Slide-to-end** — do this one deliberately with a **wet thumb**, ideally in
    actual rain, since that is the condition it exists for. Confirm: a tap alone
    does nothing; a partial drag springs back; a full drag thumps and ends.
11. **Lock screen** shows `· N rpm`.

### After the ride

12. **Summary** shows the CADENCE card: average (pedalling), peak, pedalling %,
    pedal strokes, and a plain-language read.
13. **Sanity-check the numbers against feel.** A 2-hour endurance ride should
    land around 80–90 rpm average with pedalling % in the 80s. If pedalling %
    comes out at 40% on a flat loop, something is wrong — say so rather than
    accepting it.
14. **NDJSON** — `Android/data/com.uruj/files/rides/<id>.ndjson` lines should
    carry `"cadenceRpm":`. Compare a handful against what the HUD showed.

---

## 8. Deferred, with reasons

- **Cadence in the audio coach** ("you're grinding — spin it up"). Genuinely
  useful and cheap, but it changes coaching behaviour and deserves its own test
  ride rather than riding in on a sensor-integration PR.
- **Route-map polyline coloured by cadence.** Natural follow-up, no new data
  needed.
- **Cadence distribution chart** (time-in-cadence-band, the cadence analogue of
  time-in-zone).
- **Speed from the sensor.** The S314 is dual-mode and could drive wheel-based
  speed, which is more responsive than GPS in tunnels and under trees. Requires a
  wheel-circumference setting in the rider profile and a second sensor mount, so
  it waits until there is a reason.
- **Battery-measurement of the HUD rebuild.** The battery argument above is
  reasoned, not measured. A same-route A/B (v0.9.77 vs v0.9.78, screen on, same
  brightness) would settle it. Worth doing once, not worth blocking on.
- **The two accidents.** Not a software item, but recorded because it is context
  for everything above: the bike now has hood drop bars, the front end is lighter
  and the handling has changed. Two crashes since the change, one of them putting
  a pedestrian in hospital for stitches. The HUD exists to be glanced at, not
  read — which is part of why this rebuild pushes information into bar positions
  and colour rather than more digits to parse.
