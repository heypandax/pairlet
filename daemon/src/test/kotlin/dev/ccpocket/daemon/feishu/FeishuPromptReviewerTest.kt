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
    fun any_reason_code_at_all_vetoes_an_otherwise_clean_pass() {
        for (code in PromptReviewPolicy.FORCE_OWNER_REASON_CODES) {
            assertFalse(PromptReviewPolicy.mayAutoRun(result(reasonCodes = listOf(code))), code)
        }
        // fail closed on codes the daemon doesn't know (design §21.3): a LOW auto-pass may carry NO risk
        // annotation, so an invented "code" — a signal the policy can't price — escalates just the same
        assertFalse(PromptReviewPolicy.mayAutoRun(result(reasonCodes = listOf("SOME_NOVEL_NOTE"))))
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
    fun unknown_or_misspelled_reason_codes_invalidate_the_whole_output() {
        // a typo'd or invented code is either schema drift or a smuggling channel (design §21.3) — the
        // parser rejects the output wholesale, which normalizes to REVIEWER_INVALID_OUTPUT → ASK_OWNER
        val typo = outer(
            """{"decision":"ASK_OWNER","risk":"MEDIUM","matchesContract":true,"confidence":0.95,"intent":"x","reasonCodes":["CREDENTIAL_REQUEST"],"explanation":"y"}""",
        )
        assertNull(ClaudeFeishuPromptReviewer.parseReviewOutput(typo))
        // the exact constant still parses — and vetoes auto-run downstream
        val known = ClaudeFeishuPromptReviewer.parseReviewOutput(
            outer("""{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":0.95,"intent":"x","reasonCodes":["${PromptReviewPolicy.CREDENTIAL_OR_SECRET_REQUEST}"],"explanation":"y"}"""),
        )!!
        assertFalse(PromptReviewPolicy.mayAutoRun(known), "ALLOW_GUARDED+LOW with a known code must still ask the owner")
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

    // ── capability ceiling honesty (design §21.6): the reviewer sees the REAL zero-click surface ──

    @Test
    fun payload_carries_the_owner_allowlist_for_the_reviewer_to_price_risk_against() {
        val p = ClaudeFeishuPromptReviewer.payload(
            PromptReviewInput(
                reviewId = "r1",
                projectName = "alpha",
                purpose = "review only",
                prompt = "run the tests",
                allowedCommands = listOf("npm test", "./gradlew build"),
            ),
        )
        assertTrue(""""allowed_commands":["npm test","./gradlew build"]""" in p, p)
        // an empty allowlist is still an explicit (empty) field, never an absent one
        val empty = ClaudeFeishuPromptReviewer.payload(PromptReviewInput("r2", "alpha", "review only", "hi"))
        assertTrue(""""allowed_commands":[]""" in empty, empty)
    }

    @Test
    fun capability_ceiling_admits_the_allowlist_instead_of_claiming_absolute_no_network() {
        val c = PromptReviewInput.CAPABILITY_CEILING
        assertTrue("allowed_commands" in c, "ceiling must point at the real allowlist field")
        // the old wording promised "no network access" flatly — a whitelisted command can reach the
        // network, and a reviewer judging against the fiction would under-price that risk
        assertFalse("no network access" in c, c)
        assertFalse("no shell beyond" in c.lowercase(), c)
    }
}
