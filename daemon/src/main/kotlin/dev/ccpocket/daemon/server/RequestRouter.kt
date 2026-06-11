package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.SwitchDirectory
import dev.ccpocket.protocol.SwitchMode

/** Maps an inbound [Frame] to the registry/services. Returns fast; turns run on conversation scopes. */
class RequestRouter(
    private val registry: SessionRegistry,
    private val dirs: DirectoryService,
    private val transcribe: TranscribeService,
) {
    suspend fun handle(frame: Frame, sink: OutboundSink, onOpened: suspend (String) -> Unit = {}) {
        when (frame) {
            is ListDirectories -> sink.emit(Directories(dirs.listDirectories(frame.root)))

            is ListSessions ->
                sink.emit(Sessions(frame.workdir, TranscriptScanner.scan(ProjectPaths.dirFor(frame.workdir))))

            is OpenSession -> {
                val wd = dirs.validateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(PocketError("bad_workdir", "not a readable directory: ${frame.workdir}"))
                } else {
                    dirs.noteRecent(wd.toString())
                    onOpened(registry.open(frame.copy(workdir = wd.toString()), sink))
                }
            }

            is SendPrompt -> registry.sendPrompt(frame)
            is PermissionVerdict -> registry.verdict(frame)
            is SwitchMode -> registry.switchMode(frame)
            is ClearAllowRule -> registry.clearRule(frame)

            is SwitchDirectory -> {
                val wd = dirs.validateWorkdir(frame.workdir)
                if (wd == null) {
                    sink.emit(PocketError("bad_workdir", "not a readable directory: ${frame.workdir}", frame.convoId))
                } else {
                    dirs.noteRecent(wd.toString())
                    registry.switchDir(frame.copy(workdir = wd.toString()))
                }
            }

            is CloseSession -> registry.close(frame.convoId)
            is CancelTurn -> registry.cancelTurn(frame)

            // voice capture: buffer fast here; whisper runs on the service's own scope
            is AudioChunk -> transcribe.onChunk(frame, sink)
            is AudioCancel -> transcribe.onCancel(frame)

            else -> sink.emit(PocketError("unsupported", "frame not handled by daemon: ${frame::class.simpleName}"))
        }
    }
}
