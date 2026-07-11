package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.bridge.PathScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.SessionFilesService
import dev.ccpocket.daemon.disk.UsageService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.AuthLogin
import dev.ccpocket.protocol.AuthLoginCancel
import dev.ccpocket.protocol.AuthLoginCode
import dev.ccpocket.protocol.AuthLogout
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.FetchAuthStatus
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.SessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.RunShellCommand
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
    private val shell: ShellService,
    private val scope: CoroutineScope,
    private val auth: AuthService,
    private val prefs: DaemonPrefs,
) {
    /** [origin] names the restricted credential this frame arrived from (issue #91 bridge / #115 guest) —
     *  null for every interactive owner client. [guestScope] (issue #115) is non-null ONLY for a GUEST:
     *  it clamps the project/session VISIBILITY to the shared root + the guest's own sessions, and rides
     *  into [SessionRegistry.open] as the conversation's tool path guard. */
    suspend fun handle(frame: Frame, sink: OutboundSink, origin: String? = null, guestScope: GuestScope? = null, onOpened: suspend (String) -> Unit = {}) {
        when (frame) {
            is ListDirectories ->
                if (guestScope != null) sink.emit(Directories(scopedDirectories(guestScope)))
                else sink.emit(Directories(dirs.listDirectories(frame.root, registry.busyCwds(), registry.liveByCwd())))

            is ListSessions -> {
                val busy = registry.busySessionIds()
                // merge every backend's resumable sessions for this dir (Claude ~/.claude/projects + Codex ~/.codex/sessions)
                var items = registry.listSessions(frame.workdir)
                    .map { if (it.sessionId in busy) it.copy(busy = true) else it }
                // a GUEST sees ONLY the sessions IT started — never the owner's other sessions that happen to
                // live under the shared root (visibility "by initiator", issue #115 comment §3)
                if (guestScope != null) items = items.filter { it.sessionId in guestScope.ownedSessions }
                sink.emit(Sessions(frame.workdir, items))
            }

            // heavy transcript scan → off the inbound pump so it can't wedge the socket
            is FetchUsage -> scope.launch { sink.emit(UsageService.aggregate(frame.days)) }

            // both re-scan the transcript from disk (issue #36) → same off-pump rule as FetchUsage
            is ListSessionFiles -> scope.launch {
                sink.emit(SessionFiles(frame.workdir, frame.sessionId, SessionFilesService.changedFiles(frame.agent, frame.workdir, frame.sessionId)))
            }
            is ReadFile -> scope.launch {
                sink.emit(SessionFilesService.readFile(frame.agent, frame.workdir, frame.sessionId, frame.path))
            }
            is ReadFileDiff -> scope.launch {
                sink.emit(SessionFilesService.fileDiff(frame.agent, frame.workdir, frame.sessionId, frame.path))
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
            // a verdict may resolve a SHELL ask (issue #3) or an agent tool ask — shell claims its own by askId
            is PermissionVerdict -> if (!shell.onVerdict(frame)) registry.verdict(frame)
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
            is CloseSession -> { if (registry.close(frame.convoId, sink, frame.force)) shell.forget(frame.convoId) }
            is CancelTurn -> registry.cancelTurn(frame)
            // task panel "stop" (issue #80): interrupt the agent's work for this job + settle its row killed
            is StopBackgroundJob -> registry.stopBackgroundJob(frame)

            // voice capture: buffer fast here; whisper runs on the service's own scope
            is AudioChunk -> transcribe.onChunk(frame, sink)
            is AudioCancel -> transcribe.onCancel(frame)

            // account switching: each spawns a `claude auth …` child — off the inbound pump, like FetchUsage
            is FetchAuthStatus -> scope.launch { auth.sendStatus(sink::emit) }
            is AuthLogin -> scope.launch { auth.login(frame.console, sink::emit, frame.force) }
            is AuthLoginCode -> scope.launch { auth.submitCode(frame.code, sink::emit) }
            is AuthLoginCancel -> scope.launch { auth.cancelLogin(sink::emit) }
            is AuthLogout -> scope.launch { auth.logout(sink::emit) }

            // phone-push switch: null enabled = query only; either way the daemon's truth is the reply
            is SetPushPrefs -> {
                frame.enabled?.let(prefs::setPushEnabled)
                sink.emit(PushPrefs(prefs.pushEnabled))
            }

            else -> sink.emit(PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"))
        }
    }

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
