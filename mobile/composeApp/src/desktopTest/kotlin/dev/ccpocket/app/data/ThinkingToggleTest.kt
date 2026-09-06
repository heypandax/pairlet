package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #345 — the extended-thinking toggle's client-side plumbing: advertisement gating (an old
 * daemon must never show a switch it would silently drop), the `/thinking` command routing, and the
 * per-session persistence that survives a reopen.
 */
class ThinkingToggleTest {
    private fun repo(scope: CoroutineScope, sent: MutableList<Frame>) =
        PocketRepository(scope).apply { onSendForTest = { sent += it } }

    private fun advertised(repo: PocketRepository, support: Boolean) = repo.receiveForTest(
        ModelsList(agent = AgentKind.CLAUDE, models = listOf("opus"), supportsThinkingToggle = support),
    )

    @Test
    fun switch_hidden_until_the_daemon_advertises_and_fires_the_command_when_it_does() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            advertised(repo, false)
            assertFalse(repo.supportsThinkingToggle(), "an old daemon never advertises — the switch hides")

            advertised(repo, true)
            assertTrue(repo.supportsThinkingToggle())

            repo.receiveForTest(SessionLive("c1", "/x", "sid-1", agent = AgentKind.CLAUDE))
            repo.switchThinking(false)
            assertEquals(false, repo.thinking.value, "optimistic flip")
            val cmd = sent.filterIsInstance<SendPrompt>().single()
            assertEquals("/thinking off", cmd.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun session_live_reconciles_the_toggle_in_both_directions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(SessionLive("c1", "/x", "sid-1", agent = AgentKind.CLAUDE, thinking = false))
            assertEquals(false, repo.thinking.value)

            // `/thinking default` must clear an optimistic explicit value — the daemon's null wins
            repo.receiveForTest(SessionLive("c1", "/x", "sid-1", agent = AgentKind.CLAUDE, thinking = null))
            assertNull(repo.thinking.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persisted_choice_restores_on_reopen_only_at_a_supporting_daemon() {
        // SEED: session sid-9 was last seen with thinking OFF. The seeding repo must be a different
        // instance than the opening one — the opener would otherwise be refused by the #235 alreadyOpen
        // guard (the session it is being asked to open IS its current one). The persisted row crosses
        // instances via the test SecureStore file, exactly like an app restart.
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seed = repo(seedScope, mutableListOf())
        try {
            advertised(seed, true)
            seed.receiveForTest(SessionLive("c1", "/x", "sid-9", agent = AgentKind.CLAUDE, thinking = false))
        } finally {
            seedScope.cancel()
        }

        // REOPEN at a supporting daemon: the remembered choice relaunches with the session
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            advertised(repo, true)
            assertTrue(repo.openSession("/x", "sid-9", agent = AgentKind.CLAUDE))
            assertEquals(false, sent.filterIsInstance<OpenSession>().single().thinking)
        } finally {
            scope.cancel()
        }

        // the same persisted choice against an OLD daemon (never advertised) must not ride the open:
        // the field would be silently dropped and the badge would lie
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent2 = mutableListOf<Frame>()
        val repo2 = repo(scope2, sent2)
        try {
            repo2.receiveForTest(ModelsList(agent = AgentKind.CLAUDE, models = listOf("opus")))
            assertTrue(repo2.openSession("/x", "sid-9", agent = AgentKind.CLAUDE))
            assertNull(sent2.filterIsInstance<OpenSession>().single().thinking, "a never-advertising daemon must not receive the toggle")
        } finally {
            scope2.cancel()
        }
    }
    @Test
    fun cold_reopen_keeps_off_before_the_models_reply() {
        val sid = "thinking-cold-reopen"
        seedOff(sid)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            assertFalse(repo.supportsThinkingToggle(), "UNKNOWN must not expose the switch")
            assertTrue(repo.openSession("/x", sid, agent = AgentKind.CLAUDE))
            assertEquals(false, sent.filterIsInstance<OpenSession>().single().thinking)
            advertised(repo, true) // the real response can arrive AFTER OpenSession
            repo.receiveForTest(SessionLive("cold", "/x", sid, agent = AgentKind.CLAUDE, thinking = false))
            assertEquals(false, repo.thinking.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recovery_opens_restore_off_but_do_not_override_an_explicit_value() = runBlocking {
        val sid = "thinking-recovery-open"
        seedOff(sid)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            // Reconnect, SessionGone and split-pane opens omit the field and all use this send seam.
            repo.sendForTest(OpenSession("/x", sid, lastEventSeq = 42))
            assertEquals(OpenSession("/x", sid, lastEventSeq = 42, thinking = false), sent.last())
            repo.sendForTest(OpenSession("/x", sid, thinking = true))
            assertEquals(true, (sent.last() as OpenSession).thinking)
            advertised(repo, false)
            repo.sendForTest(OpenSession("/x", sid))
            assertNull((sent.last() as OpenSession).thinking, "known unsupported is distinct from unknown")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recovery_does_not_copy_thinking_to_a_new_session_or_another_agent() = runBlocking {
        val sid = "thinking-agent-boundary"
        seedOff(sid)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.sendForTest(OpenSession("/x"))
            assertNull((sent.last() as OpenSession).thinking)
            repo.sendForTest(OpenSession("/x", sid, agent = AgentKind.CODEX))
            assertNull((sent.last() as OpenSession).thinking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun default_acknowledgement_clears_the_choice_used_by_recovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            advertised(repo, true)
            repo.receiveForTest(SessionLive("default", "/x", "thinking-default", agent = AgentKind.CLAUDE, thinking = false))
            repo.receiveForTest(SessionLive("default", "/x", "thinking-default", agent = AgentKind.CLAUDE, thinking = null))
            repo.sendForTest(OpenSession("/x", "thinking-default"))
            assertNull((sent.last() as OpenSession).thinking)
        } finally {
            scope.cancel()
        }
    }

    private fun seedOff(sid: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val seed = repo(scope, mutableListOf())
            advertised(seed, true)
            seed.receiveForTest(SessionLive("seed-$sid", "/x", sid, agent = AgentKind.CLAUDE, thinking = false))
        } finally {
            scope.cancel()
        }
    }
}
