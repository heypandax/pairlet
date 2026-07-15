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
