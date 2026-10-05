package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.diagnostics.FileReadDiagnostics
import dev.ccpocket.daemon.diagnostics.SessionOpenDiagnostics
import dev.ccpocket.daemon.agent.ApprovalTimeout
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.claude.ClaudeModelService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.sinkKey
import dev.ccpocket.daemon.codex.CodexModelService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SessionArchive
import dev.ccpocket.daemon.disk.SessionFilesService
import dev.ccpocket.daemon.disk.SessionGroups
import dev.ccpocket.daemon.disk.SkillCatalogService
import dev.ccpocket.daemon.disk.UsageService
import dev.ccpocket.daemon.opencode.OpenCodeModelService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ActivatePreset
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.ApprovalAttentionHeartbeat
import dev.ccpocket.protocol.ApprovalHistoryPage
import dev.ccpocket.protocol.ApprovalGrantMutationResult
import dev.ccpocket.protocol.FetchApprovalHistory
import dev.ccpocket.protocol.RevokeGrant
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentRepairStart
import dev.ccpocket.protocol.AgentRepairProgress
import dev.ccpocket.daemon.dsh.DshRepairService
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_KIMI
import dev.ccpocket.protocol.AGENT_WIRE_OPENCODE
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaGet
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.AuthLogin
import dev.ccpocket.protocol.AuthLoginCancel
import dev.ccpocket.protocol.AuthLoginCode
import dev.ccpocket.protocol.AuthLogout
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.GetWorkflowAgentDetail
import dev.ccpocket.protocol.DeletePreset
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.FetchPresets
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.SavePreset
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.FetchAuthStatus
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.FetchSkillCatalog
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.FileUploadCancel
import dev.ccpocket.protocol.Frame
// Git panel (#280) + worktrees (#281) — every one of these is OWNER-ONLY at dispatch, see the block
// in handle() and RequestRouterGitTest for the three credential classes that are refused.
import dev.ccpocket.protocol.AddWorktree
import dev.ccpocket.protocol.GIT_OP_WORKTREE_ADD
import dev.ccpocket.protocol.GIT_OP_WORKTREE_REMOVE
import dev.ccpocket.protocol.FetchGitStatus
import dev.ccpocket.protocol.GitAction
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.GitStatus
import dev.ccpocket.protocol.ListWorktrees
import dev.ccpocket.protocol.ReadGitDiff
import dev.ccpocket.protocol.RemoveWorktree
import dev.ccpocket.protocol.WorktreeList
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.protocol.GroupCreate
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPendingApprovals
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.ListArchivedSessions
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.RenameSession
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ApprovalPrefs
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ScheduleCancel
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleList
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.SetApprovalPrefs
import dev.ccpocket.protocol.SetPushPrefs
import dev.ccpocket.protocol.SetSessionArchived
import dev.ccpocket.protocol.ShellResult
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.SwitchServiceTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Maps an inbound [Frame] to the registry/services. Returns fast; turns run on conversation scopes. */
/** Diagnostic tap for the subscription-quota reply path (phone-not-showing investigation, 2026-08-24). */
private val quotaLog = dev.ccpocket.daemon.util.logger("QuotaRoute")

/** Failures of a session-list reply produced on a listing lane — off the caller's loop, so nothing else would log them. */
private val listingLog = dev.ccpocket.daemon.util.logger("ListingLane")

class RequestRouter(
    private val registry: SessionRegistry,
    private val dirs: DirectoryService,
    private val transcribe: TranscribeService,
    private val inbox: FileInboxService,
    private val shell: ShellService,
    private val exports: FileExportService,
    private val scope: CoroutineScope,
    private val auth: AuthService,
    private val prefs: DaemonPrefs,
    private val presets: PresetService,
    private val scheduler: SchedulerService,
    private val openCodeModels: OpenCodeModelService = OpenCodeModelService(),
    private val kimiModels: dev.ccpocket.daemon.kimi.KimiModelService = dev.ccpocket.daemon.kimi.KimiModelService(),
    private val zcodeModels: dev.ccpocket.daemon.zcode.ZCodeModelService = dev.ccpocket.daemon.zcode.ZCodeModelService(),
    private val codexModels: CodexModelService = CodexModelService(),
    private val claudeModels: ClaudeModelService = ClaudeModelService(),
    private val dshModels: dev.ccpocket.daemon.dsh.DshModelService = dev.ccpocket.daemon.dsh.DshModelService(),
    // the daemon-wide pending-approval ledger (approval design M1): the single verdict routing point;
    // defaulted so router tests that never touch approvals need no wiring
    private val approvals: dev.ccpocket.daemon.approval.ApprovalCoordinator =
        dev.ccpocket.daemon.approval.ApprovalCoordinator(scope),
    private val grants: dev.ccpocket.daemon.approval.ApprovalGrantStore =
        dev.ccpocket.daemon.approval.ApprovalGrantStore(),
    private val approvalHistory: dev.ccpocket.daemon.approval.ApprovalHistoryStore? = null,
    // the Git panel's engine (#280) and, on the same argv allow-list, worktree management (#281).
    // Defaulted like the model services so router tests that never touch git need no wiring; DaemonCore
    // passes one wired to the registry's live-session truth so a worktree with a running agent is
    // refused removal.
    private val git: dev.ccpocket.daemon.git.GitService = dev.ccpocket.daemon.git.GitService(),
    // the Claude subscription-allowance reader (the numbers behind the CLI's `/usage` panel). Defaulted
    // like [git] so a router test that never asks for quota needs no wiring; it holds only a short result
    // cache, so one instance per router is fine and a second one would just fetch twice.
    private val quota: dev.ccpocket.daemon.claude.ClaudeQuotaService = dev.ccpocket.daemon.claude.ClaudeQuotaService(),
    // the CODEX subscription-allowance reader (issue #348), the twin of [quota]. Separate instance rather
    // than a branch inside one service: the two have different transports, different failure vocabularies
    // and independent caches, and a shared cache slot would let one backend's 60s TTL hide the other's
    // fresh numbers. Defaulted like [quota] so a router test that never asks for Codex quota needs no wiring.
    private val codexQuota: dev.ccpocket.daemon.codex.CodexQuotaService = dev.ccpocket.daemon.codex.CodexQuotaService(),
    // the session-archive store's backing file (issue #202). Injectable like prefs/presets/schedules so a
    // test never reads or rewrites the developer's real ~/.cc-pocket/session-archive.json.
    private val archiveFile: java.io.File = SessionArchive.defaultFile(),
    /** Project-pin sync (issue #362): this computer's authoritative pin list. Null = not wired (a bare router
     *  in a unit test): a pin request then answers `pins_unavailable` instead of silently doing nothing. */
    private val projectPins: dev.ccpocket.daemon.pins.ProjectPinService? = null,
    /** Managed session list (issue #360). Null = not wired: a capable owner's managed request answers
     *  `managed_unsupported`, and [managedSessionAgentWires] advertises nothing. */
    private val managedSessions: dev.ccpocket.daemon.session.ManagedSessionService? = null,
    /** Voice memo → tasks. Null = not wired: nothing is advertised and every memo frame is dropped. */
    private val voiceMemo: dev.ccpocket.daemon.memo.VoiceMemoService? = null,
) {
    /** Both transports attach their owner push targets through the router they already hold (issue #360). */
    internal val managedSessionService: dev.ccpocket.daemon.session.ManagedSessionService? get() = managedSessions

    /**
     * #367 G1: the EXECUTION run plane. Deliberately a settable property rather than a constructor
     * parameter — the plane needs the grant store and the credential registry, both of which only exist
     * once the relay link is up, exactly like [dev.ccpocket.daemon.DaemonCore.executionControl].
     *
     * The transport ([dev.ccpocket.daemon.relay.DeviceSessions]) depends on the INTERFACE only and never on
     * the run service: it hands over a frame already admitted by
     * [dev.ccpocket.daemon.execution.ExecutionCaps.ingressAllowed] and already vetted by [executionGuard],
     * on a deviceId proven by the Noise static key. NULL means "not wired": every execution frame is then
     * refused with `execution_unavailable` — the router itself never routes one, and no owner handler is
     * reachable from an execution credential under any circumstances.
     */
    @Volatile
    var executionPlane: dev.ccpocket.daemon.execution.ExecutionRunPlane? = null

    /**
     * #367 G1: the per-frame authorisation gate the transport runs BEFORE [executionPlane] (grant live,
     * revision, frame type, byte budget). Same lifetime and same null semantics as the plane, and null is
     * likewise a REFUSAL — an execution frame is never admitted by a daemon with no gate to admit it.
     */
    @Volatile
    var executionGuard: dev.ccpocket.daemon.execution.ExecutionGuard? = null

    /** [dev.ccpocket.protocol.DaemonInfo.managedAgents]: empty when the managed list is not wired. */
    fun managedSessionAgentWires(): List<String> = managedSessions?.agentWires().orEmpty()

    /** What [dev.ccpocket.protocol.DaemonInfo] says about voice memos: the LOCAL prerequisites only — binaries
     *  and model files. Not wired, or the check itself failing, advertises nothing. */
    fun voiceMemoCapability(): dev.ccpocket.daemon.memo.MemoCapability =
        runCatching { voiceMemo?.capability() }.getOrNull()
            ?: dev.ccpocket.daemon.memo.MemoCapability(0, emptyList(), dev.ccpocket.protocol.VoiceMemoStatus.UNKNOWN)

    /** A pairing was revoked: its memo jobs stop and its cached transcripts go. */
    suspend fun revokeVoiceMemoDevice(deviceId: String) { voiceMemo?.revokeDevice(deviceId) }

    /** The LAN transport attaches its per-socket pin subscriber through the router it already holds, so the pin
     *  plane reaches both transports without another server-construction seam. */
    internal val projectPinService: dev.ccpocket.daemon.pins.ProjectPinService? get() = projectPins

    /**
     * The backends whose SUBSCRIPTION allowance this daemon can actually read, as
     * [dev.ccpocket.protocol.AgentKind] wire names — the payload of
     * [dev.ccpocket.protocol.DaemonInfo.quotaAgents] (issue #348).
     *
     * Claude is unconditional: its reader needs no local binary (it talks to Anthropic over HTTPS and
     * reports NO_TOKEN when the machine is signed out), so listing it is honest even on a Claude-less
     * machine — and every pre-#348 client assumes exactly that anyway. Codex is listed only when its CLI
     * is resolvable, because the whole read IS that CLI: advertising it on a machine without codex would
     * invite a request whose only possible answer is "no".
     */
    fun quotaAgentWires(): List<String> = buildList {
        add("claude")
        if (codexQuota.available()) add("codex")
    }

    /** One connection's declared wire vocabulary (see [ClientCaps] in Messages.kt). Mutable: the
     *  declaration frame lands after connect and upgrades the SAME holder the ingress created for
     *  the connection. Default (no declaration, or a legacy ingress passing null) = filter — an
     *  already-shipped client hard-fails the whole Envelope on an unknown [AgentKind], so opencode
     *  rows must never reach a peer that didn't declare them. */
    class ClientCapsHolder {
        @Volatile var supportsOpencode: Boolean = false

        /** issue #206: the client decodes AgentKind.KIMI — same gate as [supportsOpencode]. KIMI was added
         *  after the baseline vocabulary, so its rows must not reach a peer that never declared it. */
        @Volatile var supportsKimi: Boolean = false

        /** issue #228: the client decodes AgentKind.ZCODE. */
        @Volatile var supportsZcode: Boolean = false

        /** issue #255: the client decodes AgentKind.DSH. */
        @Volatile var supportsDsh: Boolean = false

        /** §18.2 P2-3: the client decodes the approval-V2 frame types. The INGRESS sinks consult this to
         *  drop [AuthorizedActionRecorded]/[PermissionRiskUpdated] for undeclared peers — old clients
         *  would drop the unknown types anyway, but gating keeps the wire quiet and the contract real. */
        @Volatile var supportsApprovalV2: Boolean = false
        @Volatile var supportsDiagnostics: Boolean = false

        /** issue #362: this connection decodes pocket/pins.state. Every such frame — reply or push — is gated
         *  on it, so a legacy sibling on the same daemon never sees one. */
        @Volatile var supportsProjectPins: Boolean = false

        /** issue #362: the push generation this connection's owner client registered with its latest ACCEPTED
         *  fetch — written only by the transport's [dev.ccpocket.daemon.pins.ProjectPinConnection.acceptFetch],
         *  never by a batch or a refusal. It lives on the CONNECTION's holder: a re-handshake starts from a fresh
         *  holder with no subscription. */
        @Volatile var pinSubscriptionId: String? = null

        /** issue #362: terminal for this holder's lifetime — set once a newer fetch of the same device was accepted
         *  on another connection, or this connection closed or was revoked. A retired holder never registers a
         *  subscription or receives a pin frame again, even when a late frame makes its session active again. */
        @Volatile var pinRetired: Boolean = false

        /** issue #360: this connection decodes pocket/managed.state and pocket/managed.discovered. Both are gated on
         *  it at EMISSION, replies and pushes alike; until the declaration arrives this connection receives neither. */
        @Volatile var supportsManagedSessions: Boolean = false

        /** issue #380 live folding: the client wants an outcome-only RESULT for every finished ordinary tool. */
        @Volatile var supportsToolOutcomes: Boolean = false

        /** voice memo → tasks: this connection decodes pocket/memo.state. Replies and pushes are both gated on it. */
        @Volatile var supportsVoiceMemo: Boolean = false

        /** Largest sealed WebSocket message this connection can receive: the client's declared
         *  [ClientCaps.maxFrameBytes] (clamped by [frameCap]), else the legacy 1 MiB that shipped iOS builds are
         *  really bound to (KTOR-6963). Both ingress writers consult it right before sealing — see [FrameFitter]. */
        @Volatile var maxFrameBytes: Long = dev.ccpocket.protocol.LEGACY_CLIENT_MAX_FRAME_BYTES

        companion object {
            /** No client can usefully take less than the relay's old 256 KB ceiling, under which everything shipped. */
            const val MIN_FRAME_BYTES: Long = 256L * 1024

            /** A declared cap made safe: 0/absent (an old build) keeps the legacy assumption; a real value is
             *  clamped between [MIN_FRAME_BYTES] and the wire ceiling — the relay drops anything larger anyway. */
            fun frameCap(declared: Long): Long =
                if (declared <= 0L) dev.ccpocket.protocol.LEGACY_CLIENT_MAX_FRAME_BYTES
                else declared.coerceIn(MIN_FRAME_BYTES, dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES)
        }

        /** Whether this peer can decode [agent]. CLAUDE/CODEX are the baseline vocabulary every shipped
         *  client understands; OPENCODE/KIMI are post-baseline additions each guarded by its own cap. */
        fun allows(agent: AgentKind): Boolean = when (agent) {
            AgentKind.OPENCODE -> supportsOpencode
            AgentKind.KIMI -> supportsKimi
            AgentKind.ZCODE -> supportsZcode
            AgentKind.DSH -> supportsDsh
            AgentKind.CLAUDE, AgentKind.CODEX -> true
        }
    }

    /**
     * The Git surface's single admission point: owner credential AND a workdir that survives the same
     * [DirectoryService.validateWorkdir] the files surface uses. Returns the canonical directory, or null
     * for "refused" — the caller then answers with the frame shape its request expects, so the phone gets
     * a readable state instead of silence.
     */
    private fun gitWorkdir(workdir: String, origin: String?, guestScope: GuestScope?): java.nio.file.Path? {
        if (!gitOwnerOnly(origin, guestScope)) return null
        return dirs.validateWorkdir(workdir)
    }

    /** Why a git request was refused. A non-owner learns only that the surface is owner-only — never
     *  whether the path they named exists, which would make this a directory oracle. */
    private fun gitDenial(origin: String?, guestScope: GuestScope?, workdir: String): String =
        if (!gitOwnerOnly(origin, guestScope)) GIT_OWNER_ONLY else "not a readable directory: $workdir"

    /**
     * Run a launched reply [block]; when it throws, answer [fallback] first so the client is not left waiting
     * for its own timeout, then rethrow so the scope's handler still reports it. Cancellation is not a
     * failure and passes straight through. A fallback that cannot be sent either is dropped silently — the
     * original failure is the one worth reporting.
     */
    private suspend inline fun replyOnFailure(sink: OutboundSink, fallback: () -> Frame, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { sink.emit(fallback()) }
            throw e
        }
    }

    companion object {
        /** The pseudo-device name of a trusted in-process caller, which has no transport-authenticated id. Never
         *  an identity a device-owned record may be filed under (see [voiceMemoRequest]). */
        const val LOCAL_DEVICE_ID = "local"

        /** The error a file-surface reply carries when its service failed unexpectedly (audit 2026-10-04 C). */
        const val FILE_SURFACE_FAILED = "the computer could not read this session's files — try again"

        /** Soft cap on the cross-project archive view (issue #202) — the archive is "put it away", not an
         *  unbounded ledger, and one frame must stay bounded however long a machine has accumulated. */
        const val MAX_ARCHIVE_ROWS = 500

        /** Per-row clip for the archive view's [SessionSummary.firstPrompt] — the view never renders it,
         *  and leaving it unbounded is how one machine-wide frame overruns the relay's 4MB limit. */
        const val ARCHIVE_PROMPT_CLIP = 200

        /** Per-row clip for a project listing's [SessionSummary.firstPrompt]. The apps render it as a
         *  two-line preview under the title, so a few hundred characters is all that is ever seen — while
         *  the untruncated text (a pasted log, a skill injection) put a busy project's `Sessions` frame
         *  over the client's frame cap, which drops the link instead of opening the list. */
        const val SESSION_PROMPT_CLIP = 400

        /** Session-list replies one lane may have waiting. A client asks for a handful at most (a sidebar
         *  refreshing its projects); past this the caller waits its turn instead of queueing without bound. */
        const val LISTING_LANE_BACKLOG = 32

        /** §18.2 P2-3: frames only an approvalV2-declaring client should receive — ingress sinks drop
         *  them for undeclared peers instead of relying on the client's unknown-type tolerance. */
        fun approvalV2Only(frame: Frame): Boolean =
            frame is dev.ccpocket.protocol.AuthorizedActionRecorded || frame is dev.ccpocket.protocol.PermissionRiskUpdated

        /** Per-connection/device gate: one modern client must never opt a sibling legacy client in. */
        fun allowedForCaps(frame: Frame, caps: ClientCapsHolder?): Boolean = when {
            frame is dev.ccpocket.protocol.HistoryComplete || frame is dev.ccpocket.protocol.PromptProgress || frame is dev.ccpocket.protocol.ApprovalProgress -> caps?.supportsDiagnostics == true
            approvalV2Only(frame) -> caps?.supportsApprovalV2 == true
            // issue #362: replies AND pushes — a pin frame only reaches a connection that declared it
            frame is dev.ccpocket.protocol.ProjectPinsState -> caps?.supportsProjectPins == true
            // issue #360: replies AND pushes — a managed frame only reaches a connection that declared it; a null /
            // not-yet-declared holder fails closed
            frame is dev.ccpocket.protocol.ManagedSessionsState || frame is dev.ccpocket.protocol.DiscoveredSessions ->
                caps?.supportsManagedSessions == true
            // issue #367: an execution peer is a DAEMON, not a capability-declaring App — it never sends
            // ClientCaps, so a null holder must not silently drop its replies. These frames are gated by
            // ExecutionCaps.egressAllowed instead, at the one place they are sealed. Stated explicitly
            // rather than left to the `else` so a future caps rule cannot swallow them by accident.
            frame is dev.ccpocket.protocol.ExecutionGrantInfo ||
                frame is dev.ccpocket.protocol.ExecutionRunAccepted ||
                frame is dev.ccpocket.protocol.ExecutionRunState ||
                frame is dev.ccpocket.protocol.ExecutionRunOutput -> true
            // issue #380 live folding: a bare outcome RESULT only reaches a connection that asked for it; sub-agent
            // and image RESULTs (outcomeOnly = false) keep flowing to every client as before
            frame is dev.ccpocket.protocol.ToolEvent && frame.outcomeOnly -> caps?.supportsToolOutcomes == true
            // voice memo → tasks: a memo snapshot carries a transcript — it only reaches a connection that declared
            // it can read one; a null / not-yet-declared holder fails closed
            frame is dev.ccpocket.protocol.VoiceMemoState -> caps?.supportsVoiceMemo == true
            else -> true
        }

        /**
         * [DirectoryEntry] carries agent vocabulary in BOTH [DirectoryEntry.activeSessions] and
         * [DirectoryEntry.sessionAgents]. Strip undeclared values from both before the frame reaches an
         * older peer, while keeping the project row itself.
         *
         * Row-level symmetry (issue #184 mechanism ②) lives UPSTREAM: this filter can't tell what backed a
         * row, so [DirectoryService.listDirectories]'s `includeOpencode=false` (fed from the SAME caps bit)
         * already dropped rows only opencode history sustains — an undeclared client's session list strips
         * opencode sessions, so such a row would open onto a bare "New session" screen. Here we handle the
         * remainder: live opencode enrichment riding on rows other backends keep alive.
         *
         * The SCALARS must be recomputed with them, not just the list. [DirectoryService] fills
         * `activeSessionId` / `activeSessionTitle` / `gitBranch` / `open` / `executing` from
         * `live.firstOrNull()` regardless of agent, so filtering the list alone left an old client holding
         * `open=true` + an opencode `activeSessionId` + an EMPTY list. It rendered a live row; tapping it
         * resolved the agent off the empty list (→ CLAUDE by default) and sent `OpenSession(resumeId=<the
         * opencode session>, agent=CLAUDE)`. The registry reattaches on resumeId alone, so the daemon
         * happily answered with a `SessionLive` carrying `agent="opencode"` — which that client cannot
         * decode, dropping the whole frame. Net effect: a row that says "running", taps that do nothing
         * forever, no error anywhere, and a registered sink that keeps dropping every later push.
         *
         * So: when nothing survives the filter, the row must look exactly like a row with no live session.
         */
        internal fun filterDirs(entries: List<DirectoryEntry>, caps: ClientCapsHolder?): List<DirectoryEntry> =
            entries.map { e ->
                val kept = e.activeSessions.filter { capsAllow(caps, it.agent) }
                val keptAgents = e.sessionAgents.filter { capsAllow(caps, it) }
                if (kept.size == e.activeSessions.size && keptAgents.size == e.sessionAgents.size) return@map e
                val first = kept.firstOrNull()
                e.copy(
                    activeSessions = kept,
                    sessionAgents = keptAgents,
                    // derive from what SURVIVED — never from the stripped-out session
                    open = first != null,
                    executing = kept.any { it.executing },
                    busy = kept.any { it.busy },
                    activeSessionId = first?.sessionId,
                    activeSessionTitle = first?.title,
                    gitBranch = first?.gitBranch,
                )
            }

        /**
         * The Git panel's owner test (#280 §3.1 / #281 §5), as a pure function so it can be asserted
         * without standing up a router: [isOwner] — no bridge or share origin, no guest scope.
         */
        internal fun gitOwnerOnly(origin: String?, guestScope: GuestScope?): Boolean = isOwner(origin, guestScope)

        /** The one refusal sentence a non-owner sees — no repository facts leak with it. */
        internal const val GIT_OWNER_ONLY = "the Git panel is owner-only"

        /**
         * Make a [Usage] reply safe for [caps]' vocabulary (issue #258). The by-model rows carry an
         * [AgentKind], and since #217/#258 that can be OPENCODE/KIMI/ZCODE — an undeclared peer would
         * hard-fail the WHOLE Envelope on the unknown enum, losing the dashboard entirely.
         *
         * Undeclared rows are DOWNGRADED to the baseline CLAUDE rather than dropped: their tokens are
         * already inside the hero total and the trend, so dropping the bar would make the page contradict
         * itself. The old client loses only the badge color — the model id it renders stays honest.
         */
        internal fun gateUsageAgents(usage: dev.ccpocket.protocol.Usage, caps: ClientCapsHolder?): dev.ccpocket.protocol.Usage {
            if (usage.models.all { capsAllow(caps, it.agent) }) return usage
            return usage.copy(
                models = usage.models.map { if (capsAllow(caps, it.agent)) it else it.copy(agent = AgentKind.CLAUDE) },
            )
        }

        /** Whether a peer with [caps] can decode [agent]. Null caps (legacy ingress / bridges) = undeclared,
         *  so only the baseline CLAUDE/CODEX vocabulary is allowed. Post-baseline agents (OPENCODE issue #184,
         *  KIMI issue #206) each need their own declared capability. Single choke point for every list/dir
         *  filter below so a new gated agent is added in ONE place ([ClientCapsHolder.allows]). */
        internal fun capsAllow(caps: ClientCapsHolder?, agent: AgentKind?): Boolean =
            agent == null || agent == AgentKind.CLAUDE || agent == AgentKind.CODEX || caps?.allows(agent) == true
    }

    /** [origin] names the restricted credential this frame arrived from (issue #91 bridge / #115 guest) —
     *  null for every interactive owner client. [guestScope] (issue #115) is non-null ONLY for a GUEST:
     *  it clamps the project/session VISIBILITY to the shared root + the guest's own sessions, and rides
     *  into [SessionRegistry.open] as the conversation's tool path guard. [caps] is the connection's
     *  capability holder — null (legacy ingress / bridges) filters like an undeclared client.
     *  [bridgeAllowedCommands] (issue #91) is a BRIDGE's owner-configured Bash allow-list, ridden down to the
     *  new conversation's PermissionBridge so whitelisted commands auto-run without a phone prompt; empty for
     *  every owner/guest client. */
    // [ownerBypass] (issue #91): this OpenSession is the bridge's CONFIGURED OWNER's OWN dedicated session, so
    // the WHOLE session auto-allows (per-session ⇒ race-free). Passed ONLY by trusted in-process code (the
    // built-in engine); the relay/LAN ingress never sets it, so an external adapter can never claim it.
    // Ignored for non-OpenSession frames.
    // [deviceId]: the TRANSPORT-authenticated identity of the sender — the relay ingress passes the
    // Noise-proven deviceId, the gated LAN path its hello'd device — NEVER a frame field. It keys the
    // device-owned planes (project pins, voice memos); null for in-process callers.
    // [bridgeContextPreamble] (issue #242) is a BUILT-IN bridge's session-stable context (which chat, which
    // project, what the session cannot see), appended to the agent's SYSTEM prompt for the conversation this
    // OpenSession creates. Carries no authority and is set only by trusted in-process code; null everywhere else.
    // [listingLane]: non-null asks for this frame's session-list reply to be produced OFF the caller's loop,
    // one at a time and in request order with every other reply of the same lane (see [emitSessions]). The
    // relay ingress passes the deviceId — its single reader serves every device, so a listing produced
    // inline holds all of them. Null (LAN socket, in-process callers, tests) keeps the reply inline.
    suspend fun handle(frame: Frame, sink: OutboundSink, origin: String? = null, guestScope: GuestScope? = null, caps: ClientCapsHolder? = null, bridgeAllowedCommands: List<String> = emptyList(), bridgeContextPreamble: String? = null, ownerBypass: Boolean = false, deviceId: String? = null, pinConnection: dev.ccpocket.daemon.pins.ProjectPinConnection? = null, listingLane: String? = null, onOpened: suspend (String) -> Unit = {}) {
        when (frame) {
            // capability declaration (wire-compat gate for AgentKind additions) — no reply; the very
            // next list request answers unfiltered. Ingress handlers may process frames concurrently,
            // so a burst's first list can still race the declaration: worst case one filtered snapshot,
            // corrected by the client's next fetch.
            is ClientCaps -> {
                caps?.supportsOpencode = AGENT_WIRE_OPENCODE in frame.supportsAgents
                caps?.supportsKimi = AGENT_WIRE_KIMI in frame.supportsAgents // issue #206: gates KIMI rows
                caps?.supportsZcode = AGENT_WIRE_ZCODE in frame.supportsAgents // issue #228: gates ZCODE rows
                caps?.supportsDsh = AGENT_WIRE_DSH in frame.supportsAgents // issue #255: gates DSH rows
                caps?.supportsDiagnostics = frame.supportsDiagnostics
                caps?.supportsApprovalV2 = frame.supportsApprovalV2 // P2-3: gates the V2 approval frames
                caps?.supportsProjectPins = frame.supportsProjectPins // #362: gates pocket/pins.state
                caps?.supportsManagedSessions = frame.supportsManagedSessions // #360: gates pocket/managed.state + .discovered
                caps?.supportsToolOutcomes = frame.supportsToolOutcomes // #380: gates outcome-only tool RESULTs
                caps?.supportsVoiceMemo = frame.supportsVoiceMemo // gates pocket/memo.state
                caps?.maxFrameBytes = ClientCapsHolder.frameCap(frame.maxFrameBytes) // KTOR-6963: sizes every frame sealed to this connection
            }

            is ListDirectories ->
                if (guestScope != null) sink.emit(Directories(filterDirs(scopedDirectories(guestScope, caps), caps)))
                else sink.emit(Directories(filterDirs(dirs.listDirectories(frame.root, registry.busyCwds(), registry.liveByCwd(), includeOpencode = caps?.supportsOpencode == true, includeKimi = caps?.supportsKimi == true, includeZcode = caps?.supportsZcode == true, includeDsh = caps?.supportsDsh == true), caps)))

            // Owner control-plane pull: push is alert-only, so every foreground client can reconstruct the
            // complete queue even if APNs/FCM was delayed or lost. Restricted credentials must never learn
            // another user's approvals; GuestCaps/BridgeGuard deny this frame and this check is defence in depth.
            is ListPendingApprovals -> if (origin == null && guestScope == null) {
                sink.emit(PendingApprovals(registry.pendingApprovals(shell.pendingApprovals() + exports.pendingApprovals())))
            }

            is ListSessions -> emitSessions(frame.workdir, sink, guestScope, caps, listingLane)

            // session groups (issue #119): mutate the daemon-side group store, then re-push this workdir's
            // session list so the grouping change reflects immediately (same response path as ListSessions).
            // A GUEST can't manage groups (they belong to the owner's project view) — silently no-op the
            // mutation but still answer with the (re-filtered) list so the client isn't left hanging.
            is GroupCreate -> {
                if (guestScope == null) SessionGroups.create(groupWorkdir(frame.workdir), frame.name)
                emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
            }
            is GroupRename -> {
                if (guestScope == null) SessionGroups.rename(groupWorkdir(frame.workdir), frame.groupId, frame.name)
                emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
            }
            is GroupDelete -> {
                if (guestScope == null) SessionGroups.delete(groupWorkdir(frame.workdir), frame.groupId)
                emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
            }
            is GroupAssign -> {
                if (guestScope == null) SessionGroups.assign(groupWorkdir(frame.workdir), frame.sessionId, frame.groupId)
                emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
            }

            // session archive (issue #202): same daemon-side-truth + re-push contract as the groups above.
            // Acting from the cross-project archive view answers with the ARCHIVE list instead, so restoring
            // a row there never repoints the client's currently-listed directory to that row's project.
            // OWNER means no restricted credential: no bridge/share origin and no guest scope ([isOwner]).
            is SetSessionArchived -> {
                val owner = isOwner(origin, guestScope)
                if (owner) {
                    val ok = SessionArchive.setArchived(groupWorkdir(frame.workdir), frame.sessionId, frame.archived, archiveFile)
                    // a refused write (bad id, cap hit) must not read as success: the re-pushed list would
                    // look unchanged and the client would still show "Archived"
                    if (!ok) {
                        sink.emit(PocketError("archive_failed", "could not update the archive for this session"))
                        return
                    }
                }
                // the emit is gated too, not just the mutation: emitArchivedSessions is a whole-machine
                // enumeration with no scope filter, so a non-owner must never reach it through this door
                if (owner && frame.fromArchiveView) scope.launch { emitArchivedSessions(sink, caps) }
                else emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
            }
            // a multi-project scan → off the inbound pump like FetchUsage. Owner only: this is a
            // cross-project discovery surface, strictly more than the per-dir listing a guest may have.
            is ListArchivedSessions ->
                if (isOwner(origin, guestScope)) {
                    scope.launch { emitArchivedSessions(sink, caps) }
                }

            // project-pin sync (issue #362): OWNER-ONLY and deliberately NOT launched — both transports hand it
            // over in receive order, so one connection's fetch and operation batches commit in the order sent.
            // Restricted credentials never reach here (GuestCaps / BridgeCaps default-deny both pin frame
            // types); the owner test below is the second door.
            is dev.ccpocket.protocol.SyncProjectPins -> syncProjectPins(frame, sink, origin, guestScope, caps, deviceId, pinConnection)

            // managed session list (issue #360): OWNER-ONLY. Restricted credentials never reach here (GuestCaps /
            // BridgeCaps default-deny all five request types); the owner test in [managedSessionsRequest] is
            // the second door and refuses before any directory or title is read.
            is dev.ccpocket.protocol.ListManagedSessions,
            is dev.ccpocket.protocol.EnableManagedSessions,
            is dev.ccpocket.protocol.DiscoverSessions,
            is dev.ccpocket.protocol.ImportSession,
            is dev.ccpocket.protocol.RemoveManagedSession -> managedSessionsRequest(frame as dev.ccpocket.protocol.ToDaemon, sink, origin, guestScope, caps)

            // session rename (issue #158): lands claude's own custom-title record (live daemon session:
            // the CLI appends it itself over a control_request; idle: a one-line transcript append) —
            // an agent-ack/disk round-trip → off the inbound pump like FetchUsage. Success answers with
            // the re-pushed Sessions (the group ops' refresh contract); failure with a PocketError. A
            // guest never reaches here (GuestCaps default-denies the frame type at the choke point) —
            // the null-check is belt-and-suspenders like the group mutations', answering with the list.
            is RenameSession -> scope.launch {
                if (guestScope != null) { emitSessions(frame.workdir, sink, guestScope, caps, listingLane); return@launch }
                val err = registry.renameSession(groupWorkdir(frame.workdir), frame.sessionId, frame.title)
                if (err == null) emitSessions(frame.workdir, sink, guestScope, caps, listingLane)
                else sink.emit(PocketError("rename_failed", err))
            }

            // heavy transcript scan → off the inbound pump so it can't wedge the socket
            // issue #258: the reply's by-model rows can now carry KIMI/ZCODE badges, so the same agent
            // gate the session rows use applies here — a peer that never declared the wire name would
            // hard-fail the whole Envelope on the unknown enum.
            is FetchUsage -> scope.launch { sink.emit(gateUsageAgents(UsageService.aggregate(frame.days, agent = frame.agent), caps)) }

            // Claude subscription allowance (the 5h/7d windows behind the CLI's own `/usage` panel).
            // A network round trip to Anthropic → off the inbound pump like FetchUsage, or the socket
            // would stall for every device while api.anthropic.com is slow.
            //
            // OWNER-ONLY, guarded here as well as by the caps allow-lists: GuestCaps / BridgeCaps both
            // default-deny an unlisted type, and this is the second door so a future ingress change cannot
            // silently open the surface. [gitOwnerOnly] is the owner judgement, shared rather than
            // re-derived. This is account-wide BILLING state for the machine's owner, strictly wider than
            // anything a scoped share covers, so a non-owner gets SILENCE (no reply frame at all) rather
            // than an empty snapshot that would read as "your allowance is fine".
            is ClaudeQuotaGet ->
                if (gitOwnerOnly(origin, guestScope)) {
                    scope.launch {
                        // Dispatch by the REQUESTED backend (issue #348). The frame name stays
                        // `claude.quota.get` for wire compatibility; `agent` is the selector.
                        val reply = when (frame.agent) {
                            AgentKind.CLAUDE -> quota.get(frame.forceRefresh)
                            AgentKind.CODEX -> codexQuota.get(frame.forceRefresh)
                            // a backend whose allowance nothing here can read. NO_TOKEN, not an error:
                            // "there is no subscription number for this one" is the state the client
                            // hides, and an http_error would draw an alarm for a missing feature.
                            else -> ClaudeQuota(
                                status = CLAUDE_QUOTA_NO_TOKEN,
                                error = "no subscription allowance is readable for this backend",
                            )
                        }
                        // ECHO the DECODED request value, never a hard-coded constant. `agent` is a
                        // coerced enum: an unknown wire name from a newer client decodes to CLAUDE here,
                        // is therefore answered with the CLAUDE allowance above, and must be LABELLED
                        // claude so that client sees the mismatch against what it asked for and drops the
                        // reading. Stamping the service's own idea of its agent would instead hand that
                        // client a Claude number wearing the label it hoped for.
                        val tagged = reply.copy(agent = frame.agent)
                        // status + row count only — never the payload (it is billing state, and error
                        // strings must stay token-free by ClaudeQuotaService's contract anyway)
                        quotaLog.info("quota → ${sinkKey(sink)} agent=${frame.agent} status=${tagged.status} limits=${tagged.limits.size}")
                        sink.emit(tagged)
                    }
                } else {
                    quotaLog.info("quota REFUSED origin=$origin guest=${guestScope != null}")
                }

            // installed skills/plugins browse page (issue #132): a disk scan → off the inbound pump like
            // FetchUsage. Guests never reach here (GuestCaps denies the frame type at the choke point).
            is FetchSkillCatalog -> scope.launch {
                sink.emit(SkillCatalogService.build(frame.workdir?.let { dirs.validateWorkdir(it) }))
            }

            // both re-scan the transcript from disk (issue #36) → same off-pump rule as FetchUsage
            // Every file-surface branch answers even when its service throws (audit 2026-10-04 C): a launched
            // branch's exception only reaches the scope's handler, which sends nothing — see [replyOnFailure].
            is ListSessionFiles -> scope.launch {
                replyOnFailure(sink, { SessionFiles(frame.workdir, frame.sessionId, error = FILE_SURFACE_FAILED) }) {
                    sink.emit(SessionFiles(frame.workdir, frame.sessionId, SessionFilesService.changedFiles(frame.agent, frame.workdir, frame.sessionId)))
                }
            }
            // serves any path canonically inside the workdir (issue #133) and, for a client that opted in,
            // streams over-cap binaries as FileContentChunk frames (issue #134)
            is ReadFile -> scope.launch {
                val observation = FileReadDiagnostics(frame.diagnostic?.validated()?.takeIf {
                    caps?.supportsDiagnostics == true && origin == null && guestScope == null
                }, sink::emit)
                // the ok=false FileContent also settles a half-sent chunk stream (it supersedes the partial)
                replyOnFailure(sink, { FileContent(frame.workdir, frame.sessionId, frame.path, ok = false, error = FILE_SURFACE_FAILED) }) {
                    try { SessionFilesService.streamFile(frame.agent, frame.workdir, frame.sessionId, frame.path, frame.allowChunks, observation::send) }
                    catch (error: Exception) { observation.failed(error); throw error }
                }
            }
            is ReadFileDiff -> scope.launch {
                replyOnFailure(sink, { FileDiff(frame.workdir, frame.sessionId, frame.path, ok = false, error = FILE_SURFACE_FAILED) }) {
                    sink.emit(SessionFilesService.fileDiff(frame.agent, frame.workdir, frame.sessionId, frame.path))
                }
            }
            // approval-gated export of a file the session did NOT change (issue #67 v2 / #79). MUST launch,
            // not await — like RunShellCommand below, it suspends on the human approval gate, and the mode
            // comes from the daemon's own registry so the gate can't be spoofed client-side.
            is ExportFile -> scope.launch {
                val observation = FileReadDiagnostics(frame.diagnostic?.validated()?.takeIf {
                    caps?.supportsDiagnostics == true && origin == null && guestScope == null
                }, sink::emit)
                replyOnFailure(sink, { FileContent(frame.workdir, frame.sessionId, frame.path, ok = false, error = FILE_SURFACE_FAILED) }) {
                    try { exports.run(frame, registry.modeOf(frame.convoId), observation::send) }
                    catch (error: Exception) { observation.failed(error); throw error }
                }
            }
            // ---- Git panel (issue #280) + worktree management (issue #281) ----
            // OWNER-ONLY, and deliberately guarded HERE as well as by the caps allow-lists. GuestCaps /
            // BridgeCaps default-deny already stops these types at the ingress; this is the second door, so a
            // future ingress change cannot silently open the surface ([gitOwnerOnly]).
            //
            // The READS are gated too, not just the writes: a guest's files/diff surface answers "what did
            // this session change", while git status answers "what does the whole repository look like" —
            // a strictly wider face, including paths and branches no share ever covered.
            //
            // All of them scope.launch: a fetch/pull/push is a network round trip and the relay pumps
            // inbound frames sequentially and inline, so awaiting here would wedge the socket for every
            // device until git returned. The workdir goes through the SAME dirs.validateWorkdir() the
            // files surface uses — an arbitrary path is never handed to a git process.
            is FetchGitStatus -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(GitStatus(frame.convoId, frame.workdir, ok = false, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.status(frame, wd))
            }
            is ReadGitDiff -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(GitDiff(frame.convoId, frame.workdir, frame.path, frame.staged, ok = false, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.diff(frame, wd))
            }
            is GitAction -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(GitActionResult(frame.convoId, frame.op, ok = false, exitCode = -1, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.act(frame, wd))
            }
            is ListWorktrees -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(WorktreeList(frame.convoId, frame.workdir, ok = false, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.listWorktrees(frame, wd))
            }
            is AddWorktree -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(GitActionResult(frame.convoId, GIT_OP_WORKTREE_ADD, ok = false, exitCode = -1, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.addWorktree(frame, wd))
            }
            is RemoveWorktree -> scope.launch {
                val wd = gitWorkdir(frame.workdir, origin, guestScope)
                if (wd == null) sink.emit(GitActionResult(frame.convoId, GIT_OP_WORKTREE_REMOVE, ok = false, exitCode = -1, error = gitDenial(origin, guestScope, frame.workdir)))
                else sink.emit(git.removeWorktree(frame, wd))
            }

            // composer @-file completion (issue #75) and the file browser: a directory scan — and, under
            // the smart filter, one bounded `git check-ignore` — so it stays off the inbound pump like the others
            is ListPathEntries -> scope.launch {
                val res = dirs.listPathEntries(frame.workdir, frame.subPath, frame.limit, frame.filter)
                // filesystem roots (#176) ride ONLY the owner's "~" home-anchor reply (the folder browser's
                // opening request — a real session's workdir is never the bare "~"): a guest must not learn
                // the disk layout (GuestGuard already denies its "~" anchor outright; this gate is defence in
                // depth), and @-completion replies don't need it.
                val fsRoots = if (guestScope == null && frame.workdir == "~") dirs.listFsRoots() else emptyList()
                sink.emit(
                    PathEntries(
                        workdir = frame.workdir,
                        subPath = frame.subPath,
                        entries = res?.first ?: emptyList(),
                        truncated = res?.second ?: false,
                        ok = res != null,
                        error = if (res == null) "not a readable directory" else null,
                        roots = fsRoots,
                    ),
                )
            }

            is OpenSession -> {
                // a new project: create the named folder if it doesn't exist yet (under an existing writable parent).
                // A GUEST may only open UNDER its shared root — the guard already vetted the workdir, but re-check the
                // (possibly newly created) real path so a create-under-parent can't land outside the scope.
                val wd = dirs.validateOrCreateWorkdir(frame.workdir)
                when {
                    wd == null -> sink.emit(PocketError("bad_workdir", "not a readable directory: ${frame.workdir}"))
                    // OpenCode runs `--auto` (no approval protocol): every tool call is CLI-approved, so the
                    // PermissionBridge that enforces a guest's path scope / a bridge's command policy is never
                    // consulted. Until opencode exposes an enforceable approval channel, a RESTRICTED origin
                    // (guest #115 / bridge #91) must not be able to open one — it would be unsandboxed
                    // full-auto under a credential whose whole design is scoped, per-call consent.
                    // KIMI (issue #206): P1 fail-closed alongside OpenCode. Its ACP approval channel COULD
                    // route a guest's path scope through PermissionBridge, but that path is unverified (probe
                    // blocked on device-code auth), so a restricted credential must not open one yet. P2
                    // re-evaluates once the ACP approval face is proven end-to-end.
                    // DSH: still fail-closed AFTER the approval bridge landed (issue #291) and after the
                    // dsh 0.1.2 ACP switch, and this is deliberately NOT the moment to lift it. A dsh
                    // ask does reach PermissionBridge, and on the ACP wire it even carries the tool's real
                    // `rawInput` now — but the walls a restricted session depends on all key on CLAUDE
                    // tool SPELLINGS, and dsh matches none of them:
                    //   - the guest/bridge path wall reads `file_path`/`path`/`notebook_path` out of the
                    //     tool input; dsh happens to spell its write target `file_path`, but nothing keeps
                    //     the two vocabularies in step, so the match is a coincidence rather than a wall.
                    //   - `BridgeCommandPolicy` only classifies `toolName == "Bash"` with an `input.command`;
                    //     dsh's shell tool is `bash`, so every command classifies as unknown.
                    // A guest/bridge would therefore self-approve tool calls the daemon cannot even name.
                    // Lifting this needs tool-name normalization + real target extraction FIRST, not just
                    // the presence of an approval channel.
                    (guestScope != null || origin != null) &&
                        (
                            frame.agent == AgentKind.OPENCODE || frame.agent == AgentKind.KIMI ||
                                frame.agent == AgentKind.ZCODE || frame.agent == AgentKind.DSH
                            ) ->
                        sink.emit(PocketError("share_forbidden", "${frame.agent} sessions are not available over shared/bridge access yet"))
                    guestScope != null && !PathScope.contains(guestScope.roots, wd.toString()) ->
                        sink.emit(PocketError("share_out_of_scope", "that folder is outside your shared folder"))
                    else -> {
                        dirs.noteRecent(wd.toString())
                        // pathScope = the guest's roots (issue #115 §4) → the conversation's
                        // PermissionBridge denies any Read/Write/Edit outside them. Null for an owner.
                        val convoId = registry.open(
                            frame.copy(workdir = wd.toString()),
                            frame.diagnostic?.validated()?.takeIf {
                                caps?.supportsDiagnostics == true && origin == null && guestScope == null
                            }?.let { SessionOpenDiagnostics(sink, it) } ?: sink,
                            origin,
                            pathScope = guestScope?.roots,
                            // null caps (legacy ingress / bridges) = undeclared, same as everywhere else here
                            peerSupportsOpencode = caps?.supportsOpencode == true,
                            peerSupportsKimi = caps?.supportsKimi == true,
                            peerSupportsZcode = caps?.supportsZcode == true,
                            peerSupportsDsh = caps?.supportsDsh == true,
                            bridgeAllowedCommands = bridgeAllowedCommands,
                            bridgeContextPreamble = bridgeContextPreamble, // #242, bridge opens only

                            announcedWorkdir = frame.workdir, // #219: announce the RAW workdir the phone opened (may be "~/x")
                            ownerBypass = ownerBypass, // trusted in-process open flag ⇒ owner's own session
                        )
                        if (convoId.isNotEmpty()) onOpened(convoId) // "" = backend unavailable (PocketError already sent)
                    }
                }
            }

            is dev.ccpocket.protocol.HistoryApplied -> {
                if (caps?.supportsDiagnostics == true && origin == null && guestScope == null)
                    SessionOpenDiagnostics.applied(frame, sink)
            }
            is SendPrompt -> if (!registry.sendPrompt(frame.copy(diagnostic = frame.diagnostic?.validated()?.takeIf {
                caps?.supportsDiagnostics == true && origin == null && guestScope == null
            }))) sink.emit(SessionGone(frame.convoId))
            // Verdicts (question answers ride this same frame) resolve at ONE routing point — agent tool ask,
            // bridge request approval, quick-shell command, file export — by (convoId, askId) in the
            // ApprovalCoordinator (approval design M1), instead of being try-offered to each service's private
            // pending map. An unknown/expired askId answers the TAPPING device honestly (issue #100): its
            // optimistic card-clear must not read as success.
            is PermissionVerdict -> if (!approvals.onVerdict(frame.copy(diagnostic = frame.diagnostic?.validated()?.takeIf {
                caps?.supportsDiagnostics == true && origin == null && guestScope == null
            }), diagnosticEmit = sink::emit)) {
                sink.emit(PocketError("ask_expired", "That approval expired before it reached your computer — ask the agent to try the action again.", frame.convoId))
            }
            is SwitchMode -> registry.switchMode(frame)
            is SwitchServiceTier -> registry.switchServiceTier(frame)
            // dsh incomplete-install one-tap repair (rides [PocketError.repair]): reinstall the CLI whose
            // broken npm install crashed the session, then the next prompt respawns it clean. OWNER-only —
            // a global `npm i -g` is a machine-wide side effect no guest/bridge credential may
            // trigger (their capability whitelists already default-deny this unknown frame; this is the
            // in-router echo of that boundary). Off the inbound loop like RunShellCommand: the reinstall
            // takes minutes and must never wedge the shared socket.
            is AgentRepairStart -> if (isOwner(origin, guestScope)) {
                if (frame.agent == AgentKind.DSH) {
                    scope.launch(Dispatchers.IO) { DshRepairService.repair(frame.convoId, sink::emit) }
                } else {
                    sink.emit(AgentRepairProgress(frame.convoId, frame.agent, done = true, ok = false, error = "auto-repair is only available for DeepSeek Harness"))
                }
            }
            // §18.1 P1-7: "clear this rule" must reach BOTH stores that can hold it — the conversation's
            // agent allowRules AND the quick terminal's — or tightening leaves a shadow rule auto-running
            is ClearAllowRule -> {
                val success = registry.clearRule(frame)
                shell.clearRule(frame.convoId, frame.rule)
                frame.requestId?.let { requestId ->
                    sink.emit(
                        ApprovalGrantMutationResult(
                            requestId = requestId,
                            convoId = frame.convoId,
                            success = success,
                            error = if (success) null else "session is no longer active",
                        ),
                    )
                }
            }

            // MUST launch, not await: shell.run suspends on the human approval gate, but the relay transport
            // pumps inbound frames sequentially & inline — awaiting here would wedge the whole socket (for every
            // device/convo) until the verdict, which itself can't be read while we block. Fire it on the daemon
            // scope so the loop stays free to deliver that verdict.
            is RunShellCommand -> scope.launch {
                val wd = dirs.validateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(ShellResult(frame.convoId, frame.command, exitCode = -1, error = "not a readable directory: ${frame.workdir}"))
                } else {
                    // the daemon (not the phone) decides the mode AND the task id → neither the approval
                    // gate nor the shared task-grant match can be spoofed client-side. stillLive (P1-5):
                    // re-checked right before the side effect, so a close during the approval wait wins.
                    shell.run(
                        frame.copy(workdir = wd.toString()), registry.modeOf(frame.convoId), registry.taskIdOf(frame.convoId),
                        stillLive = { registry.modeOf(frame.convoId) != null },
                        emit = sink::emit,
                    )
                }
            }

            // ── approval design M2 ──
            // AttentionLease: pauses only the READING budget of a still-pending ask (never authority or the
            // absolute deadline). Restricted credentials never reach here — their capability whitelists
            // default-deny unknown frame types at the choke point.
            is ApprovalAttentionHeartbeat -> approvals.heartbeat(frame.convoId, frame.askId, frame.visible)
            // "收紧后续授权" from the autorun chip: owner-only (same gate as ListPendingApprovals); the
            // store re-checks the grant belongs to the named conversation.
            is RevokeGrant -> if (origin == null && guestScope == null) {
                val success = grants.revoke(frame.convoId, frame.grantId)
                frame.requestId?.let { requestId ->
                    sink.emit(
                        ApprovalGrantMutationResult(
                            requestId = requestId,
                            convoId = frame.convoId,
                            success = success,
                            error = if (success) null else "grant is no longer active",
                        ),
                    )
                }
            }
            // §18.2 P2-2: the recoverable decision trail — owner-only, newest first, redacted rows only
            is FetchApprovalHistory -> if (origin == null && guestScope == null) {
                sink.emit(ApprovalHistoryPage(approvalHistory?.recent(frame.limit) ?: emptyList()))
            }

            is SwitchDirectory -> {
                val wd = dirs.validateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(PocketError("bad_workdir", "not a readable directory: ${frame.workdir}", frame.convoId))
                } else {
                    dirs.noteRecent(wd.toString())
                    registry.switchDir(frame.copy(workdir = wd.toString()))
                }
            }

            // fan-out: only a REAL close (last attached client) drops the quick-terminal state with it
            // (exports keep NO cross-request state to drop: every export ask is one-off, never remembered)
            is CloseSession -> { if (registry.close(frame.convoId, sink, frame.force)) shell.forget(frame.convoId) }
            is CancelTurn -> registry.cancelTurn(frame)
            // task panel "stop" (issue #80): interrupt the agent's work for this job + settle its row killed
            is StopBackgroundJob -> registry.stopBackgroundJob(frame)
            // workflow detail sheet (issue #106): read one agent's full prompt/return off disk —
            // a transcript parse, so off the inbound loop like FetchUsage
            is GetWorkflowAgentDetail -> scope.launch { registry.fetchWorkflowAgentDetail(frame) }
            // older-history page (issue #147): a transcript parse → off the inbound pump; answered to
            // the requesting sink only (never fanned out to other attached clients)
            is FetchHistoryPage -> scope.launch { registry.fetchHistoryPage(frame, sink) }
            // rewind / fork (issue #282): a transcript parse plus (on execute) a conversation swap, so
            // off the inbound loop like the other disk-bound frames. Answered to the requesting sink only —
            // the registry never fans a rewind reply out.
            is dev.ccpocket.protocol.RewindSession -> scope.launch { registry.rewind(frame, sink) }

            // voice memo → tasks: OWNER-ONLY and deliberately NOT launched — a start must be registered before its
            // chunks, and both transports hand frames over in receive order. Restricted credentials never reach
            // here (GuestCaps / BridgeCaps / ExecutionCaps default-deny all four types); the
            // checks in [voiceMemoRequest] are the second door.
            is dev.ccpocket.protocol.VoiceMemoStart,
            is dev.ccpocket.protocol.VoiceMemoAudio,
            is dev.ccpocket.protocol.VoiceMemoGet,
            is dev.ccpocket.protocol.VoiceMemoCancel ->
                voiceMemoRequest(frame as dev.ccpocket.protocol.ToDaemon, sink, origin, guestScope, caps, deviceId)

            // voice capture: buffer fast here; whisper runs on the service's own scope
            is AudioChunk -> transcribe.onChunk(frame, sink)
            is AudioCancel -> transcribe.onCancel(frame)

            // file upload (issue #90): stream each chunk into the live session's workspace inbox;
            // the FileUploaded receipt rides the same sink the chunks arrived on
            is FileChunk -> inbox.onChunk(frame, sink)
            is FileUploadCancel -> inbox.onCancel(frame)

            // account switching: each spawns a `claude auth …` child — off the inbound pump, like FetchUsage
            is FetchAuthStatus -> scope.launch { auth.sendStatus(sink::emit) }
            is AuthLogin -> scope.launch { auth.login(frame.console, sink::emit, frame.force) }
            is AuthLoginCode -> scope.launch { auth.submitCode(frame.code, sink::emit) }
            is AuthLoginCancel -> scope.launch { auth.cancelLogin(sink::emit) }
            is AuthLogout -> scope.launch { auth.logout(sink::emit) }

            // API presets (issue #113): activate/delete may close conversations (suspending) — off the
            // inbound pump like auth, so the socket stays free while the registry settles
            is FetchPresets -> scope.launch { presets.sendState(sink::emit) }
            is SavePreset -> scope.launch { presets.save(frame, sink::emit) }
            is DeletePreset -> scope.launch { presets.delete(frame, sink::emit) }
            is ActivatePreset -> scope.launch { presets.activate(frame.id, frame.force, sink::emit) }

            // scheduled tasks (issue #137): quick store ops; each answers with the full ScheduleState
            // truth (same single-reply contract as pocket/presets.*). Guests/bridges never reach here —
            // their capability whitelists deny the frame type at the choke point (default-deny).
            is ScheduleCreate -> sink.emit(filterSchedule(scheduler.create(frame, dirs.validateWorkdir(frame.workdir)?.toString()), caps))
            is ScheduleList -> sink.emit(filterSchedule(scheduler.state(), caps))
            is ScheduleCancel -> sink.emit(filterSchedule(scheduler.cancel(frame.id), caps))

            // phone-push switch: null enabled = query only; either way the daemon's truth is the reply
            is SetPushPrefs -> {
                frame.enabled?.let(prefs::setPushEnabled)
                sink.emit(PushPrefs(prefs.pushEnabled))
            }

            // issue #201 "wait for my decision": same single-reply contract as the push toggle. Owner-only
            // by the same default-deny choke point as the frames above — a guest/bridge can never flip how
            // long the OWNER's approvals wait. Persist AND mirror into the per-ask read, so the next card
            // picks it up without a relaunch.
            // origin/guestScope re-checked here like every other owner-plane approval frame
            // (ListPendingApprovals / RevokeGrant / FetchApprovalHistory): the capability choke point
            // already denies it, and this is the second lock the rest of the plane carries.
            is SetApprovalPrefs -> if (origin == null && guestScope == null) {
                frame.noAutoDeny?.let {
                    prefs.setAskNoAutoDeny(it)
                    ApprovalTimeout.noAutoDeny = it
                }
                // issue #220: same owner-only plane, same persist-and-mirror shape — the next mode switch
                // arms (or, at 0, never arms) the Full Control clock from this. Existing live Full Control
                // sessions are not re-clocked mid-flight: the change bites the owner's NEXT switch.
                frame.fullControlExpiryMs?.let {
                    prefs.setFullControlExpiryMs(it)
                    ApprovalTimeout.fullControlExpiryMs = prefs.fullControlExpiryMs
                }
                sink.emit(ApprovalPrefs(prefs.askNoAutoDeny, prefs.fullControlExpiryMs))
            }

            // agent model listing: inspect the Mac daemon's local agent config/cache.
            // On IO, not the shared Default pool: the Claude path may make a blocking HTTP call to the
            // user's gateway (#167 ②), and a gateway that accepts the connection but never answers would
            // otherwise pin a core-count-limited thread that the session pumps and scheduler share.
            is FetchModels -> scope.launch(Dispatchers.IO) {
                sink.emit(when (frame.agent) {
                    AgentKind.OPENCODE -> openCodeModels.fetch()
                    AgentKind.KIMI -> kimiModels.fetch()
                    AgentKind.ZCODE -> zcodeModels.fetch()
                    AgentKind.CODEX -> codexModels.fetch()
                    AgentKind.CLAUDE -> claudeModels.fetch(frame.workdir)
                    // issue #333 lifted the #255 scope-out: dsh's own `llm.models` + `agentPreset.list`
                    // answer without a session, so the picker gets real rows (and the agent-preset row)
                    // instead of the "not supported yet" placeholder. Failures still come back as an
                    // explicit ModelsList.error — never silence, which would hang the picker.
                    AgentKind.DSH -> dshModels.fetch()
                })
            }

            // Retired features (review requests, session handoff, collaborator contacts): their list requests
            // answer an empty list of their own type ([retiredFeatureListing]); every other frame not handled
            // above, the other retired requests included, answers `unsupported`.
            else -> sink.emit(
                retiredFeatureListing(frame)
                    ?: PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"),
            )
        }
    }

    /**
     * One managed session list request (issue #360). Order of the gates matters:
     *  1. a connection that has not declared [ClientCapsHolder.supportsManagedSessions] gets SILENCE — no managed
     *     frame could reach it (egress gates on the same bit), and it must not learn anything else either;
     *  2. a non-owner (bridge / guest — [isOwner]) gets `managed_forbidden` before the
     *     service is touched, so no directory, scan or store is read on its behalf;
     *  3. an unwired service answers `managed_unsupported`.
     * The service then validates agent / workdir / ids itself, runs reads off this pump, serializes mutations, and
     * filters rows to this connection's agent vocabulary. The requester's sink key is excluded from the push.
     */
    private suspend fun managedSessionsRequest(
        frame: dev.ccpocket.protocol.ToDaemon,
        sink: OutboundSink,
        origin: String?,
        guestScope: GuestScope?,
        caps: ClientCapsHolder?,
    ) {
        if (caps == null || !caps.supportsManagedSessions) return
        fun refuse(code: String): dev.ccpocket.protocol.ToPhone? {
            val (requestId, workdir, agent) = when (frame) {
                is dev.ccpocket.protocol.ListManagedSessions -> Triple(frame.requestId, frame.workdir, frame.agent)
                is dev.ccpocket.protocol.EnableManagedSessions -> Triple(frame.requestId, frame.workdir, frame.agent)
                is dev.ccpocket.protocol.DiscoverSessions -> Triple(frame.requestId, frame.workdir, frame.agent)
                is dev.ccpocket.protocol.ImportSession -> Triple(frame.requestId, frame.workdir, frame.agent)
                is dev.ccpocket.protocol.RemoveManagedSession -> Triple(frame.requestId, frame.workdir, frame.agent)
                else -> return null
            }
            if (!dev.ccpocket.protocol.isValidManagedId(requestId)) return null // uncorrelatable: a reply would read as a push
            val wd = workdir.take(dev.ccpocket.protocol.MANAGED_WORKDIR_MAX_CHARS)
            return if (frame is dev.ccpocket.protocol.DiscoverSessions) dev.ccpocket.protocol.DiscoveredSessions(requestId, wd, agent, error = code)
            else dev.ccpocket.protocol.ManagedSessionsState(
                requestId = requestId, workdir = wd, agent = agent,
                allAgents = (frame as? dev.ccpocket.protocol.ListManagedSessions)?.allAgents == true, error = code,
            )
        }
        if (!isOwner(origin, guestScope)) {
            refuse(dev.ccpocket.protocol.ManagedSessionErrors.FORBIDDEN)?.let { sink.emit(it) }
            return
        }
        val svc = managedSessions
        if (svc == null) {
            refuse(dev.ccpocket.protocol.ManagedSessionErrors.UNSUPPORTED)?.let { sink.emit(it) }
            return
        }
        svc.accept(frame, sink, sinkKey(sink)) { agent -> capsAllow(caps, agent) }
    }



    /**
     * Voice memo admission. A memo holds a recording and its transcript, so the request is served only when ALL
     * of these hold, and is dropped in silence otherwise — a refusal frame would itself be a `pocket/memo.state`,
     * which an undeclared or restricted peer must never receive:
     *  1. the owner test ([isOwner]: no bridge origin, no guest scope);
     *  2. a TRANSPORT-authenticated device id — in-process callers have none, and the [LOCAL_DEVICE_ID] fallback is not an identity a recording may be filed under;
     *  3. the connection declared [ClientCapsHolder.supportsVoiceMemo];
     *  4. the service is wired.
     * The job's owner is the authenticated id, never a frame field. Snapshots go back through THIS connection's
     * sink, whose own [allowedForCaps] gate re-checks the declaration on every frame.
     */
    private suspend fun voiceMemoRequest(
        frame: dev.ccpocket.protocol.ToDaemon,
        sink: OutboundSink,
        origin: String?,
        guestScope: GuestScope?,
        caps: ClientCapsHolder?,
        deviceId: String?,
    ) {
        if (!isOwner(origin, guestScope)) return
        val device = deviceId?.takeIf { it.isNotBlank() && it != LOCAL_DEVICE_ID } ?: return
        if (caps == null || !caps.supportsVoiceMemo) return
        val service = voiceMemo ?: return
        val reply = dev.ccpocket.daemon.memo.MemoReplyTarget { state ->
            caps.supportsVoiceMemo && runCatching { sink.emit(state) }.isSuccess
        }
        service.handle(dev.ccpocket.daemon.memo.MemoOwner(device), frame, reply)
    }

    /**
     * One project-pin request (issue #362). Every authority fact comes from the transport, never the frame: the
     * owner test is [isOwner], the cursor partition is the Noise-authenticated [deviceId], and the
     * connection facts — is it still current, which subscription did its accepted fetch register — come from
     * [pin], the transport's context for the connection the request arrived on. A caller without both — an
     * in-process caller — can neither subscribe nor mutate. A restricted caller
     * gets SILENCE (its egress caps would drop any pin frame anyway), and so does a connection that never declared
     * the capability: no reply could reach it, and it must not become a subscription.
     *
     * Only a fully validated, successful fetch registers a subscription. A batch must come from the connection
     * holding the subscription it names — checked here and again under the store lock — and never replaces it. A
     * durable change is offered to the other subscribers the moment [ProjectPinService.sync] returns: before the
     * requester's reply is emitted, whether that emit then fails, is cancelled or times out.
     */
    private suspend fun syncProjectPins(
        frame: dev.ccpocket.protocol.SyncProjectPins,
        sink: OutboundSink,
        origin: String?,
        guestScope: GuestScope?,
        caps: ClientCapsHolder?,
        deviceId: String?,
        pin: dev.ccpocket.daemon.pins.ProjectPinConnection?,
    ) {
        if (!isOwner(origin, guestScope)) return
        if (caps == null || !caps.supportsProjectPins) return
        val subscription = frame.subscriptionId.takeIf { dev.ccpocket.protocol.isValidProjectPinToken(it) }
        fun refusal(code: String, message: String) = dev.ccpocket.protocol.ProjectPinsState(
            subscriptionId = subscription.orEmpty(),
            requestId = frame.requestId.takeIf { dev.ccpocket.protocol.isValidProjectPinToken(it, minChars = 1) },
            streamId = frame.streamId.takeIf { dev.ccpocket.protocol.isValidProjectPinToken(it) },
            error = code,
            message = message,
        )
        val svc = projectPins
        if (svc == null || deviceId == null || pin == null) {
            emitPinReply(sink, refusal(dev.ccpocket.protocol.ProjectPinErrors.UNAVAILABLE, "project pins sync only over a paired connection to this computer"))
            return
        }
        val stale = "this connection is not the current project pin subscription of its device"
        val fetch = frame.ops.isEmpty()
        val admitted: suspend () -> Boolean = {
            pin.isCurrent() && (fetch || (subscription != null && pin.currentSubscription() == subscription))
        }
        if (!admitted()) {
            emitPinReply(sink, refusal(dev.ccpocket.protocol.ProjectPinErrors.SUBSCRIPTION_STALE, stale))
            return
        }
        val outcome = svc.sync(deviceId, frame, admitted)
        var reply = outcome.reply
        var answered = false // the requester's own reply will carry this commit as a success
        try {
            if (reply.error == null) {
                // the one place a subscription is registered: after the store accepted the fetch. A connection
                // retired meanwhile registers nothing and is not told its fetch succeeded.
                if (!fetch || (subscription != null && pin.acceptFetch(subscription))) answered = true
                else reply = refusal(dev.ccpocket.protocol.ProjectPinErrors.SUBSCRIPTION_STALE, stale)
            }
        } finally {
            // offering a durable commit is unconditional and synchronous — it never waits on the requester
            outcome.changed?.let { svc.broadcast(it, exceptKey = if (answered) sinkKey(sink) else null) }
        }
        emitPinReply(sink, reply)
    }

    /** A pin reply is bounded like a pin push: an undrainable outbox must not hold the transport's inline reader. */
    private suspend fun emitPinReply(sink: OutboundSink, reply: dev.ccpocket.protocol.ProjectPinsState) {
        kotlinx.coroutines.withTimeoutOrNull(dev.ccpocket.daemon.pins.ProjectPinService.DELIVERY_TIMEOUT_MS) { sink.emit(reply) }
    }

    /** Resolve a workdir the same way [OpenSession] does (the new-session popover ships `~` paths raw and
     *  claude keys transcript dirs by the REAL cwd) so both the session listing and the group store agree on
     *  one dir-key. An unresolvable path keeps the raw string (the same empty answer as before). */
    private fun groupWorkdir(workdir: String): String = dirs.validateWorkdir(workdir)?.toString() ?: workdir

    /**
     * Emit this [workdir]'s resumable-session list — the single reply to [ListSessions] AND the re-push after
     * every session-group mutation (issue #119). Resolves the workdir like [OpenSession] (else a raw `~/…`
     * listing scans a non-existent dir and answers EMPTY — desktop ⌘N regression), merges every backend's
     * sessions, marks the busy ones, and stamps the project's groups. A GUEST (issue #115) sees ONLY the
     * sessions IT started (visibility "by initiator") and no group headers.
     *
     * With a [lane] the reply is produced on that lane instead of the caller's loop ([listingLaneFor]) and
     * this returns as soon as it is queued.
     */
    private suspend fun emitSessions(workdir: String, sink: OutboundSink, guestScope: GuestScope?, caps: ClientCapsHolder? = null, lane: String? = null) {
        if (lane == null) return emitSessionsNow(workdir, sink, guestScope, caps)
        // send, not trySend: when the lane is full the caller waits its turn rather than jumping the queue
        listingLaneFor(lane).send {
            try {
                emitSessionsNow(workdir, sink, guestScope, caps)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // inline, the transport's own catch answers a failed request; out here nothing else would
                listingLog.warn("session list failed on its lane: ${e.message}")
                runCatching { sink.emit(PocketError("internal", e.message ?: "request failed")) }
            }
        }
    }

    private val listingLanes = java.util.concurrent.ConcurrentHashMap<String, Channel<suspend () -> Unit>>()

    /**
     * The lane a caller named with `listingLane`: its session-list replies are produced one at a time, in the
     * order they were asked for, on a coroutine of their own.
     *
     * Why a lane and not a plain `scope.launch` per reply: a project listing reads transcripts from disk, and
     * the relay leg has ONE reader for every paired device — produced inline, a slow scan holds every frame
     * from every device behind it. Launching each reply freely would fix that and break something else: the
     * re-push after a `GroupAssign` could overtake the `ListSessions` sent before it, and the client would end
     * up showing the older snapshot. On a lane every reply takes its snapshot when it RUNS, so each is at
     * least as fresh as the one before it, and they leave in that order.
     *
     * Only the scan and the emit move here. The mutation that precedes a re-push (a group edit, an archive
     * toggle) stays on the caller's loop, where it always was. A lane is never closed: one parked coroutine
     * per device that ever listed, bounded by the paired-device count. Its loop outlives anything a reply
     * throws — a dead consumer would fill the lane and then hold the caller's loop for good, which is the
     * very stall the lane exists to remove.
     */
    private fun listingLaneFor(key: String): Channel<suspend () -> Unit> = listingLanes.computeIfAbsent(key) {
        Channel<suspend () -> Unit>(LISTING_LANE_BACKLOG).also { replies ->
            scope.launch(Dispatchers.IO) {
                for (reply in replies) {
                    try {
                        reply()
                    } catch (t: Throwable) {
                        // our own cancellation ends the loop; a reply's own (a send timeout is a
                        // CancellationException too) does not
                        ensureActive()
                        listingLog.warn("session-list lane survived ${t::class.simpleName}: ${t.message}")
                    }
                }
            }
        }
    }

    private suspend fun emitSessionsNow(workdir: String, sink: OutboundSink, guestScope: GuestScope?, caps: ClientCapsHolder?) {
        val busy = registry.busySessionIds()
        val wd = groupWorkdir(workdir)
        var items = registry.listSessions(wd).map { if (it.sessionId in busy) it.copy(busy = true) else it }
        if (guestScope != null) items = items.filter { it.sessionId in guestScope.ownedSessions }
        // archived rows are filtered HERE, not client-side (issue #202): the desktop's RECENT snapshot holds
        // rows for projects it isn't currently listing and could never re-filter them, and doing it daemon-side
        // means even an old app gets the tidied list. One store load per listing, then O(1) per row.
        val archived = SessionArchive.archivedIds(wd, archiveFile)
        if (archived.isNotEmpty()) items = items.filter { it.sessionId !in archived }
        // wire-compat (ClientCaps): an undeclared client would drop this WHOLE frame on one opencode/kimi row
        items = items.filter { capsAllow(caps, it.agent) }
        items = items.map { if (it.firstPrompt.length > SESSION_PROMPT_CLIP) it.copy(firstPrompt = it.firstPrompt.take(SESSION_PROMPT_CLIP)) else it }
        val groups = if (guestScope != null) null else SessionGroups.groupsFor(wd)
        // renameSupported (issue #158) / archiveSupported (issue #202): owner-only — a guest's frame is
        // capability-denied anyway, so its client must not show the entry
        sink.emit(
            Sessions(
                workdir, items, groups = groups,
                renameSupported = guestScope == null, archiveSupported = guestScope == null,
            ),
        )
    }

    /**
     * Emit EVERY archived session across all projects (issue #202) — the cross-project archive view.
     * Bounded by the store itself: [SessionArchive.all] is exactly the projects that hold an archived
     * session, so this re-lists those and no others (never a walk of every project on the machine).
     * Still a multi-directory scan, so callers run it OFF the inbound pump, like FetchUsage.
     */
    private suspend fun emitArchivedSessions(sink: OutboundSink, caps: ClientCapsHolder? = null) {
        val busy = registry.busySessionIds()
        var rows = SessionArchive.all(archiveFile).flatMap { entry ->
            registry.listSessions(entry.workdir)
                .filter { it.sessionId in entry.sessions }
                .map { (if (it.sessionId in busy) it.copy(busy = true) else it) to (entry.sessions[it.sessionId] ?: 0L) }
        }.sortedByDescending { it.second }.map { it.first }
        rows = rows.filter { capsAllow(caps, it.agent) }
        // Bound the FRAME, not just the row count. firstPrompt is untruncated and can be huge (a skill
        // injection has produced ~800KB single messages here), and unlike a per-project Sessions frame this
        // one aggregates the whole machine — blowing the relay's 4MB cap would drop the socket, and the
        // archive screen re-fetches on every open, so it would loop. The archive view renders title + cwd
        // and never reads firstPrompt, so clipping it costs nothing.
        sink.emit(
            ArchivedSessions(
                rows.take(MAX_ARCHIVE_ROWS).map { it.copy(firstPrompt = it.firstPrompt.take(ARCHIVE_PROMPT_CLIP)) },
            ),
        )
    }

    // ── ClientCaps filters: strip agent=OPENCODE rows for peers that never declared support, so an
    // already-shipped build (unknown-enum decode = whole-frame drop) keeps its claude/codex lists ──


    private fun filterSchedule(state: ScheduleState, caps: ClientCapsHolder?): ScheduleState =
        if (state.items.all { capsAllow(caps, it.agent) }) state
        else state.copy(items = state.items.filter { capsAllow(caps, it.agent) })

    /**
     * The project list a GUEST sees (issue #115): ONLY the shared root(s) — each stamped with the origin
     * label + expiry + tier for the "Shared" row — and never any of the owner's other project folders. The
     * live-session enrichment is filtered to the guest's OWN sessions, so the owner's activity under the
     * same root never leaks into the guest's row. A root with no history yet still appears (the guest can
     * start there), so the shared folder shows up the moment the guest joins. [caps] gates opencode-only
     * rows exactly like the owner path (issue #184 mechanism ②).
     */
    private suspend fun scopedDirectories(scope: GuestScope, caps: ClientCapsHolder?): List<DirectoryEntry> {
        val all = dirs.listDirectories(null, registry.busyCwds(), registry.liveByCwd(), includeOpencode = caps?.supportsOpencode == true, includeKimi = caps?.supportsKimi == true, includeZcode = caps?.supportsZcode == true, includeDsh = caps?.supportsDsh == true)
        val underScope = all
            .filter { e -> PathScope.contains(scope.roots, e.path) }
            .map { it.stampShare(scope) }
        // ensure each shared root itself is present even with no transcript history under it yet
        val present = underScope.mapNotNullTo(HashSet()) { PathScope.canonical(it.path) }
        val bareRoots = scope.roots
            .filter { it !in present }
            .map { root ->
                DirectoryEntry(path = root, name = java.io.File(root).name.ifEmpty { root }, isDir = true, hasSessions = false)
                    .stampShare(scope)
            }
        return (bareRoots + underScope).sortedByDescending { it.lastModified }
    }

    /** Stamp a guest's shared-folder row: the origin/expiry/tier badges, and filter the live-session
     *  enrichment down to sessions the guest owns (the owner's live sessions under the same root are hidden). */
    private fun DirectoryEntry.stampShare(scope: GuestScope): DirectoryEntry {
        val mine: List<ActiveSession> = activeSessions.filter { it.sessionId in scope.ownedSessions }
        val first = mine.firstOrNull()
        return copy(
            sharedBy = scope.label, shareExpiresAt = scope.expiresAt, shareTier = scope.tier,
            activeSessions = mine,
            open = mine.isNotEmpty(),
            executing = mine.any { it.executing },
            busy = mine.any { it.busy },
            activeSessionId = first?.sessionId,
            activeSessionTitle = first?.title,
            gitBranch = first?.gitBranch,
        )
    }
}

/** A FULL-POWER owner caller: no restricted credential is present — no bridge/share [origin] and no guest
 *  [guestScope]. Spelled out once so every owner-only op tests the same thing. */
internal fun isOwner(origin: String?, guestScope: GuestScope?) =
    origin == null && guestScope == null
