package dev.ccpocket.app.desktop

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.chat_no_session
import dev.ccpocket.app.resources.chat_start_choose
import dev.ccpocket.app.resources.chat_start_in
import dev.ccpocket.app.resources.chat_start_pick_project
import dev.ccpocket.app.resources.chat_start_placeholder
import dev.ccpocket.app.resources.chat_start_timeout
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #256 — the empty pane became the session starter.
 *
 * The whole feature rests on one promise: a first prompt typed there either becomes a turn or comes back to
 * the person who typed it. There is no protocol support for "open a session carrying a prompt", so the
 * desktop model queues it — open, wait for the same `convoId` the chat pane waits for, then take the ordinary
 * sendPrompt path — and every way that wait can end is a case below.
 *
 * Model harness follows [RepoDesktopModelStopTurnTest]: Unconfined + demo loopback runs the whole queue
 * synchronously (snapshotFlow emits at collection, and by then the demo session has already landed), so the
 * success case needs no waiting at all. The failure cases run WITHOUT demo mode — nothing answers the open,
 * which is precisely the condition they are about — and drive the repo by hand, pumping snapshot apply
 * notifications to wake the queue's observer (the frame clock's job in a real app).
 */
class EmptyStateStarterTest {

    private fun withModel(demo: Boolean, block: (PocketRepository, RepoDesktopModel, MutableList<Frame>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        if (demo) repo.enterDemo()
        repo.clearDraft(DemoData.LIVE_SESSION_ID) // never inherit a draft an earlier run left in the dev store
        repo.clearDraft("convo-1")
        val sent = mutableListOf<Frame>()
        repo.onSendForTest = { sent += it }
        // production order: the model exists BEFORE any session opens, so its init collectors' first pass
        // sees a null sessionKey (see RepoDesktopModelStopTurnTest)
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        try {
            block(repo, model, sent)
        } finally {
            scope.cancel()
            repo.clearDraft(DemoData.LIVE_SESSION_ID)
            repo.clearDraft("convo-1")
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

    // ── delivered ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aQueuedFirstPromptBecomesTheNewSessionsFirstTurn() = withModel(demo = true) { _, m, sent ->
        m.newSessionPrompt = "audit the release script" // as the empty state's field would hold it
        m.startSessionWithPrompt(DemoData.LIVE_DIR, "audit the release script")

        assertEquals(
            listOf("audit the release script"),
            sent.filterIsInstance<SendPrompt>().map { it.text },
            "the queued prompt goes out as a turn of the session it opened",
        )
        assertEquals("", m.newSessionPrompt, "and only a DELIVERED prompt clears the field")
        assertNull(m.newSessionPromptError)
        assertFalse(m.startingSession)
    }

    /** The queue takes the sendPrompt path directly and never writes the composer, so whatever draft the
     *  target session restores (#88) is untouched — the success case as much as the failure ones. */
    @Test
    fun theQueueNeverRoutesThroughTheComposer() = withModel(demo = true) { _, m, sent ->
        m.startSessionWithPrompt(DemoData.LIVE_DIR, "audit the release script")

        assertEquals(1, sent.filterIsInstance<SendPrompt>().size)
        assertEquals("", m.composer, "the prompt was sent, not typed into the session's composer")
    }

    // ── every way it can fail hands the text back ────────────────────────────────────────────────

    @Test
    fun aSessionThatNeverGoesLiveGivesThePromptBack() = withModel(demo = false) { _, m, sent ->
        m.firstPromptTimeoutMs = 60 // nothing answers this open — don't sit out the real 30s window
        m.startSessionWithPrompt("~/code/thing", "rename the flaky test")
        assertTrue(m.startingSession, "the field locks while the session it belongs to is opening")

        waitFor { !m.startingSession }
        assertEquals(NewSessionPromptError.TIMEOUT, m.newSessionPromptError)
        assertEquals("rename the flaky test", m.newSessionPrompt, "the text the user typed is still theirs")
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty(), "and nothing was sent into a session that never opened")
    }

    /** The session went live but the send was gated — here by the #65 degraded-session gate. */
    @Test
    fun aRefusedSendKeepsThePromptInsteadOfSwallowingIt() = withModel(demo = false) { repo, m, sent ->
        m.startSessionWithPrompt("~/code/thing", "rename the flaky test")

        repo.sessionDegraded.value = true // the gate: the first send into a degraded session is intercepted
        repo.convoId.value = "convo-1"    // …and now the session goes live
        waitFor { !m.startingSession }

        assertEquals(NewSessionPromptError.SEND_REFUSED, m.newSessionPromptError)
        assertEquals("rename the flaky test", m.newSessionPrompt, "held, not dropped")
        assertEquals("", m.composer, "and never forced into the live session's composer")
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty())
    }

    /** A second ⏎ while one prompt is queued would open a second session for one intent. */
    @Test
    fun aSecondSubmitWhileOneIsQueuedIsIgnored() = withModel(demo = false) { _, m, sent ->
        m.firstPromptTimeoutMs = 60
        m.startSessionWithPrompt("~/code/thing", "first")
        m.startSessionWithPrompt("~/code/thing", "second")

        assertEquals(1, sent.filterIsInstance<OpenSession>().size)
        assertEquals("first", m.newSessionPrompt, "the queued prompt is not replaced mid-flight")
        waitFor { !m.startingSession }
    }

    @Test
    fun aBlankPromptStartsNothing() = withModel(demo = true) { _, m, sent ->
        m.startSessionWithPrompt(DemoData.LIVE_DIR, "   ")
        assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())
        assertFalse(m.startingSession)
    }

    // ── the pane ─────────────────────────────────────────────────────────────────────────────────

    /** Seed model with no chat, so [ChatPane] renders the empty branch; records what the starter dispatches. */
    private open class StarterModel(override val newSessionDir: String? = "~/code/cc-pocket") : SeedDesktopModel() {
        override val hasChat = false
        val started = mutableListOf<Pair<String, String>>()
        override fun startSessionWithPrompt(dir: String, prompt: String) { started += dir to prompt }
        var popovers = 0
            private set
        override fun openNewSession(seed: String?) { popovers++ }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theEmptyPaneInvitesAPromptInsteadOfNamingADeadEnd() = runComposeUiTest {
        setContent { PocketTheme { ChatPane(StarterModel()) } }
        waitForIdle()

        assertPresent(str(Res.string.chat_no_session))            // "start a new session", not "none open"
        assertPresent(str(Res.string.chat_start_in, "cc-pocket")) // named against the project in context
        assertPresent(str(Res.string.chat_start_placeholder))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sendingFromTheEmptyPaneStartsASessionInTheProjectInContext() = runComposeUiTest {
        val model = StarterModel()
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        onNodeWithTag("new-session-prompt").performTextInput("summarize the diff")
        waitForIdle()
        onNodeWithTag("new-session-send").performClick()
        waitForIdle()

        assertEquals(listOf("~/code/cc-pocket" to "summarize the diff"), model.started)
    }

    /** No project in context: the field still takes text (refusing keystrokes IS the dead end this replaces),
     *  submitting explains itself inline rather than in a dialog, and nothing typed is lost. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun withNoProjectItSaysSoInlineAndKeepsWhatWasTyped() = runComposeUiTest {
        val model = StarterModel(newSessionDir = null)
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        onNodeWithTag("new-session-prompt").performTextInput("summarize the diff")
        waitForIdle()
        onNodeWithTag("new-session-send").performClick()
        waitForIdle()

        assertPresent(str(Res.string.chat_start_pick_project))
        assertTrue(model.started.isEmpty(), "no directory means no session was opened anywhere")
        assertEquals("summarize the diff", model.newSessionPrompt, "and the text is untouched")
        assertFalse(present(str(Res.string.chat_start_timeout)))
    }

    /** …and the existing full popover stays one click away for typing a path. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theNoProjectPaneStillOffersTheFolderPopover() = runComposeUiTest {
        val model = StarterModel(newSessionDir = null)
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()

        onNodeWithTag("new-session-prompt").assertExists()
        onAllNodes(androidx.compose.ui.test.hasText(str(Res.string.chat_start_choose))).onFirst().performClick()
        waitForIdle()

        assertEquals(1, model.popovers)
    }
}
