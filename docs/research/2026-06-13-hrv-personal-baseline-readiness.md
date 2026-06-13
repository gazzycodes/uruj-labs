# Case Study: Personal-Baseline HRV Readiness — replacing fixed absolute RMSSD thresholds

**Date:** 2026-06-13
**Trigger:** The readiness engine flipped the rider's recommendation Zone-2 → Zone-1 because overnight RMSSD crossed a hard-coded `< 15 ms` line by 1 ms (15.1 → 14.1), firing a `hrv-absolute-suppressed` severe flag — despite the rider's stable constitutional baseline being ~13.8 ms. This contradicted what the app's own dashboard already states ("HRV absolute = NOT a gate").
**Method:** Adversarial deep-research harness — 5 search angles, 25 sources fetched, 92 claims extracted, top 25 fact-checked by 3-vote refutation panel. **24/25 confirmed 3-0, 1 killed.** (Run `wf_b48cafd6-4b9` / task `wl2shde68`.)

---

## The question

How does elite endurance sport + the peer-reviewed literature *actually* use HRV to gate training, and what is the sound, individualized, self-recalibrating way to flag "suppressed today" for a fit athlete with a constitutionally low RMSSD baseline (~13–14 ms, CV ~6%) who is a current daily nicotine-salt vaper?

## Verified findings

### 1. Best practice = personal rolling baseline + smallest-worthwhile-change, NOT absolute cutoffs *(confidence: high, 3-0)*
The canonical method (Plews 2016 world-champion rowers; Plews & Laursen 2012 elite triathletes; Schmitt 2015) is:
- **Baseline = 7-day rolling average of *Ln* rMSSD** (log-transform; raw RMSSD is skewed). The 7-day average is explicitly recommended over single daily values to raise signal-to-noise — it detected non-functional overreaching where daily readings did not.
- **"Smallest worthwhile change" (SWC) = 0.5 × the individual's *own* coefficient of variation (CV)**, established from that athlete's own light-week data.
- *Verbatim (Plews 2016):* "The smallest worthwhile change in Ln rMSSD and RHR from baseline was deemed as 0.5 of the individual baseline coefficient of variation (CV)."

> Sources: Plews et al. 2017 (PMID 27736257); Plews & Laursen 2012 (DOI 10.1007/s00421-012-2354-4); Schmitt, Regnard & Millet 2015 (PMID 26635629).

### 2. Absolute RMSSD is NOT comparable between individuals *(high, 3-0)*
- Resting RMSSD is **~52–64% genetically heritable** (Nederend twin study, n=1,060).
- Healthy short-term RMSSD spans **~19–75 ms** (mean 42, SD 15; Shaffer & Ginsberg 2017 / Nunan norms).
- Confounded by detection method (ECG vs PPG), posture, respiration. Intra-individual comparison is valid; **inter-individual is confounded** (Sammito 2024).
- **Conclusion: a fixed cross-individual cutoff like `< 15 ms` is scientifically unsound.** The rider's ~13–14 ms sits just below the population floor and is individually valid.

### 3. Parasympathetic saturation — low RMSSD can mean HIGH vagal tone *(high, 3-0)* — **the key reframe**
When vagal tone is very high (super-fit heart, low resting HR), further parasympathetic modulation *reduces* beat-to-beat variability. So a low/falling RMSSD **with a low resting HR** can reveal the *opposite* of sympathetic fatigue.
- *Plews 2016:* the rower with the **lowest Ln rMSSD also had the lowest resting HR (38)**; "3 of 4 [world-champion] rowers displayed substantial increases in parasympathetic activity despite **decreases** in Ln rMSSD."
- Detector: the **Ln rMSSD : R-R interval correlation** is athlete-specific. Low-RHR athletes show near-zero correlation (saturation-prone); a low reading must be cross-checked against RHR + a combination of indices, never read alone.
- **Direct relevance:** the rider's profile (RHR ~41, elite VO2, strong HR-recovery, low RMSSD) is the textbook saturation-prone pattern. His low RMSSD is plausibly *part* fitness-signature, not purely suppression.

### 4. Nicotine is a confirmed dose-dependent HRV suppressor — including vaping *(high, 3-0)*
- Nicotine acts directly on autonomic ganglia + adrenal medulla → catecholamine release → sympathetic activation + parasympathetic withdrawal.
- Smokers: RMSSD −8.9%, SDNN −9.8%, HF −19.1% per 10 g/day tobacco (CHRIS, n=4,751). Chronic **e-cigarette / nicotine-salt** users show sympathetic activation **comparable to cigarette smokers** (Middlekauff 2020, n=100; Durand 2025). A single 4 mg nicotine dose acutely cuts HRV in non-smokers (RCT, Sjoberg 2011).
- **So the rider's daily vaping is a live, dose-dependent contributor to his low baseline — the biggest movable lever.** Recovery timeline after cessation is *not well established* (cross-sectional shows former smokers recover; longitudinal suggests years). *(confidence: medium on timeline; moot while he is a current vaper.)*

### 5. Validated tools use personal-baseline deviation, auto-recalibrating *(high, 3-0)*
HRV4Training / EliteHRV / Oura / Whoop / Garmin HRV Status all flag "not recovered" by **deviation from the individual's own rolling baseline / normal range**, not a fixed number. Recommended design: maintain a rolling baseline + its CV, flag suppression only when the *smoothed* value drops beyond ~0.5–1× the individual's own CV below baseline, and **confirm with corroborating signals** (RHR, sleep, CAR) rather than acting on any single daily number.

## Honest gaps / limits

- **Norwegian method (Bu / Blummenfelt / Iden) — UNVERIFIED.** The fact-check found *no solid evidence* on whether Bu uses HRV or how. Their method is publicly characterized as lactate-measurement-driven; whether HRV is layered on top was not substantiated. **The design below rests on the academic gold standard (Plews/Buchheit), not on any claim about Bu.**
- **REFUTED claim (1-2):** "respiration/tidal-volume shifts markedly change RMSSD *without* any vagal-tone change." Respiration is a cross-study *comparability* confounder, but the strong "it's just his breathing" decoupling did NOT survive verification — do **not** over-weight breathing as the explanation for his low baseline.
- Source base for the HRV method + saturation is small-n elite studies (Plews n=4, n=2) — canonical and field-standard, but generalizability is inherently sample-limited.
- Nicotine dose-response magnitudes are from combustible tobacco; vaping applicability is by extension (the autonomic *direction* is established; the precise chronic resting-HRV magnitude in vapers is less uniform).

## Design decision (what URUJ builds)

Replace every fixed absolute RMSSD threshold in the readiness engine with a **personal-baseline-relative, self-recalibrating** assessment:

- **Baseline** = rider's own 7-day rolling mean RMSSD (already in `HrvToday.stats.recent7dMeanMs`). Work in Ln rMSSD; for the rider's small CV, raw-CV ≈ SD-of-Ln.
- **Normal range** from his **own CV** (`stats.cvPercent`): MILD if today < baseline − 0.5×SD; SEVERE only if < baseline − ~1.5×SD **AND** corroborated by elevated RHR (saturation guard) — OR an extreme crash (< baseline − 2.5×SD) regardless (illness safety).
- **Saturation guard:** a low reading with a *non-elevated* RHR is treated as benign (likely high vagal tone), never escalated to severe.
- **Self-recalibrating:** as the baseline shifts (nicotine taper / sleep / fitness), the whole band tracks with it — no hard-coded number to grow stale.
- **Single source of truth:** one `HrvReadiness` util consumed by the engine flags, the tier ceiling, the score, the unlock-day estimates, and the display — so they can never disagree.

## Implementation map (all touchpoints — 4 files)

| File | Location | Old (absolute) | New (personal-baseline) |
|---|---|---|---|
| `ReadinessCalculator` | `scoreHrv` absoluteCap | 35/50/70/85 at <12/<15/<20/<25 | cap derived from `HrvReadiness` verdict/deviation |
| `ReadinessCalculator` | sleep-score `hrvSuppressed<18` | absolute gate (benign — widens sleep window) | baseline-relative (low priority) |
| `RuleBasedReasoner` | `hrv-crashed` (<12 fallback) | absolute | extreme deviation vs baseline |
| `RuleBasedReasoner` | `hrv-absolute-suppressed` (<15) | absolute severe | `HrvReadiness` SEVERE |
| `RuleBasedReasoner` | `hrv-low` (<18 mild) | absolute mild | `HrvReadiness` MILD |
| `RuleBasedReasoner` | `computeAbsoluteCeiling` HRV ladder | <12/15/20/25 | ceiling from verdict |
| `RuleBasedReasoner` | `computeTierUnlockDays` targets | reach 18/25/30 ms absolute | gate on TSB + baseline-relative-NORMAL, not an unreachable ms target |
| `RuleBasedReasoner` | `buildRationale` HRV text | <12/<18 | verdict label |
| `ReadinessCard` | HRV row + ⓘ | "suppressed · cap 50" | "vs YOUR baseline" + saturation/nicotine explainer |
| `BioLabScreen`/`InfoDialogs` | "Below athletic average" | population framing | baseline-relative |

## Implementation status — SHIPPED v0.9.74 (2026-06-13)

All touchpoints wired to the single-source-of-truth `HrvReadiness` util (works in
Ln rMSSD vs his own 7d mean + CV; MILD at −0.5×CV, SEVERE at −1.5×CV + RHR
corroboration or −2.5×CV extreme; parasympathetic-saturation guard; NO_BASELINE
wide-absolute fallback for new users < 7 nights).

- **Engine** (`RuleBasedReasoner`): the fixed `hrv-crashed<12` / `hrv-absolute-suppressed<15` / `hrv-low<18` flags → one baseline-relative `hrv-suppressed` (SEVERE) + `hrv-low` (MILD), NO_BASELINE keeps a wide `<12`/`<18` floor. `computeAbsoluteCeiling` HRV ladder → verdict ceiling (NORMAL no-cap / MILD→EasyAerobic / SEVERE→ActiveRecovery). `computeTierUnlockDays` dropped the unreachable 18/25/30 ms targets → one baseline-relative intensity estimate gated on HRV-NORMAL + TSB (+ DFA α1 for VO2). `buildRationale` HRV driver → baseline-relative copy.
- **Score** (`ReadinessCalculator`): `scoreHrv` absoluteCap (35/50/70/85) → verdict cap (NORMAL 100 / MILD 85 / SEVERE 50); the chronic-recovery sleep gate `hrvSuppressed<18` → baseline-relative. Baseline mean + CV + samplesUsed threaded through `ReadinessInputs`, computed in `ReadinessRepository.gatherInputsWithSource` over the same disk-preferred history the context builder uses → score and engine derive identical verdicts.
- **Display**: `ReadinessCard` HRV ⓘ + `BioLabScreen` Autonomic card + `BioLabInfoDialogs` reframed — population ranges relabelled "general orientation, NOT your gate", personal-baseline + saturation + genetics explainer added; BioLab card now calls `HrvReadiness` (baseline mean + CV threaded into `BioLabSnapshot`) so a constitutionally-low value at his own baseline reads green, not red "below athletic average".

### Ripple fix (required, not optional) — CAR tier ceiling
Removing the miscalibrated HRV severe flag exposed a latent gap: CAR had a *severe flag* but **no tier ceiling** (unlike HRV/TSB/RHR/subjective), and a single severe flag with a high score doesn't cap the tier. So on the trigger day the naive fix would have flipped the recommendation from REST → **GO HARD** (high-cortisol morning, score ~82, lone CAR severe, no ceiling). Fixed by giving CAR a ceiling like every other marker: **EXAGGERATED → EasyAerobic, BLUNTED → ActiveRecovery** (recent ≤24h only). CAR is a validated genuine signal (cross-validated 2026-06-11), so it *should* gate the tier. (`hrv-trending-down` is the same latent class — flagged for the #260 audit, not changed here.)

### Device validation (HD1901, his real data, 2026-06-13)
`[v0.9.74] score=82 · tier=EasyAerobic (was HardGreenLight · ceiling EasyAerobic) · severe=1[car-exaggerated] · hrv=14.1ms [NORMAL +0.54SD base=13.7] · tsb=-8 · rhrΔ=0 · car=EXAGGERATED`. HRV 14.1 at his ~13.7 baseline reads NORMAL (+0.54 SD), fires no flag, imposes no cap; CAR-exaggerated correctly caps the tier to EasyAerobic — matching the independent coaching call. `compileDebugKotlin` clean; 12/12 `HrvReadinessTest` cases pass; the v0.9.41 genuine-crash safety net (#192) verified preserved (an 11 ms reading is SEVERE regardless of RHR).

## Citations
Plews et al. 2017 *Int J Sports Physiol Perform* (PMID 27736257) · Plews & Laursen 2012 *Eur J Appl Physiol* (DOI 10.1007/s00421-012-2354-4) · Schmitt et al. 2015 *Front Physiol* 6:343 (PMID 26635629) · Shaffer & Ginsberg 2017 *Front Public Health* 5:258 · Sammito et al. 2024 *Front Physiol* (PMC11333334) · Nederend et al. 2016 *Int J Psychophysiol* (PMC5075267) · Quante et al. 2019 CHRIS *PLOS ONE* (PMC6456196) · Middlekauff et al. 2020 *AJP-Heart* (PMID 32559135) · Sjoberg & Saint 2011 *Nicotine Tob Res* 13(5):369 · Durand et al. 2025 *Antioxidants* 14(12):1516 · Altini / HRV4Training (practitioner corroboration).
