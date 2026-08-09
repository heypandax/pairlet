package dev.ccpocket.app.ui.entry

import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateTone
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionMode

/**
 * The entry flow's state vocabulary — pure, Compose-free, unit-testable (Entry Flow UI 2.0 · Direction v1).
 *
 * The entry path answers two questions the renderers kept re-deriving inline: "what does this [ConnPhase]
 * actually let me do" and "which modes does this agent actually have". Both are product truth, both were
 * spread across `when` blocks in three files, and both are exactly the kind of thing a visual refactor
 * silently gets wrong. They live here once; the surfaces read the answer and draw it.
 *
 * Nothing here infers. A phase means what the repository says it means, and an agent offers what the daemon
 * really implements — never a plausible-looking superset.
 */

// ── connection recovery ─────────────────────────────────────────────────────────────────────────

/** A recovery action a failing connection may offer. Each maps 1:1 onto an existing repository effect. */
enum class ConnActionId {
    /** `retryConnection()` — only where retrying can actually help. */
    RETRY,

    /** `disconnect()` — leave this binding and land on the computer picker. */
    EXIT,

    /** `beginAddDevice()` — re-pair while KEEPING the dead binding, with a real Cancel back path. */
    PAIR_AGAIN,

    /** `unpairActive()` — drop the dead binding for good. */
    REMOVE,
}

/**
 * What one [ConnPhase] means for the entry surfaces.
 *
 * The six values stay six: merging "the relay didn't answer", "your computer isn't running it" and "your
 * credential was rejected" into one generic error is precisely the failure this flow exists to avoid, and a
 * Retry on [ConnPhase.PairingInvalid] is a button that cannot work.
 */
data class ConnRecoveryUi(
    val phase: ConnPhase,
    val mark: StateMark,
    val tone: StateTone,
    val actions: List<ConnActionId>,
    /** The previously loaded content stays on screen underneath (a slim banner, not a takeover). */
    val retainsContent: Boolean,
    /** Render the Projects-shaped skeleton, so skeleton → list swaps in place with no header jump. */
    val showsSkeleton: Boolean,
    /** Keep the existing "we'll reconnect as soon as it's back" daemon hint. */
    val hasDaemonHint: Boolean,
) {
    /** True when this phase owns the screen with a written state and its own recovery actions. */
    val blocks: Boolean get() = actions.isNotEmpty()
}

/**
 * The authoritative phase → recovery mapping.
 *
 * [ConnPhase.Connecting] is a FIRST attempt: it has no recovery action yet because there is nothing to
 * recover from — offering Retry there would invite a user to restart a handshake that is still in progress.
 */
fun connRecovery(phase: ConnPhase): ConnRecoveryUi = when (phase) {
    ConnPhase.Connecting -> ConnRecoveryUi(
        phase, StateMark.RING, StateTone.NEUTRAL,
        actions = emptyList(), retainsContent = false, showsSkeleton = true, hasDaemonHint = false,
    )
    ConnPhase.Reconnecting -> ConnRecoveryUi(
        phase, StateMark.DIAMOND, StateTone.ATTENTION,
        actions = emptyList(), retainsContent = true, showsSkeleton = false, hasDaemonHint = false,
    )
    ConnPhase.RelayUnreachable -> ConnRecoveryUi(
        phase, StateMark.SQUARE, StateTone.DANGER,
        actions = listOf(ConnActionId.RETRY, ConnActionId.EXIT),
        retainsContent = false, showsSkeleton = false, hasDaemonHint = false,
    )
    ConnPhase.ComputerOffline -> ConnRecoveryUi(
        phase, StateMark.SQUARE, StateTone.DANGER,
        actions = listOf(ConnActionId.RETRY, ConnActionId.EXIT),
        retainsContent = false, showsSkeleton = false, hasDaemonHint = true,
    )
    // the credential itself was rejected: retrying replays the same rejected credential, so it is absent
    ConnPhase.PairingInvalid -> ConnRecoveryUi(
        phase, StateMark.SQUARE, StateTone.DANGER,
        actions = listOf(ConnActionId.PAIR_AGAIN, ConnActionId.REMOVE),
        retainsContent = false, showsSkeleton = false, hasDaemonHint = false,
    )
    ConnPhase.Ready -> ConnRecoveryUi(
        phase, StateMark.DOT, StateTone.RUNNING,
        actions = emptyList(), retainsContent = true, showsSkeleton = false, hasDaemonHint = false,
    )
}

// ── new-session configuration ───────────────────────────────────────────────────────────────────

/** Which shape of mode choice an agent has. OpenCode is not a ladder with rows disabled — it has none. */
enum class ModeChoiceSet { CLAUDE_LADDER, CODEX_PRESETS, KIMI_LADDER, OPENCODE_AUTOMATIC }

fun modeChoiceSet(agent: AgentKind): ModeChoiceSet = when (agent) {
    AgentKind.CLAUDE -> ModeChoiceSet.CLAUDE_LADDER
    AgentKind.CODEX -> ModeChoiceSet.CODEX_PRESETS
    AgentKind.KIMI -> ModeChoiceSet.KIMI_LADDER
    AgentKind.OPENCODE -> ModeChoiceSet.OPENCODE_AUTOMATIC
}

/**
 * One selectable execution mode: exactly the pair the daemon receives ([mode] + Claude's optional
 * [nativeMode]), plus whether choosing it must pass the Full-access confirmation before anything starts.
 */
data class ModeChoice(
    val mode: PermissionMode,
    val nativeMode: String? = null,
    val danger: Boolean = false,
)

/**
 * The modes [agent] really offers, in ladder order.
 *
 * Claude: the four-rung ladder, plus its native Auto only when the connected CLI advertises it.
 * Codex: the four `CODEX_PRESETS` rows — same order, same modes (pinned by `EntryUiTest`, because the two
 * lists drifting apart would silently mislabel an approval × sandbox pair).
 * Kimi: the same ladder minus Accept edits, which has no Kimi equivalent.
 * OpenCode: one row, and it is a STATEMENT rather than a choice — the daemon runs it `--auto`.
 */
fun agentModeChoices(agent: AgentKind, autoAvailable: Boolean = false): List<ModeChoice> = when (agent) {
    AgentKind.CLAUDE -> buildList {
        add(ModeChoice(PermissionMode.DEFAULT))
        add(ModeChoice(PermissionMode.ACCEPT_EDITS))
        add(ModeChoice(PermissionMode.PLAN))
        add(ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true))
        if (autoAvailable) add(ModeChoice(PermissionMode.DEFAULT, nativeMode = CLAUDE_PERMISSION_MODE_AUTO))
    }
    AgentKind.KIMI -> listOf(
        ModeChoice(PermissionMode.DEFAULT),
        ModeChoice(PermissionMode.PLAN),
        ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true),
    )
    // mirrors CODEX_PRESETS: Cautious, Balanced, Autonomous, Full access
    AgentKind.CODEX -> listOf(
        ModeChoice(PermissionMode.PLAN),
        ModeChoice(PermissionMode.DEFAULT),
        ModeChoice(PermissionMode.ACCEPT_EDITS),
        ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true),
    )
    // BYPASS_PERMISSIONS is not a "choice" here — it is what the CLI already does, stated honestly
    AgentKind.OPENCODE -> listOf(ModeChoice(PermissionMode.BYPASS_PERMISSIONS))
}

/**
 * The mode [agent] starts on the moment it is chosen.
 *
 * Switching agent RESETS to this: a Codex sandbox preset is not a Claude permission mode, and carrying a
 * selection across backends would show one name while the daemon runs another. OpenCode resets to the mode
 * it genuinely runs in.
 */
fun agentDefaultMode(agent: AgentKind): PermissionMode = when (agent) {
    AgentKind.OPENCODE -> PermissionMode.BYPASS_PERMISSIONS
    else -> PermissionMode.DEFAULT
}

/** True when [choice] must pass the existing Full-access confirmation before a session may start. */
fun ModeChoice.needsFullAccessConfirm(agent: AgentKind): Boolean =
    danger && mode == PermissionMode.BYPASS_PERMISSIONS && agent != AgentKind.OPENCODE

/**
 * The selection to open the configuration on for [agent].
 *
 * Only the agent the sheet OPENED on may inherit the caller's persisted default — every other agent is a
 * switch, and a switch resets (see [agentDefaultMode]).
 */
fun seedModeChoice(
    agent: AgentKind,
    openedAgent: AgentKind,
    persisted: PermissionMode,
    persistedNative: String?,
    autoAvailable: Boolean = false,
): ModeChoice {
    val choices = agentModeChoices(agent, autoAvailable)
    if (agent == openedAgent) {
        choices.firstOrNull { it.mode == persisted && it.nativeMode == persistedNative }?.let { return it }
    }
    val fallback = agentDefaultMode(agent)
    return choices.firstOrNull { it.mode == fallback && it.nativeMode == null } ?: choices.first()
}
