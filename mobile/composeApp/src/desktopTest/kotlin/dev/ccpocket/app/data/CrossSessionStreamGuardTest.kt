package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #219 — cross-session stream splicing. Incident shape (08-06 forensics): two sessions of the
 * SAME project directory; the phone/desktop views session A while session B runs a live turn in the
 * background. The daemon keeps this device attached to B (that fan-out feeds the machine-wide approval
 * inbox), so B's relaunch announced `SessionLive(convoId = B)` — and the handler unconditionally
 * re-pointed `convoId.value`, the very baseline every stream guard compares against. B's thinking +
 * Bash frames then passed `f.convoId == convoId.value` and rendered into A's open transcript, while
 * both jsonl files on disk stayed perfectly separate.
 *
 * The guard under test: a SessionLive is only honored when it is the open conversation's own
 * re-announce, the same SESSION returning under a fresh convoId (reconnect / handoff migration), the
 * answer to an in-flight brand-new open (workdir-matched), or the client is fully unbound.
 */
class CrossSessionStreamGuardTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
    }

    /** Open session [sid] the way a real client does — openSession pins the identity, the daemon answers. */
    private fun PocketRepository.bindView(convo: String, sid: String, wd: String = "/w/proj") {
        openSession(wd, resumeId = sid)
        receiveForTest(SessionLive(convo, wd, sid, executing = false))
        receiveForTest(
            ConvoHistory(
                convo,
                listOf(HistoryMessage(ChatRole.USER, "q of $sid"), HistoryMessage(ChatRole.ASSISTANT, "a of $sid")),
                lastSeq = 2,
                firstSeq = 1,
            ),
        )
    }

    @Test
    fun backgroundConversationsLiveTurnMustNotSpliceIntoTheOpenView() {
        val r = repo()
        r.bindView(convo = "convo-a", sid = "sid-a")
        val before = r.messages.toList()

        // the background conversation of the SAME project starts a live turn: announce + stream frames
        // fan out to this device exactly as in the incident
        r.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-b", executing = true))
        r.receiveForTest(AssistantChunk("convo-b", seq = 3, piece = StreamPiece.Thinking("b is thinking")))
        r.receiveForTest(AssistantChunk("convo-b", seq = 4, piece = StreamPiece.Text("b says hi")))
        r.receiveForTest(ToolEvent("convo-b", seq = 5, phase = ToolPhase.START, tool = "Bash", inputPreview = "ls"))

        assertEquals("convo-a", r.convoId.value, "a background announce must not re-point the open view")
        assertEquals(before, r.messages.toList(), "no frame of the background turn may reach the open transcript")
        assertFalse(r.streaming.value, "the background turn's executing=true must not light this view's ■")
    }

    @Test
    fun ownConversationsReannounceStillLands() {
        val r = repo()
        r.bindView(convo = "convo-a", sid = "sid-a")

        // a mode/model switch (or relaunch) re-announces the SAME convo — daemon stays the source of truth
        r.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = true, model = "claude-fable-5"))

        assertEquals("claude-fable-5", r.model.value)
        assertTrue(r.streaming.value, "the open conversation's own executing=true must still land")
    }

    @Test
    fun sameSessionUnderAFreshConvoIdRebinds() {
        val r = repo()
        r.bindView(convo = "convo-a", sid = "sid-a")

        // the daemon rebuilt/reopened the SAME session under a new conversation (reconnect re-open,
        // handoff spectator migration §3.3) — the view must follow it
        r.receiveForTest(SessionLive("convo-a2", "/w/proj", "sid-a", executing = false))

        assertEquals("convo-a2", r.convoId.value, "the same session's fresh conversation must rebind the view")
    }

    @Test
    fun resumeOpenAcceptsItsAnswerAndRejectsBystanders() {
        val r = repo()
        r.bindView(convo = "convo-a", sid = "sid-a")

        // switch to session B; while the open is in flight, an unrelated background convo announces
        r.openSession("/w/proj", resumeId = "sid-b")
        r.receiveForTest(SessionLive("convo-x", "/w/proj", "sid-x", executing = true))
        assertEquals(null, r.convoId.value, "a bystander must not claim the in-flight open")

        r.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-b", executing = false))
        assertEquals("convo-b", r.convoId.value, "the open's real answer (matched by sessionId) must land")
    }

    @Test
    fun brandNewOpenIsMatchedByWorkdir() {
        val r = repo()
        r.bindView(convo = "convo-a", sid = "sid-a")

        r.openSession("/w/proj") // brand new — no sessionId exists yet
        // another project's background announce during the window: wrong workdir, rejected
        r.receiveForTest(SessionLive("convo-y", "/w/other", "sid-y", executing = true))
        assertEquals(null, r.convoId.value)

        // the daemon's answer for the brand-new session (sessionId still null pre-init) lands by workdir
        r.receiveForTest(SessionLive("convo-new", "/w/proj", sessionId = null, executing = false))
        assertEquals("convo-new", r.convoId.value)

        // …and once landed the marker is spent: a later same-workdir background announce stays out
        r.receiveForTest(SessionLive("convo-z", "/w/proj", "sid-z", executing = true))
        assertEquals("convo-new", r.convoId.value)
    }
}
