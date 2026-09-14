package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
//  Issue #367 G0 — wire types for the "remote task execution" link purpose. NOT FROZEN (see below).
//
//  NOT FROZEN — frozen only after the independent wire review signs off. Names and shapes may change
//  freely until then. Nothing in the App or the owner RequestRouter handles these: only the execution-link responder
//  (daemon `execution/`) admits them, and every other restricted whitelist (Bridge/Guest/Collaborator)
//  denies them by default. Carried only over a link minted for execution, never over a phone link.
// ---------------------------------------------------------------------------

/**
 * source daemon -> target daemon, over an EXECUTION link only: "what does my grant allow right now?"
 * The target answers from its own persisted grant bound to the transport-proven device key — never from
 * anything in this frame except [grantId], which must match that binding.
 */
@Serializable
@SerialName("pocket/execution.grant_query")
data class ExecutionGrantQuery(val grantId: String) : ToDaemon

/**
 * target daemon -> source daemon: the reply to [ExecutionGrantQuery]. Scope fields are disclosed only for
 * an ACTIVE grant; a grant still awaiting the target owner's fingerprint confirmation reports its state
 * and nothing else. Plain strings (not enums) on purpose: a newer target's value must not fail an older
 * source's decode, and the source only displays these — authority lives on the target.
 */
@Serializable
@SerialName("pocket/execution.grant_info")
data class ExecutionGrantInfo(
    val grantId: String,
    val revision: Long,
    val state: String,
    val expiresAt: Long,
    val workspaceAliases: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val approvalCeiling: String? = null,
    val maxConcurrentRuns: Int = 0,
    val maxQueuedRuns: Int = 0,
    val runTimeoutMs: Long = 0,
    val perGrantRequestBudget: Int = 0,
) : ToPhone

/**
 * The execution-grant invite DOOR. Its own host, which no released build recognises, so an older App's
 * collaborator scanner or an older daemon's `review join` falls through as "not a link I understand"
 * instead of burning the one-time ticket under a different purpose.
 */
const val EXECUTION_GRANT_INVITE_URI_PREFIX = "ccpocket://execution-grant#"

// ---------------------------------------------------------------------------
//  Issue #367 G1 — run plane. NOT FROZEN: frozen only after the independent wire review signs off.
//
//  THE THREE CONTRACTS EVERY IMPLEMENTATION ON EITHER SIDE MUST HOLD. They are stated here, once, because
//  each of them was ambiguous enough that two reasonable implementations would have disagreed:
//
//  1. ONE CONNECTION, ONE REQUEST, ONE REPLY. A source dials, sends exactly one request frame, and reads
//     exactly one reply frame. There is no multiplexing, no server-initiated push, and no second reply.
//     A `PocketError` IS that one reply — it is the refusal frame, never an out-of-band notice — so a
//     source that received one has received its answer and must not keep waiting.
//
//  2. IDEMPOTENCY IS (grantId, requestId) AND IS SCOPED TO A REVISION. Retrying a submission means
//     resending the SAME requestId with the SAME revision, and returns the ORIGINAL runId with
//     `duplicate = true`. Reusing a requestId under a DIFFERENT revision is a NEW request, not a retry:
//     the owner changed the scope in between, so the two submissions are not the same act. This is why
//     the target hashes the payload with the mode the SOURCE ASKED FOR, not the mode it clamped to — a
//     clamp result moves when the ceiling moves, which would turn an honest retry into `run_conflict`.
//
//  3. ERROR CODES ARE STABLE WIRE VALUES. `PocketError.code` on this plane is part of the contract, not a
//     log string: sources branch on it (`grant_expired`, `revision_changed`, `run_conflict`,
//     `run_queue_full`, `agent_not_allowed`, `workspace_not_allowed`, `frame_too_large`, …). Codes may be
//     ADDED; an existing code never changes meaning, and an unknown code must be treated as a plain
//     refusal rather than an error in the link.
//
//  Text is sliced by cursor so no frame exceeds the byte budget; nothing here is ever admitted on a
//  phone/owner/bridge/guest link.
// ---------------------------------------------------------------------------

@Serializable
@SerialName("pocket/execution.run_submit")
data class ExecutionRunSubmit(
    val requestId: String,
    val grantId: String,
    val revision: Long,
    val workspaceAlias: String,
    /**
     * REQUIRED, and a WIRE STRING rather than [AgentKind] on purpose.
     *
     * WHY IT IS REQUIRED: the backend a run uses is a SCOPE decision (the grant's `agents` list is what the
     * target owner approved), so it is never inferred. "The grant only allows one, so use that one" would
     * silently move an existing caller onto a different backend the day the owner widens the grant.
     *
     * WHY IT IS A STRING: an enum-typed field with no default makes a value this build does not know a
     * WHOLE-FRAME DECODE FAILURE — a newer source naming a backend this target has never heard of would
     * get silence (an undecodable frame), which is indistinguishable from a dead link. As a string the
     * frame always decodes and the target answers `agent_not_allowed`, which is both true and actionable:
     * the grant does not allow that backend, because this build cannot even name it. Values are the
     * [AgentKind] `@SerialName`s (`claude`, `codex`, …) — `ExecutionGrantInfo.agents` lists the accepted set.
     */
    val agent: String,
    val prompt: String,
    val model: String? = null,
    /** requested mode; the target clamps to the grant's approvalCeiling and never accepts BYPASS */
    val mode: PermissionMode? = null,
) : ToDaemon

@Serializable
@SerialName("pocket/execution.run_status")
data class ExecutionRunStatus(val requestId: String, val grantId: String, val runId: String) : ToDaemon

@Serializable
@SerialName("pocket/execution.run_result")
data class ExecutionRunResult(val requestId: String, val grantId: String, val runId: String, val cursor: String? = null) : ToDaemon

@Serializable
@SerialName("pocket/execution.run_cancel")
data class ExecutionRunCancel(val requestId: String, val grantId: String, val runId: String) : ToDaemon

/**
 * The target accepted (or recognised a retry of) a submission. [revision] is the grant revision the run was
 * accepted UNDER — echoed so the source can tell "my submit raced an owner scope change" apart from "the
 * owner changed scope later": a run is failed by the target the moment the revision moves, and a source
 * holding this value knows which one its run belongs to without re-querying the grant.
 */
@Serializable
@SerialName("pocket/execution.run_accepted")
data class ExecutionRunAccepted(
    val requestId: String,
    val runId: String,
    val state: String,
    val duplicate: Boolean = false,
    val revision: Long = 0,
) : ToPhone

@Serializable
@SerialName("pocket/execution.run_state")
data class ExecutionRunState(
    val requestId: String? = null,
    val runId: String,
    val state: String,
    val phase: String? = null,
    val approvalPending: Boolean = false,
    val updatedAt: Long = 0,
    val error: String? = null,
) : ToPhone

/**
 * One page of a run's collected output.
 *
 * CURSOR CONTRACT (frozen with the rest of this file, but stated so no implementation has to guess): a
 * cursor is the DECIMAL STRING of a UTF-8 BYTE OFFSET into the run's output, and nothing else — not a
 * character index, not an opaque token, not a line number. `null` means "from the beginning". The target
 * slices on a UTF-8 CHARACTER boundary at or before the requested byte budget, so [nextCursor] is not
 * necessarily `cursor + text.utf8Size`; a source MUST send back exactly the [nextCursor] it was given
 * rather than compute its own. [done] true with a null [nextCursor] is the only end-of-stream signal.
 */
@Serializable
@SerialName("pocket/execution.run_output")
data class ExecutionRunOutput(
    val requestId: String,
    val runId: String,
    val state: String,
    val cursor: String? = null,
    val nextCursor: String? = null,
    val text: String = "",
    val truncated: Boolean = false,
    val done: Boolean = false,
) : ToPhone

/**
 * Parse an [ExecutionRunSubmit.agent] wire value, or null when this build has no such backend. Null is a
 * refusal (`agent_not_allowed`), never a decode failure — see the field's KDoc for why that distinction is
 * the whole reason the field is a string. Shared so the target's gate, its run plane and the source's
 * pre-flight all read the same value the same way.
 */
fun executionAgentOrNull(wire: String): AgentKind? =
    AgentKind.entries.firstOrNull { AgentKind.serializer().descriptor.getElementName(it.ordinal) == wire.trim() }

/** The wire value for [kind], i.e. what an [ExecutionRunSubmit.agent] must carry to name it. */
fun executionAgentWire(kind: AgentKind): String = AgentKind.serializer().descriptor.getElementName(kind.ordinal)

const val EXECUTION_FRAME_BUDGET_BYTES: Int = 3 * 1024 * 1024
const val EXECUTION_OUTPUT_MAX_BYTES: Int = 256 * 1024
