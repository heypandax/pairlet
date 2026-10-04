package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList

/**
 * Client-side gates that read what the daemon advertised for a backend instead of the backend's name.
 *
 * Each gate keeps the exact pre-advertisement behaviour for the case where the field is missing, so an
 * older daemon behaves as it did before the gate moved here; only an advertising daemon decides.
 */

/**
 * Whether a session's remembered extended-thinking choice (issue #345) rides the next open of [agent].
 *
 * The daemon's [ModelsList.supportsThinkingToggle] decides once the list for [agent] has arrived (a list
 * that omits the field — an older daemon — reads false: the field would be dropped and the badge would
 * lie). Before any list arrives the answer is UNKNOWN, not unsupported: only Claude ever carried the
 * toggle, so only Claude keeps its remembered choice across a cold reopen, exactly as before.
 */
internal fun thinkingChoiceCarries(agent: AgentKind, listed: ModelsList?): Boolean =
    listed?.supportsThinkingToggle ?: (agent == AgentKind.CLAUDE)
