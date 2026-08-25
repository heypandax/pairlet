package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@OptIn(ExperimentalCoroutinesApi::class)
class RepoDesktopModelStopTurnTest {

    private fun withDemoModel(block: (PocketRepository, RepoDesktopModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.enterDemo()
        repo.clearDraft(DemoData.LIVE_SESSION_ID) // the draft key a demo open lands on (SessionLive echoes this id)
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.openSession(DemoData.LIVE_DIR) // demo loops SessionLive back synchronously — convoId is live
        try {
            block(repo, model)
        } finally {
            // Own the collectors this harness starts. In particular, a prior case's 400ms draft debounce
            // must not wake after the next case clears the shared demo draft and write stale text back.
            scope.cancel()
            repo.clearDraft(DemoData.LIVE_SESSION_ID)
        }
    }

    @Test
    fun stopInsideWindowRefillsTheComposer() = withDemoModel { repo, m ->
        m.stopRefillElapsedMsForTest = { 0 }
        assertTrue(repo.sendPrompt("fix the login bug"))
        // The stop-refill unit owns its transcript input. Demo streaming is intentionally async and
        // other desktop tests can advance it, so do not rely on its delayed echo retaining this row.
        repo.messages.add(ChatItem.User("fix the login bug"))
        m.stopTurn()
        assertEquals("fix the login bug", m.composer)
    }

    @Test
    fun stopPastWindowLeavesTheComposerAlone() = withDemoModel { repo, m ->
        m.stopRefillWindowMs = 50
        assertTrue(repo.sendPrompt("fix the login bug"))
        m.stopRefillElapsedMsForTest = { 50 } // exactly at the exclusive boundary; no wall-clock sleep
        m.stopTurn()
        assertEquals("", m.composer)
    }

    @Test
    fun stopNeverClobbersATypedDraft() = withDemoModel { repo, m ->
        m.stopRefillElapsedMsForTest = { 0 } // exercise the refill branch; the non-blank draft must still win
        assertTrue(repo.sendPrompt("fix the login bug"))
        repo.messages.add(ChatItem.User("fix the login bug"))
        m.composer = "actually, try the signup flow"
        m.stopTurn()
        assertEquals("actually, try the signup flow", m.composer)
    }

    @Test
    fun stopOnAnAttachedTurnRefillsNothing() = withDemoModel { repo, m ->
        // a running turn this app never sent: the prompt arrived via transcript replay, not sendPrompt
        repo.messages.add(ChatItem.User("prompt typed on the phone"))
        repo.streaming.value = true
        m.stopTurn()
        assertEquals("", m.composer)
    }
}
