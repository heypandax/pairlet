package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.managed_sessions_import_entry
import dev.ccpocket.app.resources.managed_sessions_unavailable
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ListManagedSessions
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #360 stage 2 on the phone's project session list: the import entry is capability-gated; a gone member is marked. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenManagedImportTest {
    private val dir = "/w/phoneproj"

    private fun repo(capable: Boolean) = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-p", daemonPub = "pk", deviceId = "dev", credential = "c")
        onSendForTest = { f ->
            if (f is ListManagedSessions) receiveForTest(
                ManagedSessionsState(
                    f.requestId, f.workdir, canonicalWorkdir = f.workdir, revision = 1, allAgents = true, complete = true,
                    agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
                    items = listOf(ManagedSessionEntry("gone", AgentKind.CLAUDE, availability = ManagedAvailability.MISSING, lastKnownTitle = "vanished-phone")),
                ),
            )
            throw CancellationException("no transport in this test")
        }
        if (capable) receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = listOf("claude")))
        receiveForTest(Sessions(dir, listOf(SessionSummary("s1", "legacy-row", "", 1, dir, 1)), groups = emptyList()))
    }

    @Test
    fun an_older_daemon_shows_no_import_entry_and_the_daemon_rows() = runComposeUiTest {
        val r = repo(capable = false)
        setContent { PocketTheme { SessionsScreen(r) } }
        waitForIdle()
        assertFalse(onAllNodes(hasContentDescription(str(Res.string.managed_sessions_import_entry))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(present("legacy-row"))
    }

    @Test
    fun a_capable_computer_offers_import_and_marks_a_gone_member() = runComposeUiTest {
        val r = repo(capable = true)
        setContent { PocketTheme { SessionsScreen(r) } }
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription(str(Res.string.managed_sessions_import_entry))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(present("vanished-phone"))
        assertTrue(present(str(Res.string.managed_sessions_unavailable), substring = true))
        assertFalse(present("legacy-row"), "a READY agent's outside session is not in the managed list")
    }
}
