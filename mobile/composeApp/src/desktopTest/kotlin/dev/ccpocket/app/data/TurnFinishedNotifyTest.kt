package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop banner's click→jump payload (issue #99): when a watched turn completes, the
 * [PocketRepository.onTurnFinished] seam must hand over the finished session's id so a clicked
 * notification can jump back to that session. Driven through demo mode, which replays the real
 * frame path ([AssistantChunk] stream → [TurnDone]).
 */
class TurnFinishedNotifyTest {

    @Test
    fun turnDoneHandsTheSessionIdToTheNotifySeam() = runBlocking {
        val r = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
        }
        r.enterDemo()
        r.openSession(wd = DemoData.LIVE_DIR, resumeId = DemoData.LIVE_SESSION_ID)

        val seen = mutableListOf<Pair<String, String?>>() // title to sessionId
        r.onTurnFinished = { title, _, sessionId -> seen += title to sessionId }

        assertTrue(r.sendPrompt("demo turn"))
        // the demo's first turn parks on its permission showcase — approve it so the reply streams to TurnDone
        withTimeout(10_000) { while (r.pendingAsk.value == null) delay(25) }
        r.resolve(Decision.ALLOW)
        withTimeout(30_000) { while (seen.isEmpty()) delay(50) }

        assertEquals(
            DemoData.LIVE_SESSION_ID, seen.first().second,
            "the notify seam must carry the finished session's id for click-to-jump",
        )
        assertTrue(seen.first().first.isNotBlank(), "the banner title must never be empty")
    }
}
