package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.claude.ClaudeQuotaService
import dev.ccpocket.daemon.codex.CodexQuotaService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaGet
import dev.ccpocket.protocol.HandoffAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Router-level contract for the per-agent allowance dispatch (issue #348).
 *
 * Two red lines meet here and both are asserted:
 *  1. **Dispatch by the requested backend**, and **echo the DECODED request value** back on the reply.
 *     The reply carries no request id, so its `agent` is the only thing the client can correlate on — and
 *     `agent` is a COERCED enum, meaning a wire name this daemon does not know arrives as CLAUDE, is
 *     answered with the Claude allowance, and must be LABELLED claude. Stamping the service's own idea of
 *     its agent instead would hand that client a Claude number wearing the label it hoped for.
 *  2. **Owner-only, unchanged.** This is account-wide BILLING state; a bridge/guest/collaborator gets
 *     SILENCE (no reply frame at all), never an empty snapshot that would read as "your allowance is fine".
 *
 * Both quota services are injected with seams, so nothing here reaches the keychain, the network or a
 * real `codex app-server`.
 */
class RequestRouterQuotaAgentTest {

    /** A weekly-only Codex account, the shape codex-cli 0.153.4 actually returns. */
    private val codexBody = """
        {"rateLimits":{"limitId":"codex","planType":"pro",
          "primary":{"usedPercent":50,"windowDurationMins":10080,"resetsAt":1789179749},"secondary":null}}
    """.trimIndent()

    private fun router(
        scope: CoroutineScope,
        codexInstalled: Boolean = true,
    ): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-router-quota").toFile()
        return RequestRouter(
            registry = SessionRegistry(scope, backends = emptyMap()),
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
            // a signed-out machine: Claude answers no_token WITHOUT touching the keychain or the network,
            // which also makes the two backends' replies trivially distinguishable below
            quota = ClaudeQuotaService(
                credentials = { ClaudeQuotaService.QuotaCredential.Missing },
                transport = { error("the Claude branch must never reach the network in this test") },
            ),
            codexQuota = CodexQuotaService(
                binary = { if (codexInstalled) Path.of("/nonexistent/codex") else null },
                transport = { CodexQuotaService.AppServerOutcome.Result(codexBody) },
            ),
        )
    }

    private fun reply(
        frame: ClaudeQuotaGet,
        origin: String? = null,
        guestScope: GuestScope? = null,
        collabScope: CollaboratorScope? = null,
    ): ClaudeQuota? = runBlocking {
        val got = CompletableDeferred<ClaudeQuota>()
        router(CoroutineScope(Dispatchers.Default)).handle(
            frame,
            { f -> if (f is ClaudeQuota) got.complete(f) },
            origin = origin, guestScope = guestScope, collabScope = collabScope,
        )
        // a refusal is SILENCE, so the negative cases must be asserted by waiting and getting nothing
        withTimeoutOrNull(2_000) { got.await() }
    }

    // -- dispatch -----------------------------------------------------------------------------------

    @Test
    fun a_claude_request_is_answered_by_the_claude_reader_and_labelled_claude() {
        val r = reply(ClaudeQuotaGet(agent = AgentKind.CLAUDE))!!
        assertEquals(AgentKind.CLAUDE, r.agent)
        assertEquals(CLAUDE_QUOTA_NO_TOKEN, r.status, "the injected Claude credential store is empty")
    }

    @Test
    fun a_codex_request_is_answered_by_the_CODEX_reader_and_labelled_codex() {
        val r = reply(ClaudeQuotaGet(agent = AgentKind.CODEX))!!
        assertEquals(AgentKind.CODEX, r.agent)
        assertEquals(CLAUDE_QUOTA_OK, r.status)
        assertEquals(50, r.limits.single().percent, "these are the CODEX numbers, not Claude's")
        assertEquals("pro", r.planType)
    }

    @Test
    fun the_default_frame_still_means_claude_so_every_pre_348_client_is_unaffected() {
        // an older App sends no `agent` key at all; it decodes to CLAUDE and must be answered as before
        val r = reply(ClaudeQuotaGet(forceRefresh = false))!!
        assertEquals(AgentKind.CLAUDE, r.agent)
        assertEquals(CLAUDE_QUOTA_NO_TOKEN, r.status)
    }

    @Test
    fun a_backend_with_no_readable_allowance_answers_no_token_with_its_own_agent_echoed() {
        for (agent in listOf(AgentKind.OPENCODE, AgentKind.KIMI, AgentKind.ZCODE, AgentKind.DSH)) {
            val r = reply(ClaudeQuotaGet(agent = agent))!!
            assertEquals(agent, r.agent, "$agent must be echoed, never rewritten to claude")
            assertEquals(CLAUDE_QUOTA_NO_TOKEN, r.status)
            assertTrue(r.limits.isEmpty(), "$agent must not be handed another backend's rows")
        }
    }

    @Test
    fun the_reply_label_is_the_DECODED_request_value_not_the_services_own_idea_of_itself() {
        // The Codex service tags its own readings CODEX. What goes out is the request's agent, so that a
        // future request for an agent this daemon coerces to CLAUDE comes back labelled claude — visibly
        // not what the client asked for, which is exactly what lets the client drop it.
        val direct = runBlocking { CodexQuotaService(
            binary = { Path.of("/nonexistent/codex") },
            transport = { CodexQuotaService.AppServerOutcome.Result(codexBody) },
        ).get() }
        assertEquals(AgentKind.CODEX, direct.agent)
        // …and the router does not merely pass that through: it stamps the frame's value over it
        assertEquals(AgentKind.CODEX, reply(ClaudeQuotaGet(agent = AgentKind.CODEX))!!.agent)
        assertEquals(AgentKind.DSH, reply(ClaudeQuotaGet(agent = AgentKind.DSH))!!.agent)
    }

    // -- owner-only, unchanged ----------------------------------------------------------------------

    private fun guest() = GuestScope(
        roots = listOf(Files.createTempDirectory("ccp-quota-wd").toRealPath().toString()),
        ownedSessions = emptySet(), label = "alex", expiresAt = null, tier = AccessTier.COLLABORATE,
    )

    private fun collaborator() = CollaboratorScope(
        deviceId = "dev-1", pathScope = emptyList(), access = HandoffAccess.REVIEW_READ_ONLY,
    )

    @Test
    fun a_bridge_a_guest_and_a_collaborator_all_get_SILENCE_on_every_agent() {
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            assertNull(reply(ClaudeQuotaGet(agent = agent), origin = "feishu:group-1"), "bridge/$agent")
            assertNull(reply(ClaudeQuotaGet(agent = agent), origin = "alex", guestScope = guest()), "guest/$agent")
            // the collaborator case is the one the older two-term owner test waved through: its `origin`
            // AND its `guestScope` are both null
            assertNull(
                reply(ClaudeQuotaGet(agent = agent), origin = null, guestScope = null, collabScope = collaborator()),
                "collaborator/$agent",
            )
        }
    }

    // -- the capability advertisement ---------------------------------------------------------------

    @Test
    fun quotaAgentWires_always_lists_claude_and_lists_codex_only_when_the_cli_is_there() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val withCodex = withTimeout(10_000) { router(scope, codexInstalled = true).quotaAgentWires() }
        assertEquals(listOf("claude", "codex"), withCodex)

        val without = withTimeout(10_000) { router(scope, codexInstalled = false).quotaAgentWires() }
        assertEquals(listOf("claude"), without)
        assertFalse("codex" in without, "advertising codex on a machine without it invites an unanswerable request")
    }
}
