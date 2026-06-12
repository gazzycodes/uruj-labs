package com.uruj.ui.biolab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.BioLabSnapshot
import com.uruj.data.TsbSnapshot
import com.uruj.domain.CarInterpretation
import com.uruj.domain.CarResult
import com.uruj.power.FrequencyDomainCalculator
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import kotlin.math.roundToInt

/**
 * v0.9.7 — info tooltip dialogs for every Bio Lab card. Same pattern as
 * Readiness card's [com.uruj.ui.checklist.ReadinessCard] InfoDialog: tap
 * the ⓘ icon → modal with sections (what it is, why cyclists care, where
 * to live, honest caveats, FOR YOU RIGHT NOW). The "FOR YOU RIGHT NOW"
 * section reads the rider's current data and tailors interpretation
 * dynamically — so the dialog is educational AND specific to today.
 *
 * Anti-pattern avoided: generic Wikipedia-style explanations with no
 * reference to the user's actual number. The rider should see "what
 * does MY 67.7 mL/kg/min mean" not "what is VO2 in general."
 *
 * Lab-grade rule 8 (verbose) — every metric self-documents from inside
 * the UI. No external manual needed.
 */

@Composable
internal fun InfoDialogShell(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title.uppercase(),
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column { body() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "GOT IT",
                    color = UrujAccent,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        },
        containerColor = UrujSurface,
    )
}

@Composable
internal fun InfoSection(heading: String, body: String) {
    Text(
        heading.uppercase(),
        color = UrujAccent,
        fontWeight = FontWeight.Black,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text(body, color = UrujText, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun YouSection(body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(
                "FOR YOU RIGHT NOW",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(body, color = UrujText, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// region Training State (TSB)

@Composable
fun TrainingStateInfoDialog(tsb: TsbSnapshot?, onDismiss: () -> Unit) {
    InfoDialogShell("Training State (TSB)", onDismiss) {
        InfoSection(
            "What it is",
            "TSB = Training Stress Balance, Coggan's fitness/fatigue model since 2003. " +
                "Two batteries:\n\n" +
                "🔋 CTL (fitness) — 42-day exponential weighted average of TSS. " +
                "Charges slowly when you train consistently.\n\n" +
                "⚡ ATL (fatigue) — 7-day exponential weighted average of TSS. " +
                "Drains in a week of rest.\n\n" +
                "TSB = CTL − ATL. Just the balance.",
        )
        InfoSection(
            "Why cyclists care",
            "Tells you whether to push hard or rest TODAY based on the cumulative " +
                "load of the last 6 weeks. The single best predictor of how a hard " +
                "session will feel — better than how you slept, better than HRV alone.",
        )
        InfoSection(
            "Where to live",
            "+5 to +15  → RACE READY (peak fresh, race day)\n" +
                "−5 to +5   → BALANCED (normal training week)\n" +
                "−10 to −15 → PRODUCTIVE FATIGUE (pros live here — adaptation zone)\n" +
                "−15 to −25 → SIGNIFICANT FATIGUE (heavy block, watch RHR + sleep)\n" +
                "Below −25  → OVER-TRAINED (rest mandatory, deeper than load alone)",
        )
        InfoSection(
            "How it auto-updates (multi-sport)",
            "Every URUJ cycling ride → hrTSS = IF² × hours × 100, where IF comes " +
                "from your HR-Reserve fraction (Banister-style). URUJ has no power " +
                "meter, so MEASURED heart rate beats physics-estimated watts (which " +
                "inflate on stop-start city surges + GPS noise).\n\n" +
                "Every Samsung-tracked run / HIIT / strength session → the SAME " +
                "hrTSS from HR Reserve. One honest method across every sport — " +
                "cycling, running + dumbbells all count the same way.\n\n" +
                "Cycling sessions in Samsung that overlap a URUJ ride are skipped " +
                "(no double-count). Bazaar walks + light activity won't trigger " +
                "session detection unless Samsung Health flags them.\n\n" +
                "Every calendar day at midnight, both batteries leak — fatigue drains " +
                "~14%/rest day, fitness ~2.4%/rest day. TSB naturally rises with rest.",
        )
        // v0.9.21 — content extracted to TsbInfoContent.kt so the Readiness
        // card's TRAINING LOAD ⓘ renders identical sections from one source.
        InfoSection("The real signal — ATL:CTL ratio", tsbAtlCtlRatioBody())
        InfoSection("Build CTL first — the prescription", tsbBuildCtlPrescriptionBody())
        if (tsb != null) {
            val tier = when {
                tsb.tsb >= 5f -> "FRESH (+${tsb.tsb.roundToInt()}) — race-ready zone"
                tsb.tsb >= -5f -> "BALANCED (${tsb.tsb.roundToInt()}) — normal training week"
                tsb.tsb >= -15f -> "PRODUCTIVE FATIGUE (${tsb.tsb.roundToInt()}) — adaptation zone, pros live here"
                tsb.tsb >= -25f -> "SIGNIFICANT FATIGUE (${tsb.tsb.roundToInt()}) — heavy block, watch markers"
                else -> "OVER-TRAINED (${tsb.tsb.roundToInt()}) — rest mandatory, body is signaling"
            }
            val action = when {
                tsb.tsb >= 5f -> "All cleared for hard sessions or racing."
                tsb.tsb >= -5f -> "Mid-tempo / Z3 OK if you feel good."
                tsb.tsb >= -15f -> "Continue planned training. Don't dig deeper without recovery markers."
                tsb.tsb >= -25f -> "Easier sessions only. Add a rest day this week."
                else -> "REST DAY. Eat to maintenance, hydrate, light walk only. Do not ride."
            }
            YouSection(
                tsbYouSectionBody(
                    tierLabel = tier,
                    ctl = tsb.ctl,
                    atl = tsb.atl,
                    totalLoad42d = tsb.totalLoad42d,
                    action = action,
                )
            )
        } else {
            YouSection(
                "TSB unlocks at 2+ rides + RHR baseline. Once snapshots accumulate, " +
                    "today's value + tier + action will show here."
            )
        }
    }
}

// endregion

// region VO2 Max

@Composable
fun Vo2MaxInfoDialog(s: BioLabSnapshot, onDismiss: () -> Unit) {
    InfoDialogShell("VO₂ Max — aerobic capacity", onDismiss) {
        InfoSection(
            "What it is",
            "Maximum rate at which your body can utilize oxygen during all-out exercise, " +
                "in mL of O₂ per kg of body weight per minute. The single strongest " +
                "predictor of endurance performance + all-cause mortality. Lab CPET " +
                "(treadmill or bike with mask) is the gold standard; URUJ uses the " +
                "Uth-Sørensen field formula calibrated against lab.",
        )
        InfoSection(
            "Why cyclists care",
            "Determines your aerobic ceiling — the engine size you're working with. " +
                "Trainable, but slowly: ~5-15% improvement over 6-12 months of " +
                "structured Z2/Z5 training. Worth tracking monthly, not daily.",
        )
        InfoSection(
            "Cooper classification (male, 20-29)",
            "60+ mL/kg/min  Elite (top 5%)\n" +
                "52–60          Excellent\n" +
                "47–52          Good\n" +
                "42–47          Above average\n" +
                "<42            Average or below\n\n" +
                "Adjust ~3 mL/kg/min downward per decade for older ranges.",
        )
        InfoSection(
            "URUJ methodology — Uth-Sørensen formula",
            "VO₂ ≈ 15.3 × (MaxHR / RestingHR). Validated against lab CPET within " +
                "±10% for trained adults. Cheap, repeatable, no special equipment.\n\n" +
                "Caveats:\n" +
                "• Sensitive to MaxHR accuracy — needs a real all-out effort to confirm\n" +
                "• Athletic RHR drops with fitness → VO₂ goes up. Real adaptation.\n" +
                "• Acute illness or stress can crash RHR briefly → inflates estimate\n" +
                "• Not as precise as lab CPET, but the TREND is what matters",
        )
        InfoSection(
            "Future: power-based cross-check",
            "When you set a real FTP (20-min all-out test), URUJ also computes a " +
                "power-based VO₂ estimate (FTP/kg × 13.5). Two independent methods → " +
                "honest cross-validation. Currently shown only if FTP isn't at the " +
                "default 200W placeholder.",
        )
        val urujVo2 = s.vo2MaxConsensus
        val samsungVo2 = s.vo2MaxFromSamsung
        if (urujVo2 != null || samsungVo2 != null) {
            val urujLine = if (urujVo2 != null) "URUJ Uth-Sørensen: ${"%.1f".format(urujVo2)} mL/kg/min" else null
            val samsungLine = if (samsungVo2 != null) "Samsung Health: ${"%.1f".format(samsungVo2)} mL/kg/min" else null
            val classification = s.vo2MaxClassification
            val action = when {
                urujVo2 != null && urujVo2 >= 60f ->
                    "Elite range. Maintenance is the win — already in the top 5%."
                urujVo2 != null && urujVo2 >= 52f ->
                    "Excellent. Push the long Z2 rides + occasional Z5 intervals to break into elite."
                urujVo2 != null && urujVo2 >= 47f ->
                    "Good range. Structured 6-12 month block (Z2 base + Z5 intervals) can push you to Excellent."
                urujVo2 != null && urujVo2 >= 42f ->
                    "Above average. Big runway — consistent Z2 base will move this number months."
                else -> "Build Z2 base time. VO₂ tracks total aerobic work over months, not days."
            }
            YouSection(
                listOfNotNull(urujLine, samsungLine).joinToString("\n") +
                    "\nClassification: $classification\n\n$action"
            )
        } else {
            YouSection(
                "VO₂ unlocks once URUJ has your MaxHR + Athletic RHR. Ride hard " +
                    "enough to spike HR past your current max + wear band overnight " +
                    "for RHR baseline."
            )
        }
    }
}

// endregion

// region HRR1

@Composable
fun Hrr1InfoDialog(s: BioLabSnapshot, onDismiss: () -> Unit) {
    InfoDialogShell("HR Recovery (HRR1)", onDismiss) {
        InfoSection(
            "What it is",
            "Heart Rate Recovery at 1 minute post-effort. Drop in HR from peak (during " +
                "hard effort) to 60 seconds after stopping. Measures parasympathetic " +
                "reactivation — how fast your vagal tone takes over from sympathetic " +
                "drive.",
        )
        InfoSection(
            "Why it matters",
            "Cole et al. NEJM 1999: HRR1 is a STRONGER predictor of all-cause + " +
                "cardiovascular mortality than VO₂ max alone. Cardiology-grade marker, " +
                "trivially measured. Same physiology Polar / Whoop / Garmin track — " +
                "URUJ shows you the per-reading data and trend they obscure.",
        )
        InfoSection(
            "Cole NEJM 1999 + URUJ overlay",
            "≥22 bpm  Elite range (URUJ athletic overlay)\n" +
                "≥18 bpm  Excellent (Cole threshold)\n" +
                "12–17 bpm Average (Cole)\n" +
                "<12 bpm  Elevated CV risk (Cole) — investigate if persistent",
        )
        InfoSection(
            "URUJ methodology",
            "Captured automatically from every qualifying ride (peak HR ≥120 bpm). " +
                "Drop = peak HR − HR at 60s after session end. URUJ adaptation " +
                "(v0.7.9): when multiple HR samples land in the recovery window, pick " +
                "the one CLOSEST to 60s (not the minimum) — closer to Cole's original " +
                "methodology than naive 'min of 10-min window' implementations.\n\n" +
                "Strap-precedence: when chest strap covered the window, BLE NDJSON " +
                "samples drive the calculation. Band fallback when strap absent. " +
                "Source labeled per reading.",
        )
        InfoSection(
            "Caveats",
            "• Single readings are noisy — track the median + trend over 30+ days\n" +
                "• Cool-down protocol matters — standing still vs spinning easy gives " +
                "different numbers. Be consistent.\n" +
                "• Hot weather + dehydration suppress HRR1 (skin blood-flow steal)\n" +
                "• Beta-blockers + some antidepressants depress autonomic response",
        )
        val median = s.hrr1Median
        if (median != null) {
            val tier = when {
                median >= 22 -> "ELITE (${median} bpm)"
                median >= 18 -> "EXCELLENT (${median} bpm) — Cole top tier"
                median >= 12 -> "AVERAGE (${median} bpm) — Cole healthy range"
                else -> "ELEVATED CV RISK (${median} bpm) — investigate if persistent"
            }
            val action = when {
                median >= 18 -> "Strong vagal reactivation. Sign of well-trained autonomic system."
                median >= 12 -> "In Cole's normal range. Trend is what matters — watch for drops."
                else -> "Consult a physician if this persists across multiple weeks. Could be hydration, sleep, illness, or genuine autonomic concern."
            }
            YouSection(
                "$tier\nClassification: ${s.hrr1Classification ?: "—"}\n" +
                    "Samples: ${s.hrr1SampleCount} rides feeding the median\n\n$action"
            )
        } else {
            YouSection(
                "HRR1 unlocks at first qualifying ride (peak HR ≥120 bpm). Wear strap " +
                    "or band during effort + 60s after."
            )
        }
    }
}

// endregion

// region Heart Rate / Athletic RHR

@Composable
fun HeartRateInfoDialog(s: BioLabSnapshot, onDismiss: () -> Unit) {
    InfoDialogShell("Heart Rate metrics", onDismiss) {
        InfoSection(
            "What's here",
            "Three numbers cycling cares about:\n\n" +
                "• MAX HR — your aerobic ceiling. Sets the top of every zone\n" +
                "• HR RESERVE — Max − Resting. Powers Karvonen zone math\n" +
                "• ATHLETIC RHR — sleep-window resting HR. Long-arc fitness marker",
        )
        InfoSection(
            "Max HR — how URUJ knows yours",
            "Auto-detected from your hardest tracked effort. URUJ watches every ride: " +
                "if you spike past the current Max + 1 bpm with sustained effort " +
                "(not noise), the value auto-bumps. Otherwise URUJ falls back to the " +
                "220−age estimate (±10-12 bpm; only as good as the formula).\n\n" +
                "Pro tip: do one 5-minute all-out hill repeat early in your training " +
                "block. Locks Max HR for zones + Karvonen math + VO₂ estimate. " +
                "Without this, all your zones are educated guesses.",
        )
        InfoSection(
            "Athletic RHR — why sleep-window matters",
            "Samsung's 'Daily RHR' bounces around based on activity level + when you " +
                "stand up. URUJ uses ONLY sleep-window minima — same protocol every " +
                "night, activity-independent. Drops as you get fitter, spikes 5+ bpm " +
                "early in illness / over-reach / dehydration.\n\n" +
                "Endurance-athlete norms (males):\n" +
                "  40–50 elite\n" +
                "  50–60 trained\n" +
                "  60–70 recreational fit\n" +
                "  70+   untrained",
        )
        InfoSection(
            "HR Reserve = Karvonen anchor",
            "Karvonen zones are computed as percentages of your HR Reserve, not of " +
                "your Max. More accurate than %-of-max because it accounts for your " +
                "specific resting HR. See the KARVONEN ZONES card below.",
        )
        val rhr = s.restingHrBpm
        val maxHr = s.maxHrBpm
        val rhrTier = when {
            rhr == null -> "Awaiting baseline"
            rhr <= 50 -> "ELITE ($rhr bpm) — top-tier endurance physiology"
            rhr <= 60 -> "TRAINED ($rhr bpm) — clearly adapted to aerobic work"
            rhr <= 70 -> "RECREATIONAL FIT ($rhr bpm) — solid baseline"
            else -> "UNTRAINED ($rhr bpm) — long runway, Z2 base will move this"
        }
        val maxHrConfidence = if (s.maxHrAutoDetected) "auto-detected (high confidence)"
        else "220−age estimate (±10-12 bpm — do one 5-min all-out effort to lock)"
        YouSection(
            "Max HR: $maxHr bpm — $maxHrConfidence\n" +
                "30d Peak: ${s.highestHr30d ?: "—"} bpm\n" +
                "HR Reserve: ${if (rhr != null) maxHr - rhr else "—"} bpm\n" +
                "Athletic RHR: $rhrTier"
        )
    }
}

// endregion

// region Karvonen Zones

@Composable
fun KarvonenInfoDialog(zones: KarvonenZonesCalculator.Result, onDismiss: () -> Unit) {
    InfoDialogShell("Karvonen HR Zones", onDismiss) {
        InfoSection(
            "What it is",
            "Heart-rate training zones computed as percentages of your HR Reserve " +
                "(Max − Resting), NOT as percentages of your Max alone. Karvonen " +
                "formula (1957): targetHR = (HRReserve × intensity%) + RestingHR.",
        )
        InfoSection(
            "Why Karvonen > %-of-max",
            "%-of-max assumes everyone with the same Max has the same effort thresholds. " +
                "Wrong — your trained Athletic RHR moves the floor up. Karvonen " +
                "anchors zones to YOUR personal reserve, so Z2 is genuinely your aerobic " +
                "base, not someone else's tempo work.",
        )
        InfoSection(
            "Zone meanings",
            "Z1 RECOVERY — easy spin, < 60% HRR — promotes mitochondrial recovery\n" +
                "Z2 ENDURANCE — 60-70% HRR — fat oxidation + aerobic base. Most miles live here.\n" +
                "Z3 TEMPO — 70-80% HRR — 'gray zone' — productive in moderation, costly in excess\n" +
                "Z4 THRESHOLD — 80-90% HRR — lactate threshold work. Hard-day sessions.\n" +
                "Z5 VO₂ — 90-100% HRR — 3-8 min intervals, builds aerobic ceiling",
        )
        InfoSection(
            "Polarized 80/20",
            "Norwegian endurance model (Seiler/Stöggl/Blummenfelt): 80% easy " +
                "(Z1+Z2), 5% gray (Z3), 15% hard (Z4+Z5). URUJ's TIZ card surfaces " +
                "your compliance per ride. Pyramidal-leaning across weeks → early " +
                "warning that you're spending too much in the gray middle.",
        )
        YouSection(
            "Your HR Reserve: ${zones.hrReserve} bpm\n\n" +
                zones.zones.joinToString("\n") { z ->
                    "Z${z.number} ${z.name}: ${z.lowerBpm}–${z.upperBpm} bpm"
                }
        )
    }
}

// endregion

// region Autonomic Health (HRV)

@Composable
fun AutonomicInfoDialog(s: BioLabSnapshot, onDismiss: () -> Unit) {
    InfoDialogShell("Autonomic Health (HRV)", onDismiss) {
        InfoSection(
            "What it is",
            "RMSSD — Root Mean Square of Successive Differences. Measures beat-to-" +
                "beat variation in your heart rate. High RMSSD = well-recovered + " +
                "parasympathetic-dominant. Low RMSSD = stressed / sympathetic-driven / " +
                "under-recovered.",
        )
        InfoSection(
            "Why it matters",
            "The single best non-invasive marker of vagal (parasympathetic) tone. " +
                "Predicts how you'll respond to training BEFORE you ride, catches " +
                "illness onset 1-3 days early, quantifies overtraining patterns. " +
                "Whoop, Oura, EliteHRV, Garmin all built businesses on this metric.",
        )
        InfoSection(
            "URUJ methodology — overnight 5-min windowed",
            "Real ECG-precision RR intervals from your Magene H613 chest strap, " +
                "captured 24/7 via BiometricService NDJSON.\n\n" +
                "Algorithm:\n" +
                "1. Slice overnight sleep window from Samsung's SleepSessionRecord\n" +
                "2. Reconstruct beat timestamps from each BLE notification's RR array\n" +
                "3. 5-minute non-overlapping windows (Task Force 1996 standard)\n" +
                "4. Per window: filter physiological 300-2000 ms RR, reject pairs " +
                "with >20% delta (ectopic beats), require ≥30 clean consecutive-pair " +
                "diffs\n" +
                "5. Compute RMSSD per window, take MEDIAN across valid windows\n\n" +
                "Same methodology Polar / Kubios / EliteHRV / HRV4Training use. Not a " +
                "PPG proxy, not a std-dev hack.",
        )
        InfoSection(
            "Reference ranges (athletic norms)",
            "80+ ms   Elite parasympathetic dominance\n" +
                "50–80    Trained athlete range\n" +
                "30–50    Average healthy adult\n" +
                "20–30    Below athletic average\n" +
                "<20      Below athletic average — check trend",
        )
        InfoSection(
            "Why this differs from morning eHRV readings",
            "Apps like Elite HRV / HRV4Training use PACED breathing (~5 breaths/min) " +
                "for morning seated reads — maximizes Respiratory Sinus Arrhythmia and " +
                "inflates RMSSD 1.5-3× over natural breathing for the SAME body.\n\n" +
                "URUJ measures NATURAL overnight autonomic state. eHRV measures " +
                "MAXIMUM vagal capacity. Both valid, different questions. eHRV " +
                "≈ 25 ms paced ↔ URUJ ≈ 12 ms natural is the same person, " +
                "different protocol.",
        )
        InfoSection(
            "Baseline building period",
            "Nights 1-6: ABSOLUTE-tier scoring. A real 60 ms scores well even without " +
                "personal history.\n\n" +
                "Day 7+: RATIO vs personal 7-day median. Catches subtle changes vs " +
                "YOUR normal (a 50 ms HRV that's normal for you can be a warning for " +
                "someone whose baseline is 80 ms).",
        )
        val rmssd = s.autonomicRmssdMs
        if (rmssd != null) {
            val tier = when {
                rmssd >= 80f -> "ELITE (${"%.1f".format(rmssd)} ms) — parasympathetic dominance"
                rmssd >= 50f -> "TRAINED (${"%.1f".format(rmssd)} ms) — athletic range"
                rmssd >= 30f -> "AVERAGE HEALTHY (${"%.1f".format(rmssd)} ms)"
                rmssd >= 20f -> "BELOW ATHLETIC (${"%.1f".format(rmssd)} ms)"
                else -> "BELOW ATHLETIC (${"%.1f".format(rmssd)} ms) — check trend over a week"
            }
            YouSection(
                "$tier\n\n" +
                    "Single-night readings vary 20-30%. Trust trend over single-point " +
                    "interpretation. See SEE TREND for the chart."
            )
        } else {
            YouSection(
                "HRV unlocks once 24/7 monitoring captures an overnight window with " +
                    "≥3 valid 5-min RMSSD windows. Wear strap to bed."
            )
        }
    }
}

// endregion

// region Autonomic Frequency-Domain (v0.9.25)

/**
 * v0.9.25 — ⓘ dialog for the new Autonomic Frequency card. Explains each
 * metric ELI10 + honest caveats (LF/HF debunked-mapping, DFA α1 LT1 future
 * feature, sample entropy interpretation). Per lab-level rules 3 + 4 (no
 * fake numbers, methodology disclosed). Cited refs: Task Force 1996,
 * Heathers 2014, Hayano 2019, Peng 1994, Rogero 2021, Richman & Moorman 2000.
 */
@Composable
fun FrequencyDomainInfoDialog(
    fd: FrequencyDomainCalculator.FrequencyDomainHrv,
    onDismiss: () -> Unit,
) {
    InfoDialogShell("Autonomic Frequency (LF/HF + DFA α1)", onDismiss) {
        InfoSection(
            "What this is — explained simply",
            "While RMSSD (overnight HRV) tells you HOW MUCH your heart varies " +
                "beat-to-beat, frequency-domain analysis tells you the PATTERN " +
                "of those variations.\n\n" +
                "Think of it like music: RMSSD = overall volume, frequency-domain " +
                "= which notes (frequencies) are loudest.\n\n" +
                "Your autonomic nervous system creates HRV at different speeds:\n" +
                "• Fast oscillations (HF) — driven by breathing rhythm (parasympathetic)\n" +
                "• Slower oscillations (LF) — driven by baroreflex (mixed sympathetic + parasympathetic)\n" +
                "• Very slow (VLF) — thermoregulation, hormones\n\n" +
                "We split your nightly HRV into these bands via FFT (Fast Fourier " +
                "Transform — same math astronomers use to find planetary cycles in starlight).",
        )
        InfoSection(
            "LF/HF ratio — the headline number",
            "The single most-discussed HRV metric beyond RMSSD. Used by Kubios, " +
                "Polar, Garmin, Whoop as a \"stress index.\"\n\n" +
                "Classical interpretation:\n" +
                "  < 1.0 → parasympathetic dominant (recovery zone)\n" +
                "  1.0 – 2.0 → balanced (normal training state)\n" +
                "  > 2.0 → sympathetic dominant (stress / fatigue / overload)\n\n" +
                "⚠️  HONEST CAVEAT: The clean \"LF = sympathetic, HF = parasympathetic\" " +
                "mapping is PARTIALLY DEBUNKED in modern research. LF actually " +
                "contains BOTH sympathetic AND parasympathetic activity weighted via " +
                "baroreflex (Heathers 2014, Hayano 2019).\n\n" +
                "Why URUJ still shows it: the number STILL correlates with stress " +
                "states empirically. It's just not for the mechanistic reason " +
                "originally claimed. We expose it WITH the caveat — lab-level rule 4 " +
                "(no fake numbers, no hidden assumptions).",
        )
        InfoSection(
            "DFA α1 — the athletic-tier killer feature",
            "Detrended Fluctuation Analysis (Peng et al. 1994). Quantifies the " +
                "fractal scaling of your heart rhythm at short scales (4-16 beats).\n\n" +
                "Healthy fractal scaling: α1 ≈ 1.0 (\"pink noise\" — a sweet spot " +
                "between pure randomness and rigid regularity).\n\n" +
                "Where to live:\n" +
                "  0.85 – 1.15 → healthy fractal organization\n" +
                "  0.75 – 0.85 → mild fatigue / Z3 territory\n" +
                "  0.50 – 0.75 → above Aerobic Threshold (LT1) — sympathetic active\n" +
                "  < 0.50 → high-intensity zone\n" +
                "  > 1.15 → over-correlated (overtraining warning, autonomic stiffness)\n\n" +
                "🌟 KILLER FEATURE: Rogero et al. 2021 showed DFA α1 crosses 0.75 " +
                "at the rider's actual Aerobic Threshold (LT1 / Z1↔Z2 boundary). " +
                "This is the foundation for a future URUJ feature: a 15-min ramp " +
                "test that detects YOUR personal LT1 without a lactate meter " +
                "(replaces our current Karvonen-estimated boundaries with a measured " +
                "one). Lactate meters cost ₹15k + ₹100/strip. DFA α1 is free.",
        )
        InfoSection(
            "Poincaré SD1 / SD2 — the visual",
            "Plot RR(n) vs RR(n+1) and you get a cloud of dots (the Poincaré plot).\n\n" +
                "• SD1 = standard deviation perpendicular to the identity line (short-" +
                "term variability, ≈ RMSSD/√2 — same info, different lens)\n" +
                "• SD2 = standard deviation along the identity line (long-term " +
                "variability, correlates with SDNN)\n\n" +
                "Stress patterns SHOW UP visually here. Ectopic beats jump out as " +
                "outliers. Polar / Kubios / Elite HRV all use this. Future URUJ " +
                "trend-screen polish: render the actual Poincaré scatter plot.",
        )
        InfoSection(
            "Sample entropy — complexity",
            "Quantifies how predictable / regular your RR sequence is " +
                "(Richman & Moorman 2000).\n\n" +
                "• Higher (~1.0+) → healthy complex variability — good autonomic flexibility\n" +
                "• Lower (~0.0-0.5) → more regular — could mean DEEP REST (parasympathetic " +
                "saturation, e.g. deep meditation) OR pathological rigidity (overtraining, " +
                "autonomic suppression)\n\n" +
                "Context matters — low entropy at rest is fine, low entropy when " +
                "you should be active is a warning.",
        )
        InfoSection(
            "How URUJ computes this",
            "Every overnight HRV window (same one used for RMSSD):\n\n" +
                "1. Reconstruct beat-by-beat timestamps from your Magene strap's " +
                "RR intervals.\n" +
                "2. Linear-interpolate to a uniform 4 Hz time series (FFT requires " +
                "uniform sampling).\n" +
                "3. Detrend + apply Hann window (reduces spectral leakage).\n" +
                "4. Welch's PSD method: 256-sample segments (64 sec each) with 50% " +
                "overlap, average squared magnitudes.\n" +
                "5. Integrate over VLF (0.0033-0.04 Hz), LF (0.04-0.15 Hz), HF " +
                "(0.15-0.4 Hz) bands per Task Force 1996 standard.\n" +
                "6. Poincaré + DFA + sample entropy computed directly from filtered " +
                "RR series.\n\n" +
                "Minimum 240 beats required (~4 min at 60 bpm). Falls back to " +
                "\"baseline building\" below that. Same physiological filters " +
                "(300-2000 ms range, 20% ectopic delta cap) as RMSSD.\n\n" +
                "References: Task Force 1996, Heathers 2014, Hayano 2019, Peng 1994, " +
                "Rogero 2021, Richman & Moorman 2000.",
        )
        // Personalized "For you right now" section.
        val ratioVerdict = when {
            fd.lfHfRatio == null -> "Baseline building — not enough beats yet."
            fd.lfHfRatio < 1.0f ->
                "LF/HF = ${"%.2f".format(fd.lfHfRatio)} — PARASYMPATHETIC DOMINANT. " +
                    "Deep recovery zone. Body is in restorative state."
            fd.lfHfRatio < 2.0f ->
                "LF/HF = ${"%.2f".format(fd.lfHfRatio)} — BALANCED autonomic state. " +
                    "Normal training territory."
            fd.lfHfRatio < 4.0f ->
                "LF/HF = ${"%.2f".format(fd.lfHfRatio)} — SYMPATHETIC DOMINANT. " +
                    "Stress / fatigue / acute training load is elevated."
            else ->
                "LF/HF = ${"%.2f".format(fd.lfHfRatio)} — HIGH SYMPATHETIC. " +
                    "Severe stress or overload — investigate sleep, food, life stress."
        }
        val dfaVerdict = when (val dfa = fd.dfaAlpha1) {
            null -> "DFA α1 baseline building."
            in 0.85f..1.15f -> "DFA α1 = ${"%.2f".format(dfa)} — healthy fractal scaling."
            in 0.75f..0.85f -> "DFA α1 = ${"%.2f".format(dfa)} — mild fatigue territory."
            in 0.50f..0.75f -> "DFA α1 = ${"%.2f".format(dfa)} — above Aerobic Threshold (sympathetic active)."
            in 1.15f..2.0f -> "DFA α1 = ${"%.2f".format(dfa)} — over-correlated. Potential overtraining marker if sustained."
            else -> "DFA α1 = ${"%.2f".format(dfa)} — outside expected range; verify capture quality."
        }
        YouSection("$ratioVerdict\n\n$dfaVerdict\n\nBeats analyzed: ${fd.sampleCount}")
    }
}

// endregion

// region CAR

@Composable
fun CarInfoDialog(
    car: CarResult,
    interpretation: CarInterpretation,
    onDismiss: () -> Unit,
) {
    InfoDialogShell("Cortisol Awakening Response", onDismiss) {
        InfoSection(
            "What it is",
            "The HR + HRV pattern in the first 30-45 min after waking. Cortisol surges " +
                "~50-160% within this window (HPA axis activation). HR climbs 10-25 bpm " +
                "above sleep baseline, RMSSD drops 30-60%. URUJ proxies the cortisol " +
                "curve via this HR/HRV inflection — NOT salivary cortisol (the lab " +
                "gold standard).",
        )
        InfoSection(
            "Why it matters",
            "BLUNTED CAR (<5 bpm rise) is a published marker of:\n" +
                "  • Chronic stress / burnout\n  • Over-training syndrome\n" +
                "  • Depression\n  • HPA-axis dysfunction\n\n" +
                "ROBUST CAR (20+ bpm) = healthy stress-response system.\n\n" +
                "EXAGGERATED CAR (>30 bpm) = possible acute stress / anxiety. Sleep " +
                "deficit + over-reach can drive transient exaggeration even in " +
                "otherwise healthy people.",
        )
        InfoSection(
            "Reference (Pruessner 1997, Clow 2010, Stalder 2016)",
            "5 tiers (URUJ amplitude bands):\n\n" +
                "BLUNTED      <5 bpm   chronic-stress pattern\n" +
                "SUPPRESSED   5–10     HPA-axis dampened\n" +
                "NORMAL       10–20    typical healthy adult\n" +
                "ROBUST       20–30    strong morning activation\n" +
                "EXAGGERATED  >30      acute stress / anxiety\n\n" +
                "Plus a latency tier (when peak occurs relative to wake) and a " +
                "trough-RMSSD-drop% tier — overall = worst-case of the two.",
        )
        InfoSection(
            "Caveats",
            "• Caffeine within 60 min of wake artificially spikes the curve\n" +
                "• Phone alarm vs natural wake matters (cortisol responds to startle)\n" +
                "• Single readings noisy — track over weeks\n" +
                "• Salivary cortisol is the lab-gold-standard; this is HR proxy",
        )
        val tier = interpretation.overallTier
        val action = when (tier.name) {
            "EXAGGERATED" ->
                "Acute-stress signal today. Hydrate, eat protein + carbs within 60 min " +
                    "of wake (don't fast it), gentle morning routine, defer coffee 60+ " +
                    "min post-wake."
            "BLUNTED" ->
                "Chronic-stress pattern. Investigate: sleep quality, daily stressors, " +
                    "training load (TSB), nutrition. Consider professional consultation " +
                    "if persistent across weeks."
            "SUPPRESSED" ->
                "HPA-axis a bit dampened. Watch trend; if recurring with low TSB, " +
                    "may indicate accumulated fatigue."
            "ROBUST", "NORMAL" ->
                "Healthy morning activation. Body is responding to wake as it should."
            else -> ""
        }
        // v0.9.48.8 — show rigorous quiet-window primary, wide-window
        // secondary. Older readings without the new field show just the
        // legacy single number.
        val primary = car.quietWindowAmplitudeBpm ?: car.amplitudeBpm
        val secondaryLine = if (car.quietWindowAmplitudeBpm != null) {
            "Wide-window peak: ${"%.0f".format(car.amplitudeBpm)} bpm " +
                "(includes any post-wake activity)\n"
        } else ""
        YouSection(
            "Quiet-window amplitude: ${"%.0f".format(primary)} bpm rise (tier ${interpretation.amplitudeTier})\n" +
                secondaryLine +
                "Latency: ${"%.0f".format(car.latencyMinutes)} min to peak (tier ${interpretation.latencyTier})\n" +
                "RMSSD trough: ${"%.0f".format(car.rmssdDropPercent)}% drop\n" +
                "Overall: ${interpretation.summary}\n\n$action"
        )
    }
}

// endregion

// region Orthostatic

// v0.9.7 — OrthostaticInfoDialog stays in BioLabScreen.kt as a private fun
// (predates this PR, handles nullable test, has full content). Unifying the
// ⓘ button presentation via BioCard.infoOnClick is the only Bio-Lab
// transparency change for the orthostatic card. Removing the unused
// OrthostaticTestResult / OrthostaticInterpretation imports.

// endregion

// region Postprandial (v0.9.31)

/**
 * v0.9.31 — ⓘ dialog for the postprandial HRV response card. Explains
 * Tier B postprandial methodology + honest caveats + personalized
 * verdict from the latest snapshot. Per lab-level rules 3 + 4.
 *
 * Citations: Hara H et al. 2016 (postprandial autonomic response in
 * cyclists); Tarvainen MP et al. 2018 (Kubios-grade HRV methodology);
 * Task Force 1996 (5-min short-term HRV window standard).
 */
@Composable
fun PostprandialInfoDialog(
    snap: com.uruj.domain.PostprandialSnapshot,
    onDismiss: () -> Unit,
) {
    InfoDialogShell("Postprandial HRV Response", onDismiss) {
        InfoSection(
            "What this is — explained simply",
            "Every meal stresses the body. Digestion is an active process: " +
                "blood flow shifts to the gut, the sympathetic nervous system " +
                "engages, HRV temporarily drops, HR rises. After 1-2 hours " +
                "HRV recovers as digestion completes.\n\n" +
                "URUJ measures this by comparing:\n" +
                "  Pre-meal window:   -30 to -5 min before MARK MEAL tap\n" +
                "  Post-meal window:  +45 to +75 min after MARK MEAL tap\n\n" +
                "The % change in RMSSD is the headline number. Larger drops " +
                "(>30%) indicate stronger metabolic reactivity.",
        )
        InfoSection(
            "Why cyclists care",
            "Meal timing + composition affects training readiness within 2-3 hours. " +
                "If you ride hard 60 min after a heavy meal with strong postprandial " +
                "response, your autonomic system is already stressed — performance " +
                "drops, recovery debt accumulates.\n\n" +
                "Track over weeks → identify which meals/macros give you the cleanest " +
                "post-meal recovery → optimize pre-ride nutrition strategy.",
        )
        InfoSection(
            "Where to live (RMSSD drop magnitude)",
            "0–15%  → Stable metabolism (minimal stress, can eat closer to ride)\n" +
                "15–30% → Typical healthy response\n" +
                "30–50% → Sensitive (allow 90+ min before hard efforts)\n" +
                "50 %+  → Strong (consider smaller meal, lower glycemic load, OR " +
                "rule out chronic stress amplification — recovery weeks may help)",
        )
        InfoSection(
            "Honest caveats (lab-level rule 4)",
            "• A single postprandial reading is not a diagnosis. Track 2+ weeks of " +
                "data with similar meals to identify your personal baseline response.\n\n" +
                "• Meal composition matters HUGELY. Carb-heavy meals produce different " +
                "responses than fat-protein meals. URUJ doesn't yet capture macros " +
                "(comes in v1.6 #111). For now, journal manually + correlate.\n\n" +
                "• Chronic stress amplifies postprandial response. If recovery " +
                "biomarkers (LF/HF, DFA α1, CAR) are elevated, postprandial drop " +
                "will be inflated. Interpret in context.\n\n" +
                "• Brief naps, cold exposure, intense conversation, or movement " +
                "during the post-meal window can confound the reading.\n\n" +
                "• v0.9.33: If meals are <90 min apart, the second meal's PRE " +
                "window overlaps with the first meal's POST recovery. URUJ flags " +
                "this with a 'Pre-window overlaps' chip — interpret cautiously.\n\n" +
                "• v0.9.33: If a meal is marked DURING SLEEP (midnight snack), " +
                "URUJ flags this. The pre-window is sleep state, not awake " +
                "baseline, so deltas need different interpretation.\n\n" +
                "• v0.9.33: If strap was off for part of either window, URUJ " +
                "shows the coverage % so you know how much data fed the analysis.",
        )
        InfoSection(
            "How URUJ computes this",
            "1. User taps MARK MEAL in Bio Lab → MealMark saved with timestamp\n" +
                "2. 75 min after meal mark, Bio Lab next refresh triggers compute\n" +
                "3. Pre-meal HRV via 5-min windowed analysis (Task Force 1996), " +
                "median-aggregated over -30..-5 min window\n" +
                "4. Post-meal HRV computed identically over +45..+75 min window\n" +
                "5. Deltas: RMSSD % change, HR Δ bpm, HF power % change, LF/HF % change\n" +
                "6. Tier interpretation based on RMSSD drop magnitude\n" +
                "7. Saved as PostprandialSnapshot to disk (trend chart in v0.9.32+)\n\n" +
                "Methodology version: ${snap.methodologyVersion}\n" +
                "References: Hara H 2016; Tarvainen MP 2018; Task Force 1996.",
        )
        val verdict = when (val drop = snap.rmssdDeltaPercent) {
            null -> "Insufficient strap data in pre or post window. Wear strap " +
                "consistently 30 min before and 75 min after meal for a complete reading."
            else -> {
                val absDrop = kotlin.math.abs(drop)
                val tier = when {
                    absDrop < 15f -> "STABLE — minimal postprandial autonomic perturbation"
                    absDrop < 30f -> "TYPICAL — healthy autonomic engagement to food"
                    absDrop < 50f -> "SENSITIVE — moderate response; review carb load + pre-ride timing"
                    else -> "STRONG — large meal, glucose-sensitive, or chronic-stress amplified"
                }
                "RMSSD dropped ${absDrop.toInt()}% post-meal " +
                    "(${"%.1f".format(snap.preRmssdMs ?: 0f)} → " +
                    "${"%.1f".format(snap.postRmssdMs ?: 0f)} ms). " +
                    "Tier: $tier."
            }
        }
        YouSection(verdict + "\n\nPre-meal beats: ${snap.preSampleCount} · Post-meal beats: ${snap.postSampleCount}")
    }
}

// endregion
