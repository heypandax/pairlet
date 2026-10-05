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

/** What the current daemon's per-backend ModelService puts on the wire (only the capability fields). */
internal fun currentDaemonModels(agent: AgentKind): ModelsList = when (agent) {
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
internal fun oldDaemonModels(agent: AgentKind): ModelsList =
    ModelsList(agent = agent, models = currentDaemonModels(agent).models)

/**
 * The client gates that moved from "is this Claude / Codex" to "what did the daemon advertise".
 *
 * Every case runs all six backends twice: against the lists today's daemon really sends (the per-backend
 * ModelService answers) and against an older daemon that omits the capability fields. In both the result
 * must equal the legacy name-based answer — the move is an equivalence, not a behaviour change.
 */
class AdvertisedCapabilitiesTest {
    private fun currentDaemon(agent: AgentKind) = currentDaemonModels(agent)
    private fun oldDaemon(agent: AgentKind) = oldDaemonModels(agent)

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

    // ── permission modes ─────────────────────────────────────────────────────────────────────────────

    /** Receive every backend's list from one daemon, as a connected App does. */
    private fun repoWithLists(scope: CoroutineScope, lists: (AgentKind) -> ModelsList) =
        PocketRepository(scope).apply {
            onSendForTest = {}
            AgentKind.entries.forEach { receiveForTest(lists(it)) }
        }

    private fun daemons(): List<Pair<String, (AgentKind) -> ModelsList>> =
        listOf("current daemon" to ::currentDaemon, "old daemon" to ::oldDaemon)

    @Test
    fun auto_permission_mode_is_offered_exactly_where_the_name_check_offered_it() {
        for ((label, lists) in daemons()) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = repoWithLists(scope, lists)
            try {
                for (agent in AgentKind.entries) {
                    // pre-move Settings.kt: `agent == CLAUDE && repo.supportsPermissionMode(AUTO)` (Claude's list)
                    val legacy = agent == AgentKind.CLAUDE && repo.supportsPermissionMode(CLAUDE_PERMISSION_MODE_AUTO)
                    assertEquals(legacy, repo.supportsPermissionMode(CLAUDE_PERMISSION_MODE_AUTO, agent), "$agent / $label")
                }
                assertEquals(
                    label == "current daemon",
                    repo.supportsPermissionMode(CLAUDE_PERMISSION_MODE_AUTO, AgentKind.CLAUDE),
                    "Claude Auto: offered by today's daemon, hidden by an old one",
                )
            } finally {
                scope.cancel()
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
