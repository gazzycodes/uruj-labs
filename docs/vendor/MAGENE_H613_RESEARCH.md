---
name: magene-h613-capabilities
description: "Magene H613 chest strap BLE/ANT+ capabilities research (2026-05-17, pre-v1.5.0 build). What URUJ can openly access via standards vs what's locked behind Magene's OnelapFit / Magene Utility apps. Sourced from official product pages, app compatibility lists (Elite HRV, Zwift, TrainerRoad), and indirect inference via app-required protocols."
metadata: 
  node_type: memory
  type: reference
  originSessionId: 8b092746-b296-4b0a-96c3-de4bc8b28fa7
---

## TL;DR for URUJ design decisions

H613 is a **standards-compliant BLE Heart Rate Profile chest strap** at the data-streaming layer. URUJ can build v1.5.0 (live HR + RR during rides) and v1.5.1 (24/7 continuous RR for HRV) using stock Android BLE with **zero Magene-specific code**. The proprietary bits (offline 17h sync, threshold-alert config, firmware updates) are locked in Magene's apps and not worth reverse-engineering for URUJ's roadmap. Treat strap as **live-stream-only data source**.

## Standards exposed (high-confidence based on app compatibility)

| Capability | UUID | URUJ-accessible |
|---|---|---|
| Heart Rate Service | `0x180D` | ✓ stock BLE |
| HR Measurement (notify) | `0x2A37` | ✓ stock BLE |
| RR intervals (flag bit 4) | within `0x2A37` payload | ✓ — required by Elite HRV compatibility, must be present |
| Battery Service | `0x180F` | ✓ very likely standard |
| Battery Level | `0x2A19` | ✓ very likely standard |
| Device Info Service | `0x180A` | ✓ very likely standard (FW rev, model, mfr) |
| ANT+ HR profile | standard | ✓ standard |
| 3 simultaneous BLE connections | hardware feature | ✓ — any 3 third-party apps, no Magene reservation required |

**Confidence basis**: Magene officially lists Elite HRV + Selfloops HRV as compatible with H613. Elite HRV's published compatibility requirement is *"transmit unaltered RR intervals via Bluetooth 4.0 or ANT+."* That can only be satisfied by standard `0x2A37` with flag bit 4 — there is no proprietary Elite HRV protocol. Same logic applies to listed compatibility with Zwift, TrainerRoad, Wahoo Fitness, Strava, ROUVY, Fulgaz, Kinomap, Nike Run Club — none use vendor-specific protocols, all consume standard `0x180D` notifications. Therefore the strap MUST expose standard HR Service with RR intervals.

## Locked features (Magene apps only — NOT accessible from URUJ)

| Feature | Locked in | Notes |
|---|---|---|
| 17h offline workout sync | OnelapFit | Proprietary file transfer; not worth fighting |
| HR threshold alert config (high/low BPM) | OnelapFit | URUJ can implement own zone-discipline TTS instead (task #103) |
| LED zone customization | OnelapFit | Vendor characteristic |
| Firmware update | Magene Utility / OnelapFit | Proprietary, not Nordic DFU |
| Buzzer alarm trigger | onboard-only | User configures once in OnelapFit, runs autonomously |

## Architectural implications for URUJ

**Accept these gaps as design constraints, do not engineer around them:**

1. **Assume phone is always present during rides** — URUJ cannot retrieve the 17h offline buffer. If rider goes without phone, that ride's RR fidelity is lost to OnelapFit cloud (or local Magene app).
2. **URUJ's zone-discipline alerts via phone TTS** (planned task #103) — more flexible than strap's onboard threshold buzzer.
3. **FW version surfaced read-only** in URUJ's "About sensor" screen via `0x180A`; user updates via OnelapFit when prompted.

## Critical pre-build validation (5 minutes when hardware arrives)

Before writing any v1.5.0 BLE code:

1. **nRF Connect for Mobile scan** — free Android app, do service discovery, screenshot full UUID tree. Confirms all "High/Medium" confidence assumptions above.
2. **Side-by-side HRV validation** — Elite HRV + URUJ-prototype both subscribed for 5 min seated, compare RMSSD. Within ±5% = good. Diverges = parser bug or strap-smoothed RR.
3. **3-connection bench test** — pair phone + bike computer + smartwatch simultaneously, all see HR live, no drops.
4. **RR-jitter check** — at rest, consecutive RR values should fluctuate ≥5ms beat-to-beat (natural sinus variability). If all identical or quantized → strap smooths RR → HRV unreliable.

## Kotlin code skeleton (drop-in)

```kotlin
object HrService {
    val SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
object BatteryService {
    val SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}
object DeviceInfoService {
    val SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REV = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
}

fun parseHeartRateMeasurement(value: ByteArray): HrSample {
    val flags = value[0].toInt() and 0xFF
    val hrIs16Bit = (flags and 0x01) != 0
    val rrPresent = (flags and 0x10) != 0
    var idx = 1
    val hr = if (hrIs16Bit) {
        val v = (value[idx+1].toInt() and 0xFF) shl 8 or (value[idx].toInt() and 0xFF)
        idx += 2; v
    } else {
        val v = value[idx].toInt() and 0xFF
        idx += 1; v
    }
    if ((flags and 0x08) != 0) idx += 2  // skip Energy Expended
    val rrIntervalsMs = mutableListOf<Int>()
    while (rrPresent && idx + 1 < value.size) {
        val raw = (value[idx+1].toInt() and 0xFF) shl 8 or (value[idx].toInt() and 0xFF)
        rrIntervalsMs += (raw * 1000) / 1024  // 1/1024s units → ms
        idx += 2
    }
    return HrSample(hr, rrIntervalsMs)
}
```

## Battery life real-world expectation

Manufacturer claims 100h continuous on ANT+ mode with LED off. Real-world v1.5.1 24/7 BLE-streaming + LED active expected **40-60h** = nightly recharge habit required. Disable LED via OnelapFit if user wants longer wear cycles.

## Confidence-level summary

| Claim | Confidence | Verification path |
|---|---|---|
| Standard `0x180D` + `0x2A37` exposed | High | Inferred via Elite HRV / Zwift compatibility (would not be listed if absent) |
| RR intervals in flag bit 4 | High | Same as above; required for HRV apps |
| Standard battery + device info services | Medium | Industry default; verify with nRF Connect on first unit |
| 3 BLE connections work for any 3 clients | Medium-High | Advertised use case (phone+computer+watch) requires it; no contradicting reports |
| Offline buffer + threshold + FW locked to Magene apps | High | Manufacturer documentation explicit |
| RR per-beat accurate (unsmoothed) | Medium-Low | No PubMed validation study; verify empirically against Elite HRV / Kubios |

## Comparison vs Polar H10 / Wahoo TICKR

H613 matches H10/TICKR on the open-stream layer (the only layer that matters for URUJ). H613 has unique features (rechargeable battery, onboard offline storage, LED zone display, threshold buzzer) that don't exist on H10/TICKR — these are app-locked but irrelevant to URUJ. **For URUJ purposes the H613 is functionally equivalent to a TICKR with a buzzer.**

HRV accuracy: no published independent validation. Polar H10 has multiple PubMed validation studies showing ECG-gold-standard accuracy. H613 has none. Validate empirically before trusting RMSSD numbers.

## Sources

- Magene H613 official product page (compatible apps + threshold-alert mechanism)
- Magene H603 predecessor product page (BT 4.2 + same compat list)
- Elite HRV compatible monitors documentation (RR-interval requirement)
- Amazon H613 listing (compat-apps confirmation)
- OnelapFit on Google Play (companion app scope)
- Magene Utility on App Store (FW utility scope)
- DCRainmaker Polar H10 review (openness baseline)
- alexeystn/heart-rate-fpv on GitHub (only public project mentioning Magene HR straps — treats them as standard BLE HR)
- PMC Polar H10 HRV validation study (baseline accuracy reference)
- Marco Altini HRV4Training sensor recommendations (framework for HRV-suitable straps)

## Related
- [[reference_biohacker_lab_vision]] — what we build on top of the strap data
- [[reference_lab_level_uruj]] — rule 8 verbose logging (apply to BLE stream)
- v1.5.0 task #108 — workout BLE integration
- v1.5.1 task — continuous biometric service
- v1.5.2 task #109 — Tier B time-anchored tests
- v1.5.3 task #110 — HRV frequency-domain measures
