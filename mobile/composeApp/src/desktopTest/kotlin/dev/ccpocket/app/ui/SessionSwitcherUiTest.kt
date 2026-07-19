package dev.ccpocket.app.ui
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.SessionWorkingSet
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.switcher_current
import dev.ccpocket.app.resources.switcher_empty
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.DirectoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/**
 * End-to-end wiring for the cross-project session switcher (issue #165). SessionWorkingSetTest pins the
 * rules as pure functions; what these cover is the part that cannot be proved that way — that a live
 * [PocketRepository]'s state actually reaches the sheet, and that a tap on a row moves the app.
 *
 * Every repo is pinned to a synthetic account so the working set is keyed somewhere no real pairing (or
 * another test) can reach: the MRU is persisted per computer, so a shared key would let these read the
 * developer's own switcher history — and each other's.
 */
@OptIn(ExperimentalTestApi::class)
class SessionSwitcherUiTest {
    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )
    private fun liveDir(path: String, sid: String, title: String, executing: Boolean = false) = DirectoryEntry(
        path = path, name = path.substringAfterLast('/'), isDir = true, open = true,
        activeSessions = listOf(ActiveSession(sessionId = sid, title = title, executing = executing)),
        activeSessionId = sid, activeSessionTitle = title,
    )
    /** Standing in one project's session, with another project's alive and a third only remembered. */
    private fun seeded(scope: CoroutineScope, id: String) = PocketRepository(scope, account(id)).apply {
        directories.add(liveDir("/w/relay", "s-relay", "Fix flaky retry backoff", executing = true))
        workdir.value = "/w/pocket"; sessionKey.value = "s-pocket"; convoId.value = "c1"
        chatTitle.value = "Refactor auth module"
        rememberOpenedSession("/w/billing", "s-billing", "Wire up usage endpoint", null)
        rememberOpenedSession("/w/pocket", "s-pocket", "Refactor auth module", null) // current lands on top
    }
    @Test
    fun sheetShowsCurrentRunningAndRecentAcrossProjects() = runComposeUiTest {
        setContent {
            val repo = seeded(rememberCoroutineScope(), "acct-shows")
            PocketTheme { SessionSwitcherSheet(repo.workingSet(), onSelect = {}, onAllProjects = {}, onDismiss = {}) }
        }
        waitForIdle()
        assertPresent("Refactor auth module")                          // current, pinned on top…
        assertPresent(runBlocking { getString(Res.string.switcher_current) })
        assertPresent("Fix flaky retry backoff")                       // …a live session in another project…
        assertPresent("relay")
        assertPresent("Wire up usage endpoint")                        // …and one only this device remembers,
        assertPresent("billing")                                       // whose project the daemon never listed
    }
    /**
     * Demo mode answers the resulting OpenSession locally, so this exercises the real send path without a
     * link — switching is a navigation move and must not depend on one being up to change the back stack.
     */
    @Test
    fun aTapLandsInThatSessionAndPointsBackAtItsProject() = runComposeUiTest {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-tap")).apply {
                    enterDemo()
                    directories.add(liveDir("/w/relay", "s-relay", "Fix flaky retry backoff", executing = true))
                    workdir.value = "/w/pocket"; sessionKey.value = "s-pocket"; convoId.value = "c1"
                    chatTitle.value = "Refactor auth module"
                }
            }
            PocketTheme {
                SessionSwitcherSheet(repo.workingSet(), onSelect = { repo.switchToSession(it) }, onAllProjects = {}, onDismiss = {})
            }
        }
        waitForIdle()
        onAllNodes(hasText("Fix flaky retry backoff")).onFirst().performClick()
        waitForIdle()
        // the back stack now points at the TARGET's project, not the one we came from — otherwise backing
        // out of a switched-into session reads as the switch having been undone
        assertEquals("/w/relay", repo.sessionsDir.value)
        assertEquals("s-relay", repo.sessionKey.value) // and the chat adopted the target's identity
        // the switch RELEASES the router once it lands. A stuck flag would strand the user on a chat
        // that never fills, with no way back — the dangerous half of holding the screen (issue #165)
        assertTrue(!repo.switchingSession.value, "a landed switch must release the router")
    }

    /**
     * The router shows the session LIST whenever convoId is null, and [PocketRepository.openSession] nulls
     * it while it waits for the daemon. Shipped that way, the switcher visibly bounced you out to a list
     * for a beat on the way to the session you tapped. The chat has to stay mounted across the round trip.
     */
    @Test
    fun switchingHoldsTheChatInsteadOfBouncingOutToTheList() = runComposeUiTest {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            // NOT demo: nothing answers, so the in-flight window stays open to be observed
            repo = remember {
                PocketRepository(scope, account("acct-hold")).apply {
                    directories.add(liveDir("/w/relay", "s-relay", "Fix flaky retry backoff"))
                    workdir.value = "/w/pocket"; sessionKey.value = "s-pocket"; convoId.value = "c1"
                    chatTitle.value = "Refactor auth module"
                }
            }
            PocketTheme { }
        }
        waitForIdle()
        val target = repo.workingSet().running.first { it.sessionId == "s-relay" }
        repo.switchToSession(target)
        // convoId is null mid-flight (openSession cleared it) — the flag is what keeps the router on chat
        assertTrue(repo.switchingSession.value, "an in-flight switch must hold the chat")
    }

    /** Switching from the project LIST has no chat to hold — the router must keep showing the list. */
    @Test
    fun switchingFromTheListDoesNotClaimAChat() = runComposeUiTest {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-fromlist")).apply {
                    directories.add(liveDir("/w/relay", "s-relay", "Fix flaky retry backoff"))
                    sessionsDir.value = "/w/pocket" // standing on a list, no conversation open
                }
            }
            PocketTheme { }
        }
        waitForIdle()
        repo.switchToSession(repo.workingSet().running.first())
        assertTrue(!repo.switchingSession.value, "no chat was on screen, so none should be held")
    }

    @Test
    fun theCurrentSessionIsNotOfferedAsSomewhereToGo() = runComposeUiTest {
        lateinit var set: SessionWorkingSet
        setContent {
            set = seeded(rememberCoroutineScope(), "acct-current").workingSet()
            PocketTheme { SessionSwitcherSheet(set, onSelect = {}, onAllProjects = {}, onDismiss = {}) }
        }
        waitForIdle()
        assertEquals(2, set.otherCount) // relay (running) + billing (recent) — never the one on screen
        assertTrue(set.running.none { it.current } && set.recent.none { it.current })
        assertEquals("Refactor auth module", set.current?.title)
    }
    @Test
    fun aSoloSessionGetsTheEmptyStateAndNoChip() = runComposeUiTest {
        lateinit var set: SessionWorkingSet
        setContent {
            val repo = PocketRepository(rememberCoroutineScope(), account("acct-solo")).apply {
                workdir.value = "/w/pocket"; sessionKey.value = "s-pocket"; convoId.value = "c1"
                chatTitle.value = "Refactor auth module"
                rememberOpenedSession("/w/pocket", "s-pocket", "Refactor auth module", null)
            }
            set = repo.workingSet()
            PocketTheme {
                SessionSwitcherSheet(set, onSelect = {}, onAllProjects = {}, onDismiss = {})
                SessionStackChip(set.otherCount, set.attention) {} // draws nothing at zero
            }
        }
        waitForIdle()
        assertEquals(0, set.otherCount)
        assertPresent(runBlocking { getString(Res.string.switcher_empty) })
        assertTrue(!present("0"), "a zero chip would be pure noise in an already dense header")
    }
    /**
     * The MRU is per computer: its rows name sessions by path + id on ONE machine, so a switcher that
     * carried them across a machine switch would offer rows that open nothing over there.
     */
    @Test
    fun anotherComputersSessionsAreNotOffered() = runComposeUiTest {
        lateinit var other: SessionWorkingSet
        setContent {
            val scope = rememberCoroutineScope()
            seeded(scope, "acct-machine-a") // machine A remembers three sessions…
            other = PocketRepository(scope, account("acct-machine-b")).apply {
                workdir.value = "/w/elsewhere"; sessionKey.value = "s-elsewhere"; convoId.value = "c9"
                chatTitle.value = "Something else"
            }.workingSet()
        }
        waitForIdle()
        assertEquals(0, other.otherCount, "machine B must not offer machine A's sessions")
    }
}
