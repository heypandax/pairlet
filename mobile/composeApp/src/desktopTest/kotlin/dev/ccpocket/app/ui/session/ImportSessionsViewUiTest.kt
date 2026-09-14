package dev.ccpocket.app.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #360 stage 2, user-visible: the import screen driven by a real controller over a fake gateway. */
@OptIn(ExperimentalTestApi::class)
class ImportSessionsViewUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val here = ManagedScope("dev-a", "/w/app")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun row(id: String, agent: AgentKind = AgentKind.CLAUDE, managed: Boolean = false, prompt: String? = "first question $id") =
        DiscoveredSession(agent, id, "Title $id", prompt, System.currentTimeMillis(), managed)

    /** Answers are queued per call; an unqueued call suspends until the test completes it. */
    private class FakeGateway : ManagedSessionsGateway {
        val discovers = mutableListOf<Triple<AgentKind, String, String?>>()
        val imports = mutableListOf<Pair<ManagedScope, String>>()
        var nextDiscover: (AgentKind, String, String?) -> DiscoverResult = { _, _, _ -> DiscoverResult.Page(emptyList(), null, true) }
        var importAnswer: CompletableDeferred<ImportResult>? = null
        var importResult: (String) -> ImportResult = { ImportResult.Imported(DiscoveredKey(AgentKind.CLAUDE, it), false) }

        override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?): DiscoverResult {
            discovers += Triple(agent, query, cursor)
            return nextDiscover(agent, query, cursor)
        }
        override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String): ImportResult {
            imports += scope to nativeId
            return importAnswer?.await() ?: importResult(nativeId)
        }
        override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String): RemoveResult = RemoveResult.Removed
    }

    private fun controller(gw: FakeGateway, onImported: (ImportSessionsEffect.Imported) -> Unit = {}) =
        ImportSessionsController(gw, scope, debounceMillis = 0, onImported = onImported)

    @Test
    fun rows_show_title_first_prompt_and_import_while_already_managed_rows_are_marked() = runComposeUiTest {
        val gw = FakeGateway().apply { nextDiscover = { _, _, _ -> DiscoverResult.Page(listOf(row("s1"), row("s2", managed = true)), null, true) } }
        val c = controller(gw)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        assertPresent("Title s1")
        assertPresent("first question s1")
        assertPresent(str(Res.string.managed_sessions_imported))
        assertPresent(str(Res.string.managed_sessions_import))
        assertPresent(str(Res.string.managed_sessions_import_hint))
    }

    @Test
    fun importing_shows_progress_then_imported_and_hands_the_session_to_the_host_without_a_second_request() = runComposeUiTest {
        val gw = FakeGateway().apply {
            nextDiscover = { _, _, _ -> DiscoverResult.Page(listOf(row("s1")), null, true) }
            importAnswer = CompletableDeferred()
        }
        val located = mutableListOf<ImportSessionsEffect.Imported>()
        val c = controller(gw) { located += it }
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()

        onNodeWithText(str(Res.string.managed_sessions_import)).performClick()
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_importing))
        assertFalse(present(str(Res.string.managed_sessions_import)), "no import button while in flight")
        c.dispatch(ImportSessionsEvent.ImportClicked(DiscoveredKey(AgentKind.CLAUDE, "s1")))
        assertEquals(1, gw.imports.size, "a repeat click must not send a second import")

        gw.importAnswer!!.complete(ImportResult.Imported(DiscoveredKey(AgentKind.CLAUDE, "s1"), false))
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_imported))
        assertEquals(listOf(ImportSessionsEffect.Imported(here, DiscoveredKey(AgentKind.CLAUDE, "s1"), false)), located)
    }

    @Test
    fun a_failed_import_says_why_and_offers_retry() = runComposeUiTest {
        val gw = FakeGateway().apply {
            nextDiscover = { _, _, _ -> DiscoverResult.Page(listOf(row("s1")), null, true) }
            importResult = { ImportResult.Failure(ManagedSessionsError.NOT_FOUND) }
        }
        val c = controller(gw)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        onNodeWithText(str(Res.string.managed_sessions_import)).performClick()
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_error_not_found), substring = true)
        onNodeWithText(str(Res.string.managed_sessions_retry)).performClick()
        waitForIdle()
        assertEquals(2, gw.imports.size)
    }

    @Test
    fun search_and_agent_filter_ask_the_gateway_and_empty_results_name_the_query() = runComposeUiTest {
        val gw = FakeGateway().apply {
            nextDiscover = { agent, q, _ ->
                if (agent == AgentKind.CODEX) DiscoverResult.Page(listOf(row("cx", AgentKind.CODEX)), null, true)
                else DiscoverResult.Page(emptyList(), null, true)
            }
        }
        val c = controller(gw)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_empty))

        onNodeWithTag(ImportSessionsTags.SEARCH).performTextInput("zebra")
        waitForIdle()
        assertTrue(gw.discovers.any { it.first == AgentKind.CLAUDE && it.second == "zebra" }, "the search text reaches the gateway: ${gw.discovers}")
        assertPresent(str(Res.string.managed_sessions_empty_query, "zebra"))

        onNodeWithText("Codex").performClick()
        waitForIdle()
        assertEquals(Triple(AgentKind.CODEX, "zebra", null), gw.discovers.last())
        assertPresent("Title cx")
    }

    @Test
    fun partial_results_warn_and_load_more_appends() = runComposeUiTest {
        val gw = FakeGateway().apply {
            nextDiscover = { _, _, cursor ->
                if (cursor == null) DiscoverResult.Page(listOf(row("p1")), "c1", complete = false, DiscoverDiagnostic.SCAN_ERROR)
                else DiscoverResult.Page(listOf(row("p2")), null, complete = true)
            }
        }
        val c = controller(gw)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(700.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_partial_scan_error))
        onNodeWithText(str(Res.string.managed_sessions_load_more)).performClick()
        waitForIdle()
        assertPresent("Title p1")
        assertPresent("Title p2")
        assertFalse(present(str(Res.string.managed_sessions_load_more)), "no more pages")
        assertPresent(str(Res.string.managed_sessions_partial_scan_error)) // one partial page keeps the list partial
    }

    @Test
    fun a_failed_load_explains_and_retry_recovers() = runComposeUiTest {
        var fail = true
        val gw = FakeGateway().apply {
            nextDiscover = { _, _, _ -> if (fail) DiscoverResult.Failure(ManagedSessionsError.DISCONNECTED) else DiscoverResult.Page(listOf(row("ok")), null, true) }
        }
        val c = controller(gw)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_failed))
        assertPresent(str(Res.string.managed_sessions_error_disconnected))
        fail = false
        onNodeWithText(str(Res.string.managed_sessions_retry)).performClick()
        waitForIdle()
        assertPresent("Title ok")
    }

    @Test
    fun an_answer_for_the_previous_computer_never_appears_after_switching() = runComposeUiTest {
        val slow = CompletableDeferred<DiscoverResult>()
        val gw = object : ManagedSessionsGateway {
            override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?): DiscoverResult =
                if (scope.computerId == "dev-a") slow.await() else DiscoverResult.Page(listOf(row("fromB")), null, true)
            override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String) = ImportResult.Failure(ManagedSessionsError.INTERNAL)
            override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String) = RemoveResult.Removed
        }
        val c = ImportSessionsController(gw, scope, debounceMillis = 0)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        c.dispatch(ImportSessionsEvent.Open(ManagedScope("dev-b", "/w/app")))
        waitForIdle()
        slow.complete(DiscoverResult.Page(listOf(row("fromA")), null, true))
        waitForIdle()
        assertPresent("Title fromB")
        assertFalse(present("Title fromA"), "a late page from the previous computer must be dropped")
    }

    @Test
    fun the_first_use_notice_explains_and_enables_one_agent_then_goes_away() = runComposeUiTest {
        var uninitialized = listOf(AgentKind.CLAUDE, AgentKind.CODEX)
        val enabled = mutableListOf<AgentKind>()
        val gw = object : ManagedSessionsGateway {
            override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?) = DiscoverResult.Page(emptyList(), null, true)
            override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String) = ImportResult.Failure(ManagedSessionsError.INTERNAL)
            override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String) = RemoveResult.Removed
            override suspend fun status(scope: ManagedScope) = ManagedStatusResult.Statuses(uninitialized, readOnly = false)
            override suspend fun enable(scope: ManagedScope, agent: AgentKind): EnableResult {
                enabled += agent
                return if (agent == AgentKind.CODEX) EnableResult.Failure(ManagedSessionsError.SCAN_INCOMPLETE, DiscoverDiagnostic.PERMISSION)
                else { uninitialized = uninitialized - agent; EnableResult.Enabled }
            }
        }
        setContent {
            PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, IMPORTABLE_AGENTS, onLocate = {}, onClose = {}) } }
        }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_enable_body))

        onNodeWithText(str(Res.string.managed_sessions_enable_for, "Codex")).performClick()
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_error_scan_incomplete), substring = true)
        assertPresent(str(Res.string.managed_sessions_partial_permission), substring = true)

        onNodeWithText(str(Res.string.managed_sessions_enable_for, "Claude")).performClick()
        waitForIdle()
        assertEquals(listOf(AgentKind.CODEX, AgentKind.CLAUDE), enabled)
        assertFalse(present(str(Res.string.managed_sessions_enable_for, "Claude")), "an enabled agent has no button left")
        assertPresent(str(Res.string.managed_sessions_enable_for, "Codex"))
    }

    @Test
    fun a_gateway_that_throws_surfaces_as_a_failure_not_a_hang() = runComposeUiTest {
        val gw = object : ManagedSessionsGateway {
            override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?): DiscoverResult = error("adapter bug")
            override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String): ImportResult = error("adapter bug")
            override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String): RemoveResult = error("adapter bug")
        }
        val c = ImportSessionsController(gw, scope, debounceMillis = 0)
        c.dispatch(ImportSessionsEvent.Open(here))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 700.dp)) { ImportSessionsScreen(c) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_error_internal))
        assertFalse(onAllNodes(hasText(str(Res.string.managed_sessions_loading))).fetchSemanticsNodes().isNotEmpty())
    }
}
