package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.ApprovalTimeout
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.claude.ClaudeModelService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.codex.CodexModelService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SessionFilesService
import dev.ccpocket.daemon.disk.SessionGroups
import dev.ccpocket.daemon.disk.SkillCatalogService
import dev.ccpocket.daemon.disk.UsageService
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.handoff.HandoffGuard
import dev.ccpocket.daemon.handoff.HandoffRegistry
import dev.ccpocket.daemon.handoff.HandoffService
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
import dev.ccpocket.protocol.AGENT_WIRE_OPENCODE
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.AuthLogin
import dev.ccpocket.protocol.AuthLoginCancel
import dev.ccpocket.protocol.AuthLoginCode
import dev.ccpocket.protocol.AuthLogout
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.CancelHandoff
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CompleteHandoff
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.GetWorkflowAgentDetail
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.RecallHandoff
import dev.ccpocket.protocol.ReturnHandoff
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
import dev.ccpocket.protocol.FileUploadCancel
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.protocol.GroupCreate
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPendingApprovals
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
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
import dev.ccpocket.protocol.ShellResult
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.SwitchServiceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Maps an inbound [Frame] to the registry/services. Returns fast; turns run on conversation scopes. */
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
    private val codexModels: CodexModelService = CodexModelService(),
    private val claudeModels: ClaudeModelService = ClaudeModelService(),
    // the daemon-wide pending-approval ledger (approval design M1): the single verdict routing point;
    // defaulted so router tests that never touch approvals need no wiring
    private val approvals: dev.ccpocket.daemon.approval.ApprovalCoordinator =
        dev.ccpocket.daemon.approval.ApprovalCoordinator(scope),
    private val grants: dev.ccpocket.daemon.approval.ApprovalGrantStore =
        dev.ccpocket.daemon.approval.ApprovalGrantStore(),
    private val approvalHistory: dev.ccpocket.daemon.approval.ApprovalHistoryStore? = null,
) {
    /** One connection's declared wire vocabulary (see [ClientCaps] in Messages.kt). Mutable: the
     *  declaration frame lands after connect and upgrades the SAME holder the ingress created for
     *  the connection. Default (no declaration, or a legacy ingress passing null) = filter — an
     *  already-shipped client hard-fails the whole Envelope on an unknown [AgentKind], so opencode
     *  rows must never reach a peer that didn't declare them. */
    class ClientCapsHolder {
        @Volatile var supportsOpencode: Boolean = false

        /** §18.2 P2-3: the client decodes the approval-V2 frame types. The INGRESS sinks consult this to
         *  drop [AuthorizedActionRecorded]/[PermissionRiskUpdated] for undeclared peers — old clients
         *  would drop the unknown types anyway, but gating keeps the wire quiet and the contract real. */
        @Volatile var supportsApprovalV2: Boolean = false
    }

    companion object {
        /** The device identity for callers with no transport-authenticated id: the plaintext `--local`
         *  dev socket and trusted in-process callers. One machine-local pseudo-device, so the handoff
         *  gate still arbitrates it (it is never a lease holder unless it accepted a handoff itself). */
        const val LOCAL_DEVICE_ID = "local"


        /** §18.2 P2-3: frames only an approvalV2-declaring client should receive — ingress sinks drop
         *  them for undeclared peers instead of relying on the client's unknown-type tolerance. */
        fun approvalV2Only(frame: Frame): Boolean =
            frame is dev.ccpocket.protocol.AuthorizedActionRecorded || frame is dev.ccpocket.protocol.PermissionRiskUpdated

        /** Per-connection/device gate: one modern client must never opt a sibling legacy client in. */
        fun allowedForCaps(frame: Frame, caps: ClientCapsHolder): Boolean =
            !approvalV2Only(frame) || caps.supportsApprovalV2

        /**
         * [DirectoryEntry] rows themselves are agent-free; only their [DirectoryEntry.activeSessions]
         * enrichment carries [AgentKind] — strip the opencode entries, keep the row.
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
            if (caps?.supportsOpencode == true) entries
            else entries.map { e ->
                if (e.activeSessions.none { it.agent == AgentKind.OPENCODE }) return@map e
                val kept = e.activeSessions.filter { it.agent != AgentKind.OPENCODE }
                val first = kept.firstOrNull()
                e.copy(
                    activeSessions = kept,
                    // derive from what SURVIVED — never from the stripped-out session
                    open = first != null,
                    executing = kept.any { it.executing },
                    activeSessionId = first?.sessionId,
                    activeSessionTitle = first?.title,
                    gitBranch = first?.gitBranch,
                )
            }
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
    // [deviceId] (SESSION-HANDOFF.md §5.3): the TRANSPORT-authenticated identity of the sender — the relay
    // ingress passes the Noise-proven deviceId, the gated LAN path its hello'd device — NEVER a frame field.
    // It drives the handoff controller gate and stamps handoff mutations; null (plaintext --local dev mode /
    // in-process callers) falls back to [LOCAL_DEVICE_ID].
    // [collabScope] (SESSION-HANDOFF.md §4.1) is non-null ONLY for a COLLABORATOR link credential, whose
    // frame was already vetted by CollaboratorGuard at the ingress: it restricts the handoff plane to the
    // device's OWN offers (accept/decline/return + a filtered listing), denies every owner-side handoff
    // op, and carries the vetted OpenSession's path scope into the conversation's PermissionBridge.
    suspend fun handle(frame: Frame, sink: OutboundSink, origin: String? = null, guestScope: GuestScope? = null, caps: ClientCapsHolder? = null, bridgeAllowedCommands: List<String> = emptyList(), ownerBypass: Boolean = false, deviceId: String? = null, collabScope: CollaboratorScope? = null, onOpened: suspend (String) -> Unit = {}) {
        val dev = deviceId ?: LOCAL_DEVICE_ID
        when (frame) {
            // capability declaration (wire-compat gate for AgentKind additions) — no reply; the very
            // next list request answers unfiltered. Ingress handlers may process frames concurrently,
            // so a burst's first list can still race the declaration: worst case one filtered snapshot,
            // corrected by the client's next fetch.
            is ClientCaps -> {
                caps?.supportsOpencode = AGENT_WIRE_OPENCODE in frame.supportsAgents
                caps?.supportsApprovalV2 = frame.supportsApprovalV2 // P2-3: gates the V2 approval frames
            }

            is ListDirectories ->
                if (guestScope != null) sink.emit(Directories(filterDirs(scopedDirectories(guestScope, caps), caps)))
                else sink.emit(Directories(filterDirs(dirs.listDirectories(frame.root, registry.busyCwds(), registry.liveByCwd(), includeOpencode = caps?.supportsOpencode == true), caps)))

            // Owner control-plane pull: push is alert-only, so every foreground client can reconstruct the
            // complete queue even if APNs/FCM was delayed or lost. Restricted credentials must never learn
            // another user's approvals; GuestCaps/BridgeGuard deny this frame and this check is defence in depth.
            is ListPendingApprovals -> if (origin == null && guestScope == null) {
                sink.emit(PendingApprovals(registry.pendingApprovals(shell.pendingApprovals() + exports.pendingApprovals())))
            }

            is ListSessions -> emitSessions(frame.workdir, sink, guestScope, caps)

            // session groups (issue #119): mutate the daemon-side group store, then re-push this workdir's
            // session list so the grouping change reflects immediately (same response path as ListSessions).
            // A GUEST can't manage groups (they belong to the owner's project view) — silently no-op the
            // mutation but still answer with the (re-filtered) list so the client isn't left hanging.
            is GroupCreate -> {
                if (guestScope == null) SessionGroups.create(groupWorkdir(frame.workdir), frame.name)
                emitSessions(frame.workdir, sink, guestScope, caps)
            }
            is GroupRename -> {
                if (guestScope == null) SessionGroups.rename(groupWorkdir(frame.workdir), frame.groupId, frame.name)
                emitSessions(frame.workdir, sink, guestScope, caps)
            }
            is GroupDelete -> {
                if (guestScope == null) SessionGroups.delete(groupWorkdir(frame.workdir), frame.groupId)
                emitSessions(frame.workdir, sink, guestScope, caps)
            }
            is GroupAssign -> {
                if (guestScope == null) SessionGroups.assign(groupWorkdir(frame.workdir), frame.sessionId, frame.groupId)
                emitSessions(frame.workdir, sink, guestScope, caps)
            }


            // session rename (issue #158): lands claude's own custom-title record (live daemon session:
            // the CLI appends it itself over a control_request; idle: a one-line transcript append) —
            // an agent-ack/disk round-trip → off the inbound pump like FetchUsage. Success answers with
            // the re-pushed Sessions (the group ops' refresh contract); failure with a PocketError. A
            // guest never reaches here (GuestCaps default-denies the frame type at the choke point) —
            // the null-check is belt-and-suspenders like the group mutations', answering with the list.
            is RenameSession -> scope.launch {
                if (guestScope != null) { emitSessions(frame.workdir, sink, guestScope, caps); return@launch }
                val err = registry.renameSession(groupWorkdir(frame.workdir), frame.sessionId, frame.title)
                if (err == null) emitSessions(frame.workdir, sink, guestScope, caps)
                else sink.emit(PocketError("rename_failed", err))
            }

            // heavy transcript scan → off the inbound pump so it can't wedge the socket
            is FetchUsage -> scope.launch { sink.emit(UsageService.aggregate(frame.days)) }

            // installed skills/plugins browse page (issue #132): a disk scan → off the inbound pump like
            // FetchUsage. Guests never reach here (GuestCaps denies the frame type at the choke point).
            is FetchSkillCatalog -> scope.launch {
                sink.emit(SkillCatalogService.build(frame.workdir?.let { dirs.validateWorkdir(it) }))
            }

            // both re-scan the transcript from disk (issue #36) → same off-pump rule as FetchUsage
            is ListSessionFiles -> scope.launch {
                sink.emit(SessionFiles(frame.workdir, frame.sessionId, SessionFilesService.changedFiles(frame.agent, frame.workdir, frame.sessionId)))
            }
            // serves any path canonically inside the workdir (issue #133) and, for a client that opted in,
            // streams over-cap binaries as FileContentChunk frames (issue #134)
            is ReadFile -> scope.launch {
                SessionFilesService.streamFile(frame.agent, frame.workdir, frame.sessionId, frame.path, frame.allowChunks, sink::emit)
            }
            is ReadFileDiff -> scope.launch {
                sink.emit(SessionFilesService.fileDiff(frame.agent, frame.workdir, frame.sessionId, frame.path))
            }
            // approval-gated export of a file the session did NOT change (issue #67 v2 / #79). MUST launch,
            // not await — like RunShellCommand below, it suspends on the human approval gate, and the mode
            // comes from the daemon's own registry so the gate can't be spoofed client-side.
            is ExportFile -> scope.launch {
                exports.run(frame, registry.modeOf(frame.convoId), sink::emit)
            }
            // composer @-file completion (issue #75): a directory scan → off the inbound pump like the others
            is ListPathEntries -> scope.launch {
                val res = dirs.listPathEntries(frame.workdir, frame.subPath, frame.limit)
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
                    (guestScope != null || origin != null) && frame.agent == AgentKind.OPENCODE ->
                        sink.emit(PocketError("share_forbidden", "OpenCode sessions are not available over shared/bridge access (no enforceable approval channel)"))
                    guestScope != null && !PathScope.contains(guestScope.roots, wd.toString()) ->
                        sink.emit(PocketError("share_out_of_scope", "that folder is outside your shared folder"))
                    else -> {
                        dirs.noteRecent(wd.toString())
                        // pathScope = the guest's roots (issue #115 §4) or a collaborator grant's
                        // workdir+allowedRoots (SESSION-HANDOFF.md §8.3) → the conversation's
                        // PermissionBridge denies any Read/Write/Edit outside them. Null for an owner.
                        val convoId = registry.open(
                            frame.copy(workdir = wd.toString()), sink, origin,
                            pathScope = guestScope?.roots ?: collabScope?.pathScope?.takeIf { it.isNotEmpty() },
                            // non-null exactly for a COLLABORATOR open: the grant's operation ceiling.
                            // Keys BOTH crypto MUST-FIX halves in registry.open — the hot→cold rebuild
                            // (a reattach must not drop the grant walls) and the PermissionBridge's
                            // REVIEW write-tool refusal (SESSION-HANDOFF.md §8.3).
                            handoffAccess = collabScope?.access,
                            // null caps (legacy ingress / bridges) = undeclared, same as everywhere else here
                            peerSupportsOpencode = caps?.supportsOpencode == true,
                            bridgeAllowedCommands = bridgeAllowedCommands,
                            ownerBypass = ownerBypass, // trusted in-process open flag ⇒ owner's own session
                        )
                        if (convoId.isNotEmpty()) onOpened(convoId) // "" = backend unavailable (PocketError already sent)
                    }
                }
            }

            // handoff drive gate (SESSION-HANDOFF.md §5.3 items 2/3): every input-shaped frame checks the
            // controller lease FIRST — WAITING denies everyone, IN_PROGRESS only the lease-holding
            // recipient drives. A Deny maps to a PocketError so the client can show why (never silence).
            is SendPrompt -> when (val deny = registry.driveDenied(frame.convoId, dev)) {
                null -> if (!registry.sendPrompt(frame)) sink.emit(SessionGone(frame.convoId))
                else -> sink.emit(handoffDenied(deny, frame.convoId))
            }
            // Verdicts pass the handoff drive gate first (question answers ride this same frame, so the
            // lease covers them too), then resolve at ONE routing point — agent tool ask, bridge request
            // approval, quick-shell command, file export — by (convoId, askId) in the ApprovalCoordinator
            // (approval design M1), instead of being try-offered to each service's private pending map.
            // An unknown/expired askId answers the TAPPING device honestly (issue #100): its optimistic
            // card-clear must not read as success.
            is PermissionVerdict -> when (val deny = registry.driveDenied(frame.convoId, dev)) {
                null -> if (!approvals.onVerdict(frame)) {
                    sink.emit(PocketError("ask_expired", "That approval expired before it reached your computer — ask the agent to try the action again.", frame.convoId))
                }
                else -> sink.emit(handoffDenied(deny, frame.convoId))
            }
            is SwitchMode -> registry.switchMode(frame)
            is SwitchServiceTier -> registry.switchServiceTier(frame)
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
            is CancelTurn -> when (val deny = registry.driveDenied(frame.convoId, dev)) {
                null -> registry.cancelTurn(frame)
                else -> sink.emit(handoffDenied(deny, frame.convoId))
            }
            // task panel "stop" (issue #80): interrupt the agent's work for this job + settle its row killed
            is StopBackgroundJob -> registry.stopBackgroundJob(frame)
            // workflow detail sheet (issue #106): read one agent's full prompt/return off disk —
            // a transcript parse, so off the inbound loop like FetchUsage
            is GetWorkflowAgentDetail -> scope.launch { registry.fetchWorkflowAgentDetail(frame) }
            // older-history page (issue #147): a transcript parse → off the inbound pump; answered to
            // the requesting sink only (never fanned out to other attached clients)
            is FetchHistoryPage -> scope.launch { registry.fetchHistoryPage(frame, sink) }

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
                sink.emit(ApprovalPrefs(prefs.askNoAutoDeny))
            }

            // agent model listing: inspect the Mac daemon's local agent config/cache.
            // On IO, not the shared Default pool: the Claude path may make a blocking HTTP call to the
            // user's gateway (#167 ②), and a gateway that accepts the connection but never answers would
            // otherwise pin a core-count-limited thread that the session pumps and scheduler share.
            is FetchModels -> scope.launch(Dispatchers.IO) {
                sink.emit(when (frame.agent) {
                    AgentKind.OPENCODE -> openCodeModels.fetch()
                    AgentKind.CODEX -> codexModels.fetch()
                    AgentKind.CLAUDE -> claudeModels.fetch(frame.workdir)
                })
            }

            // ---- Session Handoff control frames (SESSION-HANDOFF.md §9.1). Owner-plane except for the
            // recipient-side trio (accept/decline/return) + a filtered listing, which a COLLABORATOR link
            // credential may use for ITS OWN offers only (§4.1): bridges/guests never reach here (their
            // ingress caps default-deny), the origin/guestScope re-check below is defence in depth, and a
            // collaborator caller is marked by [collabScope] (its ingress already passed CollaboratorCaps
            // + CollaboratorGuard). The executing device identity is ALWAYS the transport's [dev].
            is CreateHandoff -> {
                val svc = registry.handoffs
                when {
                    svc == null -> sink.emit(HandoffCreated(ok = false, error = "handoffs are not available on this daemon"))
                    origin != null || guestScope != null || collabScope != null ->
                        sink.emit(PocketError("handoff_forbidden", "not permitted for a restricted credential"))
                    else -> {
                        // §4.1 preconditions live on the registry (it owns the live conversation state)
                        val blocker = registry.handoffBlocker(frame.sessionId)
                        val contacts = svc.collaborators
                        val recipient = frame.recipientDeviceId
                        if (blocker != null) sink.emit(HandoffCreated(ok = false, error = blocker))
                        // recipient binding (§4.2 step 7): when the contact ledger is available, the named
                        // device must be a live (non-removed, credential-backed) collaborator — a dead link
                        // must fail the send, not mint an offer nobody can ever accept. With no ledger
                        // (dev/local mode) the binding passes through and is still enforced at accept.
                        else if (recipient != null && contacts != null && !contacts.isActive(recipient)) {
                            sink.emit(HandoffCreated(ok = false, error = "that collaborator link is gone — reconnect before handing off"))
                        } else when (
                            val out = svc.registry.create(
                                sourceSessionId = frame.sessionId,
                                // resolve like OpenSession/ListSessions so the durable binding uses the real cwd
                                workdir = groupWorkdir(frame.workdir),
                                agent = frame.agent,
                                initiatorDeviceId = dev,
                                kind = frame.kind,
                                access = frame.access,
                                brief = frame.brief,
                                allowedRoots = frame.allowedRoots,
                                expiresInSec = frame.expiresInSec,
                                // TODO: initiatorLabel could carry the daemon hostname / device label once
                                // the router learns it; sourceEventSeq (the transcript cursor) is not on the
                                // wire CreateHandoff yet — left 0 (recipient replays the full window).
                                recipientLabel = frame.recipientLabel ?: recipient?.let { contacts?.labelOf(it) },
                                sourceConvoId = frame.sourceConvoId,
                                recipientDeviceId = recipient,
                            )
                        ) {
                            is HandoffRegistry.HandoffOutcome.Ok -> {
                                sink.emit(HandoffCreated(ok = true, handoff = out.handoff))
                                svc.broadcast(listOf(out.handoff)) // includes the bound recipient's sink, if attached
                                recipient?.let { contacts?.noteHandoff(it, out.handoff.createdAt) } // stats + CollaboratorUpdated
                                // §4.2 step 9 / §3.4: nudge an OFFLINE bound recipient with a CONTENT-FREE,
                                // device-TARGETED push. Self-gating (WAITING + bound + the relay can even
                                // deliver one) lives in the service; when any of that is missing the offer
                                // still arrives on the recipient's next connect/foreground pull.
                                svc.announceOffer(out.handoff)
                                svc.reconcile() // announce anything the create's internal sweep settled
                            }
                            // the refusal's machine-readable code rides along (§6: an unimplemented
                            // kind/access combination answers `handoff_not_supported`, not just prose)
                            is HandoffRegistry.HandoffOutcome.Refused ->
                                sink.emit(HandoffCreated(ok = false, error = out.message, code = out.code))
                        }
                    }
                }
            }
            is ListHandoffs -> {
                val svc = registry.handoffs
                when {
                    svc == null -> sink.emit(HandoffListing())
                    origin != null || guestScope != null -> sink.emit(PocketError("handoff_forbidden", "not permitted for a restricted credential"))
                    else -> {
                        var items = svc.registry.list(frame.workdir?.let { groupWorkdir(it) }, frame.sessionId)
                        // a COLLABORATOR credential sees ONLY handoffs addressed to its own device (§4.1:
                        // offers + their history — never the owner's other handoffs); owners see everything
                        if (collabScope != null) items = items.filter { it.recipientDeviceId == collabScope.deviceId }
                        sink.emit(HandoffListing(items))
                    }
                }
            }
            is AcceptHandoff -> handoffMutation(sink, origin, guestScope, collabScope, recipientSide = true, handoffId = frame.handoffId) {
                // a collaborator's accept stamps its contact label (owner devices resolve to null → the
                // registry keeps the initiator's chosen recipientLabel)
                it.accept(frame.handoffId, dev, deviceLabel = registry.handoffs?.collaborators?.labelOf(dev))
            }
            is DeclineHandoff -> handoffMutation(sink, origin, guestScope, collabScope, recipientSide = true, handoffId = frame.handoffId) { it.decline(frame.handoffId, dev, frame.reason) }
            is CancelHandoff -> handoffMutation(sink, origin, guestScope, collabScope) { it.cancel(frame.handoffId, dev) }
            // GRACEFUL RECALL (SESSION-HANDOFF.md §5.4): an idle session settles RECALLED at once; with a
            // turn EXECUTING the daemon arms the hand-back (nobody may drive from this instant), pushes
            // the recallPending row to both sides, then interrupts + waits for the stable point OFF this
            // pump — awaiting it inline would wedge the whole socket for the length of the turn.
            is RecallHandoff -> {
                val svc = registry.handoffs
                when {
                    svc == null -> sink.emit(PocketError("handoff_unavailable", "handoffs are not available on this daemon"))
                    origin != null || guestScope != null -> sink.emit(PocketError("handoff_forbidden", "not permitted for a restricted credential"))
                    collabScope != null -> sink.emit(PocketError("handoff_forbidden", "not permitted for a collaborator link"))
                    else -> when (val out = svc.beginRecall(frame.handoffId, dev)) {
                        is HandoffService.RecallOutcome.Refused -> {
                            sink.emit(PocketError(handoffCode(out.code), out.message))
                            svc.reconcile() // the refusal's internal sweep may have settled the row itself
                        }
                        is HandoffService.RecallOutcome.Settled -> {
                            sink.emit(HandoffUpdated(out.handoff))
                            svc.broadcast(listOf(out.handoff))
                            svc.reconcile()
                        }
                        is HandoffService.RecallOutcome.Pending -> {
                            // both sides learn the recall is in flight NOW (the initiator waits instead of
                            // typing, the recipient sees control being taken back); the terminal RECALLED
                            // arrives as a second HandoffUpdated when the turn actually stops.
                            sink.emit(HandoffUpdated(out.handoff))
                            svc.broadcast(listOf(out.handoff))
                            scope.launch { svc.settleRecall(frame.handoffId) }
                        }
                    }
                }
            }
            is ReturnHandoff -> handoffMutation(sink, origin, guestScope, collabScope, recipientSide = true, handoffId = frame.handoffId) { it.returnHandoff(frame.handoffId, dev, frame.result) }
            is CompleteHandoff -> handoffMutation(sink, origin, guestScope, collabScope) { it.complete(frame.handoffId, dev) }

            else -> sink.emit(PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"))
        }
    }

    /** A [HandoffGuard.Verdict.Deny] as the wire error the App keys on: code `handoff_<reason>`
     *  (e.g. `handoff_waiting_locked`, `handoff_not_controller`), message = the guard's client copy. */
    private fun handoffDenied(deny: HandoffGuard.Verdict.Deny, convoId: String?) =
        PocketError("handoff_${deny.reason.name.lowercase()}", deny.message, convoId)

    /**
     * One handoff state-machine mutation through the router: run [op] against the registry, answer the
     * caller with [HandoffUpdated] (success) or a `handoff_*`-coded [PocketError] (refusal), and fan the
     * transition out to every other attached client. [reconcile] runs after a success so transitions the
     * mutation's internal sweep settled (an expiry racing an accept) are announced too, not lost.
     */
    private suspend fun handoffMutation(
        sink: OutboundSink,
        origin: String?,
        guestScope: GuestScope?,
        collab: CollaboratorScope? = null,
        /** True for the transitions a bound RECIPIENT may drive (accept/decline/return); false for the
         *  initiator-side ones (cancel/recall/complete), which a collaborator may never touch. */
        recipientSide: Boolean = false,
        /** The targeted handoff — required to enforce a collaborator's own-offer binding. */
        handoffId: String? = null,
        op: (HandoffRegistry) -> HandoffRegistry.HandoffOutcome,
    ) {
        val svc = registry.handoffs
        if (svc == null) { sink.emit(PocketError("handoff_unavailable", "handoffs are not available on this daemon")); return }
        if (origin != null || guestScope != null) { sink.emit(PocketError("handoff_forbidden", "not permitted for a restricted credential")); return }
        if (collab != null) {
            if (!recipientSide) { sink.emit(PocketError("handoff_forbidden", "not permitted for a collaborator link")); return }
            // own-offer binding (§4.1): a collaborator may act ONLY on a handoff addressed to its own
            // device — an unbound (open-invite) or foreign handoff is refused before the state machine
            // runs. "Doesn't exist" and "not yours" share one answer on purpose (no probe oracle).
            val h = handoffId?.let { svc.registry.byId(it) }
            if (h?.recipientDeviceId != collab.deviceId) {
                sink.emit(PocketError("handoff_not_allowed", "this handoff is not addressed to you")); return
            }
        }
        when (val out = op(svc.registry)) {
            is HandoffRegistry.HandoffOutcome.Ok -> {
                sink.emit(HandoffUpdated(out.handoff))
                svc.broadcast(listOf(out.handoff))
                svc.reconcile()
            }
            is HandoffRegistry.HandoffOutcome.Refused -> sink.emit(PocketError(handoffCode(out.code), out.message))
        }
    }

    /** Registry refusal code → wire error code: already-namespaced codes (`handoff_not_supported`) ride
     *  through, bare ones (`not_found`, `not_allowed`) get the `handoff_` prefix the App keys on. */
    private fun handoffCode(code: String) = if (code.startsWith("handoff")) code else "handoff_$code"

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
     */
    private suspend fun emitSessions(workdir: String, sink: OutboundSink, guestScope: GuestScope?, caps: ClientCapsHolder? = null) {
        val busy = registry.busySessionIds()
        val wd = groupWorkdir(workdir)
        var items = registry.listSessions(wd).map { if (it.sessionId in busy) it.copy(busy = true) else it }
        if (guestScope != null) items = items.filter { it.sessionId in guestScope.ownedSessions }
        // wire-compat (ClientCaps): an undeclared client would drop this WHOLE frame on one opencode row
        if (caps?.supportsOpencode != true) items = items.filter { it.agent != AgentKind.OPENCODE }
        val groups = if (guestScope != null) null else SessionGroups.groupsFor(wd)
        // renameSupported (issue #158): owner-only — a guest's RenameSession is capability-denied anyway,
        // so its client must not show the entry
        sink.emit(Sessions(workdir, items, groups = groups, renameSupported = guestScope == null))
    }


    // ── ClientCaps filters: strip agent=OPENCODE rows for peers that never declared support, so an
    // already-shipped build (unknown-enum decode = whole-frame drop) keeps its claude/codex lists ──


    private fun filterSchedule(state: ScheduleState, caps: ClientCapsHolder?): ScheduleState =
        if (caps?.supportsOpencode == true || state.items.none { it.agent == AgentKind.OPENCODE }) state
        else state.copy(items = state.items.filter { it.agent != AgentKind.OPENCODE })

    /**
     * The project list a GUEST sees (issue #115): ONLY the shared root(s) — each stamped with the origin
     * label + expiry + tier for the "Shared" row — and never any of the owner's other project folders. The
     * live-session enrichment is filtered to the guest's OWN sessions, so the owner's activity under the
     * same root never leaks into the guest's row. A root with no history yet still appears (the guest can
     * start there), so the shared folder shows up the moment the guest joins. [caps] gates opencode-only
     * rows exactly like the owner path (issue #184 mechanism ②).
     */
    private suspend fun scopedDirectories(scope: GuestScope, caps: ClientCapsHolder?): List<DirectoryEntry> {
        val all = dirs.listDirectories(null, registry.busyCwds(), registry.liveByCwd(), includeOpencode = caps?.supportsOpencode == true)
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
