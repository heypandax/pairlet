package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two-stage prompt deadline. Stage 1 (issue #78): a sent prompt whose PromptAck / stream evidence never
 * comes back must flip [PocketRepository.sendStalled] instead of reading "sending…" forever. Stage 2 (issue
 * #104): a PromptAck only means the daemon WROTE the prompt to the agent's stdin — if no turn frame follows,
 * the agent swallowed it (wedged / mid-relaunch), and [PocketRepository.turnStalled] must surface a resend cue
 * rather than spinning "thinking" forever. The first REAL turn frame clears both. A prompt sent INTO a
 * running turn is the queued flavor: the CLI parks it, so the same silence flips [PocketRepository.turnQueued]
 * (a calm, non-actionable status) instead — a resend there would double-run the queued original. Uses the
 * internal [PocketRepository.promptReceiptTimeoutMs] / [PocketRepository.promptTurnTimeoutMs] seams so all run in ms.
 */
class PromptDeliveryTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        promptReceiptTimeoutMs = 50
        promptTurnTimeoutMs = 80
    }

    private fun lastUserPromptId(r: PocketRepository) =
        r.messages.filterIsInstance<ChatItem.User>().last().promptId!!

    /** The turn watchdog fires ~80ms in on a DefaultExecutor tick; on a cold or loaded JVM (parallel gradle
     *  runs) that tick can land well past a fixed sleep — poll for the cue instead of racing it. */
    private suspend fun awaitCue(what: String, cond: () -> Boolean) {
        repeat(150) { if (cond()) return; delay(20) }
        fail("$what did not surface within the 3s ceiling")
    }

    @Test
    fun aSendWithNoReceiptStallsInsteadOfSendingForever() = runBlocking {
        val r = repo()
        r.convoId.value = "c1" // a live-looking conversation whose link answers nothing

        assertTrue(r.sendPrompt("hello?"))
        val bubble = r.messages.last() as ChatItem.User
        assertTrue(bubble.pending)
        assertFalse(r.sendStalled.value) // the deadline hasn't elapsed — no premature warning

        delay(400)
        assertTrue(r.sendStalled.value, "no ack + no evidence within the deadline must surface as a stall")
        assertTrue((r.messages.last() as ChatItem.User).pending, "an unconfirmed prompt stays honestly pending")
    }

    @Test
    fun lateDaemonEvidenceClearsTheStallAndThePendingMarker() = runBlocking {
        val r = repo()
        r.enterDemo() // demo loops sends back as synthesized daemon replies — first evidence lands ~500ms in
        r.openSession(wd = DemoData.LIVE_DIR, resumeId = DemoData.LIVE_SESSION_ID)
        assertTrue(r.convoId.value != null)

        assertTrue(r.sendPrompt("run the demo turn"))
        delay(250) // past the 50ms deadline, before the demo's first reply chunk
        assertTrue(r.sendStalled.value)

        delay(1_000) // the demo's thinking chunk (daemon evidence) has streamed in by now
        assertFalse(r.sendStalled.value, "first daemon evidence must retract the stall warning")
        assertFalse(
            r.messages.filterIsInstance<ChatItem.User>().last().pending,
            "first daemon evidence must flip the bubble off pending",
        )
    }

    // ── stage 2: ack-but-no-turn (issue #104) ────────────────────────────────────────────────────

    @Test
    fun anAckWithNoFollowingTurnSurfacesTheResendCue() = runBlocking {
        val r = repo()
        r.convoId.value = "c1"
        r.workdir.value = "/tmp/proj"

        assertTrue(r.sendPrompt("do the thing"))
        val pid = lastUserPromptId(r)
        r.receiveForTest(PromptAck("c1", pid)) // daemon wrote it to the agent's stdin — NOT a started turn

        delay(30) // the 80ms turn deadline hasn't elapsed yet
        assertFalse(r.turnStalled.value, "a fresh delivery must not cry stall before the turn deadline")
        assertFalse(r.sendStalled.value, "the receipt cleared the stage-1 (delivery) stall")
        assertTrue((r.messages.last() as ChatItem.User).delivered, "the ack flips the bubble to delivered")

        awaitCue("the resend cue") { r.turnStalled.value } // no chunk / tool / turn-end → the deadline fires
        assertFalse(r.turnQueued.value, "an idle-session send is the strict flavor — never the queued status")
        assertTrue(r.streaming.value, "the cue is an overlay — the turn hasn't been declared over")
    }

    @Test
    fun aSendQueuedBehindARunningTurnShowsTheQueuedCueNotTheResendCue() = runBlocking {
        val r = repo()
        r.convoId.value = "c1"
        r.workdir.value = "/tmp/proj"
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { f: Frame -> if (f is SendPrompt) sent.add(f) }

        // turn 1 is running and has produced real frames
        assertTrue(r.sendPrompt("long job"))
        r.receiveForTest(PromptAck("c1", lastUserPromptId(r)))
        r.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Text("working…")))
        assertTrue(r.streaming.value)

        // the mid-turn send: the CLI queues it — silence past the deadline is expected, not a swallow
        assertTrue(r.sendPrompt("queued instruction"))
        r.receiveForTest(PromptAck("c1", lastUserPromptId(r)))
        // zero frames while the running turn sits in a silent stretch → the deadline fires, queued-flavored
        awaitCue("the queued status") { r.turnQueued.value }
        assertFalse(
            r.turnStalled.value,
            "a queued prompt must never raise the resend cue — the original still sits in the CLI queue, so a fresh-id resend would double-run it",
        )

        // the queued status is not actionable — a (stale desktop click / racing tap) resend stays inert
        val sends = sent.size
        r.resendStalledPrompt()
        assertEquals(sends, sent.size, "no resend path exists from the queued status")

        // the running turn producing again retracts the status, same evidence as every other cue
        r.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Text("still here")))
        assertFalse(r.turnQueued.value, "a real turn frame must retract the queued status")
    }

    @Test
    fun aRealTurnFrameAfterTheAckNeverStalls() = runBlocking {
        val r = repo()
        r.convoId.value = "c1"
        r.workdir.value = "/tmp/proj"

        assertTrue(r.sendPrompt("do the thing"))
        val pid = lastUserPromptId(r)
        r.receiveForTest(PromptAck("c1", pid))
        r.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Thinking("weighing options"))) // the turn is producing

        delay(150) // well past the 80ms turn deadline
        assertFalse(r.turnStalled.value, "a real turn frame must cancel the ack→turn watchdog")

        // and a normal turn-end must leave nothing stalled either
        r.receiveForTest(TurnDone("c1"))
        assertFalse(r.turnStalled.value)
        assertFalse(r.streaming.value)
    }

    @Test
    fun theResendCueReRunsUnderAFreshIdExactlyOnce() = runBlocking {
        val r = repo()
        r.convoId.value = "c1"
        r.workdir.value = "/tmp/proj"
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { f: Frame -> if (f is SendPrompt) sent.add(f) }

        assertTrue(r.sendPrompt("do the thing"))
        val originalId = lastUserPromptId(r)
        r.receiveForTest(PromptAck("c1", originalId))
        awaitCue("the resend cue") { r.turnStalled.value }
        val userBubblesBefore = r.messages.count { it is ChatItem.User }

        r.resendStalledPrompt()
        assertFalse(r.turnStalled.value, "acting on the cue clears it")
        assertEquals(
            userBubblesBefore, r.messages.count { it is ChatItem.User },
            "resend must NOT add a duplicate You bubble (the very symptom of #104)",
        )
        assertEquals(2, sent.size, "exactly one original send + one resend went out")
        assertNotEquals(
            originalId, sent[1].promptId,
            "the resend must use a FRESH id — a same-id resend is deduped by the live daemon (#66) into a bare re-ack, never re-running the turn",
        )

        // single-shot: the cue is consumed, so a second tap can't double-run
        r.resendStalledPrompt()
        assertEquals(2, sent.size, "a second tap after the cue cleared must not send again")
    }

    @Test
    fun aLateTurnBeforeTheUserTapsMakesResendANoOp() = runBlocking {
        val r = repo()
        r.convoId.value = "c1"
        r.workdir.value = "/tmp/proj"
        val sent = mutableListOf<SendPrompt>()
        r.onSendForTest = { f: Frame -> if (f is SendPrompt) sent.add(f) }

        assertTrue(r.sendPrompt("do the thing"))
        r.receiveForTest(PromptAck("c1", lastUserPromptId(r)))
        awaitCue("the resend cue") { r.turnStalled.value }

        // the "swallowed" turn was actually just slow — its first frame lands before the user reacts
        r.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Text("here you go")))
        assertFalse(r.turnStalled.value, "a late real turn frame retracts the cue")

        r.resendStalledPrompt() // user's stale tap
        assertEquals(1, sent.size, "resend after the turn recovered must be a no-op — no double turn")
    }
}
