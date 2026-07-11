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

/**
 * The two-stage prompt deadline. Stage 1 (issue #78): a sent prompt whose PromptAck / stream evidence never
 * comes back must flip [PocketRepository.sendStalled] instead of reading "sending…" forever. Stage 2 (issue
 * #104): a PromptAck only means the daemon WROTE the prompt to the agent's stdin — if no turn frame follows,
 * the agent swallowed it (wedged / mid-relaunch), and [PocketRepository.turnStalled] must surface a resend cue
 * rather than spinning "thinking" forever. The first REAL turn frame clears both. Uses the internal
 * [PocketRepository.promptReceiptTimeoutMs] / [PocketRepository.promptTurnTimeoutMs] seams so both run in ms.
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

        delay(120) // now past 80ms with no chunk / tool / turn-end
        assertTrue(r.turnStalled.value, "ack but no turn within the deadline must surface the resend cue")
        assertTrue(r.streaming.value, "the cue is an overlay — the turn hasn't been declared over")
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
        delay(150) // let the turn watchdog fire
        assertTrue(r.turnStalled.value)
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
        delay(150)
        assertTrue(r.turnStalled.value)

        // the "swallowed" turn was actually just slow — its first frame lands before the user reacts
        r.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Text("here you go")))
        assertFalse(r.turnStalled.value, "a late real turn frame retracts the cue")

        r.resendStalledPrompt() // user's stale tap
        assertEquals(1, sent.size, "resend after the turn recovered must be a no-op — no double turn")
    }
}
