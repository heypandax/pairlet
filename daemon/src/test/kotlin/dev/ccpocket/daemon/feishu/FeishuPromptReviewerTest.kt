package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon's own judgement layer over reviewer signals: the pass bar and the output-shape validation. */
class FeishuPromptReviewerTest {

    private fun result(
        decision: PromptReviewDecision = PromptReviewDecision.ALLOW_GUARDED,
        risk: PromptReviewRisk = PromptReviewRisk.LOW,
        matches: Boolean = true,
        confidence: Double = 0.97,
        reasonCodes: List<String> = emptyList(),
    ) = PromptReviewResult(decision, risk, matches, confidence, "intent", reasonCodes, "why", "fake")

    // ── the pass bar (design §7.2) ──

    @Test
    fun only_a_clean_low_risk_high_confidence_contract_match_may_auto_run() {
        assertTrue(PromptReviewPolicy.mayAutoRun(result()))
        assertFalse(PromptReviewPolicy.mayAutoRun(result(decision = PromptReviewDecision.ASK_OWNER)))
        for (risk in listOf(PromptReviewRisk.MEDIUM, PromptReviewRisk.HIGH, PromptReviewRisk.UNKNOWN)) {
            assertFalse(PromptReviewPolicy.mayAutoRun(result(risk = risk)), "risk=$risk must ask the owner")
        }
        // ALLOW_GUARDED but off-contract: still the owner's call
        assertFalse(PromptReviewPolicy.mayAutoRun(result(matches = false)))
    }

    @Test
    fun degenerate_confidence_never_passes() {
        for (c in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.5, 1.5, 0.899999)) {
            assertFalse(PromptReviewPolicy.mayAutoRun(result(confidence = c)), "confidence=$c must ask the owner")
        }
        assertTrue(PromptReviewPolicy.mayAutoRun(result(confidence = 0.90)))
        assertTrue(PromptReviewPolicy.mayAutoRun(result(confidence = 1.0)))
    }

    @Test
    fun any_force_owner_reason_code_vetoes_an_otherwise_clean_pass() {
        for (code in PromptReviewPolicy.FORCE_OWNER_REASON_CODES) {
            assertFalse(PromptReviewPolicy.mayAutoRun(result(reasonCodes = listOf(code))), code)
        }
        // an unknown, non-forcing code the model invented does not veto by itself
        assertTrue(PromptReviewPolicy.mayAutoRun(result(reasonCodes = listOf("SOME_NOVEL_NOTE"))))
    }

    @Test
    fun forcedAskOwner_is_always_unrunnable_and_bounded() {
        val r = PromptReviewPolicy.forcedAskOwner(listOf(PromptReviewPolicy.REVIEWER_TIMEOUT), "claude-cli", "x".repeat(10_000))
        assertFalse(PromptReviewPolicy.mayAutoRun(r))
        assertEquals(PromptReviewRisk.UNKNOWN, r.risk)
        assertTrue(r.explanation.length <= PromptReviewPolicy.MAX_FIELD_CHARS)
    }

    // ── CLI output parsing (design §7.5): only schema-shaped structured output counts ──

    private fun outer(structured: String?) =
        if (structured == null) """{"result":"I think this is fine, ALLOW"}"""
        else """{"result":"…","structured_output":$structured}"""

    private val valid = """
        {"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":0.95,
         "intent":"summarize the readme","reasonCodes":[],"explanation":"routine read within the project"}
    """.trimIndent()

    @Test
    fun valid_structured_output_parses_and_passes_the_bar() {
        val r = ClaudeFeishuPromptReviewer.parseReviewOutput(outer(valid))!!
        assertEquals(PromptReviewDecision.ALLOW_GUARDED, r.decision)
        assertEquals(PromptReviewRisk.LOW, r.risk)
        assertTrue(PromptReviewPolicy.mayAutoRun(r))
        assertEquals("claude-cli", r.assessor)
    }

    @Test
    fun missing_structured_output_is_invalid_even_when_the_text_says_allow() {
        // the assistant's final text is NEVER an authorization conclusion
        assertNull(ClaudeFeishuPromptReviewer.parseReviewOutput(outer(null)))
    }

    @Test
    fun drifted_shapes_are_invalid() {
        val broken = listOf(
            "not json at all",
            "[]",
            outer("""{"decision":"ALLOW","risk":"LOW","matchesContract":true,"confidence":0.95,"intent":"x","reasonCodes":[],"explanation":"y"}"""), // unknown enum value
            outer("""{"decision":"ALLOW_GUARDED","risk":"SAFE","matchesContract":true,"confidence":0.95,"intent":"x","reasonCodes":[],"explanation":"y"}"""),
            outer("""{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":"high","intent":"x","reasonCodes":[],"explanation":"y"}"""),
            outer("""{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":1.7,"intent":"x","reasonCodes":[],"explanation":"y"}"""),
            outer("""{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":0.95,"reasonCodes":[],"explanation":"y"}"""), // missing intent
            outer("""{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":0.95,"intent":"x","reasonCodes":"none","explanation":"y"}"""),
        )
        for (o in broken) {
            assertNull(ClaudeFeishuPromptReviewer.parseReviewOutput(o), "should be invalid: ${o.take(120)}")
        }
    }

    @Test
    fun overlong_string_fields_are_clamped_not_stored_raw() {
        val long = "z".repeat(5_000)
        val r = ClaudeFeishuPromptReviewer.parseReviewOutput(
            outer("""{"decision":"ASK_OWNER","risk":"UNKNOWN","matchesContract":false,"confidence":0.1,"intent":"$long","reasonCodes":[],"explanation":"$long"}"""),
        )!!
        assertTrue(r.intent.length <= PromptReviewPolicy.MAX_FIELD_CHARS)
        assertTrue(r.explanation.length <= PromptReviewPolicy.MAX_FIELD_CHARS)
    }

    @Test
    fun oversized_stdout_is_rejected_wholesale() {
        val padded = outer(valid) + " ".repeat(ClaudeFeishuPromptReviewer.MAX_STDOUT_BYTES)
        assertNull(ClaudeFeishuPromptReviewer.parseReviewOutput(padded))
    }
}
