package com.uruj.power

import com.uruj.domain.ReadinessComponent
import com.uruj.domain.ReadinessContext
import com.uruj.domain.Recommendation

/**
 * v0.9.4 — the seam where a rule-based or AI-based reasoner produces a
 * [Recommendation] from a unified [ReadinessContext].
 *
 * # AI HOOK
 *
 * Implementations:
 *   - [RuleBasedReasoner] — deterministic local engine. Ships in v0.9.4.
 *     Reads context fields directly. No network. ≤ 2 ms compute.
 *   - `GroqAiReasoner` (Task #105 / v0.5) — sends context as a structured
 *     prompt to Groq's API, parses the JSON response back into a
 *     [Recommendation]. Network-dependent; needs rider consent prompt.
 *   - `CompositeReasoner` (v0.5+) — wraps both: tries AI primary, falls
 *     back to rules when AI declines / offline / parse fails. The
 *     bulletproof rider-facing contract is "you always get guidance,
 *     even without AI."
 *
 * Add a new reasoner by implementing this interface — no engine rewrite
 * required, no UI changes. UI consumes [Recommendation] identically
 * regardless of which reasoner produced it.
 *
 * # Why this seam exists
 *
 * Per [[reference_readiness_context_architecture]], every biomarker plugs
 * into [ReadinessContext]. Every reasoner reads the same struct. Adding a
 * new biomarker means updating the context builder (data layer) + optionally
 * the reasoner's rule set — never the UI, never the interface.
 */
interface ReadinessReasoner {
    suspend fun reason(
        context: ReadinessContext,
        score: Int,
        components: List<ReadinessComponent>,
    ): Recommendation
}
