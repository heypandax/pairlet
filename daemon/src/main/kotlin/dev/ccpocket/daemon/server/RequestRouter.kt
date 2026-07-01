package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.UsageService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.ShellResult
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
) {
    suspend fun handle(frame: Frame, sink: OutboundSink, onOpened: suspend (String) -> Unit = {}) {
        when (frame) {
            is ListDirectories -> sink.emit(Directories(dirs.listDirectories(frame.root, registry.busyCwds())))

            is ListSessions -> {
                val busy = registry.busySessionIds()
                // merge every backend's resumable sessions for this dir (Claude ~/.claude/projects + Codex ~/.codex/sessions)
                val items = registry.listSessions(frame.workdir)
                    .map { if (it.sessionId in busy) it.copy(busy = true) else it }
                sink.emit(Sessions(frame.workdir, items))
            }

            // heavy transcript scan → off the inbound pump so it can't wedge the socket
            is FetchUsage -> scope.launch { sink.emit(UsageService.aggregate(frame.days)) }

            is OpenSession -> {
                // a new project: create the named folder if it doesn't exist yet (under an existing writable parent)
                val wd = dirs.validateOrCreateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(PocketError("bad_workdir", "not a readable directory: ${frame.workdir}"))
                } else {
                    dirs.noteRecent(wd.toString())
                    val convoId = registry.open(frame.copy(workdir = wd.toString()), sink)
                    if (convoId.isNotEmpty()) onOpened(convoId) // "" = backend unavailable (PocketError already sent)
                }
            }

            is SendPrompt -> registry.sendPrompt(frame)
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

            is CloseSession -> { registry.close(frame.convoId); shell.forget(frame.convoId) }
            is CancelTurn -> registry.cancelTurn(frame)

            // voice capture: buffer fast here; whisper runs on the service's own scope
            is AudioChunk -> transcribe.onChunk(frame, sink)
            is AudioCancel -> transcribe.onCancel(frame)

            else -> sink.emit(PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"))
        }
    }
}
