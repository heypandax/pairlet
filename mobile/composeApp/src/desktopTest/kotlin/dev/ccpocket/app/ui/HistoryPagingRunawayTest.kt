package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening a session with older history behind it must not page that history in by itself (issue #165).
 *
 * The "load earlier" row asks for one older page when it composes, on the theory that it only composes
 * once the reader has scrolled to the top of the loaded window. That theory breaks on arrival: a transcript
 * lands through `clear() + addAll()`, which clamps the list back to index 0, so the row composed on every
 * history frame. Each spurious page prepended more rows and clamped again — a self-driving loop that walked
 * the whole session in while the view fought to stay at the bottom. On the device this read as "switching
 * doesn't scroll to the latest message": it did land, then got dragged off by the runaway paging.
 *
 * Device trace that produced this test (transcript grew unprompted, four pages deep):
 *   scrollToEnd(landing) after first=100 lastVisible=100 canFwd=false
 *   follow effect FIRE msgs=200 first=104 …  then 300, then 400, then 475
 */
@OptIn(ExperimentalTestApi::class)
class HistoryPagingRunawayTest {

    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )

    private fun live(convo: String) = SessionLive(
        convoId = convo, workdir = "/w/proj", sessionId = "s-$convo",
        mode = PermissionMode.DEFAULT, executing = false,
        model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    private fun history(convo: String, tag: String, n: Int = 60) = ConvoHistory(
        convo,
        (1..n).map { HistoryMessage(if (it % 2 == 0) ChatRole.ASSISTANT else ChatRole.USER, "$tag line $it") },
        lastSeq = n.toLong(),
        firstSeq = 1L,
        hasMore = true,
    )

    @Test
    fun openingASessionWithOlderHistoryDoesNotPageItInByItself() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val listState = LazyListState()
        val pageRequests = mutableListOf<Frame>()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-paging")).apply {
                    onSendForTest = { f -> if (f is FetchHistoryPage) pageRequests.add(f) }
                    receiveForTest(live("c1"))
                    receiveForTest(history("c1", "alpha"))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo, listStateForTest = listState) } }
        }
        waitForIdle()

        assertTrue(repo.historyHasMore.value, "sanity: the daemon says there is older history behind this window")
        assertEquals(
            0, pageRequests.size,
            "just opening a session must not fetch older pages — the reader never asked for them, and each " +
                "one prepends rows that drag the view off the latest message",
        )

        // …and the same on the transcript re-landing (a second full replay / a switch into it), which is
        // the clear()+addAll() that used to look like "scrolled to the top"
        repo.receiveForTest(history("c1", "alpha"))
        waitForIdle()
        assertEquals(0, pageRequests.size, "a re-landing transcript must not fetch older pages either")

        assertTrue(
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1,
            "and the view is still on the latest message",
        )
    }
}
