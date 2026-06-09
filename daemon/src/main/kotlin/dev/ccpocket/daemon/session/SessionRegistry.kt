package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.Conversation
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SwitchDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.UUID

/** convoId -> live [Conversation]. Create on open, relaunch on switch-dir, reap on close. */
class SessionRegistry(
    private val scope: CoroutineScope,
    private val claudeExe: Path,
) {
    private val mutex = Mutex()
    private val convos = mutableMapOf<String, Conversation>()

    suspend fun open(open: OpenSession, sink: OutboundSink): String {
        val convoId = UUID.randomUUID().toString()
        val c = Conversation(convoId, Path.of(open.workdir), open.mode, sink, scope, claudeExe)
        mutex.withLock { convos[convoId] = c }
        c.open(open.resumeId, open.model)
        return convoId
    }

    suspend fun sendPrompt(p: SendPrompt) = get(p.convoId)?.sendPrompt(p.text) ?: Unit
    suspend fun verdict(v: PermissionVerdict) = get(v.convoId)?.submitVerdict(v) ?: Unit
    suspend fun switchDir(s: SwitchDirectory) = get(s.convoId)?.switchDirectory(Path.of(s.workdir)) ?: Unit

    suspend fun close(convoId: String) {
        val c = mutex.withLock { convos.remove(convoId) }
        c?.close()
    }

    suspend fun closeAll() {
        val all = mutex.withLock { convos.values.toList().also { convos.clear() } }
        all.forEach { it.close() }
    }

    private suspend fun get(id: String): Conversation? = mutex.withLock { convos[id] }
}
