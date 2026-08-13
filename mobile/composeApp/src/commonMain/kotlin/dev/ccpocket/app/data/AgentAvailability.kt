package dev.ccpocket.app.data

import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind

/**
 * Whether an agent is safe to send to the currently connected daemon.
 *
 * Claude, Codex, OpenCode and Kimi predate the reverse advertisement and keep their established
 * behavior when an older daemon omits it. ZCode is the first agent added after that boundary: sending
 * `agent:"zcode"` to an old daemon can be coerced to the OpenSession default (Claude), so it is
 * deny-by-omission until DaemonInfo explicitly advertises the wire name.
 */
internal fun agentAvailableFromDaemon(agent: AgentKind, supportedAgents: Collection<String>): Boolean =
    agent != AgentKind.ZCODE || AGENT_WIRE_ZCODE in supportedAgents

/** Pure projection shared by the mobile and desktop pickers. */
internal fun availableAgentsFromDaemon(
    supportedAgents: Collection<String>,
    candidates: List<AgentKind> = AgentKind.entries,
): List<AgentKind> = candidates.filter { agentAvailableFromDaemon(it, supportedAgents) }
