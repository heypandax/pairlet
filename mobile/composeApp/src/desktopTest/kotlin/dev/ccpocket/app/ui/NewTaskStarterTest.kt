package dev.ccpocket.app.ui

import androidx.compose.runtime.snapshots.Snapshot
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #260 — the phone's new-task composer, as a contract rather than a screen.
 *
 * The feature's whole promise is one sentence: **what you typed either becomes the first turn of a session
 * opened with the project and agent shown on the chips, or it comes back to you untouched.** Everything
 * below is one way that sentence can be broken.
 *
 * Harness follows [dev.ccpocket.app.desktop.EmptyStateStarterTest] (#256, the surface that learned these
 * lessons first): a REAL repository on Unconfined, demo loopback for the delivered case (the demo session
 * lands synchronously, so no waiting), and hand-driven repo state for the failures — nothing answers those
 * opens, which is precisely the condition they are about.
 */
class NewTaskStarterTest {

    private fun withRepo(demo: Boolean, block: (PocketRepository, MutableList<Frame>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-newtask", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        if (demo) repo.enterDemo()
        val sent = mutableListOf<Frame>()
        repo.onSendForTest = { sent += it }
        try {
            block(repo, sent)
        } finally {
            scope.cancel()
        }
    }

    /** Poll a condition while pumping snapshot apply notifications — the queue's wake-up, headless. */
    private fun waitFor(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Snapshot.sendApplyNotifications()
            if (cond()) return
            Thread.sleep(5)
        }
        Snapshot.sendApplyNotifications()
        assertTrue(cond(), "condition never became true within ${timeoutMs}ms")
    }

    // ── delivered: the chips' choices are the ones that reach the wire ───────────────────────────────

    @Test
    fun sendOpensTheChosenProjectAndAgentThenSendsThePromptAsTurnOne() = withRepo(demo = true) { repo, sent ->
        repo.startTaskWithPrompt(DemoData.LIVE_DIR, "audit the release script", AgentKind.CLAUDE)

        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals(DemoData.LIVE_DIR, open.workdir, "the session opens in the project the chip named")
        assertEquals(AgentKind.CLAUDE, open.agent, "…on the agent the chip named")
        assertNull(open.resumeId, "a NEW session, never a resume")
        assertEquals(
            listOf("audit the release script"),
            sent.filterIsInstance<SendPrompt>().map { it.text },
            "and the draft goes out as that session's first turn",
        )
        assertEquals("", repo.newTaskDraft.value, "only a DELIVERED prompt clears the draft")
        assertNull(repo.newTaskError.value)
        assertFalse(repo.newTaskStarting.value)
    }

    /** "Send → you are in the conversation" needs no navigation call: the root router renders the chat the
     *  moment convoId lands. Pinning it here means a future refactor can't quietly break the routing
     *  premise the sheet relies on to close itself. */
    @Test
    fun aDeliveredTaskLeavesTheAppInsideTheNewConversation() = withRepo(demo = true) { repo, _ ->
        assertNull(repo.convoId.value, "precondition: no conversation, so the router is on the project list")

        repo.startTaskWithPrompt(DemoData.LIVE_DIR, "audit the release script", AgentKind.CLAUDE)

        assertNotNull(repo.convoId.value, "convoId is what the router keys the chat screen on")
    }

    /** The agent chip is not decorative: picking a non-default backend must change what is opened. */
    @Test
    fun aNonDefaultAgentPickReachesTheOpen() = withRepo(demo = true) { repo, sent ->
        repo.daemonSupportedAgents.value = dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES.toSet()

        repo.startTaskWithPrompt(DemoData.LIVE_DIR, "port the picker", AgentKind.CODEX)

        assertEquals(AgentKind.CODEX, sent.filterIsInstance<OpenSession>().single().agent)
    }

    // ── refused / failed: the text is always still the user's ────────────────────────────────────────

    /** An agent this daemon never advertised is refused BEFORE anything opens — and, critically, while the
     *  sheet is still on screen holding the picks that caused it. */
    @Test
    fun anUnsupportedAgentIsRefusedWithoutOpeningAnything() = withRepo(demo = true) { repo, sent ->
        repo.daemonSupportedAgents.value = emptySet() // an older daemon: ZCode/DSH are deny-by-omission

        val started = repo.startTaskWithPrompt(DemoData.LIVE_DIR, "try zcode", AgentKind.ZCODE)

        assertFalse(started, "the sheet stays open — startTaskWithPrompt reports it never started")
        assertTrue(sent.filterIsInstance<OpenSession>().isEmpty(), "nothing reached the wire")
        assertEquals(PocketRepository.NewTaskError.OPEN_REFUSED, repo.newTaskError.value)
        assertEquals("try zcode", repo.newTaskDraft.value, "the text the user typed is still theirs")
    }

    @Test
    fun aSessionThatNeverGoesLiveGivesTheDraftBack() = withRepo(demo = false) { repo, sent ->
        repo.firstPromptTimeoutMs = 60 // nothing answers this open — don't sit out the real 30s window
        repo.startTaskWithPrompt("~/code/thing", "rename the flaky test", AgentKind.CLAUDE)
        assertTrue(repo.newTaskStarting.value, "the sheet reports the session it belongs to is opening")

        waitFor { !repo.newTaskStarting.value }

        assertEquals(PocketRepository.NewTaskError.TIMEOUT, repo.newTaskError.value)
        assertEquals("rename the flaky test", repo.newTaskDraft.value, "held, not dropped")
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty(), "nothing was sent into a session that never opened")
    }

    /** The session went live but the send was gated — here by the #65 degraded-session gate. */
    @Test
    fun aRefusedSendKeepsTheDraftInsteadOfSwallowingIt() = withRepo(demo = false) { repo, sent ->
        repo.startTaskWithPrompt("~/code/thing", "rename the flaky test", AgentKind.CLAUDE)

        repo.sessionDegraded.value = true // the gate: the first send into a degraded session is intercepted
        repo.convoId.value = "convo-1"    // …and now the session goes live
        waitFor { !repo.newTaskStarting.value }

        assertEquals(PocketRepository.NewTaskError.SEND_REFUSED, repo.newTaskError.value)
        assertEquals("rename the flaky test", repo.newTaskDraft.value, "held, not dropped")
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty())
    }

    /** A second send while one is queued would open a second session for one intent. */
    @Test
    fun aSecondSendWhileOneIsQueuedIsIgnored() = withRepo(demo = false) { repo, sent ->
        repo.firstPromptTimeoutMs = 60
        repo.startTaskWithPrompt("~/code/thing", "first", AgentKind.CLAUDE)
        repo.startTaskWithPrompt("~/code/thing", "second", AgentKind.CLAUDE)

        assertEquals(1, sent.filterIsInstance<OpenSession>().size)
        assertEquals("first", repo.newTaskDraft.value, "the queued prompt is not replaced mid-flight")
        waitFor { !repo.newTaskStarting.value }
    }

    @Test
    fun aBlankDraftStartsNothing() = withRepo(demo = true) { repo, sent ->
        assertFalse(repo.startTaskWithPrompt(DemoData.LIVE_DIR, "   ", AgentKind.CLAUDE))
        assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())
        assertFalse(repo.newTaskStarting.value)
    }

    // ── the sheet's state survives being dismissed ───────────────────────────────────────────────────

    /**
     * The draft and both chip picks live on the repository, not in a `remember` inside the sheet — so
     * dismissing and re-opening shows the same scratchpad. This is the #256 lesson applied one surface
     * over: a composable-local field would be gone exactly when a failed queue needs to hand it back.
     */
    @Test
    fun theDraftAndChipPicksOutliveTheSheet() = withRepo(demo = true) { repo, _ ->
        repo.newTaskDraft.value = "half a thought"
        repo.newTaskDir.value = "/Users/alex/code/other"
        repo.newTaskAgent.value = AgentKind.CODEX

        // whatever the sheet composable does on dismiss, it does not touch these — they are not its state
        assertEquals("half a thought", repo.newTaskDraft.value)
        assertEquals("/Users/alex/code/other", repo.newTaskDir.value)
        assertEquals(AgentKind.CODEX, repo.newTaskAgent.value)
    }

    // ── the recents projection the chips prefill from ────────────────────────────────────────────────

    @Test
    fun recentsPutLiveProjectsFirstThenNewestTranscript() {
        val dirs = listOf(
            entry("/a/old", lastModified = 10),
            entry("/a/new", lastModified = 900),
            entry("/a/live", lastModified = 1, open = true),
            entry("/a/none", lastModified = 500, hasSessions = false, recent = false),
        )
        assertEquals(
            listOf("/a/live", "/a/new", "/a/old"),
            recentProjects(dirs).map { it.path },
            "a running session is the one recent that is not a guess; mtime orders the rest",
        )
    }

    @Test
    fun recentsAreCappedSoThePickerStaysAShortcut() {
        val dirs = (1..12).map { entry("/p/$it", lastModified = it.toLong()) }
        assertEquals(5, recentProjects(dirs).size)
        assertEquals(3, recentProjects(dirs, limit = 3).size)
    }

    private fun entry(
        path: String,
        lastModified: Long = 0,
        open: Boolean = false,
        hasSessions: Boolean = true,
        recent: Boolean = true,
    ) = DirectoryEntry(
        path = path, name = path.substringAfterLast('/'), isDir = true,
        hasSessions = hasSessions, recent = recent, lastModified = lastModified, open = open,
    )

    // ── the label the project chip shows ─────────────────────────────────────────────────────────────

    @Test
    fun theProjectChipNamesTheFolderNotThePath() {
        assertEquals("cc-pocket", folderLeaf("/Users/alex/code/cc-pocket"))
        assertEquals("cc-pocket", folderLeaf("/Users/alex/code/cc-pocket/"))
        assertEquals("proj", folderLeaf("C:\\dev\\proj"), "a Windows daemon's paths read right too")
        assertEquals("/", folderLeaf("/"), "a bare root keeps its own name rather than becoming empty")
    }
}
