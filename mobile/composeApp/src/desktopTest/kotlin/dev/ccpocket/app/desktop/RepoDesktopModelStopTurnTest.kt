package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * stopTurn's #48 refill window: interrupting a run hands the prompt back to the composer for
 * re-editing ONLY near its own send — a stop minutes into a run, or into a turn this app never
 * sent (attached mid-run), must not resurrect the prompt.
 *
 * Demo-mode harness (same as [RepoDesktopModelRecentTest]): outbound frames loop back synchronously
 * under Unconfined, and the demo's first reply frame sits behind a delay, so right after sendPrompt
 * the turn is genuinely in flight. Construction order matters: the model must exist BEFORE the
 * session opens (the production order) — the init collectors' first pass must see a null sessionKey,
 * or it evaluates derived state inside the snapshotFlow read and blows up the suite. The demo
 * session's persisted draft is cleared up front so the composer deterministically starts blank
 * even when the dev-machine store carries one from an earlier run.
 */
class RepoDesktopModelStopTurnTest {

    private fun demoModel(): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.enterDemo()
        repo.clearDraft(DemoData.LIVE_SESSION_ID) // the draft key a demo open lands on (SessionLive echoes this id)
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.openSession(DemoData.LIVE_DIR) // demo loops SessionLive back synchronously — convoId is live
        return repo to model
    }

    @Test
    fun stopInsideWindowRefillsTheComposer() {
        val (repo, m) = demoModel()
        assertTrue(repo.sendPrompt("fix the login bug"))
        m.stopTurn()
        assertEquals("fix the login bug", m.composer)
    }

    @Test
    fun stopPastWindowLeavesTheComposerAlone() {
        val (repo, m) = demoModel()
        m.stopRefillWindowMs = 50
        assertTrue(repo.sendPrompt("fix the login bug"))
        Thread.sleep(200) // outlive the shrunken window — the anchor is monotonic, no frame traffic needed
        m.stopTurn()
        assertEquals("", m.composer)
    }

    @Test
    fun stopNeverClobbersATypedDraft() {
        val (repo, m) = demoModel()
        assertTrue(repo.sendPrompt("fix the login bug"))
        m.composer = "actually, try the signup flow"
        m.stopTurn()
        assertEquals("actually, try the signup flow", m.composer)
    }

    @Test
    fun stopOnAnAttachedTurnRefillsNothing() {
        val (repo, m) = demoModel()
        // a running turn this app never sent: the prompt arrived via transcript replay, not sendPrompt
        repo.messages.add(ChatItem.User("prompt typed on the phone"))
        repo.streaming.value = true
        m.stopTurn()
        assertEquals("", m.composer)
    }
}
