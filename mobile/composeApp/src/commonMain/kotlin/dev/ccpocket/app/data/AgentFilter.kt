package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind

/**
 * The project/session agent filter as a SET of backends (issue #248).
 *
 * It started as one of "both" | "claude" | "codex" | … — a single radio value — which made "Claude *and*
 * Codex, but not the rest" unexpressible and squeezed five options into one segmented row. The selection is
 * a set now; the FULL set means "no filter" and is the default, so an install that never touched the control
 * behaves exactly as before.
 *
 * Persistence stays a plain string in SecureStore and deliberately keeps the legacy vocabulary:
 *   - every agent selected  → "both"        (what an older build writes for "no filter", and still reads)
 *   - exactly one selected  → "claude"      (byte-identical to the value the radio used to store)
 *   - several selected      → "claude,codex"
 * so a downgrade keeps working: an old build reads a single value natively, and falls back to its "show
 * everything" branch on a multi-value one (widened list, never a silently emptied one).
 */
internal val ALL_AGENTS: Set<AgentKind> = AgentKind.entries.toSet()

/** The stored token for "no filter" — the pre-#248 radio's own value for it, kept for round-tripping. */
internal const val AGENT_FILTER_ALL = "both"

/** The wire-ish key one agent persists under (matches the old radio's values, hence the `@SerialName`s). */
internal fun agentFilterKey(agent: AgentKind): String = agent.name.lowercase()

/**
 * Read a persisted filter, migrating every shape the key has ever held.
 *
 * Unset / blank / "both" / an unrecognised token all mean the full set: the filter can only ever narrow
 * from an explicit choice, so a future agent key this build doesn't know cannot blank someone's list.
 */
internal fun parseAgentFilter(stored: String?): Set<AgentKind> {
    val raw = stored?.trim().orEmpty()
    if (raw.isEmpty() || raw.equals(AGENT_FILTER_ALL, ignoreCase = true)) return ALL_AGENTS
    val picked = raw.split(',')
        .mapNotNull { tok -> AgentKind.entries.firstOrNull { agentFilterKey(it) == tok.trim().lowercase() } }
        .toSet()
    return if (picked.isEmpty()) ALL_AGENTS else picked
}

/** The inverse of [parseAgentFilter]. Empty is not a reachable UI state; it stores as "no filter" too. */
internal fun encodeAgentFilter(selected: Set<AgentKind>): String =
    if (selected.isEmpty() || selected.containsAll(ALL_AGENTS)) AGENT_FILTER_ALL
    else AgentKind.entries.filter { it in selected }.joinToString(",") { agentFilterKey(it) }

/**
 * Whether this selection filters anything out at all. Everything downstream (the three filter call sites,
 * the removable chip, the Projects empty state) asks THIS rather than comparing to a literal, so "no filter"
 * has exactly one definition — including the empty set, which fails open rather than hiding every row.
 */
internal fun agentFilterIsAll(selected: Set<AgentKind>): Boolean =
    selected.isEmpty() || selected.containsAll(ALL_AGENTS)

/**
 * Toggling one option in the multi-select.
 *
 * Two rules keep the control honest: turning the last remaining agent off returns to "all" (an empty
 * selection would show nothing and offer no way back), and picking one while "all" is active narrows to
 * just that agent instead of quietly dropping it from a full set.
 */
internal fun toggleAgentFilter(selected: Set<AgentKind>, agent: AgentKind): Set<AgentKind> {
    if (agentFilterIsAll(selected)) return setOf(agent)
    val next = if (agent in selected) selected - agent else selected + agent
    return if (next.isEmpty()) ALL_AGENTS else next
}
