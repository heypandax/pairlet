package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #147, phone side: the incremental-reattach cursor plumbing and the older-history paging state.
 *  - a `delta = true` ConvoHistory continues the transcript at the tail — never a wipe/replace;
 *  - an empty delta changes nothing (only an empty FULL replay is the /clear wipe);
 *  - paging: hasMore arms the affordance, a page PREPENDS and moves the anchor, an unsolicited page
 *    (this client never asked) is dropped, and an old daemon (no cursor fields) never offers paging.
 */
class HistoryPagingTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        convoId.value = "c1"
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
    }

    private fun u(text: String) = HistoryMessage(ChatRole.USER, text)
    private fun a(text: String) = HistoryMessage(ChatRole.ASSISTANT, text)

    @Test
    fun failedHistoryReadKeepsRowsAndTheSamePageCanBeRetried() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q5"), a("a5")), lastSeq = 40, firstSeq = 20, hasMore = true))
        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", emptyList(), firstSeq = 20, hasMore = true))
        r.receiveForTest(dev.ccpocket.protocol.PocketError("history_unavailable", "DSH history unavailable", "c1"))
        assertFalse(r.historyLoadingOlder.value)
        assertTrue(r.historyHasMore.value)
        assertEquals(listOf("q5", "a5"), texts(r).filter { it != "?" })
        r.loadOlderHistory()
        assertTrue(r.historyLoadingOlder.value, "retry uses the retained page cursor")
        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("q1"), a("a1")), firstSeq = 1, hasMore = false))
        assertEquals(listOf("q1", "a1", "q5", "a5"), texts(r).filter { it != "?" })
        assertFalse(r.historyLoadingOlder.value)
        assertFalse(r.historyHasMore.value)
    }

    @Test
    fun deltaHistoryAppendsAtTheTailInsteadOfReplacing() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q1"), a("a1")), lastSeq = 10, firstSeq = 1))
        assertEquals(2, r.messages.size)

        r.receiveForTest(ConvoHistory("c1", listOf(a("a2")), lastSeq = 12, firstSeq = 11, delta = true))

        assertEquals(3, r.messages.size, "a delta continues the transcript — the pre-cursor rows survive")
        assertEquals("a2", (r.messages.last() as ChatItem.Assistant).text)
    }

    @Test
    fun emptyDeltaIsNotTheClearWipe() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q1"), a("a1")), lastSeq = 10, firstSeq = 1))
        r.contextUsed.value = 42_000

        r.receiveForTest(ConvoHistory("c1", emptyList(), lastSeq = 10, delta = true))

        assertEquals(2, r.messages.size, "an empty DELTA must not wipe the transcript")
        assertEquals(42_000L, r.contextUsed.value, "…nor reset the context statusline (that's /clear's empty FULL)")
    }

    @Test
    fun emptyFullReplayStillClearsLikeToday() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q1")), lastSeq = 5, firstSeq = 1))
        r.contextUsed.value = 42_000

        r.receiveForTest(ConvoHistory("c1", emptyList())) // the daemon's /clear wipe — pre-#147 shape

        assertTrue(r.messages.isEmpty())
        assertEquals(null, r.contextUsed.value)
    }

    @Test
    fun hasMoreArmsPagingAndAPagePrependsAndMovesTheAnchor() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q5"), a("a5")), lastSeq = 40, firstSeq = 20, hasMore = true))
        assertTrue(r.historyHasMore.value)

        r.loadOlderHistory()
        assertTrue(r.historyLoadingOlder.value)

        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("q1"), a("a1")), firstSeq = 5, hasMore = false))

        assertFalse(r.historyLoadingOlder.value)
        assertEquals(listOf("q1", "a1", "q5", "a5"), r.messages.map {
            when (it) {
                is ChatItem.User -> it.text
                is ChatItem.Assistant -> it.text
                else -> "?"
            }
        })
        assertFalse(r.historyHasMore.value, "the last page retires the affordance")
        assertEquals(2, r.lastHistoryPrependCount)
        assertEquals(1, r.historyPrependGen.value)
    }

    private fun texts(r: PocketRepository) = r.messages.map {
        when (it) {
            is ChatItem.User -> it.text
            is ChatItem.Assistant -> it.text
            else -> "?"
        }
    }

    private fun rows(from: Int, to: Int) = (from..to).map { HistoryMessage(ChatRole.USER, "row $it", seq = it.toLong()) }

    @Test
    fun aReducedFullWindowReconcilesTheOlderPageWithoutDuplicatingRetainedOrLiveRows() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", rows(1, 10), firstSeq = 1, lastSeq = 10))
        // Live rows have no on-disk coordinates yet. A large reattach delta is reduced to a full tail.
        r.transcript.messages.addAll((11..20).map { ChatItem.User("row $it", promptId = "p$it", delivered = true) })
        r.receiveForTest(ConvoHistory("c1", rows(15, 30), firstSeq = 15, lastSeq = 30, hasMore = true))
        val pending = ChatItem.User("next prompt", pending = true, promptId = "pending")
        val live = ChatItem.Assistant("still streaming after the window")
        r.transcript.messages.addAll(listOf(live, pending))

        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", rows(1, 14), firstSeq = 1, hasMore = false))

        assertEquals((1..30).map { "row $it" } + listOf(live.text, pending.text), texts(r))
        val delivered = r.messages.filterIsInstance<ChatItem.User>().single { it.text == "row 12" }
        assertEquals(12L, delivered.seq, "the page enriches the live row with its real coordinates")
        assertEquals("p12", delivered.promptId, "delivery identity survives reconciliation")
        assertTrue(r.messages[r.messages.lastIndex] === pending)
        assertTrue(r.messages[r.messages.lastIndex - 1] === live)
        assertEquals(0, r.lastHistoryPrependCount, "an overlapping page must not jump the viewport")
        assertFalse(r.historyHasMore.value)
    }

    @Test
    fun severalOlderPagesOnlyInsertRowsBeforeTheRetainedPrefix() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", rows(10, 20), firstSeq = 10, lastSeq = 20, hasMore = true))
        r.transcript.messages.addAll((21..30).map { ChatItem.User("row $it") })
        r.receiveForTest(ConvoHistory("c1", rows(25, 40), firstSeq = 25, lastSeq = 40, hasMore = true))

        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", rows(18, 24), firstSeq = 18, hasMore = true))
        assertEquals((10..40).map { "row $it" }, texts(r))
        assertEquals(0, r.lastHistoryPrependCount)

        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", rows(1, 17), firstSeq = 1, hasMore = false))
        assertEquals((1..40).map { "row $it" }, texts(r))
        assertEquals(9, r.lastHistoryPrependCount, "only rows 1..9 were newly inserted")
    }

    @Test
    fun olderPageNeverMatchesAnIdenticalPromptInsideTheCurrentWindow() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("continue"), u("boundary")), firstSeq = 10, lastSeq = 20))
        r.receiveForTest(ConvoHistory("c1", listOf(u("boundary"), u("continue")), firstSeq = 20, lastSeq = 30, hasMore = true))
        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("continue")), firstSeq = 10, hasMore = false))
        assertEquals(listOf("continue", "boundary", "continue"), texts(r))
        assertEquals(0, r.lastHistoryPrependCount)
    }

    @Test
    fun clearDropsTheRetainedPrefixBeforeAnotherWindowPages() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", rows(1, 20), firstSeq = 1, lastSeq = 20))
        r.receiveForTest(ConvoHistory("c1", rows(15, 30), firstSeq = 15, lastSeq = 30, hasMore = true))
        r.receiveForTest(ConvoHistory("c1", emptyList()))
        r.receiveForTest(ConvoHistory("c1", rows(20, 25), firstSeq = 20, lastSeq = 25, hasMore = true))
        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("c1", rows(10, 19), firstSeq = 10, hasMore = false))
        assertEquals((10..25).map { "row $it" }, texts(r))
        assertEquals(10, r.lastHistoryPrependCount)
    }

    @Test
    fun aSlowPageLandingAfterTheDeadlineIsStillAcceptedAndPagingSurvives() {
        // the CONFIRMED bug (#147): on a slow cross-border link the page reply can take >10s; the reply
        // deadline fired first, permanently disabled paging, and the late page was then dropped.
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q5"), a("a5")), lastSeq = 40, firstSeq = 20, hasMore = true))
        r.loadOlderHistory()
        assertTrue(r.historyLoadingOlder.value)

        // simulate the 10s deadline firing: it collapses the spinner ONLY — the affordance stays.
        r.historyLoadingOlder.value = false
        assertTrue(r.historyHasMore.value, "the deadline must NOT retire the load-earlier affordance")

        // the genuine page arrives late — still accepted, prepended, anchor advanced.
        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("q1"), a("a1")), firstSeq = 5, hasMore = false))
        assertEquals(listOf("q1", "a1", "q5", "a5"), texts(r), "the late page is accepted, not dropped")
        assertEquals(2, r.lastHistoryPrependCount)
        assertEquals(1, r.historyPrependGen.value)
        assertFalse(r.historyHasMore.value, "only once the LAST page lands does the affordance retire")
    }

    @Test
    fun aDuplicateLatePageIsDroppedAfterTheFirstLands() {
        // accepting a page clears the outstanding request, so a duplicate late fan-out can't double-prepend.
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q5")), lastSeq = 40, firstSeq = 20, hasMore = true))
        r.loadOlderHistory()

        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("q1"), a("a1")), firstSeq = 5, hasMore = true))
        assertEquals(listOf("q1", "a1", "q5"), texts(r))

        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("q1"), a("a1")), firstSeq = 5, hasMore = true)) // dup
        assertEquals(listOf("q1", "a1", "q5"), texts(r), "the duplicate late page must not prepend again")
    }

    @Test
    fun anUnsolicitedPageIsDropped() {
        // a page fanned out to a client that never asked (or a stale late reply) must not double-prepend
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q5")), lastSeq = 40, firstSeq = 20, hasMore = true))

        r.receiveForTest(ConvoHistoryPage("c1", listOf(u("stale")), firstSeq = 2, hasMore = true))

        assertEquals(1, r.messages.size, "no in-flight request — the page is dropped")
    }

    @Test
    fun anOldDaemonsReplayNeverOffersPaging() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(u("q1"), a("a1")))) // pre-#147 frame: no cursor fields

        assertFalse(r.historyHasMore.value)
        r.loadOlderHistory() // no anchor — must be a clean no-op
        assertFalse(r.historyLoadingOlder.value)
    }
}
