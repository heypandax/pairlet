package dev.ccpocket.app.desktop

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.managed_sessions_error_store_corrupt
import dev.ccpocket.app.resources.managed_sessions_group_ambiguous
import dev.ccpocket.app.resources.managed_sessions_list_loading
import dev.ccpocket.app.resources.managed_sessions_remove
import dev.ccpocket.app.resources.managed_sessions_remove_confirm
import dev.ccpocket.app.resources.managed_sessions_remove_confirm_body
import dev.ccpocket.app.resources.managed_sessions_remove_failed
import dev.ccpocket.app.resources.managed_sessions_unavailable_open
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.session.ImportSessionsTags
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
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #360 review fixes on the desktop sidebar: MISSING rows, "group unclear", the loading hint beside other rows, the popup's Esc. */
@OptIn(ExperimentalTestApi::class)
class SidebarManagedRowsTest {
    private val acct = "acct-r"
    private val alpha = "/w/rowsproj"

    private class Live(val repo: PocketRepository, val model: RepoDesktopModel, val sent: MutableList<Frame>)

    /** [removeError] null = removal stays unanswered (pending); else the daemon refuses with that code. */
    private fun <T> live(answerList: Boolean, removeError: String? = null, block: (Live) -> T): T {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.demoMode.value = true
            repo.paired.value = PairedDaemon(relay = "wss://test", accountId = acct, daemonPub = "pk", deviceId = "dev", credential = "c")
            val sent = mutableListOf<Frame>()
            val daemonRows = listOf(
                SessionSummary("a1", "kept-title", "", 1, alpha, 2),
                SessionSummary("x1", "codex-title", "", 1, alpha, 1, agent = AgentKind.CODEX),
            )
            repo.onSendForTest = { f ->
                sent += f
                when (f) {
                    is ListSessions -> { repo.receiveForTest(Sessions(f.workdir, daemonRows, groups = emptyList())); throw CancellationException("answered") }
                    is ListManagedSessions -> if (answerList) {
                        repo.receiveForTest(
                            ManagedSessionsState(
                                f.requestId, f.workdir, canonicalWorkdir = f.workdir, revision = 1, allAgents = true, complete = true,
                                agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
                                items = listOf(
                                    ManagedSessionEntry("a1", AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = daemonRows[0].copy(cwd = ""), groupAmbiguous = true),
                                    ManagedSessionEntry("gone", AgentKind.CLAUDE, availability = ManagedAvailability.MISSING, lastKnownTitle = "vanished-title"),
                                ),
                            ),
                        )
                        throw CancellationException("answered")
                    }
                    is RemoveManagedSession -> if (removeError != null) {
                        repo.receiveForTest(ManagedSessionsState(f.requestId, f.workdir, f.agent, sessionId = f.sessionId, error = removeError))
                        throw CancellationException("answered")
                    }
                    else -> throw CancellationException("no transport in this test")
                }
            }
            repo.receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = listOf("claude")))
            val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
            model.openProject(DkProject(path = alpha, name = "rowsproj"))
            return block(Live(repo, model, sent))
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.show(model: DesktopModel) {
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides PocketContextMenuRepresentation) {
                    Box(Modifier.width(900.dp).height(700.dp)) { Sidebar(model) }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String) {
        onAllNodes(hasText(text)).onFirst().performClick()
        waitForIdle()
    }

    @Test
    fun a_row_whose_record_is_gone_is_not_opened_and_removal_asks_first() = runComposeUiTest {
        live(answerList = true) { l ->
            show(l.model)
            click("vanished-title")
            assertTrue(l.sent.none { it is OpenSession }, "a gone record must never be resumed: ${l.sent}")
            assertTrue(present(str(Res.string.managed_sessions_unavailable_open, "vanished-title")))

            click(str(Res.string.managed_sessions_remove))
            assertTrue(present(str(Res.string.managed_sessions_remove_confirm_body)), "what removing does and doesn't do, before it happens")
            assertTrue(l.sent.none { it is RemoveManagedSession }, "nothing is removed before the confirmation")

            Thread.sleep(dev.ccpocket.app.ui.session.REMOVE_CONFIRM_ARM_MS + 100) // deliberate, past the double-click guard
            click(str(Res.string.managed_sessions_remove_confirm))
            val remove = l.sent.filterIsInstance<RemoveManagedSession>().single()
            assertEquals("gone" to AgentKind.CLAUDE, remove.sessionId to remove.agent)
        }
    }

    @Test
    fun a_double_click_on_remove_never_removes() = runComposeUiTest {
        live(answerList = true) { l ->
            show(l.model)
            click("vanished-title")
            val removeLabel = str(Res.string.managed_sessions_remove)
            val spot = onAllNodes(hasText(removeLabel)).onFirst().fetchSemanticsNode().boundsInRoot.center
            // a double click: the second press lands on the same spot once the confirmation has replaced the button
            click(removeLabel)
            onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().performMouseInput { click(spot) }
            waitForIdle()
            assertTrue(l.sent.none { it is RemoveManagedSession }, "the spot under a double click is never the destructive verb")

            // and a click on "Remove" right as the confirmation appears is the tail of that click, not a decision
            click("vanished-title")
            click(removeLabel)
            click(str(Res.string.managed_sessions_remove_confirm))
            assertTrue(l.sent.none { it is RemoveManagedSession }, "a confirmation clicked within the guard window is ignored")

            Thread.sleep(dev.ccpocket.app.ui.session.REMOVE_CONFIRM_ARM_MS + 100)
            click(str(Res.string.managed_sessions_remove_confirm))
            assertEquals(1, l.sent.filterIsInstance<RemoveManagedSession>().size, "a deliberate confirmation still removes")
        }
    }

    @Test
    fun a_refused_removal_says_so_instead_of_vanishing() = runComposeUiTest {
        live(answerList = true, removeError = "managed_store_corrupt") { l ->
            show(l.model)
            click("vanished-title")
            click(str(Res.string.managed_sessions_remove))
            Thread.sleep(dev.ccpocket.app.ui.session.REMOVE_CONFIRM_ARM_MS + 100) // deliberate, past the double-click guard
            click(str(Res.string.managed_sessions_remove_confirm))
            assertNotNull(l.model.unavailableNotice?.error)
            assertTrue(present(str(Res.string.managed_sessions_remove_failed), substring = true))
            assertTrue(present(str(Res.string.managed_sessions_error_store_corrupt), substring = true))
        }
    }

    @Test
    fun the_notice_belongs_to_its_computer() = runComposeUiTest {
        live(answerList = true) { l ->
            show(l.model)
            click("vanished-title")
            assertNotNull(l.model.unavailableNotice)
            l.repo.paired.value = l.repo.paired.value!!.copy(accountId = "acct-other")
            assertNull(l.model.unavailableNotice, "another computer's list must not offer this computer's removal")
            l.model.removeFromManagedList()
            assertTrue(l.sent.none { it is RemoveManagedSession })
        }
    }

    @Test
    fun a_row_whose_group_cannot_be_attributed_says_so() = runComposeUiTest {
        live(answerList = true) { l ->
            show(l.model)
            assertTrue(present(str(Res.string.managed_sessions_group_ambiguous)))
        }
    }

    @Test
    fun the_loading_hint_shows_while_managed_rows_are_held_back_even_beside_other_agents_rows() = runComposeUiTest {
        live(answerList = false) { l ->
            show(l.model)
            assertTrue(l.repo.managedListLoading.value)
            assertTrue(present("codex-title"), "Codex is not managed here: its row shows")
            assertTrue(present(str(Res.string.managed_sessions_list_loading)), "…and the held-back Claude rows are announced, not silently absent")
        }
    }

    @Test
    fun the_import_popup_closes_on_escape_from_inside_it() = runComposeUiTest {
        live(answerList = true) { l ->
            l.model.openManagedImport(alpha)
            show(l.model)
            assertTrue(onAllNodes(hasTestTag(MANAGED_IMPORT_POPUP_TAG)).fetchSemanticsNodes().isNotEmpty())
            val search = onAllNodes(hasTestTag(ImportSessionsTags.SEARCH)).onFirst()
            search.performClick()
            waitForIdle()
            search.performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertNull(l.model.managedImport)
        }
    }

    @Test
    fun the_import_popup_closes_on_a_click_outside_it() = runComposeUiTest {
        live(answerList = true) { l ->
            l.model.openManagedImport(alpha)
            show(l.model)
            assertNotNull(l.model.managedImport)
            click("rowsproj") // the sidebar's own header lies outside the centred panel
            assertNull(l.model.managedImport)
            assertFalse(onAllNodes(hasTestTag(MANAGED_IMPORT_POPUP_TAG)).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
