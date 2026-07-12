package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.FileUploadCancel
import dev.ccpocket.protocol.FileUploaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * File-upload orchestration (issue #90): streams [FileChunk]s into the session's workspace inbox via
 * [UploadReassembler] and replies [FileUploaded] on the sink the chunks arrived on (results only reach
 * the device that uploaded — the [dev.ccpocket.daemon.transcribe.TranscribeService] pattern). The cwd
 * comes from the daemon's own registry, NEVER from the phone, so a client can't aim the write anywhere
 * but the live session's `.ccpocket/inbox/`. Writes are small appends on Dispatchers.IO; per-chunk work
 * stays quick enough to ride the inbound pump inline, like audio chunks do.
 */
class FileInboxService(
    private val workdirOf: suspend (String) -> Path?,
) {
    private val log = logger("Inbox")
    private val uploads = UploadReassembler()

    suspend fun onChunk(f: FileChunk, sink: OutboundSink) {
        val workdir = workdirOf(f.convoId)
        if (workdir == null) {
            // reply once per stream edge, not once per chunk — a dead session mid-upload otherwise
            // yields hundreds of identical error frames
            if (f.idx == 0 || f.last) sink.emit(FileUploaded(f.convoId, f.captureId, ok = false, error = "session not live"))
            return
        }
        val result = withContext(Dispatchers.IO) { uploads.add(f, workdir) }
        when (result) {
            is UploadReassembler.Result.Incomplete, UploadReassembler.Result.Stale -> {}
            is UploadReassembler.Result.Complete -> {
                log.info("${f.convoId} landed ${result.relPath} (${result.size} bytes)")
                sink.emit(FileUploaded(f.convoId, result.captureId, path = result.relPath, name = result.name, size = result.size))
            }
            is UploadReassembler.Result.Refused -> {
                log.info("${f.convoId} upload ${result.captureId} refused: ${result.error}")
                sink.emit(FileUploaded(f.convoId, result.captureId, ok = false, error = result.error))
            }
        }
    }

    suspend fun onCancel(f: FileUploadCancel) {
        withContext(Dispatchers.IO) { uploads.cancel(f.convoId, f.captureId) }
    }
}
