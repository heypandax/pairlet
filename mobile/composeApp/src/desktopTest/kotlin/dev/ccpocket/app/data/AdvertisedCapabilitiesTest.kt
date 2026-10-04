package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client gates that moved from "is this Claude / Codex" to "what did the daemon advertise".
 *
 * Every case runs all six backends twice: against the lists today's daemon really sends (the per-backend
 * ModelService answers) and against an older daemon that omits the capability fields. In both the result
 * must equal the legacy name-based answer — the move is an equivalence, not a behaviour change.
 */
class AdvertisedCapabilitiesTest {

    /** What the current daemon's per-backend ModelService puts on the wire (only the fields that matter). */
    private fun currentDaemon(agent: AgentKind): ModelsList = when (agent) {
        AgentKind.CLAUDE -> ModelsList(
            agent = agent,
            models = listOf("opus"),
            permissionModes = listOf(CLAUDE_PERMISSION_MODE_AUTO),
            supportsThinkingToggle = true,
        )
        AgentKind.CODEX -> ModelsList(
            agent = agent,
            models = listOf("gpt-5.5"),
            modelCapabilities = listOf(
                ModelCapabilities("gpt-5.5", serviceTiers = listOf(ModelServiceTier("priority", "Fast"))),
            ),
        )
        AgentKind.DSH -> ModelsList(
            agent = agent,
            models = listOf("deepseek-v4"),
            modelCapabilities = listOf(ModelCapabilities("deepseek-v4", reasoningEfforts = listOf("high"))),
        )
        AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.ZCODE -> ModelsList(agent = agent, models = listOf("m"))
    }

    /** An older daemon: the same list with every capability field absent (decoded to its default). */
    private fun oldDaemon(agent: AgentKind): ModelsList = ModelsList(agent = agent, models = currentDaemon(agent).models)

    private fun modelOf(agent: AgentKind) = currentDaemon(agent).models.first()

    // ── thinking toggle ──────────────────────────────────────────────────────────────────────────────

    /** The pre-move expression, verbatim: `agent == CLAUDE && agentModels[agent]?.supportsThinkingToggle != false`. */
    private fun legacyThinkingCarries(agent: AgentKind, listed: ModelsList?) =
        agent == AgentKind.CLAUDE && listed?.supportsThinkingToggle != false

    @Test
    fun thinking_choice_carries_exactly_as_the_name_check_did_for_every_backend() {
        for (agent in AgentKind.entries) {
            for ((label, listed) in listOf("no list yet" to null, "current daemon" to currentDaemon(agent), "old daemon" to oldDaemon(agent))) {
                assertEquals(
                    legacyThinkingCarries(agent, listed),
                    thinkingChoiceCarries(agent, listed),
                    "$agent / $label",
                )
            }
        }
        // spot-check the table the loop compares against
        assertTrue(thinkingChoiceCarries(AgentKind.CLAUDE, currentDaemon(AgentKind.CLAUDE)))
        assertEquals(false, thinkingChoiceCarries(AgentKind.CLAUDE, oldDaemon(AgentKind.CLAUDE)))
        assertTrue(thinkingChoiceCarries(AgentKind.CLAUDE, null), "unknown is not unsupported for Claude")
        assertEquals(false, thinkingChoiceCarries(AgentKind.CODEX, null))
    }

    @Test
    fun remembered_thinking_rides_the_reopen_only_where_the_daemon_supports_it() {
        for (agent in AgentKind.entries) {
            for ((label, listed) in listOf("current daemon" to currentDaemon(agent), "old daemon" to oldDaemon(agent))) {
                val sid = "adv-thinking-${agent.name.lowercase()}-${label.replace(' ', '-')}"
                seedThinkingOff(sid, agent)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val sent = mutableListOf<Frame>()
                val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
                try {
                    repo.receiveForTest(listed)
                    repo.openSession("/x", sid, agent = agent)
                    val expected = if (legacyThinkingCarries(agent, listed)) false else null
                    assertEquals(expected, sent.filterIsInstance<OpenSession>().lastOrNull()?.thinking, "$agent / $label")
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    private fun seedThinkingOff(sid: String, agent: AgentKind) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val seed = PocketRepository(scope).apply { onSendForTest = {} }
            seed.receiveForTest(ModelsList(agent = agent, models = listOf("m"), supportsThinkingToggle = true))
            seed.receiveForTest(SessionLive("seed-$sid", "/x", sid, agent = agent, thinking = false))
        } finally {
            scope.cancel()
        }
    }
}
