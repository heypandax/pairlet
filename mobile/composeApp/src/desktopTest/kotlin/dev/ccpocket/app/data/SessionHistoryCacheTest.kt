package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.AuthError
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryApplied
import dev.ccpocket.protocol.HistoryComplete
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Session-open latency, plan #1: reopening a session this screen recently LEFT paints its cached rows at once
 * and echoes their cursor, so the daemon answers with the #147 delta instead of the whole ~0.4 MB window.
 *
 * The contract pinned here:
 *  - A → B → A: the second open of A carries A's cursor and the list is non-empty before any history lands;
 *    the first open (a miss) is unchanged — cursor 0, empty list.
 *  - the reply settles the cached rows without duplicates or gaps: a delta APPENDS past them (never anchored
 *    inside them), a full window REPLACES them (nothing of the cached rows survives that the window lacks).
 *  - nothing crosses computers: a different binding never hits, and every identity exit clears.
 *  - bounds: four sessions, a byte budget with images counted, a session over budget is not kept.
 *  - a session left mid-turn parks only what the daemon replayed — never streamed text or a pending prompt.
 *  - the cache fill is not "history arrived": HistoryApplied still waits for a real merge.
 */
class SessionHistoryCacheTest {

    private val computerA = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk-a", deviceId = "dev", credential = "cred-a")
    private val computerB = PairedDaemon(relay = "wss://test", accountId = "acct-b", daemonPub = "pk-b", deviceId = "dev", credential = "cred-b")

    private class Harness(paired: PairedDaemon) {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            this.paired.value = paired
            onSendForTest = { sent += it }
        }
        private var convoSeq = 0

        fun opens() = sent.filterIsInstance<OpenSession>()
        fun lastOpen() = opens().last()

        /** Open [sid] and land its SessionLive only — the history reply is the test's to send. Returns the convoId. */
        fun openLive(sid: String, executing: Boolean = false): String {
            assertTrue(repo.openSession("/w", resumeId = sid, agent = AgentKind.CLAUDE), "open of $sid must be accepted")
            val convo = "convo-${++convoSeq}"
            repo.receiveForTest(SessionLive(convo, "/w", sid, executing = executing, agent = AgentKind.CLAUDE))
            return convo
        }

        /** A full open: SessionLive + a full window ending at [lastSeq]. */
        fun open(sid: String, rows: List<HistoryMessage>, lastSeq: Long, firstSeq: Long = 1, hasMore: Boolean = false): String =
            openLive(sid).also { repo.receiveForTest(ConvoHistory(it, rows, lastSeq = lastSeq, firstSeq = firstSeq, hasMore = hasMore)) }
    }

    private fun u(text: String) = HistoryMessage(ChatRole.USER, text)
    private fun a(text: String) = HistoryMessage(ChatRole.ASSISTANT, text)

    private fun texts(r: PocketRepository) = r.messages.map {
        when (it) {
            is ChatItem.User -> "U:${it.text}"
            is ChatItem.Assistant -> "A:${it.text}"
            else -> it::class.simpleName ?: "?"
        }
    }

    // ── the round trip ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun reopeningALeftSessionEchoesItsCursorAndPaintsBeforeHistory() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q1"), a("a1"), u("q2"), a("a2")), lastSeq = 40, firstSeq = 20, hasMore = true)
        assertEquals(0L, h.lastOpen().lastEventSeq, "a first open (a miss) still asks for the full window")
        h.open("sid-b", listOf(u("b1"), a("b2")), lastSeq = 7)

        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)

        val reopen = h.lastOpen()
        assertEquals("sid-a", reopen.resumeId)
        assertEquals(40L, reopen.lastEventSeq, "the reopen must echo A's cached cursor so the daemon answers with a delta")
        assertEquals(listOf("U:q1", "A:a1", "U:q2", "A:a2"), texts(h.repo), "A's rows are on screen before any history lands")
        assertTrue(h.repo.historyHasMore.value, "…and so is its older-history affordance")
    }

    @Test
    fun aMissIsUnchangedNoCursorAndAnEmptyList() {
        val h = Harness(computerA)
        h.open("sid-b", listOf(u("b1")), lastSeq = 3)
        h.repo.openSession("/w", resumeId = "sid-never-seen", agent = AgentKind.CLAUDE)
        assertEquals(0L, h.lastOpen().lastEventSeq)
        assertTrue(h.repo.messages.isEmpty())
    }

    @Test
    fun backToTheListAndStopAlsoParkTheSession() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 12)
        h.repo.backToBrowse()
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(12L, h.lastOpen().lastEventSeq, "back to the list parks the session")

        val convo = "convo-stop"
        h.repo.receiveForTest(SessionLive(convo, "/w", "sid-a", executing = false))
        h.repo.receiveForTest(ConvoHistory(convo, listOf(a("a2")), lastSeq = 15, firstSeq = 13, delta = true))
        h.repo.stopSession()
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(15L, h.lastOpen().lastEventSeq, "stop parks it too, at the cursor the last delta advanced to")
        assertEquals(listOf("U:q1", "A:a1", "A:a2"), texts(h.repo))
    }

    // ── settling the cached rows ──────────────────────────────────────────────────────────────────────

    @Test
    fun aDeltaAppendsPastTheCachedRowsWithoutDuplicatesOrGaps() {
        val h = Harness(computerA)
        // the repeated short prompt is the trap: the generic delta merge anchors on ANY pairing row, so a new
        // "继续" would latch onto the old one and drop every row after it
        h.open("sid-a", listOf(u("继续"), a("old answer"), u("另一个问题"), a("another answer")), lastSeq = 10)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false))

        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(u("继续"), a("new answer")), lastSeq = 14, firstSeq = 11, delta = true))

        assertEquals(
            listOf("U:继续", "A:old answer", "U:另一个问题", "A:another answer", "U:继续", "A:new answer"),
            texts(h.repo),
            "a delta continues the cached rows: every cached row kept once, every new row appended once",
        )
        // and the cursor moved on: the next reopen asks from 14
        h.repo.backToBrowse()
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(14L, h.lastOpen().lastEventSeq)
        assertEquals(6, h.repo.messages.size, "the parked snapshot grew by exactly the delta")
    }

    @Test
    fun aFullWindowReplacesTheCachedRowsWithNothingLeftOver() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q1"), a("a1"), u("q2"), a("a2")), lastSeq = 10, firstSeq = 1)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false))

        // the daemon could not honor the cursor (a rewritten file, a late patch, too many new rows): a full window
        // that overlaps the cached tail. The anchored merge would have kept q1/a1/q2 above it as "scrollback".
        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(a("a2"), u("q3"), a("a3")), lastSeq = 30, firstSeq = 9, hasMore = true))

        assertEquals(listOf("A:a2", "U:q3", "A:a3"), texts(h.repo), "exactly the window — what a cache miss shows")
        assertTrue(h.repo.historyHasMore.value)
        h.repo.backToBrowse()
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(30L, h.lastOpen().lastEventSeq, "the full window's cursor and rows are what is parked next")
        assertEquals(listOf("A:a2", "U:q3", "A:a3"), texts(h.repo))
    }

    @Test
    fun anEmptyFullWindowAfterAClearWipesTheCachedRows() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 10)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false))
        h.repo.receiveForTest(ConvoHistory("convo-a2", emptyList()))
        assertTrue(h.repo.messages.isEmpty())
    }

    @Test
    fun anOlderPageOnTheCachedRowsIsKeptByADeltaAndParked() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q5"), a("a5")), lastSeq = 40, firstSeq = 20, hasMore = true)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false))
        h.repo.loadOlderHistory()
        h.repo.receiveForTest(dev.ccpocket.protocol.ConvoHistoryPage("convo-a2", listOf(u("q1"), a("a1")), firstSeq = 5, hasMore = false))
        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(u("q6")), lastSeq = 44, firstSeq = 41, delta = true))
        assertEquals(listOf("U:q1", "A:a1", "U:q5", "A:a5", "U:q6"), texts(h.repo))

        h.repo.backToBrowse()
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(listOf("U:q1", "A:a1", "U:q5", "A:a5", "U:q6"), texts(h.repo))
        assertFalse(h.repo.historyHasMore.value, "the page's anchor travelled with the snapshot")
    }

    // ── identity ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anotherComputerNeverHitsEvenForTheSameSessionId() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("on computer A")), lastSeq = 10)
        h.repo.backToBrowse()
        // the binding changes under the repository without passing through disconnect (the cache is keyed, not
        // only cleared): the same session id on another computer is another transcript
        h.repo.paired.value = computerB
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(0L, h.lastOpen().lastEventSeq)
        assertTrue(h.repo.messages.isEmpty())
    }

    @Test
    fun aRepairedBindingOfTheSameComputerNeverHits() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q")), lastSeq = 10)
        h.repo.backToBrowse()
        h.repo.paired.value = computerA.copy(deviceId = "dev-after-repair", credential = "cred-new")
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(0L, h.lastOpen().lastEventSeq)
    }

    @Test
    fun everyIdentityExitClearsTheCache() {
        fun parked(): Harness = Harness(computerA).apply {
            open("sid-a", listOf(u("q")), lastSeq = 10)
            repo.backToBrowse()
            assertEquals(1, repo.sessionCacheSizeForTest.first)
        }
        val exits: List<Pair<String, (PocketRepository) -> Unit>> = listOf(
            "disconnect (exit / unpair / cold switch / add-device / leave demo)" to { r -> r.disconnect() },
            "demote to a fleet satellite" to { r -> r.demoteToSatellite() },
            "a relay AuthError (revoked pairing)" to { r -> r.receiveControlForTest(AuthError("revoked")) },
        )
        for ((name, exit) in exits) {
            val h = parked()
            exit(h.repo)
            assertEquals(0 to 0L, h.repo.sessionCacheSizeForTest, "$name must clear the cache")
            h.repo.paired.value = computerA
            h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
            assertEquals(0L, h.lastOpen().lastEventSeq, "after $name the same session is a miss")
        }
    }

    // ── bounds ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFifthLeftSessionEvictsTheLeastRecentlyLeft() {
        val h = Harness(computerA)
        for (i in 1..5) h.open("sid-$i", listOf(u("q$i")), lastSeq = 10L + i)
        h.repo.backToBrowse() // parks sid-5; sid-1 (left first) is now the one over the bound
        assertEquals(4, h.repo.sessionCacheSizeForTest.first)
        h.repo.openSession("/w", resumeId = "sid-1", agent = AgentKind.CLAUDE)
        assertEquals(0L, h.lastOpen().lastEventSeq, "the least recently left session was evicted")
        h.repo.backToBrowse()
        h.repo.openSession("/w", resumeId = "sid-2", agent = AgentKind.CLAUDE)
        assertEquals(12L, h.lastOpen().lastEventSeq, "the next one is still there")
    }

    @Test
    fun theByteBoundCountsImagesEvictsOldestAndRefusesAnOversizedSession() {
        val cache = SessionHistoryCache(maxEntries = 4, maxBytes = 1_000_000)
        fun withImage(bytes: Int) = listOf(ChatItem.Tool("Read", "shot.png", ok = true, images = listOf(ByteArray(bytes))))
        val k = { sid: String -> SessionHistoryCache.Key("computer", sid) }

        assertTrue(cache.put(k("s1"), withImage(400_000), 1, 1, false))
        assertTrue(cache.put(k("s2"), withImage(400_000), 1, 1, false))
        assertTrue(cache.totalBytes >= 800_000, "image bytes are counted: ${cache.totalBytes}")
        assertTrue(cache.put(k("s3"), withImage(400_000), 1, 1, false))
        assertEquals(2, cache.size, "over the byte bound the least recently stored goes")
        assertEquals(null, cache.take(k("s1")))
        assertNotNull(cache.take(k("s2")))

        assertFalse(cache.put(k("big"), withImage(1_200_000), 1, 1, false), "a session over the bound on its own is not kept")
        assertEquals(null, cache.take(k("big")))
        assertTrue(cache.totalBytes <= 1_000_000)

        // re-storing a session replaces its older snapshot and makes it the most recent
        assertTrue(cache.put(k("s3"), listOf(ChatItem.Assistant("fresher")), 9, 1, false))
        assertEquals(1, cache.size)
        assertEquals(9L, cache.take(k("s3"))?.lastSeq)
        assertEquals(0L, cache.totalBytes)
    }

    @Test
    fun anOversizedSessionThroughTheRepositoryIsAMiss() {
        val h = Harness(computerA)
        // ~9 MB of decoded image bytes in one replayed row: over the 8 MB total on its own
        val huge = java.util.Base64.getEncoder().encodeToString(ByteArray(9 * 1024 * 1024))
        h.open("sid-big", listOf(HistoryMessage(ChatRole.USER, "look", images = listOf(dev.ccpocket.protocol.ImageData(mediaType = "image/png", base64 = huge)))), lastSeq = 5)
        h.repo.backToBrowse()
        assertEquals(0 to 0L, h.repo.sessionCacheSizeForTest)
        h.repo.openSession("/w", resumeId = "sid-big", agent = AgentKind.CLAUDE)
        assertEquals(0L, h.lastOpen().lastEventSeq)
    }

    // ── a session left mid-turn ───────────────────────────────────────────────────────────────────────

    @Test
    fun aSessionLeftMidTurnParksOnlyWhatTheDaemonReplayed() {
        val h = Harness(computerA)
        val convo = h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 10)
        // live: a pending prompt of ours, then the turn streams — none of it is on disk as far as our cursor knows
        assertTrue(h.repo.sendPrompt("q2"))
        h.repo.receiveForTest(AssistantChunk(convo, 1, StreamPiece.Text("streaming…")))
        assertTrue(h.repo.streaming.value)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2) // the running turn keeps going in the background

        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        assertEquals(10L, h.lastOpen().lastEventSeq, "the cursor of the last REPLAY, not of what streamed after it")
        assertEquals(listOf("U:q1", "A:a1"), texts(h.repo), "no streamed text and no pending bubble came back from the cache")

        // the turn is still running: a live block can race the delta. It must not glue onto the cached a1 —
        // the delta carries it as its own row, and glued it would show twice
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = true))
        h.repo.receiveForTest(AssistantChunk("convo-a2", 1, StreamPiece.Text("more")))
        h.repo.receiveForTest(
            ConvoHistory("convo-a2", listOf(u("q2"), a("streaming…"), a("more")), lastSeq = 13, firstSeq = 11, delta = true),
        )
        assertEquals(listOf("U:q1", "A:a1", "U:q2", "A:streaming…", "A:more"), texts(h.repo))
    }

    @Test
    fun aPromptTypedBeforeTheDeltaLandsIsKeptOnceAndResolvedByIt() {
        val h = Harness(computerA)
        h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 10)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false))
        assertTrue(h.repo.sendPrompt("q2"))

        // the delta was read before the prompt reached the transcript: the bubble stays, after the new rows
        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(a("written at the computer")), lastSeq = 11, firstSeq = 11, delta = true))
        assertEquals(listOf("U:q1", "A:a1", "A:written at the computer", "U:q2"), texts(h.repo))
        assertTrue((h.repo.messages.last() as ChatItem.User).pending)

        // a later delta carrying it resolves the bubble in place — one q2, no longer pending
        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(u("q2")), lastSeq = 12, firstSeq = 12, delta = true))
        assertEquals(listOf("U:q1", "A:a1", "A:written at the computer", "U:q2"), texts(h.repo))
        assertFalse((h.repo.messages.last() as ChatItem.User).pending)
    }

    // ── the cache fill is not history ─────────────────────────────────────────────────────────────────

    @Test
    fun historyAppliedStillWaitsForTheRealMerge() {
        val h = Harness(computerA)
        h.repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude"), supportsDiagnostics = true))
        h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 10)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)

        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        val context = assertNotNull(h.lastOpen().diagnostic, "this open is diagnosable")
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false, diagnostic = context))
        assertTrue(h.repo.messages.isNotEmpty())
        assertTrue(h.sent.filterIsInstance<HistoryApplied>().none { it.convoId == "convo-a2" }, "rows from the cache are not an applied history")

        h.repo.receiveForTest(ConvoHistory("convo-a2", listOf(a("a2")), lastSeq = 12, firstSeq = 11, delta = true, diagnostic = context))
        h.repo.receiveForTest(HistoryComplete("convo-a2", context, rows = 1, quality = "complete", replaySent = true))
        assertEquals(1, h.sent.filterIsInstance<HistoryApplied>().count { it.convoId == "convo-a2" }, "the merged delta is")
    }

    @Test
    fun aCaughtUpReopenIsAnsweredWithoutAnyHistoryFrame() {
        // the daemon sends NO ConvoHistory for an empty delta — only HistoryComplete(replaySent = false)
        val h = Harness(computerA)
        h.repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude"), supportsDiagnostics = true))
        h.open("sid-a", listOf(u("q1"), a("a1")), lastSeq = 10)
        h.open("sid-b", listOf(u("b1")), lastSeq = 2)
        h.repo.openSession("/w", resumeId = "sid-a", agent = AgentKind.CLAUDE)
        val context = assertNotNull(h.lastOpen().diagnostic)
        h.repo.receiveForTest(SessionLive("convo-a2", "/w", "sid-a", executing = false, diagnostic = context))
        h.repo.receiveForTest(HistoryComplete("convo-a2", context, rows = 0, quality = "complete", replaySent = false))

        assertEquals(1, h.sent.filterIsInstance<HistoryApplied>().count { it.convoId == "convo-a2" })
        assertEquals(listOf("U:q1", "A:a1"), texts(h.repo))
        h.sent.clear()
        h.repo.restoreAfterReconnectForTestBlocking()
        assertEquals(10L, h.lastOpen().lastEventSeq, "a reconnect still continues from the cached cursor")
    }

    private fun PocketRepository.restoreAfterReconnectForTestBlocking() =
        kotlinx.coroutines.runBlocking { restoreAfterReconnectForTest() }
}
