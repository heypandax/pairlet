package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #226 — returning from a chat is a navigation boundary, not an invitation to reattach it. */
class BackNavigationSessionGuardTest {
    private class Harness {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            sessionsDir.value = "/w/proj"
            onSendForTest = { sent += it }
        }

        fun bind(convo: String = "convo-a", sid: String = "sid-a") {
            assertTrue(repo.openSession("/w/proj", resumeId = sid))
            repo.receiveForTest(SessionLive(convo, "/w/proj", sid, executing = false))
            repo.receiveForTest(
                ConvoHistory(
                    convo,
                    listOf(HistoryMessage(ChatRole.USER, "question"), HistoryMessage(ChatRole.ASSISTANT, "answer")),
                    lastSeq = 2,
                    firstSeq = 1,
                ),
            )
        }
    }

    @Test
    fun lateReannounceAfterBackCannotReopenAnEmptyChat() {
        val h = Harness()
        h.bind()

        h.repo.backToBrowse()
        assertNull(h.repo.convoId.value)
        assertEquals("/w/proj", h.repo.sessionsDir.value)
        assertTrue(h.repo.messages.isEmpty(), "the chat transcript is intentionally cleared on back")

        // This is the incident: the just-left session re-announces after CloseSession/ListSessions raced it.
        h.repo.receiveForTest(SessionLive("convo-a2", "/w/proj", "sid-a", executing = true))
        h.repo.receiveForTest(AssistantChunk("convo-a2", 3, StreamPiece.Text("late output")))

        assertNull(h.repo.convoId.value, "a late SessionLive must not route the user back into chat")
        assertTrue(h.repo.messages.isEmpty(), "late frames must not manufacture an empty/partial chat view")
        assertFalse(h.repo.streaming.value)
    }

    @Test
    fun explicitOpenAfterBackLowersTheFence() {
        val h = Harness()
        h.bind()
        h.repo.backToBrowse()
        h.sent.clear()

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
        assertEquals(1, h.sent.filterIsInstance<OpenSession>().size)
        h.repo.receiveForTest(SessionLive("convo-a3", "/w/proj", "sid-a", executing = false))

        assertEquals("convo-a3", h.repo.convoId.value, "a fresh user action may reopen the session normally")
    }

    @Test
    fun backingOutOfAnInflightOpenRejectsItsLateAnswer() {
        val h = Harness()
        h.bind()

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-b"))
        assertTrue(h.repo.opening.value)
        h.repo.backToBrowse()

        assertFalse(h.repo.opening.value)
        h.repo.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-b", executing = false))
        assertNull(h.repo.convoId.value, "BACK must win even when the daemon finishes opening afterward")
    }
}
