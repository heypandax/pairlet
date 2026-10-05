package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ApprovalPrefs
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.SlashCommand
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.Usage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone switches computers through [PocketRepository.disconnect]. Everything that describes the
 * computer being left must go with it — otherwise computer A's schedules, usage, archive rows and
 * filesystem roots render under computer B, and acting on them sends A's ids to B.
 */
class DisconnectPerComputerStateTest {

    @Test
    fun disconnectDropsTheLeftComputersSchedulesUsageArchiveAndRoots() {
        val r = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
        }
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
        r.receiveForTest(ScheduleState(items = listOf(ScheduleInfo(id = "sched-a", workdir = "/w", prompt = "p", nextRunAtMs = 5))))
        r.fetchUsage()
        r.receiveForTest(Usage(tokensToday = 42))
        r.fetchUsage() // a refresh still in flight when the user switches away
        r.receiveForTest(ArchivedSessions(listOf(SessionSummary("old-a", "t", "p", 1, "/w", 0))))
        r.receiveForTest(PathEntries(workdir = PocketRepository.BROWSE_HOME, subPath = "", roots = listOf("C:\\")))
        r.receiveForTest(TurnDone("c1", error = "usage limit reached|1720000000", usageLimitResetAt = 1_720_000_000_000))
        // preconditions: A's data is on screen
        assertTrue(r.schedulesLoaded.value)
        assertNotNull(r.usage.value)
        assertEquals(1, r.archivedSessions.size)
        assertEquals(listOf("C:\\"), r.browseRoots.value)
        assertNotNull(r.limitOffer.value)
        assertTrue(r.usageLoading.value)

        r.disconnect()

        assertTrue(r.schedules.isEmpty(), "A's schedules must not list under B")
        assertFalse(r.schedulesLoaded.value, "B's list is 'still loading', not A's 'loaded'")
        assertFalse(r.schedulesUnavailable.value)
        assertNull(r.scheduleError.value)
        assertNull(r.usage.value)
        assertNull(r.usageAgent.value)
        assertFalse(r.usageLoading.value)
        assertTrue(r.archivedSessions.isEmpty())
        assertEquals(emptyList(), r.browseRoots.value)
        assertNull(r.limitOffer.value, "the auto-continue offer targets a session on A")
        assertNull(r.limitConfirmed.value)
        assertNull(r.repairOffer.value)
        assertNull(r.repairProgress.value)
    }

    /**
     * The per-computer caches the reset inventory used to mark GAP: each one is visible (or acted on) after a
     * switch — prefs gate a settings switch on "the daemon answered", the archive toast's action re-sends A's
     * row, the rewind sheet confirms A's anchor, A's slash commands and @-listing complete B's composer.
     */
    @Test
    fun disconnectDropsDaemonPrefsTransientSheetsSlashCommandsAndSessionPanels() {
        val r = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
        }
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
        r.receiveForTest(PushPrefs(enabled = false))
        r.receiveForTest(ApprovalPrefs(noAutoDeny = true, fullControlExpiryMs = 60_000))
        r.receiveForTest(CommandList("c1", listOf(SlashCommand("deploy-a"))))
        r.receiveForTest(PathEntries(workdir = "/w", subPath = "", entries = listOf(PathEntry("secret-a.txt", isDir = false))))
        r.setSessionArchived("/w", "sid-old", archived = true, title = "old")
        r.rewindSheet.value = PocketRepository.RewindSheet(PocketRepository.RewindTarget("c1", 3, "u", "t", "rewind"))
        r.rewindError.value = "stale_anchor"
        r.runShell("ls")
        r.fetchChangedFiles()
        r.fetchGitStatus()
        // preconditions
        assertEquals(false, r.pushPrefs.value)
        assertEquals(true, r.approvalPrefs.value)
        assertEquals(1, r.slashCommands.size)
        assertNotNull(r.pathListing.value)
        assertNotNull(r.archiveToast.value)
        assertTrue(r.terminalBusy.value && r.changedFilesLoading.value && r.gitStatusLoading.value)

        r.disconnect()

        assertNull(r.pushPrefs.value, "B answers for itself — null is the 'not answered' gate")
        assertNull(r.approvalPrefs.value)
        assertNull(r.approvalFullControlExpiryMs.value)
        assertTrue(r.slashCommands.isEmpty(), "A's commands must not complete B's composer")
        assertNull(r.pathListing.value, "openSession keeps pathListing — the switch has to drop it")
        assertNull(r.archiveToast.value, "its action would re-send A's (workdir, session) to B")
        assertNull(r.rewindSheet.value)
        assertNull(r.rewindError.value)
        assertTrue(r.terminalEntries.isEmpty())
        assertFalse(r.terminalBusy.value)
        assertFalse(r.changedFilesLoading.value)
        assertFalse(r.changedFilesUnavailable.value)
        assertFalse(r.gitStatusLoading.value)
    }

    private fun liveTurnOnA(): PocketRepository = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "cred")
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = true, title = "Fix the build"))
        assertTrue(streaming.value, "precondition: A's turn is running")
    }

    /**
     * NOTE-流式: the flag is only ever cleared by a frame of the conversation on screen, so leaving a RUNNING chat
     * left it true with no chat to clear it — and the project list's 12 s busy/finished poll (App.kt) skips while
     * it is true, so the list stopped noticing other sessions finishing until some chat was opened again.
     */
    @Test
    fun leavingARunningChatDropsTheStreamingFlag() {
        val disconnected = liveTurnOnA()
        disconnected.disconnect()
        assertFalse(disconnected.streaming.value, "B's project list must keep polling")

        val backed = liveTurnOnA()
        backed.backToBrowse()
        assertFalse(backed.streaming.value, "the list behind a backgrounded turn must keep polling")
    }

    /** NOTE-身份: the computer switcher's current row reads chatTitle ungated — B's row said A's chat title. */
    @Test
    fun disconnectDropsTheChatTitle() {
        val r = liveTurnOnA()
        assertEquals("Fix the build", r.chatTitle.value)
        r.disconnect()
        assertNull(r.chatTitle.value)
    }
}
