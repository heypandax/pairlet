package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind

/**
 * Identity of the focused conversation for per-pane chat bookkeeping (issue #380): THIS device, the paired
 * computer ([accountId]), the [agent] and the stable [sessionId] — or the [convoId] while a brand-new session
 * has no sessionId yet.
 *
 * History: this used to key a per-session "collapse tool process" switch stored on the device. That switch
 * was removed on 2026-09-15 (user decision): finished tool steps now always fold, and what remains is only
 * this identity, which the desktop models still expose per pane / split column.
 */
data class ToolProcessScope(
    val accountId: String?,
    val agent: AgentKind,
    val sessionId: String?,
    val convoId: String?,
)

/** The focused conversation's scope: this device × paired computer × agent × session (convoId until then). */
val PocketRepository.toolProcessScope: ToolProcessScope
    get() = ToolProcessScope(paired.value?.accountId, sessionAgent.value ?: AgentKind.CLAUDE, sessionKey.value, convoId.value)
