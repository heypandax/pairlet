package dev.ccpocket.app.desktop

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.managed_sessions_import_entry
import dev.ccpocket.app.resources.managed_sessions_unavailable
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.session.DiscoveredKey
import dev.ccpocket.app.ui.session.ImportSessionsEffect
import dev.ccpocket.app.ui.session.ManagedScope
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #360 stage 2 on the desktop sidebar: the RECENT project menu offers "Import from local history…" only on a computer
 * that advertised the managed list, for THAT header's own path; an import is located by listing its project and
 * publishing a reveal; a managed member whose record is gone stays in place and says so.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarManagedImportTest {
    private val acct = "acct-m"
    private val alpha = "/w/alphaproj"
    private val beta = "/w/betaproj"

    private fun row(id: String, dir: String) = SessionSummary(id, title = "title-$id", firstPrompt = "", messageCount = 1, cwd = dir, lastModified = 1)

    private class Harness(val repo: PocketRepository, val model: RepoDesktopModel, val sent: MutableList<Frame>)

    private fun <T> withModel(capable: Boolean, managedMissing: Boolean = false, block: (Harness) -> T): T {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.demoMode.value = true
            repo.paired.value = PairedDaemon(relay = "wss://test", accountId = acct, daemonPub = "pk", deviceId = "dev", credential = "c")
            val sent = mutableListOf<Frame>()
            val rows = mapOf(alpha to listOf(row("a1", alpha)), beta to listOf(row("b1", beta)))
            repo.onSendForTest = { f ->
                sent += f
                when (f) {
                    is ListSessions -> repo.receiveForTest(Sessions(f.workdir, rows[f.workdir].orEmpty(), groups = emptyList()))
                    is ListManagedSessions -> repo.receiveForTest(
                        ManagedSessionsState(
                            f.requestId, f.workdir, canonicalWorkdir = f.workdir, revision = 1, allAgents = true, complete = true,
                            agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
                            items = rows[f.workdir].orEmpty().map { ManagedSessionEntry(it.sessionId, AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = it) } +
                                if (managedMissing) listOf(ManagedSessionEntry("gone", AgentKind.CLAUDE, availability = ManagedAvailability.MISSING, lastKnownTitle = "vanished-title")) else emptyList(),
                        ),
                    )
                    else -> Unit
                }
                throw CancellationException("no transport in this test")
            }
            if (capable) repo.receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = listOf("claude", "codex")))
            val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
            model.openProject(DkProject(path = beta, name = "betaproj"))
            model.openProject(DkProject(path = alpha, name = "alphaproj")) // alpha is listed; beta is a RECENT snapshot
            return block(Harness(repo, model, sent))
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.show(model: DesktopModel) {
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides PocketContextMenuRepresentation) {
                    Box(Modifier.height(640.dp)) { Sidebar(model) }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.rightClickHeader(name: String) {
        onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst().performMouseInput { rightClick(center) }
        waitForIdle()
    }

    @Test
    fun an_older_daemon_keeps_the_project_menu_exactly_as_it_was() = runComposeUiTest {
        withModel(capable = false) { h ->
            show(h.model)
            rightClickHeader("betaproj")
            assertFalse(present(str(Res.string.managed_sessions_import_entry)))
            assertFalse(h.model.canImportManagedSessions)
            assertTrue(h.sent.none { it is ListManagedSessions }, "no managed frame may reach a daemon that never advertised it")
        }
    }

    @Test
    fun the_import_entry_opens_the_panel_for_that_headers_own_project_even_when_it_is_not_listed() = runComposeUiTest {
        withModel(capable = true) { h ->
            show(h.model)
            rightClickHeader("betaproj")
            onAllNodes(hasText(str(Res.string.managed_sessions_import_entry))).onFirst().performClick()
            waitForIdle()
            assertEquals(ManagedScope(acct, beta), h.model.managedImport?.scope, "explicit workdir, not the listed alpha")
            assertEquals(alpha, h.repo.sessionsDir.value, "opening the panel navigates nowhere")
            h.model.closeManagedImport()
            assertNull(h.model.managedImport)
        }
    }

    @Test
    fun locating_an_import_in_another_project_lists_it_and_reveals_the_row_without_sending_anything_else() = runComposeUiTest {
        withModel(capable = true) { h ->
            show(h.model)
            h.model.openManagedImport(beta)
            h.sent.clear()
            h.model.locateImportedSession(ImportSessionsEffect.Imported(ManagedScope(acct, beta), DiscoveredKey(AgentKind.CLAUDE, "b1"), alreadyManaged = false))
            waitForIdle()
            assertNull(h.model.managedImport)
            assertEquals(beta, h.repo.sessionsDir.value)
            assertEquals("b1", h.model.projectListReveal?.sessionId)
            assertTrue(h.sent.all { it is ListSessions || it is ListManagedSessions }, "locating must never open, resume or prompt: ${h.sent}")

            // a late result for a computer we are no longer on changes nothing
            val reveal = h.model.projectListReveal
            h.model.locateImportedSession(ImportSessionsEffect.Imported(ManagedScope("acct-gone", alpha), DiscoveredKey(AgentKind.CLAUDE, "a1"), false))
            assertEquals(reveal, h.model.projectListReveal)
        }
    }

    @Test
    fun a_missing_managed_member_keeps_its_row_and_is_marked_unavailable() = runComposeUiTest {
        withModel(capable = true, managedMissing = true) { h ->
            show(h.model)
            assertTrue(present("vanished-title"))
            assertTrue(present(str(Res.string.managed_sessions_unavailable)))
        }
    }
}
