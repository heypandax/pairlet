package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.managed_sessions_error_denied
import dev.ccpocket.app.resources.managed_sessions_group_ambiguous
import dev.ccpocket.app.resources.managed_sessions_remove
import dev.ccpocket.app.resources.managed_sessions_remove_confirm
import dev.ccpocket.app.resources.managed_sessions_remove_confirm_body
import dev.ccpocket.app.resources.managed_sessions_remove_failed
import dev.ccpocket.app.resources.managed_sessions_unavailable_open
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListManagedSessions
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #360 review fixes on the phone list: a MISSING row is never resumed, removal asks first and reports a refusal, and
 *  "group unclear" is shown. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenManagedRowsTest {
    private val dir = "/w/phonerows"
    private val sent = mutableListOf<Frame>()

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        demoMode.value = true
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-p", daemonPub = "pk", deviceId = "dev", credential = "c")
        onSendForTest = { f ->
            sent += f
            when (f) {
                is ListManagedSessions -> {
                    receiveForTest(
                        ManagedSessionsState(
                            f.requestId, f.workdir, canonicalWorkdir = f.workdir, revision = 1, allAgents = true, complete = true,
                            agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
                            items = listOf(
                                ManagedSessionEntry("gone", AgentKind.CLAUDE, availability = ManagedAvailability.MISSING, lastKnownTitle = "vanished-phone"),
                                ManagedSessionEntry("s1", AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = SessionSummary("s1", "kept-phone", "", 1, "", 1), groupAmbiguous = true),
                            ),
                        ),
                    )
                    throw CancellationException("answered")
                }
                is RemoveManagedSession -> {
                    receiveForTest(ManagedSessionsState(f.requestId, f.workdir, f.agent, sessionId = f.sessionId, error = "managed_forbidden"))
                    throw CancellationException("answered")
                }
                else -> throw CancellationException("no transport in this test")
            }
        }
        receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = listOf("claude")))
        receiveForTest(Sessions(dir, listOf(SessionSummary("s1", "kept-phone", "", 1, dir, 1)), groups = emptyList()))
    }

    @Test
    fun tapping_a_gone_member_explains_asks_before_removing_and_reports_a_refusal() = runComposeUiTest {
        val r = repo()
        setContent { PocketTheme { SessionsScreen(r) } }
        waitForIdle()
        assertTrue(present(str(Res.string.managed_sessions_group_ambiguous), substring = true))

        onAllNodes(hasText("vanished-phone")).onFirst().performClick()
        waitForIdle()
        assertTrue(sent.none { it is OpenSession }, "a gone record must never be resumed: $sent")
        assertTrue(present(str(Res.string.managed_sessions_unavailable_open, "vanished-phone")))

        onAllNodes(hasText(str(Res.string.managed_sessions_remove))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.managed_sessions_remove_confirm_body)))
        assertTrue(sent.none { it is RemoveManagedSession }, "nothing is removed before the confirmation")

        Thread.sleep(dev.ccpocket.app.ui.session.REMOVE_CONFIRM_ARM_MS + 100) // a deliberate confirmation, past the double-click guard
        onAllNodes(hasText(str(Res.string.managed_sessions_remove_confirm))).onFirst().performClick()
        waitForIdle()
        val remove = sent.filterIsInstance<RemoveManagedSession>().single()
        assertEquals("gone" to AgentKind.CLAUDE, remove.sessionId to remove.agent)
        assertTrue(present(str(Res.string.managed_sessions_remove_failed), substring = true), "a refused removal is reported")
        assertTrue(present(str(Res.string.managed_sessions_error_denied), substring = true))
    }
}
