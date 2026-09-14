package dev.ccpocket.app.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** #360 review fixes in the import screen: an UNINITIALIZED agent is switched on before importing; an unanswered enable is
 *  re-checked, not reported as failed; a corrupt store offers no import. */
@OptIn(ExperimentalTestApi::class)
class ManagedImportHostTest {
    private val here = ManagedScope("dev-a", "/w/app")

    private class Gw(
        var uninitialized: List<AgentKind>,
        var readOnly: Boolean = false,
        val enableAnswer: (AgentKind, Gw) -> EnableResult = { a, g -> g.uninitialized = g.uninitialized - a; EnableResult.Enabled },
    ) : ManagedSessionsGateway {
        val imports = mutableListOf<String>()
        override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?) =
            DiscoverResult.Page(listOf(DiscoveredSession(agent, "n1", "Candidate", null, System.currentTimeMillis(), false)), null, true)
        override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String): ImportResult {
            imports += nativeId
            return ImportResult.Imported(DiscoveredKey(agent, nativeId), false)
        }
        override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String) = RemoveResult.Removed
        override suspend fun status(scope: ManagedScope) = ManagedStatusResult.Statuses(uninitialized, readOnly)
        override suspend fun enable(scope: ManagedScope, agent: AgentKind) = enableAnswer(agent, this)
    }

    @Test
    fun an_agent_still_on_the_legacy_list_must_be_switched_on_before_its_sessions_can_be_imported() = runComposeUiTest {
        val gw = Gw(uninitialized = listOf(AgentKind.CLAUDE))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        assertPresent("Candidate")
        assertPresent(str(Res.string.managed_sessions_enable_required, "Claude"))
        assertFalse(present(str(Res.string.managed_sessions_import)), "no import before the agent is READY")

        onNodeWithText(str(Res.string.managed_sessions_enable_for, "Claude")).performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.managed_sessions_enable_required, "Claude")))
        onNodeWithText(str(Res.string.managed_sessions_import)).performClick()
        waitForIdle()
        assertEquals(listOf("n1"), gw.imports)
    }

    @Test
    fun an_enable_without_an_answer_is_re_checked_and_turns_into_success_when_the_daemon_did_commit_it() = runComposeUiTest {
        // the daemon commits READY but the answer is lost
        val gw = Gw(uninitialized = listOf(AgentKind.CLAUDE), enableAnswer = { a, g -> g.uninitialized = g.uninitialized - a; EnableResult.Failure(ManagedSessionsError.UNCONFIRMED) })
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        onNodeWithText(str(Res.string.managed_sessions_enable_for, "Claude")).performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.managed_sessions_error_unconfirmed), substring = true), "a committed enable is not reported as unconfirmed")
        assertFalse(present(str(Res.string.managed_sessions_enable_body)), "the notice is gone: the agent is READY")
        assertPresent(str(Res.string.managed_sessions_import))
    }

    @Test
    fun an_enable_without_an_answer_that_was_not_committed_says_unconfirmed_not_failed() = runComposeUiTest {
        val gw = Gw(uninitialized = listOf(AgentKind.CLAUDE), enableAnswer = { _, _ -> EnableResult.Failure(ManagedSessionsError.UNCONFIRMED) })
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        onNodeWithText(str(Res.string.managed_sessions_enable_for, "Claude")).performClick()
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_error_unconfirmed), substring = true)
    }

    /** A gateway whose status answer is under the test's control. */
    private class SlowStatusGw(val answers: ArrayDeque<kotlinx.coroutines.CompletableDeferred<ManagedStatusResult>>) : ManagedSessionsGateway {
        override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?) =
            DiscoverResult.Page(listOf(DiscoveredSession(agent, "n1", "Candidate", null, System.currentTimeMillis(), false)), null, true)
        override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String) = ImportResult.Imported(DiscoveredKey(agent, nativeId), false)
        override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String) = RemoveResult.Removed
        override suspend fun status(scope: ManagedScope): ManagedStatusResult = answers.removeFirst().await()
    }

    @Test
    fun until_the_status_arrives_nothing_can_be_imported() = runComposeUiTest {
        val pending = kotlinx.coroutines.CompletableDeferred<ManagedStatusResult>()
        val gw = SlowStatusGw(ArrayDeque(listOf(pending)))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        assertPresent("Candidate")
        assertFalse(present(str(Res.string.managed_sessions_import)), "the agent may still be UNINITIALIZED — no import before the daemon says")
        pending.complete(ManagedStatusResult.Statuses(emptyList(), readOnly = false))
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_import))
    }

    @Test
    fun a_failed_status_shows_an_error_with_retry_and_never_unlocks_import() = runComposeUiTest {
        val failed = kotlinx.coroutines.CompletableDeferred<ManagedStatusResult>(ManagedStatusResult.Failure(ManagedSessionsError.DISCONNECTED))
        val ok = kotlinx.coroutines.CompletableDeferred<ManagedStatusResult>(ManagedStatusResult.Statuses(emptyList(), readOnly = false))
        val gw = SlowStatusGw(ArrayDeque(listOf(failed, ok)))
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_error_disconnected), substring = true)
        assertFalse(present(str(Res.string.managed_sessions_import)))
        onNodeWithText(str(Res.string.managed_sessions_retry)).performClick()
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_import))
    }

    @Test
    fun a_read_only_store_says_why_and_offers_no_import() = runComposeUiTest {
        val gw = Gw(uninitialized = emptyList(), readOnly = true)
        setContent { PocketTheme { Box(Modifier.size(420.dp, 800.dp)) { ManagedImportHost(gw, here, listOf(AgentKind.CLAUDE), onLocate = {}, onClose = {}) } } }
        waitForIdle()
        assertPresent(str(Res.string.managed_sessions_read_only))
        assertFalse(present(str(Res.string.managed_sessions_import)))
    }
}
