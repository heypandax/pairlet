package dev.ccpocket.daemon.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #167 ②: where the gateway's own model list joins the picker's data.
 *
 * The invariant under test is that this is PURELY ADDITIVE — `models` (the alias list that direction ①
 * promoted to the recommended path) is identical no matter what the gateway says or whether it answers
 * at all. The gateway's ids ride in a separate field, so a probe failure can never subtract a model the
 * user had before.
 */
class ClaudeModelServiceGatewayTest {

    private fun svc(
        baseUrl: String?,
        token: String = "tok",
        history: () -> List<String> = { emptyList() },
        probe: (GatewayDetector.Paired) -> List<String>?,
    ) = ClaudeModelService(
        userConfigDir = null,
        presetEnv = { null },
        resolveGateway = { baseUrl?.let { GatewayDetector.Paired(it, token, "ANTHROPIC_AUTH_TOKEN") } },
        probe = probe,
        historyModels = history,
    )

    /** No gateway configured (or the official endpoint): no probe fires, nothing changes. */
    @Test
    fun officialEndpointNeverProbes() {
        var probed = false
        val out = svc(baseUrl = null) { probed = true; listOf("x") }.fetch(workdir = null)
        assertTrue(!probed, "official/unconfigured users must not trigger an outbound request")
        assertEquals(ClaudeModelService.CLAUDE_MODEL_ALIASES, out.models)
        assertTrue(out.gatewayModels.isEmpty())
    }

    /** The gateway answered: its ids arrive in the dedicated field, aliases untouched. */
    @Test
    fun gatewayIdsRideAlongside() {
        val out = svc("https://gw.example") { listOf("deepseek-chat", "deepseek-reasoner") }
            .fetch(workdir = null)
        assertEquals(ClaudeModelService.CLAUDE_MODEL_ALIASES, out.models, "aliases stay the recommended path")
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), out.gatewayModels)
        assertEquals("gateway", out.gatewayModelsSource)
    }

    /** An id the gateway reports that is ALSO one of our aliases must not be listed twice. */
    @Test
    fun gatewayIdsDoNotDuplicateAliases() {
        val out = svc("https://gw.example") { listOf("sonnet", "deepseek-chat", "opus") }
            .fetch(workdir = null)
        assertEquals(listOf("deepseek-chat"), out.gatewayModels, "alias overlap is dropped from the gateway group")
    }

    /** Gateway couldn't tell us: ids actually used on this machine become the last-resort list. */
    @Test
    fun probeFailureFallsBackToRecentLocalModels() {
        val out = svc("https://gw.example", history = { listOf("dsv4pro", "dsv4flash") }) { null }
            .fetch(workdir = null)
        assertEquals(ClaudeModelService.CLAUDE_MODEL_ALIASES, out.models)
        assertEquals(listOf("dsv4pro", "dsv4flash"), out.gatewayModels)
        assertEquals("history", out.gatewayModelsSource)
    }

    /**
     * A throwing probe must not take the whole model list down with it. Before the runCatching this
     * would have turned "gateway hiccup" into "picker shows nothing".
     */
    @Test
    fun probeThrowingDoesNotBreakTheList() {
        val out = svc("https://gw.example", history = { listOf("used-model") }) { error("boom") }
            .fetch(workdir = null)
        assertEquals(ClaudeModelService.CLAUDE_MODEL_ALIASES, out.models)
        assertEquals(listOf("used-model"), out.gatewayModels)
        assertEquals("history", out.gatewayModelsSource)
    }

    /** The credential resolver is consulted lazily and handed straight to the probe. */
    @Test
    fun passesResolvedCredentialToProbe() {
        var seenToken: String? = "unset"
        svc("https://gw.example", token = "sekrit") { gw -> seenToken = gw.token; null }.fetch(workdir = null)
        assertEquals("sekrit", seenToken)
    }

    @Test
    fun authoritativeGatewayAnswerWinsWithoutScanningHistory() {
        var scanned = false
        val out = svc("https://gw.example", history = { scanned = true; listOf("old") }) { listOf("live") }
            .fetch(workdir = null)
        assertEquals(listOf("live"), out.gatewayModels)
        assertEquals("gateway", out.gatewayModelsSource)
        assertTrue(!scanned, "a gateway answer is authoritative; local history must stay cold")
    }

    @Test
    fun officialEndpointNeverScansHistoryEither() {
        var scanned = false
        val out = svc(baseUrl = null, history = { scanned = true; listOf("old") }) { listOf("live") }
            .fetch(workdir = null)
        assertTrue(!scanned, "official/unconfigured users must not receive unrelated historical ids")
        assertEquals(null, out.gatewayModelsSource)
    }

    @Test
    fun historicalFallbackIsSanitizedBoundedAndDoesNotRepeatAliases() {
        val huge = "x".repeat(129)
        val history = buildList {
            add("sonnet"); add(" used "); add("used"); add(""); add(huge)
            repeat(250) { add("model-$it") }
        }
        val out = svc("https://gw.example", history = { history }) { null }.fetch(workdir = null)
        assertEquals(200, out.gatewayModels.size)
        assertEquals("used", out.gatewayModels.first())
        assertTrue("sonnet" !in out.gatewayModels)
        assertTrue(huge !in out.gatewayModels)
    }
}
