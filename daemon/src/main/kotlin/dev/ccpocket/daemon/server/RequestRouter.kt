package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
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
import dev.ccpocket.daemon.opencode.OpenCodeModelService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ActivatePreset
import dev.ccpocket.protocol.ActiveSession
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
import dev.ccpocket.protocol.FileUploadCancel
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.protocol.GroupCreate
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.RenameSession
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ScheduleCancel
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleList
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.SetPushPrefs
import dev.ccpocket.protocol.ShellResult
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode
import kotlinx.coroutines.CoroutineScope
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
) {
    /** One connection's declared wire vocabulary (see [ClientCaps] in Messages.kt). Mutable: the
     *  declaration frame lands after connect and upgrades the SAME holder the ingress created for
     *  the connection. Default (no declaration, or a legacy ingress passing null) = filter — an
     *  already-shipped client hard-fails the whole Envelope on an unknown [AgentKind], so opencode
     *  rows must never reach a peer that didn't declare them. */
    class ClientCapsHolder {
        @Volatile var supportsOpencode: Boolean = false
    }

    /** [origin] names the restricted credential this frame arrived from (issue #91 bridge / #115 guest) —
     *  null for every interactive owner client. [guestScope] (issue #115) is non-null ONLY for a GUEST:
     *  it clamps the project/session VISIBILITY to the shared root + the guest's own sessions, and rides
     *  into [SessionRegistry.open] as the conversation's tool path guard. [caps] is the connection's
     *  capability holder — null (legacy ingress / bridges) filters like an undeclared client. */
    suspend fun handle(frame: Frame, sink: OutboundSink, origin: String? = null, guestScope: GuestScope? = null, caps: ClientCapsHolder? = null, onOpened: suspend (String) -> Unit = {}) {
        when (frame) {
            // capability declaration (wire-compat gate for AgentKind additions) — no reply; the very
            // next list request answers unfiltered. Ingress handlers may process frames concurrently,
            // so a burst's first list can still race the declaration: worst case one filtered snapshot,
            // corrected by the client's next fetch.
            is ClientCaps -> caps?.supportsOpencode = AGENT_WIRE_OPENCODE in frame.supportsAgents

            is ListDirectories ->
                if (guestScope != null) sink.emit(Directories(filterDirs(scopedDirectories(guestScope), caps)))
                else sink.emit(Directories(filterDirs(dirs.listDirectories(frame.root, registry.busyCwds(), registry.liveByCwd()), caps)))

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
                sink.emit(
                    PathEntries(
                        workdir = frame.workdir,
                        subPath = frame.subPath,
                        entries = res?.first ?: emptyList(),
                        truncated = res?.second ?: false,
                        ok = res != null,
                        error = if (res == null) "not a readable directory" else null,
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
                        // pathScope = the guest's roots → the conversation's PermissionBridge denies any
                        // Read/Write/Edit whose target lands outside them (issue #115 §4). Null for an owner.
                        val convoId = registry.open(frame.copy(workdir = wd.toString()), sink, origin, pathScope = guestScope?.roots)
                        if (convoId.isNotEmpty()) onOpened(convoId) // "" = backend unavailable (PocketError already sent)
                    }
                }
            }

            is SendPrompt -> if (!registry.sendPrompt(frame)) sink.emit(SessionGone(frame.convoId))
            // a verdict may resolve a SHELL ask (issue #3), an EXPORT ask (issue #67 v2), or an agent tool
            // ask — each service claims its own by askId (pending-map membership) before the registry
            is PermissionVerdict -> if (!shell.onVerdict(frame) && !exports.onVerdict(frame)) registry.verdict(frame)
            is SwitchMode -> registry.switchMode(frame)
            is ClearAllowRule -> registry.clearRule(frame)

            // MUST launch, not await: shell.run suspends on the human approval gate, but the relay transport
            // pumps inbound frames sequentially & inline — awaiting here would wedge the whole socket (for every
            // device/convo) until the verdict, which itself can't be read while we block. Fire it on the daemon
            // scope so the loop stays free to deliver that verdict.
            is RunShellCommand -> scope.launch {
                val wd = dirs.validateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(ShellResult(frame.convoId, frame.command, exitCode = -1, error = "not a readable directory: ${frame.workdir}"))
                } else {
                    // the daemon (not the phone) decides the mode → the approval gate can't be spoofed client-side
                    shell.run(frame.copy(workdir = wd.toString()), registry.modeOf(frame.convoId), sink::emit)
                }
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

            // agent model listing: inspect the Mac daemon's local agent config/cache.
            is FetchModels -> scope.launch {
                sink.emit(when (frame.agent) {
                    AgentKind.OPENCODE -> openCodeModels.fetch()
                    AgentKind.CODEX -> codexModels.fetch()
                    AgentKind.CLAUDE -> claudeModels.fetch(frame.workdir)
                })
            }

            else -> sink.emit(PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"))
        }
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

    /** [DirectoryEntry] rows themselves are agent-free; only their [DirectoryEntry.activeSessions]
     *  enrichment carries [AgentKind] — strip the opencode entries, keep the row. */
    private fun filterDirs(entries: List<DirectoryEntry>, caps: ClientCapsHolder?): List<DirectoryEntry> =
        if (caps?.supportsOpencode == true) entries
        else entries.map { e ->
            if (e.activeSessions.any { it.agent == AgentKind.OPENCODE }) {
                e.copy(activeSessions = e.activeSessions.filter { it.agent != AgentKind.OPENCODE })
            } else e
        }

    private fun filterSchedule(state: ScheduleState, caps: ClientCapsHolder?): ScheduleState =
        if (caps?.supportsOpencode == true || state.items.none { it.agent == AgentKind.OPENCODE }) state
        else state.copy(items = state.items.filter { it.agent != AgentKind.OPENCODE })

    /**
     * The project list a GUEST sees (issue #115): ONLY the shared root(s) — each stamped with the origin
     * label + expiry + tier for the "Shared" row — and never any of the owner's other project folders. The
     * live-session enrichment is filtered to the guest's OWN sessions, so the owner's activity under the
     * same root never leaks into the guest's row. A root with no history yet still appears (the guest can
     * start there), so the shared folder shows up the moment the guest joins.
     */
    private suspend fun scopedDirectories(scope: GuestScope): List<DirectoryEntry> {
        val all = dirs.listDirectories(null, registry.busyCwds(), registry.liveByCwd())
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
