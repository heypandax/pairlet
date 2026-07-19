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
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A conversation opens at its LATEST message, including one you switch into (issue #165).
 *
 * The chat used to be reachable only from a session list, so every open was a fresh mount and the scroll
 * state reset for free. The switcher made chat→chat possible without remounting, and the previous session's
 * position then rode along — you landed mid-transcript.
 *
 * COVERAGE, honestly: this proves the landing effect works and would catch it breaking outright. It does
 * NOT reproduce the shipped bug, which needed the list to be UNPINNED — and unpinning requires a real drag
 * (programmatic scrolls deliberately never unpin, see [rememberBottomPinned]). Synthetic touch input does
 * not drive this list in the headless scene: swipes inject fine but move nothing, so any assertion built on
 * them passes with the fix reverted. Two attempts at that test proved nothing and are not repeated here;
 * the unpinned path is verified on device.
 *
 * It asserts where the list actually PARKED via an injected [LazyListState] rather than "is the last line
 * visible" — in a roomy scene the whole transcript renders either way, which is its own blind spot.
 */
@OptIn(ExperimentalTestApi::class)
class SwitchScrollLandingTest {

    private fun account(id: String) = PairedDaemon(
        relay = "wss://test.invalid", accountId = id, daemonPub = "pub", deviceId = "dev", credential = "cred",
    )

    private fun live(convo: String, dir: String) = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s-$convo",
        mode = PermissionMode.DEFAULT, executing = false,
        model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    private fun history(convo: String, tag: String) = ConvoHistory(
        convo,
        (1..40).map { HistoryMessage(if (it % 2 == 0) ChatRole.ASSISTANT else ChatRole.USER, "$tag line $it") },
    )

    /**
     * The one that reproduces what shipped, twice.
     *
     * A transcript lands through `clear() + addAll()`, and emptying the list clamps [LazyListState] back to
     * index 0 — so every history frame parks the chat at the TOP and something has to re-land it. The
     * follow-the-stream effect does that, but only while the chat is pinned, and switching used to re-mint
     * the pin state per conversation: effects still holding the previous instance read a dead object, so
     * nothing re-landed and the session opened at the top of its history. Entering from a session list
     * never showed it — that path remounts, so only one instance ever existed.
     *
     * A SECOND history frame is the point: the first lands while everything is still fresh.
     */
    @Test
    fun aSecondHistoryFrameAfterSwitchingStillLandsAtTheEnd() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val listState = LazyListState()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-land-2wave")).apply {
                    receiveForTest(live("c1", "/w/alpha"))
                    receiveForTest(history("c1", "alpha"))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo, listStateForTest = listState) } }
        }
        waitForIdle()

        // switch into another project's session
        repo.messages.clear()
        repo.receiveForTest(live("c2", "/w/beta"))
        repo.receiveForTest(history("c2", "beta"))
        waitForIdle()

        // …and the daemon sends more of it (paging catch-up / a replay landing after the first) — another
        // clear()+addAll(), another clamp to the top, which only the pin gets the view back down from
        repo.receiveForTest(history("c2", "beta"))
        waitForIdle()

        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        assertTrue(info.totalItemsCount > 1, "sanity: the transcript must overflow this viewport")
        assertTrue(
            lastVisible?.index == info.totalItemsCount - 1,
            "the chat must be back at its last item after the transcript re-lands " +
                "(last visible ${lastVisible?.index} of ${info.totalItemsCount - 1})",
        )
        // …and REACHED, not merely the last one composed: a list scrolled to its maximum still reads as
        // "it didn't scroll down" if the final message's bottom sits past the viewport (which is what a
        // tall Bash block looks like on the phone)
        val bottom = (lastVisible!!.offset + lastVisible.size)
        assertTrue(
            bottom <= info.viewportEndOffset,
            "the last message must end INSIDE the viewport — its bottom is at $bottom, viewport ends at " +
                "${info.viewportEndOffset} (clipped by ${bottom - info.viewportEndOffset}px)",
        )
    }

    @Test
    fun switchingProjectsLandsOnTheLatestMessage() = runComposeUiTest {
        lateinit var repo: PocketRepository
        val listState = LazyListState()
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account("acct-land")).apply {
                    receiveForTest(live("c1", "/w/alpha"))
                    receiveForTest(history("c1", "alpha"))
                }
            }
            // a phone-sized viewport, so a 40-message transcript genuinely overflows and parking matters
            PocketTheme { Box(Modifier.requiredSize(390.dp, 600.dp)) { ChatScreen(repo, listStateForTest = listState) } }
        }
        waitForIdle()
        val tail = listState.firstVisibleItemIndex
        assertTrue(tail > 0, "sanity: a 40-message transcript must overflow this viewport (was $tail)")

        // switch to ANOTHER PROJECT's session: openSession clears the transcript and nulls the conversation,
        // the daemon answers with the new one, and its history streams in
        repo.messages.clear()
        repo.receiveForTest(live("c2", "/w/beta"))
        repo.receiveForTest(history("c2", "beta"))
        waitForIdle()

        assertTrue(
            listState.firstVisibleItemIndex >= tail,
            "the session you switch INTO must open at its latest message " +
                "(landed at ${listState.firstVisibleItemIndex}, expected around $tail)",
        )
    }
}
