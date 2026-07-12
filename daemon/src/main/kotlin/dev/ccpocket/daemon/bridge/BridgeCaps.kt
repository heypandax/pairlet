package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone

/**
 * The capability firewall for a HEADLESS bridge credential (issue #91). The single source of truth
 * for what a bridge may SEND to the daemon and RECEIVE from it. Both directions are WHITELISTS —
 * any frame type not explicitly listed is denied, so a message type added to the protocol later is
 * denied-by-default for bridges until someone consciously admits it here.
 *
 * These predicates are enforced at the daemon's two authenticated, deviceId-proven choke points
 * (the relay path's [dev.ccpocket.daemon.relay.DeviceSessions] transport). A bridge NEVER reaches the
 * direct-LAN path (its key lives in bridges.json, which the LAN gate does not read), so there is no
 * second enforcement site to keep in sync.
 *
 * The two guarantees that make an externally-triggered session safe:
 *  1. A bridge can never RECEIVE a [PermissionAsk] / [AskWithdrawn] — [egressAllowed] drops them, so a
 *     permission prompt is structurally invisible to the trigger source.
 *  2. A bridge can never SEND a `pocket/verdict` (or mode.switch / shell.run / file.read / …) —
 *     [ingressType] admits only four request types, so even a leaked askId can't be approved by the
 *     bridge. Combined with the daemon's timeout→deny, an unattended dangerous action is DENIED.
 */
object BridgeCaps {

    /** Whether a decoded outbound [frame] may be delivered to a bridge. The eight data-plane frames a
     *  bridge needs to observe a turn and get its result — and nothing that carries an approval. */
    fun egressAllowed(frame: Frame): Boolean = when (frame) {
        is SessionLive -> true
        is ConvoHistory -> true
        is AssistantChunk -> true
        is ToolEvent -> true
        is TurnDone -> true
        is PromptAck -> true
        is PocketError -> true
        is SessionGone -> true
        // Everything else is dropped. Named here so the intent is explicit for the two that MUST never
        // reach a bridge; the `else` covers the rest (CommandList, BackgroundJobs, Directories, Sessions,
        // ShellResult, Usage, AuthState, DaemonInfo, PathEntries, FileContent, FileDiff, Transcript, …).
        is PermissionAsk -> false
        is AskWithdrawn -> false
        is CommandList -> false
        else -> false
    }

    /** The reason a bridge is (or isn't) allowed to send a request type. Structural admission only —
     *  the per-frame CONSTRAINTS (workdir allow-list, mode clamp, resume ownership, force=false) are
     *  applied separately by [dev.ccpocket.daemon.bridge.BridgeGuard] because they need session state. */
    fun ingressAllowed(frame: Frame): Boolean = when (frame) {
        is OpenSession -> true
        is SendPrompt -> true
        is CancelTurn -> true
        is CloseSession -> true
        else -> false // verdict, mode.switch, rule.clear, shell.run, file.*, path.*, dirs.list, sessions.list,
        // usage.fetch, auth.*, push.prefs.set, audio.*, switchDir, job.stop, files.list — all refused for a bridge
    }

    /** The clamp a bridge's requested permission mode is capped to: PLAN and DEFAULT prompt for
     *  everything dangerous (which then routes to the phone). ACCEPT_EDITS is allowed (file edits without
     *  a prompt, but shell/network still prompt). BYPASS_PERMISSIONS is NEVER reachable — a bridge cannot
     *  put the daemon into "approve nothing", the whole point of routing approvals to the owner. */
    fun clampMode(requested: PermissionMode): PermissionMode = when (requested) {
        PermissionMode.BYPASS_PERMISSIONS -> PermissionMode.DEFAULT
        else -> requested
    }
}
