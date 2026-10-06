package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_CLI_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_FILE
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_LAST_GOOD
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelCatalogMeta
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repository half of the Codex catalog cache: request correlation (token + binding + generation + target
 * directory), what a surface sees before the daemon answers, which answers may drive capabilities or correct a
 * saved preference, what is persisted, and what the refresh rules send on the wire.
 */
class CodexModelCatalogRepoTest {
    private val account = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-catalog-test", daemonPub = "pub-ct", deviceId = "dev-ct", credential = "cred", hostName = "mac",
    )
    private val identity = "${account.relay}|${account.accountId}|${account.daemonPub}|${account.deviceId}"
    private val key = ModelCatalogStore.key(identity, AgentKind.CODEX)
    private lateinit var scope: CoroutineScope
    private val sent = mutableListOf<Frame>()

    @BeforeTest fun setUp() { SecureStore.remove(key); scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); sent.clear() }

    @AfterTest fun tearDown() { SecureStore.remove(key); scope.cancel() }

    private fun repo(s: CoroutineScope = scope) = PocketRepository(s, account).apply { onSendForTest = { sent += it } }

    private fun list(
        models: List<String> = listOf("gpt-6-astra", "gpt-6-sol"), source: String? = MODEL_CATALOG_SOURCE_DYNAMIC,
        refreshing: Boolean = false, error: String? = null, scope: String? = "scope-1", requestId: String?,
        tiers: List<ModelServiceTier> = listOf(ModelServiceTier("priority", "Fast", "2x")),
    ) = ModelsList(
        agent = AgentKind.CODEX, models = models, error = error, requestId = requestId,
        modelCapabilities = models.map { ModelCapabilities(it, listOf("low", "high"), "high", tiers) },
        catalog = source?.let { ModelCatalogMeta(source = it, scope = scope, contentVersion = "v-${models.hashCode()}-${tiers.size}", checkedAt = 10, changedAt = 5, refreshing = refreshing) },
    )

    private fun fetches() = sent.filterIsInstance<FetchModels>().filter { it.agent == AgentKind.CODEX }
    private fun lastToken() = fetches().last().requestId!!

    /** Open session A (Codex) so there is an active context to protect. */
    private fun PocketRepository.openA(): String {
        openSession("/w/a", agent = AgentKind.CODEX, startModel = "gpt-6-sol")
        receiveForTest(SessionLive("c-a", "/w/a", "s-a", mode = PermissionMode.DEFAULT, executing = false, model = "gpt-6-sol", agent = AgentKind.CODEX))
        return lastToken()
    }

    // ── preview vs authority ─────────────────────────────────────────────────────────────────────────

    @Test
    fun a_restart_shows_the_persisted_rows_as_a_preview_that_feeds_no_capability_and_moves_no_preference() {
        val first = repo()
        first.openA()
        first.receiveForTest(list(requestId = lastToken()))
        assertNotNull(SecureStore.getString(key), "a confirmed dynamic catalog is persisted for this binding")

        // "restart": a fresh repository on the same binding, default service tier set to something the cached rows
        // do NOT list — the preview must neither clear it (reconcile) nor claim to know the model's tiers
        sent.clear()
        val second = repo()
        second.setDefaultModelFor(AgentKind.CODEX, "gpt-6-astra")
        second.setDefaultServiceTier("flex")
        try {
            second.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")

            val shown = second.modelListFor(AgentKind.CODEX)
            assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), shown?.models, "rows are visible before any daemon answer")
            assertTrue(second.isModelListPreview(AgentKind.CODEX))
            assertNull(second.agentModels[AgentKind.CODEX], "the preview is not the authoritative catalog")
            assertNull(second.modelCapabilities(AgentKind.CODEX, "gpt-6-astra"), "preview rows never answer capability questions")
            assertEquals("flex", second.defaultServiceTier.value, "a preview never reconciles a saved preference away")
            assertEquals(1, fetches().size, "and the daemon is asked once")
            assertEquals("/w/a", fetches().single().workdir)
            assertNotNull(fetches().single().requestId, "every Codex request carries a correlation token")
            assertTrue(second.agentModelsRefreshing[AgentKind.CODEX] == true, "rows on screen + request out = updating cue")

            // the daemon answers for THIS request: the preview is replaced, the cue ends, tier reconciles now
            second.openA() // the active session is /w/a too, so the answer describes the open session
            second.receiveForTest(list(requestId = lastToken()))
            assertFalse(second.isModelListPreview(AgentKind.CODEX))
            assertNotNull(second.modelCapabilities(AgentKind.CODEX, "gpt-6-astra"))
            assertFalse(second.agentModelsRefreshing[AgentKind.CODEX] == true)
            assertNull(second.defaultServiceTier.value, "the CONFIRMED catalog reconciles the unsupported tier (pre-existing rule)")
        } finally {
            second.setDefaultModelFor(AgentKind.CODEX, null)
            second.setDefaultServiceTier(null)
        }
    }

    @Test
    fun file_and_unconfirmed_answers_never_clear_a_saved_tier_and_only_the_confirmed_one_persists() {
        val r = repo()
        r.setDefaultModelFor(AgentKind.CODEX, "gpt-6-astra")
        r.setDefaultServiceTier("flex") // not among the rows' tiers → a confirmed answer WOULD clear it
        try {
            r.openA()
            r.receiveForTest(list(source = MODEL_CATALOG_SOURCE_FILE, requestId = lastToken()))
            assertEquals("flex", r.defaultServiceTier.value, "the CLI's file is rows to show, not a basis to edit preferences")
            assertNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-astra"), "…nor a capability basis")
            assertTrue(r.isModelListPreview(AgentKind.CODEX))
            assertNull(SecureStore.getString(key))

            r.refreshModels(AgentKind.CODEX)
            r.receiveForTest(list(source = MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scope = "scope-u", requestId = lastToken()))
            assertEquals("flex", r.defaultServiceTier.value, "an unconfirmed account's rows may serve the session, never edit the saved tier")
            assertNotNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-astra"), "but they are the CLI's real answer: capabilities for the session")
            assertFalse(r.isModelListPreview(AgentKind.CODEX))
            assertNull(SecureStore.getString(key), "not persisted as a confirmed environment")
            r.setDefaultModelFor(AgentKind.CODEX, "gpt-6-sol")
            assertEquals("flex", r.defaultServiceTier.value, "changing the default must not reconcile from temporary capabilities either")

            r.refreshModels(AgentKind.CODEX)
            r.receiveForTest(list(requestId = lastToken()))
            assertNull(r.defaultServiceTier.value, "only the confirmed account catalog corrects the saved tier")
            assertNotNull(SecureStore.getString(key))
        } finally {
            r.setDefaultModelFor(AgentKind.CODEX, null); r.setDefaultServiceTier(null)
        }
    }

    @Test
    fun a_failed_refresh_keeps_the_confirmed_rows_but_shows_the_failure_and_demotes_across_scopes() {
        val r = repo()
        r.openA()
        r.receiveForTest(list(requestId = lastToken()))
        assertNotNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"))

        // same scope: the daemon kept its last good answer and says why — rows + capabilities stay, the failure shows
        r.refreshModels(AgentKind.CODEX)
        r.receiveForTest(list(source = MODEL_CATALOG_SOURCE_LAST_GOOD, error = "the Codex app-server did not answer in time", requestId = lastToken()))
        val shown = r.modelListFor(AgentKind.CODEX)!!
        assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), shown.models)
        assertEquals(MODEL_CATALOG_SOURCE_LAST_GOOD, shown.catalog?.source, "the surfaces see the failed refresh, not a pretend success")
        assertEquals("the Codex app-server did not answer in time", shown.error)
        assertNotNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"), "same scope: the confirmed capabilities still hold")
        assertFalse(r.agentModelsRefreshing[AgentKind.CODEX] == true)
        val beforeRetry = fetches().size
        r.fetchModels(AgentKind.CODEX)
        assertEquals(beforeRetry + 1, fetches().size, "a failed refresh does not earn the ten-minute success reuse window")

        // another scope (signed out → the CLI's built-ins): the confirmed capabilities no longer describe this environment
        r.refreshModels(AgentKind.CODEX)
        r.receiveForTest(list(models = listOf("gpt-5.5"), source = MODEL_CATALOG_SOURCE_CLI_BUILTIN, scope = "scope-none", requestId = lastToken()))
        assertEquals(listOf("gpt-5.5"), r.modelListFor(AgentKind.CODEX)?.models, "the fresher rows are shown")
        assertTrue(r.isModelListPreview(AgentKind.CODEX))
        assertNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"), "cross-scope: unknown")
        assertNull(r.modelCapabilities(AgentKind.CODEX, "gpt-5.5"))
    }

    @Test
    fun the_interim_frame_shows_rows_and_keeps_the_cue_without_granting_authority() {
        val r = repo()
        r.openA()
        val token = lastToken()
        r.receiveForTest(list(refreshing = true, requestId = token))
        assertTrue(r.agentModelsRefreshing[AgentKind.CODEX] == true)
        assertTrue(r.isModelListPreview(AgentKind.CODEX), "interim rows are a preview")
        assertNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"))
        assertNull(SecureStore.getString(key), "an interim frame is never persisted")
        r.receiveForTest(list(models = emptyList(), source = MODEL_CATALOG_SOURCE_BUILTIN, scope = null, error = "no cli", requestId = token))
        assertFalse(r.agentModelsRefreshing[AgentKind.CODEX] == true, "any final frame ends the cue")
        assertNull(SecureStore.getString(key), "the built-in fallback is not a cache")
    }

    @Test
    fun a_legacy_daemon_answer_without_token_or_provenance_is_taken_as_before() {
        val r = repo()
        r.openA()
        r.receiveForTest(list(source = null, requestId = null))
        assertNotNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"), "the pre-cache behaviour for an older daemon")
        assertNull(SecureStore.getString(key), "but nothing unscoped is persisted")
    }

    // ── correlation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_new_session_sheet_for_project_b_asks_for_b_and_a_late_answer_for_a_is_dropped() {
        val r = repo()
        val tokenA = r.openA()
        assertEquals("/w/a", fetches().last().workdir)
        // the user opens the new-session sheet on project B while A is still the open session
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/b")
        val tokenB = lastToken()
        assertEquals("/w/b", fetches().last().workdir, "the sheet's request names B, not the open session's directory")
        assertTrue(tokenA != tokenB)
        // A's answer arrives late: dropped, nothing of it is shown or trusted
        r.receiveForTest(list(models = listOf("a-only"), requestId = tokenA))
        assertNull(r.modelListFor(AgentKind.CODEX)?.models?.firstOrNull { it == "a-only" })
        assertNull(r.modelCapabilities(AgentKind.CODEX, "a-only"))
        // B's answer: shown for the sheet, but it is NOT the open session's (A) catalog — capabilities stay unknown there
        r.receiveForTest(list(models = listOf("b-model"), requestId = tokenB))
        assertEquals(listOf("b-model"), r.modelListFor(AgentKind.CODEX)?.models)
        assertEquals("/w/a", r.workdir.value, "the open session is still A")
        assertNull(r.modelCapabilities(AgentKind.CODEX, "b-model"), "B's catalog must not pose as A's capabilities")
    }

    @Test
    fun opening_b_without_prefetch_never_clamps_its_options_using_a_catalog() {
        val r = repo()
        r.openA()
        val catalogA = list(requestId = lastToken(), tiers = emptyList()).let { rows ->
            rows.copy(modelCapabilities = rows.modelCapabilities.map { it.copy(reasoningEfforts = listOf("low")) })
        }
        r.receiveForTest(catalogA)
        r.setDefaultModelFor(AgentKind.CODEX, "gpt-6-sol")
        r.setDefaultEffortFor(AgentKind.CODEX, "high")
        r.setDefaultServiceTier("priority")
        try {
            r.openSession("/w/b", agent = AgentKind.CODEX, startModel = "gpt-6-sol")
            val opened = sent.filterIsInstance<OpenSession>().last()
            assertEquals("/w/b", opened.workdir)
            assertEquals("high", opened.effort, "B has no known catalog; A's low-only row does not describe it")
            assertEquals("priority", opened.serviceTier, "A's missing tier must not strip B's requested tier")
        } finally {
            r.setDefaultModelFor(AgentKind.CODEX, null)
            r.setDefaultEffortFor(AgentKind.CODEX, null)
            r.setDefaultServiceTier(null)
        }
    }

    @Test
    fun opening_a_session_in_another_directory_retargets_and_drops_the_previous_request() {
        val r = repo()
        val tokenA = r.openA()
        // the user opens a session in B: the open asks for B's catalog at once (not after SessionLive, and never
        // for the directory of the session being left)
        r.openSession("/w/b", agent = AgentKind.CODEX)
        assertEquals("/w/b", fetches().last().workdir, "the open re-targets the catalog at the directory being opened")
        val tokenB = lastToken()
        assertTrue(tokenB != tokenA)
        r.receiveForTest(list(models = listOf("a-only"), requestId = tokenA))
        assertNull(r.agentModels[AgentKind.CODEX], "the request for A was revoked when the session moved to B")
        assertNull(r.modelListFor(AgentKind.CODEX)?.models?.firstOrNull { it == "a-only" })
        r.receiveForTest(SessionLive("c-b", "/w/b", "s-b", mode = PermissionMode.DEFAULT, executing = false, model = "gpt-6-sol", agent = AgentKind.CODEX))
        assertEquals(tokenB, lastToken(), "the announce confirms the same directory: no second request")
        r.receiveForTest(list(models = listOf("b-model"), requestId = tokenB))
        assertEquals("/w/b", r.workdir.value)
        assertNotNull(r.modelCapabilities(AgentKind.CODEX, "b-model"), "B is now the open session: its answer is authoritative")
    }

    @Test
    fun the_timeout_ends_only_its_own_request() {
        val scheduler = TestCoroutineScheduler()
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val r = repo(s)
        try {
            r.receiveForTest(list(requestId = null, source = null)) // rows on screen so the cue is raised
            r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a"); scheduler.runCurrent()
            val first = lastToken()
            assertTrue(r.agentModelsRefreshing[AgentKind.CODEX] == true)
            scheduler.advanceTimeBy(ModelCatalogRefreshPolicy.IN_FLIGHT_MS / 2); scheduler.runCurrent()
            // a new target mid-flight: a second request with its own timer
            r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/b"); scheduler.runCurrent()
            val second = lastToken()
            assertTrue(second != first)
            scheduler.advanceTimeBy(ModelCatalogRefreshPolicy.IN_FLIGHT_MS / 2 + 1); scheduler.runCurrent()
            assertTrue(r.agentModelsRefreshing[AgentKind.CODEX] == true, "the first request's deadline must not end the second request")
            r.receiveForTest(list(models = listOf("b-model"), requestId = second))
            assertEquals(listOf("b-model"), r.modelListFor(AgentKind.CODEX)?.models, "the second request is still accepted")
            // and a request nobody answers ends its own cue
            r.refreshModels(AgentKind.CODEX, targetWorkdir = "/w/b"); scheduler.runCurrent()
            assertTrue(r.agentModelsRefreshing[AgentKind.CODEX] == true)
            scheduler.advanceTimeBy(ModelCatalogRefreshPolicy.IN_FLIGHT_MS + 1); scheduler.runCurrent()
            assertFalse(r.agentModelsRefreshing[AgentKind.CODEX] == true, "no permanent spinner")
            assertNotNull(r.modelListFor(AgentKind.CODEX)?.error, "a timeout remains visible after the spinner ends")
        } finally {
            s.cancel()
        }
    }

    // ── throttling and the wire ──────────────────────────────────────────────────────────────────────

    @Test
    fun a_live_announcement_that_changes_the_directory_revokes_the_previous_catalog_request() {
        val r = repo()
        val oldToken = r.openA()
        r.receiveForTest(SessionLive("c-a", "/w/b", "s-a", mode = PermissionMode.DEFAULT, executing = false, model = "gpt-6-sol", agent = AgentKind.CODEX))
        assertEquals("/w/b", r.workdir.value)
        assertEquals("/w/b", fetches().last().workdir)
        assertTrue(lastToken() != oldToken)
        r.receiveForTest(list(models = listOf("a-only"), requestId = oldToken))
        assertNull(r.modelListFor(AgentKind.CODEX)?.models?.firstOrNull { it == "a-only" })
    }

    @Test
    fun a_successful_cli_builtin_preview_is_reused_without_becoming_authoritative() {
        val r = repo()
        r.fetchModels(AgentKind.CODEX)
        r.receiveForTest(list(source = MODEL_CATALOG_SOURCE_CLI_BUILTIN, requestId = lastToken()))
        assertTrue(r.isModelListPreview(AgentKind.CODEX))
        r.fetchModels(AgentKind.CODEX)
        assertEquals(1, fetches().size)
        assertNull(r.modelCapabilities(AgentKind.CODEX, "gpt-6-sol"))
    }

    @Test
    fun opening_several_surfaces_sends_one_request_and_refresh_forces_a_new_one() {
        val r = repo()
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")
        assertEquals(1, fetches().size, "an outstanding request is not duplicated")
        assertFalse(fetches().single().forceRefresh)
        r.receiveForTest(list(requestId = lastToken()))
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")
        assertEquals(1, fetches().size, "a fresh answer is reused")
        r.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/other")
        assertEquals(2, fetches().size, "another directory is another catalog — never reused")
        r.refreshModels(AgentKind.CODEX, targetWorkdir = "/w/other")
        assertEquals(2, fetches().size, "a refresh while a request for the same target is out merges into it")
        r.receiveForTest(list(requestId = lastToken()))
        r.refreshModels(AgentKind.CODEX, targetWorkdir = "/w/other")
        assertEquals(3, fetches().size)
        assertTrue(fetches().last().forceRefresh, "the user's refresh bypasses the reuse window")
    }

    @Test
    fun the_advertisement_prefetches_once_and_other_agents_keep_their_one_request_per_call() {
        val r = repo()
        r.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
        assertEquals(1, fetches().size, "a daemon that advertises Codex is asked for its catalog")
        assertNotNull(fetches().single().requestId)
        r.fetchModels(AgentKind.CLAUDE); r.fetchModels(AgentKind.CLAUDE)
        assertEquals(2, sent.filterIsInstance<FetchModels>().count { it.agent == AgentKind.CLAUDE })
    }

    @Test
    fun leaving_the_computer_drops_the_preview_and_the_cue_and_unpairing_forgets_the_record() {
        val r = repo()
        r.openA()
        r.receiveForTest(list(requestId = lastToken()))
        val again = repo()
        again.fetchModels(AgentKind.CODEX, targetWorkdir = "/w/a")
        assertTrue(again.isModelListPreview(AgentKind.CODEX))
        again.disconnect()
        assertNull(again.modelListFor(AgentKind.CODEX), "nothing of the computer we left stays on screen")
        assertFalse(again.agentModelsRefreshing[AgentKind.CODEX] == true)
        assertNotNull(SecureStore.getString(key), "disconnecting keeps the record — the same computer may come back")

        again.unpair(account)
        assertNull(SecureStore.getString(key), "unpairing removes the identity the record is keyed by")
    }

    @Test
    fun a_codex_turn_that_refuses_the_model_rechecks_the_catalog_but_keeps_the_choice() {
        val r = repo()
        r.openA()
        r.receiveForTest(list(requestId = lastToken()))
        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals("gpt-6-sol", open.model)
        val before = fetches().size
        r.receiveForTest(TurnDone("c-a", error = "The model `gpt-6-sol` does not exist or you do not have access to it."))
        assertEquals(before + 1, fetches().size, "the catalog is re-checked")
        assertTrue(fetches().last().forceRefresh)
        assertEquals("gpt-6-sol", r.model.value, "the user's choice is untouched — nothing is switched or resent")
        assertEquals(1, sent.filterIsInstance<OpenSession>().size, "no automatic relaunch")
        r.receiveForTest(list(requestId = lastToken()))
        r.receiveForTest(TurnDone("c-a", error = "usage limit reached|1720000000"))
        assertEquals(before + 1, fetches().size, "an unrelated error triggers nothing")
    }
}
