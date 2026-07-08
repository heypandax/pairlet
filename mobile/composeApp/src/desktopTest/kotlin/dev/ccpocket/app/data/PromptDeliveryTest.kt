package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prompt receipt deadline (issue #78): a sent prompt whose PromptAck / stream evidence never comes
 * back must flip [PocketRepository.sendStalled] instead of reading "sending…" forever — and the first
 * daemon evidence must clear both the stall flag and the bubble's pending marker. Uses the internal
 * [PocketRepository.promptReceiptTimeoutMs] seam so the stall path runs in milliseconds.
 */
class PromptDeliveryTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        promptReceiptTimeoutMs = 50
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
}
