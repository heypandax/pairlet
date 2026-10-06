package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.HISTORY_PAGE_RETRY_MS
import dev.ccpocket.app.ui.chat.SHORT_WINDOW_AUTO_PAGES_LEGACY
import dev.ccpocket.app.ui.chat.ShortWindowPaging
import dev.ccpocket.app.ui.chat.wantsOlderHistory
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lean history, the paging half (docs/design/SLOW-LINK-RESILIENCE.md §6): the daemon may open a session with
 * only the newest rows that fit a byte budget. When that is less than a screenful the chat has to fetch older
 * pages by itself — a list that cannot scroll has no "reached the top" to trigger on — and it must stop as soon
 * as the screen is full. [HistoryPagingRunawayTest] pins the other side: a window that IS tall never pages.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryAutoPageTest {

    private fun account(id: String) = PairedDaemon(relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred")

    private fun live(convo: String) = SessionLive(convoId = convo, workdir = "/w/proj", sessionId = "s-$convo",
        mode = PermissionMode.DEFAULT, executing = false, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE)

    /** [n] one-line rows ending at cursor [last]. */
    private fun rows(last: Long, n: Int) = (last - n + 1..last).map { HistoryMessage(if (it % 2 == 0L) ChatRole.ASSISTANT else ChatRole.USER, "line $it", seq = it) }

    @Test
    fun the_decision_needs_every_one_of_its_facts() {
        assertTrue(wantsOlderHistory(parkedAtTop = true, landed = true, hasMore = true, laidOutItems = 6, rows = 5))
        assertFalse(wantsOlderHistory(parkedAtTop = false, landed = true, hasMore = true, laidOutItems = 6, rows = 5))
        assertFalse(wantsOlderHistory(parkedAtTop = true, landed = false, hasMore = true, laidOutItems = 6, rows = 5), "not before the first landing")
        assertFalse(wantsOlderHistory(parkedAtTop = true, landed = true, hasMore = false, laidOutItems = 6, rows = 5), "nothing above")
        assertFalse(wantsOlderHistory(parkedAtTop = true, landed = true, hasMore = true, laidOutItems = 0, rows = 5), "the new rows are not measured yet")
        assertFalse(wantsOlderHistory(parkedAtTop = true, landed = true, hasMore = true, laidOutItems = 0, rows = 0), "an empty chat")
    }

    @Test
    fun the_short_window_budget_stops_on_no_progress_and_on_its_cap() {
        val budget = ShortWindowPaging()
        assertTrue(budget.mayAsk(pageGen = 0, rows = 3), "the first page is always worth asking for")
        assertTrue(budget.mayAsk(pageGen = 0, rows = 3), "asking again for a page that has not landed is the same request")
        assertTrue(budget.mayAsk(pageGen = 1, rows = 4), "the page added a row: one more")
        assertFalse(budget.mayAsk(pageGen = 2, rows = 4), "that page folded away entirely: stop")
        assertFalse(budget.mayAsk(pageGen = 2, rows = 4))
        assertTrue(budget.mayAsk(pageGen = 2, rows = 9), "the reader opened the fold: there is progress to build on again")
        assertFalse(budget.mayAsk(pageGen = 3, rows = 30), "but three automatic pages is the cap, whatever they showed")

        // against a daemon whose pages are not byte-bounded: the one automatic page this screen always fetched
        val legacy = ShortWindowPaging()
        assertTrue(legacy.mayAsk(pageGen = 0, rows = 3, maxPages = SHORT_WINDOW_AUTO_PAGES_LEGACY))
        assertFalse(legacy.mayAsk(pageGen = 1, rows = 9, maxPages = SHORT_WINDOW_AUTO_PAGES_LEGACY))
    }

    @Test
    fun a_window_shorter_than_the_screen_pages_by_itself_until_the_screen_is_full() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val listState = LazyListState()
        val pageRequests = mutableListOf<FetchHistoryPage>()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-autopage")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(dev.ccpocket.protocol.DaemonInfo(supportsLeanHistory = true))
                    receiveForTest(live("c1"))
                    // the lean first window: two short rows, far less than the chat area of a 600 dp screen, with history above
                    receiveForTest(ConvoHistory("c1", rows(last = 100, n = 2), lastSeq = 100, firstSeq = 99, hasMore = true))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo, listStateForTest = listState) } }
        }
        waitForIdle()
        assertEquals(listOf(99L), pageRequests.map { it.beforeSeq }, "a window that cannot scroll asks for the page above it")

        // a page that still leaves the window short: ask for the next one, from the new anchor
        repo.receiveForTest(ConvoHistoryPage("c1", rows(last = 98, n = 1), firstSeq = 98, hasMore = true))
        waitForIdle()
        assertEquals(listOf(99L, 98L), pageRequests.map { it.beforeSeq })

        // a page that fills the screen: stop. The reader takes it from here by scrolling.
        repo.receiveForTest(ConvoHistoryPage("c1", rows(last = 97, n = 60), firstSeq = 38, hasMore = true))
        waitForIdle()
        assertEquals(2, pageRequests.size, "a full screen must not keep paging: ${pageRequests.map { it.beforeSeq }}")
        assertTrue(listState.canScrollBackward, "there is now history to scroll up into")
        assertEquals(listState.layoutInfo.totalItemsCount - 1, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index, "and the view is still on the latest message")
    }

    @Test
    fun against_a_daemon_without_lean_history_a_short_window_fetches_one_page_and_no_more() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val pageRequests = mutableListOf<FetchHistoryPage>()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-autopage-legacy")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(dev.ccpocket.protocol.DaemonInfo()) // an older daemon: no capability
                    receiveForTest(SessionLive(convoId = "c1", workdir = "/w/proj", sessionId = "s-c1", mode = PermissionMode.DEFAULT,
                        executing = false, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE))
                    receiveForTest(ConvoHistory("c1", rows(last = 100, n = 2), lastSeq = 100, firstSeq = 99, hasMore = true))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo) } }
        }
        waitForIdle()
        assertEquals(1, pageRequests.size)
        // its page is still short — but an old daemon's pages are a hundred full rows: not three of them unasked
        repo.receiveForTest(ConvoHistoryPage("c1", rows(last = 98, n = 1), firstSeq = 98, hasMore = true))
        waitForIdle()
        assertEquals(1, pageRequests.size)
    }

    @Test
    fun a_new_full_window_starts_with_a_fresh_budget() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val pageRequests = mutableListOf<FetchHistoryPage>()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-autopage-rewindow")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(dev.ccpocket.protocol.DaemonInfo(supportsLeanHistory = true))
                    receiveForTest(live("c1"))
                    receiveForTest(ConvoHistory("c1", rows(last = 100, n = 2), lastSeq = 100, firstSeq = 99, hasMore = true))
                }
            }
            // a tall screen: five one-line rows must still leave it unfilled, so only the budget can stop the paging
            PocketTheme { Box(Modifier.requiredSize(390.dp, 1600.dp)) { ChatScreen(repo) } }
        }
        waitForIdle()
        // spend the whole budget: three pages of one row each, the window still short after every one
        for (anchor in listOf(98L, 97L, 96L)) {
            repo.receiveForTest(ConvoHistoryPage("c1", rows(last = anchor, n = 1), firstSeq = anchor, hasMore = true))
            waitForIdle()
        }
        assertEquals(listOf(99L, 98L, 97L), pageRequests.map { it.beforeSeq }, "three automatic pages, then it waits")
        // the link reconnects and the same conversation gets a full window again (same convoId): what the old
        // window's pages showed says nothing about this one
        repo.receiveForTest(ConvoHistory("c1", rows(last = 120, n = 2), lastSeq = 120, firstSeq = 119, hasMore = true))
        waitForIdle()
        assertEquals(119L, pageRequests.last().beforeSeq)
        assertEquals(4, pageRequests.size)
    }

    @Test
    fun a_short_window_with_nothing_above_it_asks_for_nothing() = runComposeUiTest {
        val pageRequests = mutableListOf<FetchHistoryPage>()
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember {
                PocketRepository(scope, account("acct-autopage-end")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(dev.ccpocket.protocol.DaemonInfo(supportsLeanHistory = true))
                    receiveForTest(live("c1"))
                    receiveForTest(ConvoHistory("c1", rows(last = 2, n = 2), lastSeq = 2, firstSeq = 1, hasMore = false))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo) } }
        }
        waitForIdle()
        assertTrue(pageRequests.isEmpty())
    }

    @Test
    fun a_page_that_never_arrives_is_asked_for_again_but_not_in_a_hurry() = runComposeUiTest {
        val pageRequests = mutableListOf<FetchHistoryPage>()
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember {
                PocketRepository(scope, account("acct-autopage-lost")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(dev.ccpocket.protocol.DaemonInfo(supportsLeanHistory = true))
                    receiveForTest(live("c1"))
                    receiveForTest(ConvoHistory("c1", rows(last = 100, n = 2), lastSeq = 100, firstSeq = 99, hasMore = true))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo) } }
        }
        waitForIdle()
        assertEquals(1, pageRequests.size)
        // the spinner's own deadline (10 s) passing is not a reason to hand a slow link the same page twice
        mainClock.advanceTimeBy(HISTORY_PAGE_RETRY_MS - 5_000)
        waitForIdle()
        assertEquals(1, pageRequests.size)
        // …but a window too short to scroll has no gesture to retry with, so the chat does it
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        assertEquals(listOf(99L, 99L), pageRequests.map { it.beforeSeq })
    }
}
