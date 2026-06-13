# Audit: every fixed threshold in the readiness + biomarker system (#260)

**Date:** 2026-06-13
**Trigger:** After v0.9.74 replaced the fixed absolute-RMSSD thresholds with a personal-baseline util (`HrvReadiness`), the rider asked for the broader sweep: *are there OTHER places in URUJ where a fixed population number is mis-judging an individual physiology — the same class of bug?*
**Method:** Two parallel read-only audit passes over the entire readiness + biomarker stack (engine, score, all calculators, all display/info dialogs, all trend screens). Every hardcoded numeric threshold catalogued with file:line, what it gates, the stakes (training decision vs display label), and a verdict.

---

## Verdict framework

Every threshold falls into one of three buckets:

- **KEEP — legitimately population/universal.** The metric IS a population comparison by definition (VO2 fitness percentile, HRR1 mortality risk), or the number is a universal physiological/methodology constant (Task-Force frequency bands, Karvonen zone %, sleep-hour norms, Coggan TSB bands, a 1–10 subjective scale). Personalizing these would be *wrong* — it would destroy the meaning of the metric. This is the **large majority**.
- **ALREADY-INDIVIDUALIZED.** Compares to the rider's own baseline/delta already (RHR Δ vs his 7d baseline; HRV ratio + verdict vs his own mean+CV; HRV trend vs his own series).
- **QUESTIONABLE.** A fixed cutoff that, like the HRV bug, could mis-judge a healthy-but-atypical individual. These are the only candidates for change.

**Headline conclusion:** the v0.9.74 HRV reframe was the one genuinely-miscalibrated, always-on, high-stakes case. The rest of the system is sound — almost every other threshold is either inherently population-referenced (and correctly so) or already individualized. Only a handful of QUESTIONABLE items remain, and most are low-stakes or new-user-only.

---

## What v0.9.75 fixes

### `hrv-trending-down` ceiling gap (the one clear structural fix)
`hrv-trending-down` was a SEVERE flag (fires when the 7-night HRV trend slope drops below the noise band) with **no tier ceiling** — the *identical* structural gap the v0.9.74 CAR-ceiling fix closed. A lone falling-HRV-trend with an otherwise-high score could still green-light a hard session, precisely when an early-overreach trend says ease off.

**Fix:** a genuinely FALLING 7-night HRV trend now caps the tier at **ModerateEndurance** (solid aerobic + controlled tempo stay available; max VO2/threshold is held until the trend turns). Deliberately *gentler* than the acute-CAR EasyAerobic cap — a declining trend is a slower, softer signal than an acute cortisol spike, and today's absolute HRV may still be NORMAL vs his own baseline. The flag's severe status (toward the 2-severe→FullRest rule) is unchanged; this only removes the lone-flag "go hard" case. Dormant for the rider today (his slope is +0.07 ms/day = FLAT).

This makes the rule uniform: **every signal serious enough to be a severe flag now also constrains the tier ceiling** (HRV, TSB, RHR, CAR, HRV-trend, subjective).

---

## What is intentionally KEPT (do NOT individualize)

| Threshold | File | Verdict | Why keep |
|---|---|---|---|
| Sleep 7–9h optimal / <5h crashed / >10–12h excess | ReadinessCalculator.scoreSleep + ReadinessCard + BioLabScreen | KEEP | Walker 2017 / Hirshkowitz 2015 NSF consensus — universal for healthy adults; the chronic-recovery wider 7–12h band is already context-aware. |
| TSB bands (+5 fresh / −15 productive / −25 over-trained) | scoreTsb + engine flags + ceiling + TsbTrendScreen | KEEP | Coggan & Allen PMC methodology — TSB is scaled by his own CTL/ATL, so the bands are already personalized via his training history. |
| VO2 Cooper bands (55 elite … 33 below-avg) | VO2MaxCalculator + Vo2TrendScreen | KEEP | Fitness IS a population percentile; "am I top 5%?" is the whole point. |
| HRR1 (≥18 excellent / ≥12 average, Cole NEJM 1999) | HrRecoveryCalculator + Hrr1TrendScreen | KEEP | Peer-reviewed, mortality-validated; display-label only, cited. |
| CAR amplitude/latency tiers (5/10/20/30 bpm; 10/20/40/60 min) | CarDetector | KEEP | Pruessner/Clow/Stalder consensus; HPA physiology, cross-validated 2026-06-11 ([[reference_car_cross_validation_2026_06_11]]). |
| Orthostatic HR-delta + RMSSD-ratio tiers | OrthostaticTestCalculator + OrthostaticTrendScreen | KEEP | Display-label; established autonomic-test norms. |
| Karvonen zone % (0.50–1.00 HRR) | KarvonenZonesCalculator | KEEP | Universal training-methodology definition; the formula is already individualized via his maxHR/RHR. |
| Frequency bands (VLF/LF/HF Hz) + DFA α1 / LF-HF interpretation | FrequencyDomainCalculator + BioLab dialogs + DfaAlpha1TrendScreen | KEEP | Task Force 1996 definitions; physiologically fixed. |
| Subjective 1–10 cutoffs (mood/energy ≤3–4, soreness ≥7–8) | engine flags + ceilings + rationale | KEEP | Universal self-report scale; his own perception is the signal, the gate just automates the tier. |
| Readiness score bands (30/45/55/60/75) | engine baseTier | KEEP | 0–100 composite scale — methodology-defined. |
| RHR/HRV data-quality filters (HR<35 glitch, ≥5 samples) | SleepingRhrCalculator | KEEP | Sanity checks, not stratification. |

| Already-individualized (confirmed working) | File |
|---|---|
| HRV verdict + score cap + ceiling (vs his own mean+CV) | HrvReadiness / ReadinessCalculator / engine — v0.9.74 |
| HRV ratio scoring day 7+ (today / his 7d median) | scoreHrv |
| RHR scoring + flags + ceiling (Δ vs his 7d baseline) | scoreRestingHr / engine |
| HRV trend direction (his own 7-night series) | ReadinessContextBuilder |

---

## Deferred / future (logged, low-stakes — not changed in v0.9.75)

1. **Cosmetic baseline split (mean vs median).** The HRV verdict + BioLab card label use the 7-day **mean** (~13.7 ms, the Plews Ln-rMSSD standard); the HRV row stats sub-line shows the 7-day **median** (~13.8 ms, robust to outlier nights). Both are correct and serve different purposes; they differ by ~0.1 ms. Not a bug — a labelling nicety. **Decision: defer** (pick one to display, or label each as mean/median). Rider explicitly OK to pick up later.
2. **New-user HRV fallback (`<12` severe / `<18` mild, days 1–6 / NO_BASELINE).** Intentionally a *wide absolute sanity floor* for riders with no baseline yet (the rider explicitly wanted this kept). Self-corrects at day 7 when the personal-baseline verdict takes over. A constitutionally-low new user could see a day-1–6 mild flag, but it's low-stakes and transient. **Decision: keep as-is.**
3. **DFA α1 > 1.30 VO2-intensity gate.** "Research-grade, not clinical-validated" (Rogero 2021) per the code's own caveat. Only gates the *forward VO2 estimate* (motivational text), not a hard training block. **Decision: acceptable as advisory; revisit with confidence bands if it ever feels wrong.**
4. **CAR arousal-artifact floor (latency <10 min AND RMSSD-drop <15%).** A heuristic; the 15% drop isn't literature-cited. CAR is cross-validated and the methodology is sensitive — don't touch reactively. **Decision: monitor; future.**
5. **CAR baseline-individualization (v1.0+).** CAR amplitude can drift with chronic stress; a 6-week rolling personal-CAR baseline (parallel to the HRV reframe) is a possible future upgrade. Not needed now.

---

## Method note
Two read-only audit agents swept the codebase in parallel; their findings were cross-checked against the engine/score code already read during v0.9.74. No threshold was changed beyond the single `hrv-trending-down` ceiling. This document is the threshold manifest — the lab-grade record that the system was audited end-to-end and is sound, with each fixed number justified or flagged.
