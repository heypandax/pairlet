package dev.ccpocket.app.ui.entry

import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.ui.codexPresetSpecs
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateTone
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentModePreset
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

// ── where a disconnected app root lands ─────────────────────────────────────────────────────────

/** The surface a session-less app root opens on. */
enum class EntryLanding {
    /** First run: the install guide, because "the daemon must already be running on a computer" is the
     *  precondition the pairing screen could only imply (issue #278 — the pairing wall was the largest
     *  drop-off in the activation funnel). */
    FIRST_RUN_CONNECT,

    /** The pairing screen itself. */
    PAIR,

    /** Bindings exist — the computer picker / reconnect surface. */
    COMPUTERS,
}

/**
 * Which surface a session-less root opens on.
 *
 * [addingDevice] is an EXISTING user deliberately adding a second computer: they have already installed the
 * daemon at least once, so walking them back through the install guide is a step backwards. It therefore
 * wins over [hasBindings] in both directions — including the (rare) case of an add-device request raised
 * while no binding is left, which must still land on pairing rather than on the guide.
 *
 * [hasCollaboratorLinks] is a PURE RECIPIENT (SESSION-HANDOFF.md §10: "接收方要求 cc-pocket App；不要求
 * daemon"). They own no binding, so they look exactly like a first run — but they are the one audience for
 * whom "install the daemon on your computer" is the wrong instruction entirely. The pairing screen states
 * their real situation; this guide would not.
 */
fun entryLanding(
    hasBindings: Boolean,
    addingDevice: Boolean,
    hasCollaboratorLinks: Boolean = false,
): EntryLanding = when {
    addingDevice -> EntryLanding.PAIR
    hasBindings -> EntryLanding.COMPUTERS
    hasCollaboratorLinks -> EntryLanding.PAIR
    else -> EntryLanding.FIRST_RUN_CONNECT
}

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
    AgentKind.ZCODE -> ModeChoiceSet.CLAUDE_LADDER
    // DSH (issue #255): shares Kimi's shape, and for the same reason — see [agentModeChoices].
    AgentKind.DSH -> ModeChoiceSet.KIMI_LADDER
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
 * Codex: [codexPresetSpecs] — the daemon-advertised vocabulary in [codexPresets] when it sent one, else the
 * built-in `CODEX_PRESETS` rows (same order, same modes, pinned by `EntryUiTest`). Derived rather than
 * re-listed here, because two hand-kept copies drifting apart would silently mislabel an approval × sandbox
 * pair — the whole reason the daemon now owns the vocabulary.
 * Kimi: the same ladder minus Accept edits, which has no Kimi equivalent.
 * ZCode: the four shared modes map directly to build / edit / plan / yolo.
 * DSH: Kimi's three rungs. dsh fixes its permission mode at PROCESS LAUNCH — it cannot be changed
 * mid-session — and v1 bridges no approvals, so the ladder must state what the process was started with
 * and nothing finer. Accept edits has no dsh equivalent, same as Kimi.
 * OpenCode: one row, and it is a STATEMENT rather than a choice — the daemon runs it `--auto`.
 */
fun agentModeChoices(
    agent: AgentKind,
    autoAvailable: Boolean = false,
    codexPresets: List<AgentModePreset> = emptyList(),
): List<ModeChoice> = when (agent) {
    AgentKind.CLAUDE -> buildList {
        add(ModeChoice(PermissionMode.DEFAULT))
        add(ModeChoice(PermissionMode.ACCEPT_EDITS))
        add(ModeChoice(PermissionMode.PLAN))
        add(ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true))
        if (autoAvailable) add(ModeChoice(PermissionMode.DEFAULT, nativeMode = CLAUDE_PERMISSION_MODE_AUTO))
    }
    AgentKind.KIMI, AgentKind.DSH -> listOf(
        ModeChoice(PermissionMode.DEFAULT),
        ModeChoice(PermissionMode.PLAN),
        ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true),
    )
    AgentKind.ZCODE -> listOf(
        ModeChoice(PermissionMode.DEFAULT),
        ModeChoice(PermissionMode.ACCEPT_EDITS),
        ModeChoice(PermissionMode.PLAN),
        ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true),
    )
    // one vocabulary, two renderers: Cautious, Balanced, Autonomous, Full access — or whatever the
    // connected daemon advertises instead
    AgentKind.CODEX -> codexPresetSpecs(codexPresets).map { ModeChoice(it.mode, danger = it.danger) }
    // BYPASS_PERMISSIONS is not a "choice" here — it is what the CLI already does, stated honestly
    AgentKind.OPENCODE -> listOf(ModeChoice(PermissionMode.BYPASS_PERMISSIONS))
}

/**
 * The mode [agent] falls back to when nothing legal survives the switch.
 *
 * #363 narrowed what "nothing legal" means: a saved default or a hand-picked rung whose exact value pair
 * this agent also offers is carried over (see [carryModeAcrossAgents]) — only a pair it has no row for
 * lands here, because showing one name while the daemon runs another is the failure this guards. OpenCode
 * falls back to the mode it genuinely runs in.
 */
fun agentDefaultMode(agent: AgentKind): PermissionMode = when (agent) {
    AgentKind.OPENCODE -> PermissionMode.BYPASS_PERMISSIONS
    else -> PermissionMode.DEFAULT
}

/**
 * True when [choice] must pass the existing Full-access confirmation before a session may start.
 *
 * The gate keys on the MODE, never on [ModeChoice.danger] — the same split the mode-switch sheet already
 * makes. `danger` is EMPHASIS and the daemon owns it; a daemon that advertises its bypass row without the
 * flag (or an older/hostile one) would otherwise silently remove the one confirmation standing between a tap
 * and unrestricted filesystem access. OpenCode stays out because there [PermissionMode.BYPASS_PERMISSIONS]
 * is a statement of what the CLI already does, not a choice being made.
 */
fun ModeChoice.needsFullAccessConfirm(agent: AgentKind): Boolean =
    mode == PermissionMode.BYPASS_PERMISSIONS && agent != AgentKind.OPENCODE

/**
 * The selection to open the configuration on for [agent].
 *
 * #363: the saved default is the USER'S answer, not the opening agent's. Every agent seeds from it as long
 * as the exact ([PermissionMode] + Claude's optional native mode) PAIR is a rung that agent really offers —
 * the same "carry the meaning, never the offset" rule the desktop popover already follows. Restricting the
 * inheritance to the agent the sheet happened to open on is what produced the 09-13 report: a user with
 * Full access saved, a sheet that opened on Codex (App.kt persists the last-picked agent), and a tap on
 * Claude that silently reset them to step-by-step approvals. A pair the target has no rung for still falls
 * back to [agentDefaultMode] — a Claude native Auto is not a Kimi mode, and inventing one would label the
 * session something the daemon never runs.
 *
 * [codexPresets] is the connected daemon's advertised vocabulary, and it decides the seed too: a persisted
 * mode the daemon no longer offers must not be resurrected here, and when a newer daemon drops the rung
 * [agentDefaultMode] names, the fallback lands on the first row it DOES advertise rather than on nothing.
 */
fun seedModeChoice(
    agent: AgentKind,
    persisted: PermissionMode,
    persistedNative: String?,
    autoAvailable: Boolean = false,
    codexPresets: List<AgentModePreset> = emptyList(),
): ModeChoice {
    val choices = agentModeChoices(agent, autoAvailable, codexPresets)
    choices.firstOrNull { it.mode == persisted && it.nativeMode == persistedNative }?.let { return it }
    val fallback = agentDefaultMode(agent)
    return choices.firstOrNull { it.mode == fallback && it.nativeMode == null } ?: choices.first()
}

/**
 * The rung to show for [agent] after an agent switch or a capability refresh (issue #363).
 *
 * [picked] is the rung the user chose BY HAND in this sheet, or null while the selection is still the seed.
 * Three cases, and the difference between them is the whole contract:
 *
 *  - **OpenCode**: not a ladder — the daemon runs it `--auto` and the sheet states that. There is nothing to
 *    carry INTO it, and carrying a Plan rung there would send a mode the backend cannot honour.
 *  - **untouched**: [seedModeChoice], i.e. the saved default wherever it is legal.
 *  - **hand-picked**: the target's own row for that exact value pair when it has one (its `danger` flag is
 *    the backend's to set), otherwise the pick is KEPT so the validity gate can ask for a new one. Falling
 *    back to the saved default here is exactly what must not happen — that default may be wider than the
 *    rung the user just chose, and nobody chose it for this agent.
 */
fun carryModeAcrossAgents(
    picked: ModeChoice?,
    agent: AgentKind,
    persisted: PermissionMode,
    persistedNative: String?,
    autoAvailable: Boolean = false,
    codexPresets: List<AgentModePreset> = emptyList(),
): ModeChoice {
    val choices = agentModeChoices(agent, autoAvailable, codexPresets)
    if (modeChoiceSet(agent) == ModeChoiceSet.OPENCODE_AUTOMATIC) return choices.first()
    if (picked == null) return seedModeChoice(agent, persisted, persistedNative, autoAvailable, codexPresets)
    return choices.firstOrNull { it.mode == picked.mode && it.nativeMode == picked.nativeMode } ?: picked
}
