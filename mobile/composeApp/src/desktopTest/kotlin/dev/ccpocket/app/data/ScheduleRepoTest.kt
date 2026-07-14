package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ScheduleCancel
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleRepeat
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.TurnDone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phone-side scheduled tasks (issue #137): the ScheduleState reply plumbing, the composer-side
 * create defaults (open session + cwd), and the usage-limit auto-continue offer lifecycle
 * (TurnDone.usageLimitResetAt → banner state → one-tap ScheduleCreate at reset+margin).
 */
class ScheduleRepoTest {

    private fun repo(sent: MutableList<Frame> = CopyOnWriteArrayList()) =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
            convoId.value = "c1"
            receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
        }

    @Test
    fun scheduleStateReplyFillsTheListAndSettlesTheDeadline() {
        val r = repo()
        assertTrue(!r.schedulesLoaded.value)
        r.receiveForTest(ScheduleState(items = listOf(ScheduleInfo(id = "a", workdir = "/w", prompt = "p", nextRunAtMs = 5))))
        assertEquals(listOf("a"), r.schedules.map { it.id })
        assertTrue(r.schedulesLoaded.value)
        assertTrue(!r.schedulesUnavailable.value)
        assertNull(r.scheduleError.value)
        // a refusal rides error while the list stays the truth
        r.receiveForTest(ScheduleState(items = emptyList(), error = "prompt is required"))
        assertEquals("prompt is required", r.scheduleError.value)
        assertTrue(r.schedules.isEmpty())
    }

    @Test
    fun createScheduleTargetsTheOpenSessionByDefault() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(sent)
        assertTrue(r.createSchedule("run the report", runAtMs = 123_456L, repeat = ScheduleRepeat(intervalMs = 86_400_000)))
        val f = sent.filterIsInstance<ScheduleCreate>().single()
        assertEquals("/w", f.workdir)
        assertEquals("sid-1", f.resumeId, "defaults to the OPEN session")
        assertEquals(123_456L, f.runAtMs)
        assertEquals(86_400_000L, f.repeat?.intervalMs)
        // and a blank prompt / no workdir refuses locally (nothing sent)
        assertTrue(!r.createSchedule("  ", runAtMs = 1))
        assertEquals(1, sent.filterIsInstance<ScheduleCreate>().size)
    }

    @Test
    fun cancelScheduleSendsTheCancelFrame() {
        val sent = CopyOnWriteArrayList<Frame>()
        repo(sent).cancelSchedule("id-9")
        assertEquals("id-9", sent.filterIsInstance<ScheduleCancel>().single().id)
    }

    @Test
    fun limitErrorWithResetMomentArmsTheOfferAndAutoContinueSchedulesIt() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(sent)
        r.receiveForTest(TurnDone("c1", error = "Claude AI usage limit reached|1720000000", usageLimitResetAt = 1_720_000_000_000))
        val offer = assertNotNull(r.limitOffer.value, "a parsed reset moment lights the banner")
        assertEquals("sid-1", offer.sessionId)
        assertEquals(1_720_000_000_000, offer.resetAtMs)

        assertTrue(r.scheduleAutoContinue())
        val f = sent.filterIsInstance<ScheduleCreate>().single()
        assertEquals("Continue", f.prompt)
        assertEquals("sid-1", f.resumeId, "the one-shot resumes the LIMITED session")
        assertEquals(1_720_000_000_000 + PocketRepository.LIMIT_RESUME_MARGIN_MS, f.runAtMs, "fires a margin after the reset")
        assertNull(f.repeat)
        assertNull(r.limitOffer.value, "the offer is consumed")
        assertTrue(r.messages.last() is ChatItem.Sys, "the chat notes the scheduled auto-continue")
    }

    @Test
    fun ordinaryErrorsOrOldDaemonsNeverArmTheOffer() {
        val r = repo()
        r.receiveForTest(TurnDone("c1", error = "turn failed")) // no reset moment (old daemon / unparseable)
        assertNull(r.limitOffer.value)
        r.receiveForTest(TurnDone("c1", finalText = "done", usageLimitResetAt = 1_720_000_000_000)) // no error → not a limit
        assertNull(r.limitOffer.value)
    }

    @Test
    fun aManualSendClearsTheOffer() {
        val r = repo()
        r.receiveForTest(TurnDone("c1", error = "usage limit reached|1720000000", usageLimitResetAt = 1_720_000_000_000))
        assertNotNull(r.limitOffer.value)
        assertTrue(r.sendPrompt("keep going yourself"))
        assertNull(r.limitOffer.value, "the user moved on — the stale offer must not fire later")
    }
}
