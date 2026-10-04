package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.PathEntries
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
}
