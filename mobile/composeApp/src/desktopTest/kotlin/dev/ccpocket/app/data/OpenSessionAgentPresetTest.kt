package dev.ccpocket.app.data

import androidx.compose.runtime.snapshots.Snapshot
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentPresetInfo
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #333 — the agent preset's two wire rules, at the repository seam.
 *
 *  1. It rides [OpenSession.agentPreset] on a NEW session only. A resume's preset is already fixed on the
 *     backend (dsh locks it once the session has produced output), so sending it there is a request that
 *     can only be refused — and the gate lives in the repo rather than in the picker so that EVERY caller
 *     inherits it: deep link, push tap, the desktop popover, the #235 retry replay.
 *  2. What the session-info surface shows is [SessionLive.agentPreset] — the daemon's read-back — and
 *     never the value the App asked for. A preset the backend refused must not be displayed as though it
 *     had taken effect.
 */
class OpenSessionAgentPresetTest {

    private fun withRepo(block: (PocketRepository, MutableList<Frame>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-preset", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        // dsh is post-baseline: without the advertisement openSession refuses the agent outright.
        repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_DSH)))
        val sent = mutableListOf<Frame>()
        repo.onSendForTest = { sent += it }
        try {
            block(repo, sent)
        } finally {
            scope.cancel()
        }
    }

    private fun opens(sent: List<Frame>) = sent.filterIsInstance<OpenSession>()

    @Test
    fun a_new_session_carries_the_chosen_preset() = withRepo { repo, sent ->
        repo.openSession("/w/proj", agent = AgentKind.DSH, startAgentPreset = "minimal")
        Snapshot.sendApplyNotifications()
        assertEquals("minimal", opens(sent).single().agentPreset)
    }

    @Test
    fun a_resume_never_carries_one_even_when_the_caller_passes_it() = withRepo { repo, sent ->
        repo.openSession("/w/proj", resumeId = "sid-1", agent = AgentKind.DSH, startAgentPreset = "minimal")
        Snapshot.sendApplyNotifications()
        assertNull(
            opens(sent).single().agentPreset,
            "the backend locks a resumed session's preset — asking can only be refused",
        )
    }

    /** No pick = the backend's own default. The App must not invent one from the advertised catalogue. */
    @Test
    fun no_pick_sends_no_preset() = withRepo { repo, sent ->
        repo.openSession("/w/proj", agent = AgentKind.DSH)
        Snapshot.sendApplyNotifications()
        assertNull(opens(sent).single().agentPreset)
    }

    /**
     * #235's retry replays the SAME request. If the preset were left out of the remembered attempt the
     * retry would quietly start the session under a DIFFERENT persona than the click that failed asked for
     * — and the user, who only ever saw one spinner, would have no way to tell.
     */
    @Test
    fun the_retry_replays_the_same_preset() = withRepo { repo, sent ->
        repo.openSession("/w/proj", agent = AgentKind.DSH, startAgentPreset = "code")
        Snapshot.sendApplyNotifications()
        // A refusal releases the #235 claim while KEEPING the attempt — that is exactly the retry's window.
        repo.receiveForTest(dev.ccpocket.protocol.PocketError("agent_unavailable", "no dsh over there"))
        Snapshot.sendApplyNotifications()
        sent.clear()
        assertTrue(repo.retryOpen(), "a refused open must stay retryable")
        Snapshot.sendApplyNotifications()
        assertEquals(listOf<String?>("code"), opens(sent).map { it.agentPreset })
    }

    // ── what the session surface shows ───────────────────────────────────────────────────────────────

    @Test
    fun the_displayed_preset_is_the_daemon_read_back_not_the_request() = withRepo { repo, _ ->
        repo.openSession("/w/proj", agent = AgentKind.DSH, startAgentPreset = "minimal")
        Snapshot.sendApplyNotifications()
        // the daemon answers with the preset that is REALLY in force — dsh refused the switch and kept its
        // own default. Nothing may render "minimal" from here on.
        repo.receiveForTest(
            SessionLive("convo-1", "/w/proj", "sid-1", agent = AgentKind.DSH, agentPreset = "standard"),
        )
        Snapshot.sendApplyNotifications()
        assertEquals("standard", repo.sessionAgentPreset.value)
    }

    /** Unconditional reconcile: a session with no preset must clear the previous session's value. */
    @Test
    fun a_session_without_a_preset_clears_the_previous_one() = withRepo { repo, _ ->
        repo.receiveForTest(
            SessionLive("convo-1", "/w/proj", "sid-1", agent = AgentKind.DSH, agentPreset = "minimal"),
        )
        Snapshot.sendApplyNotifications()
        assertEquals("minimal", repo.sessionAgentPreset.value)
        repo.receiveForTest(SessionLive("convo-1", "/w/proj", "sid-1", agent = AgentKind.CLAUDE))
        Snapshot.sendApplyNotifications()
        assertNull(repo.sessionAgentPreset.value, "a stale preset would label the wrong session")
    }

    // ── the catalogue accessor the picker gates on ───────────────────────────────────────────────────

    @Test
    fun agentPresetsFor_is_empty_until_the_daemon_advertises_them() = withRepo { repo, _ ->
        assertTrue(repo.agentPresetsFor(AgentKind.DSH).isEmpty())
        repo.receiveForTest(
            ModelsList(
                agent = AgentKind.DSH,
                models = listOf("deepseek-v4-pro"),
                agentPresets = listOf(AgentPresetInfo("standard", "Standard", recommended = true)),
            ),
        )
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("standard"), repo.agentPresetsFor(AgentKind.DSH).map { it.id })
        assertTrue(repo.agentPresetsFor(AgentKind.CLAUDE).isEmpty(), "presets are per agent, never shared")
    }
}
