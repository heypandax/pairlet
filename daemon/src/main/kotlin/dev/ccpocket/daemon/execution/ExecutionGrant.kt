package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.review.ReviewLimits
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Issue #367 G0: the lifecycle of one "remote task execution" authorization, as the TARGET daemon (the
 * machine that would run the work) records it. The target is the only authority; the source mirrors.
 *
 * ```
 * owner approves ─► PENDING_REDEEM ─(first frame decrypts under the invite-derived PSK)─► AWAITING_OWNER_CONFIRM
 *                        │                                                                     │
 *                        │ ticket lapses / restart loses the armed PSK                         │ owner compares the
 *                        ▼                                                                     │ link fingerprint
 *                  (never bindable)                                                            ▼
 *                                                           mismatch ─► REVOKED ◄─ revoke ─ ACTIVE
 * ```
 * EXPIRED is also derived at decision time from [ExecutionGrant.expiresAt] against the store's clock
 * high-water mark; nothing has to write it for an expired grant to stop working.
 *
 * An unrecognised state in the store file fails the WHOLE store closed (see [ExecutionGrantStore]).
 */
@Serializable
enum class ExecutionGrantState {
    @SerialName("pending_redeem") PENDING_REDEEM,
    @SerialName("awaiting_owner_confirm") AWAITING_OWNER_CONFIRM,
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED,
    ;

    val terminal: Boolean get() = this == REVOKED || this == EXPIRED
    val wire: String get() = ExecutionGrantState.serializer().descriptor.getElementName(ordinal)
}

/**
 * An owner-named workspace: the source only ever names [alias]; [canonicalRoot] never leaves the target.
 * [fileKey] is the directory's filesystem identity (device + inode on POSIX) at approval, so a different
 * directory later created at the same path does not inherit the grant. Null where the platform has none.
 */
@Serializable
data class WorkspaceAlias(val alias: String, val canonicalRoot: String, val fileKey: String? = null)

/**
 * One persisted execution grant (target side). Every scope field is decided by the TARGET OWNER; the
 * source can neither write nor widen any of it.
 */
@Serializable
data class ExecutionGrant(
    val grantId: String,
    /** Bumped on every owner change to scope. Starts at 1. Queued work must re-check it before running. */
    val revision: Long,
    val state: ExecutionGrantState,
    val createdAt: Long,
    val expiresAt: Long,
    val targetAccountId: String,
    val targetDaemonPub: String,
    /** [ExecutionFingerprint] of THIS daemon's identity E2E key — a machine identity, as the invite pins it. */
    val targetDaemonFingerprint: String,
    val sourceLabel: String,
    /** The relay-issued deviceId the source's link credential holds in the target account. Bound at first proof. */
    val sourceDeviceId: String? = null,
    /** The source link's static E2E key, PINNED at first proof. Every later handshake must meet exactly it. */
    val sourceLinkPub: String? = null,
    /**
     * [ExecutionFingerprint] of [sourceLinkPub]: the fingerprint of THIS LINK, NOT of the source machine.
     * The source generates a fresh key per join; nothing here binds it to the source daemon's long-term
     * identity (user decision 09-14: that signature is deferred to the real-device acceptance stage).
     */
    val sourceLinkFingerprint: String? = null,
    /** When the source was first seen reconnecting with the ordinary empty PSK — proof it moved past first
     *  contact. Null while it never has; drives the `link_stuck_first_contact_lost` diagnostic. */
    val sourceLinkConfirmedAt: Long? = null,
    /** hex(sha256(derived first-contact PSK)) while PENDING_REDEEM only. Neither the ticket nor the invite secret is persisted. */
    val ticketHash: String? = null,
    val ticketExpiresAt: Long? = null,
    val workspaces: List<WorkspaceAlias>,
    val allowedAgents: List<AgentKind>,
    val approvalCeiling: PermissionMode,
    val maxConcurrentRuns: Int,
    val maxQueuedRuns: Int,
    val runTimeoutMs: Long,
    val perGrantRequestBudget: Int,
    val endedReason: String? = null,
)

/**
 * What the target owner submits on approval (or on a scope revision). Raw roots are canonicalised here.
 * The limits are chosen per approval within [ExecutionPolicy]'s ranges (user decision 09-14: keep the
 * prototype ranges; the defaults below are only what a caller gets when it names none).
 */
data class ExecutionGrantDraft(
    val sourceLabel: String,
    val workspaces: Map<String, String>,
    val allowedAgents: List<AgentKind>,
    val approvalCeiling: PermissionMode,
    val ttlMs: Long,
    val maxConcurrentRuns: Int = 1,
    val maxQueuedRuns: Int = 4,
    val runTimeoutMs: Long = 30 * 60_000L,
    val perGrantRequestBudget: Int = 200,
)

/** Owner-input validation and persisted-row invariants. Both fail closed with a stable code. */
object ExecutionPolicy {
    const val MAX_TTL_MS = 30L * 24 * 3600_000
    const val MAX_WORKSPACES = 16
    const val MAX_CONCURRENT = 4
    const val MAX_QUEUED = 32
    const val MIN_TIMEOUT_MS = 60_000L
    const val MAX_TIMEOUT_MS = 6L * 3600_000
    const val MAX_BUDGET = 10_000

    /**
     * Backends whose approval chain the owner can actually police for a remote caller. Mirrors the
     * collaborator rule ([dev.ccpocket.daemon.handoff.CollaboratorGuard]): OpenCode runs --auto, and
     * KIMI/ZCODE/DSH tool names are not normalised into the permission wall, so a ceiling would be
     * advisory there. Denied at approval, not merely at run time.
     */
    val SUPPORTED_AGENTS = setOf(AgentKind.CLAUDE, AgentKind.CODEX)

    /** The ceilings a remote caller may ever run under. BYPASS_PERMISSIONS is never one of them. */
    val ALLOWED_CEILINGS = setOf(PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS)

    data class Validated(val workspaces: List<WorkspaceAlias>)

    /** Returns the canonicalised scope, or a refusal code. */
    fun validate(draft: ExecutionGrantDraft): Result<Validated> {
        fun refuse(code: String) = Result.failure<Validated>(ExecutionRefusal(code))
        if (draft.approvalCeiling == PermissionMode.BYPASS_PERMISSIONS) return refuse("ceiling_bypass_forbidden")
        if (draft.approvalCeiling !in ALLOWED_CEILINGS) return refuse("ceiling_not_allowed")
        if (draft.allowedAgents.isEmpty()) return refuse("agents_required")
        if (draft.allowedAgents.any { it !in SUPPORTED_AGENTS }) return refuse("agent_unsupported")
        if (ReviewLimits.singleLine(draft.sourceLabel, ReviewLimits.MAX_LABEL, "label") != null || draft.sourceLabel.isBlank()) {
            return refuse("label_invalid")
        }
        if (draft.ttlMs <= 0 || draft.ttlMs > MAX_TTL_MS) return refuse("ttl_out_of_range")
        if (draft.maxConcurrentRuns !in 1..MAX_CONCURRENT) return refuse("limits_out_of_range")
        if (draft.maxQueuedRuns !in 0..MAX_QUEUED) return refuse("limits_out_of_range")
        if (draft.runTimeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) return refuse("limits_out_of_range")
        if (draft.perGrantRequestBudget !in 1..MAX_BUDGET) return refuse("limits_out_of_range")
        if (draft.workspaces.isEmpty() || draft.workspaces.size > MAX_WORKSPACES) return refuse("workspaces_out_of_range")
        val out = ArrayList<WorkspaceAlias>()
        for ((alias, raw) in draft.workspaces) {
            if (!ExecutionWorkspaces.validAlias(alias)) return refuse("workspace_alias_invalid")
            val root = ExecutionWorkspaces.canonicalRoot(raw) ?: return refuse("workspace_root_invalid")
            out += WorkspaceAlias(alias, root, ExecutionWorkspaces.fileKeyOf(root))
        }
        return Result.success(Validated(out))
    }

    /**
     * A persisted row that breaks any of these is not a grant this build can police (review MEDIUM-4):
     * the same rules approval applies, plus the ones only a stored row can violate — a lifetime longer than
     * [MAX_TTL_MS], and a target key that is not THIS daemon's identity key.
     */
    fun violation(g: ExecutionGrant, expectedTargetPub: String): String? {
        if (g.approvalCeiling !in ALLOWED_CEILINGS) return "ceiling"
        if (g.revision < 1) return "revision"
        if (g.allowedAgents.isEmpty() || g.allowedAgents.any { it !in SUPPORTED_AGENTS }) return "agents"
        if (g.workspaces.isEmpty() || g.workspaces.size > MAX_WORKSPACES) return "workspaces"
        if (g.workspaces.any { !ExecutionWorkspaces.validAlias(it.alias) || !ExecutionWorkspaces.lexicallyCanonical(it.canonicalRoot) }) return "workspace_root"
        if (g.workspaces.any { (it.fileKey?.length ?: 0) > 512 }) return "workspace_root"
        if (g.workspaces.map { it.alias }.toSet().size != g.workspaces.size) return "workspace_duplicate"
        if (g.maxConcurrentRuns !in 1..MAX_CONCURRENT || g.maxQueuedRuns !in 0..MAX_QUEUED ||
            g.runTimeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS || g.perGrantRequestBudget !in 1..MAX_BUDGET
        ) return "limits"
        if (g.createdAt <= 0 || g.expiresAt <= g.createdAt || g.expiresAt - g.createdAt > MAX_TTL_MS) return "lifetime"
        if (g.targetDaemonPub != expectedTargetPub) return "target_key"
        if (g.targetDaemonFingerprint != ExecutionFingerprint.of(g.targetDaemonPub)) return "target_fingerprint"
        when (g.state) {
            ExecutionGrantState.PENDING_REDEEM ->
                if (g.ticketHash == null || g.ticketExpiresAt == null || g.sourceDeviceId != null || g.sourceLinkPub != null) return "pending_shape"
            ExecutionGrantState.AWAITING_OWNER_CONFIRM, ExecutionGrantState.ACTIVE -> {
                val pub = g.sourceLinkPub ?: return "bound_shape"
                if (g.sourceDeviceId == null || g.ticketHash != null) return "bound_shape"
                if (g.sourceLinkFingerprint != ExecutionFingerprint.of(pub)) return "source_fingerprint"
            }
            ExecutionGrantState.REVOKED, ExecutionGrantState.EXPIRED -> {}
        }
        return null
    }
}

/** A refusal carrying a stable, log-safe code (never a path, key or ticket). */
class ExecutionRefusal(val code: String) : RuntimeException(code)
