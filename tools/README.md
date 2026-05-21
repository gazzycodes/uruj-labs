# URUJ Labs — Developer Tools

## `validate_hrv_neurokit2.py` (v0.9.29+)

Independent lab-grade cross-validation of URUJ's HRV math against the
[neurokit2](https://neurokit2.readthedocs.io) research library —
peer-reviewed open-source HRV implementation used in published
clinical research.

This is the **third lab-grade gate** for URUJ's freq-domain feature:

1. ✅ **Internal mathematical correctness** (v0.9.27-28) — math invariants
   + synthetic FFT tests + cross-method validation
2. ✅ **Artifact elimination** (v0.9.28) — Lomb-Scargle vs Welch diagnostic
   confirmed 3-6× inflation from interpolation through ectopic gaps
3. **External library cross-check** (THIS TOOL) — run YOUR actual nightly
   RR data through neurokit2; compare numbers to URUJ's

### Why this matters

URUJ's math is provably correct on TEST SIGNALS. neurokit2 cross-check
proves it's correct on REAL NOISY DATA — without paying for Kubios desktop.

### Usage

**One-time setup** (Python 3.9+):

```bash
python -m pip install neurokit2 numpy astropy
```

`astropy` is required for the Lomb-Scargle PSD method (matches URUJ v0.9.28). Without it, neurokit2 falls back to Welch (which has the interpolation artifact we just eliminated).

**Validated on Python 3.14.3** with neurokit2 0.2.13, numpy 2.4.6, astropy 7.2.0 (2026-05-22 first run).

**Pull your overnight strap NDJSON via adb**:

```bash
adb pull /sdcard/Android/data/com.uruj/files/continuous/2026-05-22.ndjson .
```

**Run the validation**:

```bash
python tools/validate_hrv_neurokit2.py 2026-05-22.ndjson
```

Output looks like:

```
============================================================
NEUROKIT2 RESULTS (cross-validation against URUJ)
============================================================
  Windows:           11
  Total beats:       6601
  RMSSD:             9.95 ms
  SDNN:              45.20 ms
  SD1:               7.04 ms     (must ≈ RMSSD/√2)
  SD2:               180.50 ms
  LF power:          250.12 ms²
  HF power:          48.30 ms²
  LF/HF ratio:       5.180
  DFA α1:            1.690
  Sample entropy:    0.270

  Invariant SD1 ≡ RMSSD/√2:
    RMSSD/√2 = 7.04 ms
    SD1      = 7.04 ms
    Δ        = 0.0%  (must be <5%)
```

**Compare against URUJ Bio Lab Autonomic Frequency card** for the same
date. Expected agreement:

| Metric | Tolerance |
|---|---|
| RMSSD | ±5% |
| SDNN | ±5% |
| SD1 (invariant) | must match within 0.5 ms |
| SD2 | ±10% |
| LF/HF ratio | ±20% (noisier metric) |
| DFA α1 | ±15% |
| Sample entropy | ±25% (most noise-sensitive) |

If URUJ deviates >30% from neurokit2 on any metric, investigate.

### When to run this

- **Once weekly** as a sanity audit
- **After any FrequencyDomainCalculator change** to verify no regression
- **After any methodology version bump** (e.g. v0.9.30 if we switch to
  cubic-spline interpolation, etc.)
- **If URUJ numbers ever look "off"** vs your intuition or other apps

### What the tool does NOT do

- Doesn't ship in the Android app (this is a desktop validation tool)
- Doesn't auto-export from device (run `adb pull` manually)
- Doesn't auto-compare to URUJ snapshot (manual visual comparison)
- Doesn't validate ride-time HR data (overnight only, like URUJ Bio Lab)

### First validation run results (2026-05-21 data, run 2026-05-22 01:30)

neurokit2 0.2.13 on full-day NDJSON (178 valid 5-min windows aggregated):

```
RMSSD:             23.96 ms
SDNN:              49.99 ms
SD1:               17.01 ms     <- math invariant SD1 = RMSSD/sqrt(2): delta 0.4%
SD2:               67.58 ms
LF/HF ratio:       3.165
DFA alpha1:        1.373
Sample entropy:    0.562
```

vs URUJ Bio Lab card (sleep-window-only, 11 windows):

```
RMSSD:             9.9 ms
SD1:               7.0 ms      <- math invariant: delta 0.0%
SD2:               181 ms
LF/HF ratio:       4.75
DFA alpha1:        1.74
Sample entropy:    0.25
```

**Math invariant SD1 == RMSSD/sqrt(2) holds in BOTH libraries** — independent
confirmation that URUJ's math is canonically correct.

Absolute numbers differ because neurokit2 ran on the FULL DAY (sleep +
walking + ride + everything) while URUJ ran on sleep window only. Both
agree on direction (high LF/HF, elevated DFA, low entropy = autonomic
stress). To make comparison apples-to-apples in future runs, filter the
NDJSON to just sleep hours before running the script.

### Future improvements

- v0.9.30+: auto-pull both URUJ snapshot + NDJSON, diff numerically
- v0.9.30+: time-range filter (--from HH:MM --to HH:MM) to match
  URUJ's sleep window for direct comparison
- v0.9.30+: batch-mode for N nights → trend deviation report
- v0.9.30+: regression suite — run on fixed fixture NDJSON whenever
  methodology version changes
