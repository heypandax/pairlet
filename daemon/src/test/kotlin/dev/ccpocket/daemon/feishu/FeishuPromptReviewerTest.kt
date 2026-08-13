package dev.ccpocket.daemon.feishu

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon's own judgement layer over reviewer signals: the pass bar and the output-shape validation. */
class FeishuPromptReviewerTest {

    private val tmp: File = Files.createTempDirectory("ccp-reviewer").toFile()

    /** A schema-valid ALLOW_GUARDED envelope on one line — a fake CLI can echo it verbatim. */
    private val compactValid =
        """{"decision":"ALLOW_GUARDED","risk":"LOW","matchesContract":true,"confidence":0.95,""" +
            """"intent":"summarize the readme","reasonCodes":[],"explanation":"routine read"}"""

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

    // ── argv transport (Windows post-mortem): the isolation flags must be single, quote-free tokens ──

    @Test
    fun the_reviewer_argv_isolates_without_relying_on_quotes_or_an_empty_argument() {
        val argv = ClaudeFeishuPromptReviewer.buildArgv("/usr/local/bin/claude")
        // still tool-less, MCP-less, settings-less, non-persistent — the whole point of the one-shot
        assertTrue("--tools=" in argv, argv.toString())
        assertTrue("--strict-mcp-config" in argv, argv.toString())
        assertTrue("--safe-mode" in argv, argv.toString())
        assertTrue("--no-session-persistence" in argv, argv.toString())
        // and the fail-closed contract is untouched: the schema still rides along
        assertTrue("--json-schema" in argv, argv.toString())
        assertEquals(ClaudeFeishuPromptReviewer.SCHEMA, argv[argv.indexOf("--json-schema") + 1])
        // …but the empty MCP set is no longer an inline JSON literal, and the empty tool set no longer a
        // standalone "": both shapes are eaten when a command line is re-parsed (see ClaudeLauncher)
        assertFalse("--mcp-config" in argv, argv.toString())
        assertFalse(argv.any { it.contains("mcpServers") }, argv.toString())
        assertFalse(argv.any { it.isEmpty() }, argv.toString())
    }

    @Test
    fun a_batch_shim_cli_disables_the_reviewer_instead_of_running_it_mangled() = runBlocking {
        // A `.cmd` can only start through cmd.exe, which re-parses the line — and this argv carries the
        // multi-line, quote-bearing --system-prompt. Running it mangled is WORSE than not running: the
        // --json-schema contract survives (it comes earlier), so a classifier that lost its "untrusted
        // data" defense would still emit schema-valid output that the daemon honours. So: refuse.
        // The fixture is a REAL runnable script that answers ALLOW_GUARDED — the only thing standing
        // between it and an auto-run is the extension, which is exactly what this pins.
        val body = "#!/bin/sh\necho '{\"version\":\"t\",\"structured_output\":$compactValid}'\n"
        val shim = File(tmp, "fake-claude.cmd").apply { writeText(body); setExecutable(true) }
        val r = ClaudeFeishuPromptReviewer(cwd = File(tmp, "state"), resolveBin = { shim.toPath() })
            .review(PromptReviewInput("r1", "alpha", "review only", "summarize the readme"))
        assertEquals(PromptReviewDecision.ASK_OWNER, r.decision)
        assertFalse(PromptReviewPolicy.mayAutoRun(r))
        assertTrue(PromptReviewPolicy.REVIEWER_UNAVAILABLE in r.reasonCodes, r.reasonCodes.toString())
    }

    @Test
    fun the_same_fixture_without_the_shim_extension_does_reach_the_pass_bar() {
        // control for the test above — proves the refusal comes from the extension, not from a broken
        // fixture that would have failed anyway. (Needs a POSIX shell; the daemon suite runs on Linux/macOS.)
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"))
        val body = "#!/bin/sh\necho '{\"version\":\"t\",\"structured_output\":$compactValid}'\n"
        val native = File(tmp, "fake-claude").apply { writeText(body); setExecutable(true) }
        val r = runBlocking {
            ClaudeFeishuPromptReviewer(cwd = File(tmp, "state"), resolveBin = { native.toPath() })
                .review(PromptReviewInput("r2", "alpha", "review only", "summarize the readme"))
        }
        assertEquals(PromptReviewDecision.ALLOW_GUARDED, r.decision)
        assertTrue(PromptReviewPolicy.mayAutoRun(r))
    }

    @Test
    fun reviewed_capability_ceiling_remains_the_legacy_restricted_authority() {
        val c = PromptReviewInput.CAPABILITY_CEILING
        assertTrue("INSIDE the bound project" in c, c)
        assertTrue("allowed_commands" in c, c)
        assertTrue("Everything else" in c && "requires the machine owner's approval" in c, c)
        assertFalse("full-auto" in c, "a legacy REVIEWED record must not silently acquire #233 authority: $c")
    }

    @Test
    fun full_auto_capability_ceiling_prices_the_real_broad_authority_and_limited_holds() {
        val c = PromptReviewInput.FULL_AUTO_CAPABILITY_CEILING
        assertTrue("full-auto" in c, c)
        assertTrue("arbitrary" in c && "Bash" in c, c)
        assertTrue("MCP" in c && "network" in c && "sub-agents" in c, c)
        assertTrue("not confined" in c && "outside the project" in c && "send data externally" in c, c)
        assertTrue("human-decision tools still ask" in c, c)
        assertTrue("only when" in c && "structured file target" in c, c)
        assertTrue("does not confine" in c && "shell or unknown tools" in c, c)
        assertTrue("best-effort defense-in-depth" in c && "not a complete or unbypassable" in c, c)
        assertTrue("must ask the owner" in c, c)
    }
}
