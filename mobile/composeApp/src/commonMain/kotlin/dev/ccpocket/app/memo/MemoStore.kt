package dev.ccpocket.app.memo

import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoLimits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun defaultVoiceMemoStore(files: MemoFiles = platformMemoFiles()): VoiceMemoStore = DefaultVoiceMemoStore(files)

/**
 * One directory per memo: `<scope-token>/<memoId>/` holding `document.json`, `audio.m4a` and, once the user
 * deleted it, a `deleted` tombstone. [MemoFiles] makes each file replacement atomic; this class adds the rules that
 * span files:
 *
 *  - audio and metadata are two phases (`writeAudio`, then `commit` of a document that references it). A
 *    directory with audio but no document is an orphan: never listed, never uploaded.
 *  - `commit` is compare-and-swap on [MemoDocument.revision] and refuses a tombstoned memo, so a late reply can
 *    never resurrect a deleted memo and two writers cannot silently overwrite each other.
 *  - a document with an unknown [MemoDocument.schemaVersion] is reported, never rewritten with defaults.
 *  - a listing that cannot be read is [MemoRead.Unreadable], not an empty library; one damaged document is left
 *    out of the list without hiding the others.
 *
 * Every call runs under one [Mutex], so the read-check-write of `commit` is atomic within this process.
 */
class DefaultVoiceMemoStore(private val files: MemoFiles) : VoiceMemoStore {

    private val mutex = Mutex()

    override suspend fun list(scope: MemoScope, offset: Int, limit: Int): MemoRead<List<MemoHeader>> = mutex.withLock {
        val root = scopeToken(scope)
        val ids = files.listDirs(root) ?: return@withLock MemoRead.Unreadable("listing failed")
        val headers = ids.filter(MemoPaths::isToken).mapNotNull { id ->
            val dir = "$root/$id"
            val entries = files.listFiles(dir) ?: return@mapNotNull null
            if (TOMBSTONE in entries || DOCUMENT !in entries) return@mapNotNull null
            (readDocument(dir, scope, id) as? MemoRead.Found)?.value?.takeIf { !it.deleted }?.header()
        }
        MemoRead.Found(
            headers.sortedWith(compareByDescending<MemoHeader> { it.createdAtMs }.thenBy { it.memoId })
                .drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0)),
        )
    }

    /** Memos that occupy a slot of [VoiceMemoLimits.MAX_LOCAL_MEMOS]: every non-tombstoned directory with a
     *  document, readable or not — a damaged document still holds its audio. */
    override suspend fun count(scope: MemoScope): MemoRead<Int> = mutex.withLock {
        val root = scopeToken(scope)
        val ids = files.listDirs(root) ?: return@withLock MemoRead.Unreadable("listing failed")
        var n = 0
        for (id in ids.filter(MemoPaths::isToken)) {
            val entries = files.listFiles("$root/$id") ?: return@withLock MemoRead.Unreadable("listing failed")
            if (TOMBSTONE !in entries && DOCUMENT in entries) n++
        }
        MemoRead.Found(n)
    }

    override suspend fun read(scope: MemoScope, memoId: String): MemoRead<MemoDocument> = mutex.withLock {
        if (!MemoPaths.isToken(memoId)) return@withLock MemoRead.Unreadable("invalid id")
        val dir = memoDir(scope, memoId)
        when (val tomb = files.read(dir, TOMBSTONE)) {
            is MemoRead.Found -> return@withLock MemoRead.Missing
            is MemoRead.Unreadable -> return@withLock tomb
            else -> Unit
        }
        readDocument(dir, scope, memoId)
    }

    override suspend fun readAudio(scope: MemoScope, memoId: String): MemoRead<ByteArray> = mutex.withLock {
        if (!MemoPaths.isToken(memoId)) return@withLock MemoRead.Unreadable("invalid id")
        val dir = memoDir(scope, memoId)
        if (files.read(dir, TOMBSTONE) !is MemoRead.Missing) return@withLock MemoRead.Missing
        files.read(dir, AUDIO)
    }

    override suspend fun writeAudio(scope: MemoScope, memoId: String, bytes: ByteArray, mediaType: String): MemoWrite<AudioRef> =
        mutex.withLock {
            if (!MemoPaths.isToken(memoId) || bytes.isEmpty()) return@withLock MemoWrite.NotWritten()
            val dir = memoDir(scope, memoId)
            if (files.read(dir, TOMBSTONE) !is MemoRead.Missing) return@withLock MemoWrite.NotWritten(conflict = true)
            val ref = AudioRef(AUDIO, bytes.size.toLong(), VoiceMemoHash.sha256Hex(bytes), mediaType)
            when (val w = files.write(dir, AUDIO, bytes)) {
                is MemoWrite.Durable -> MemoWrite.Durable(ref)
                is MemoWrite.NotWritten -> w
                MemoWrite.Indeterminate -> MemoWrite.Indeterminate
            }
        }

    override suspend fun commit(document: MemoDocument, expectedRevision: Long?): MemoWrite<Unit> = mutex.withLock {
        if (!MemoPaths.isToken(document.memoId) || document.schemaVersion != MEMO_SCHEMA_VERSION) {
            return@withLock MemoWrite.NotWritten()
        }
        if (expectedRevision != null && document.revision != expectedRevision + 1) return@withLock MemoWrite.NotWritten()
        val dir = memoDir(document.scope, document.memoId)
        when (files.read(dir, TOMBSTONE)) {
            is MemoRead.Missing -> Unit
            is MemoRead.Found -> return@withLock MemoWrite.NotWritten(conflict = true)
            else -> return@withLock MemoWrite.NotWritten()
        }
        when (val current = readDocument(dir, document.scope, document.memoId)) {
            is MemoRead.Missing -> if (expectedRevision != null) return@withLock MemoWrite.NotWritten(conflict = true)
            is MemoRead.Found -> {
                if (expectedRevision == null || current.value.revision != expectedRevision || current.value.deleted) {
                    return@withLock MemoWrite.NotWritten(conflict = true)
                }
            }
            // Never replace what could not be read or belongs to a newer build.
            is MemoRead.Unreadable, is MemoRead.UnsupportedVersion -> return@withLock MemoWrite.NotWritten(conflict = true)
        }
        val bytes = try {
            json.encodeToString(MemoDocument.serializer(), document).encodeToByteArray()
        } catch (e: Exception) {
            return@withLock MemoWrite.NotWritten()
        }
        files.write(dir, DOCUMENT, bytes)
    }

    override suspend fun deleteAudio(scope: MemoScope, memoId: String): MemoWrite<Unit> = mutex.withLock {
        if (!MemoPaths.isToken(memoId)) return@withLock MemoWrite.NotWritten()
        files.delete(memoDir(scope, memoId), AUDIO)
    }

    override suspend fun markDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit> = mutex.withLock {
        if (!MemoPaths.isToken(memoId)) return@withLock MemoWrite.NotWritten()
        files.write(memoDir(scope, memoId), TOMBSTONE, byteArrayOf('1'.code.toByte()))
    }

    /** Content first, the tombstone last: an interrupted purge leaves the tombstone for [pendingPurges]. */
    override suspend fun purgeDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit> = mutex.withLock {
        if (!MemoPaths.isToken(memoId)) return@withLock MemoWrite.NotWritten()
        val dir = memoDir(scope, memoId)
        when (files.read(dir, TOMBSTONE)) {
            is MemoRead.Found -> Unit
            is MemoRead.Missing -> return@withLock MemoWrite.NotWritten() // only a tombstoned memo is purged
            else -> return@withLock MemoWrite.NotWritten()
        }
        val entries = files.listFiles(dir) ?: return@withLock MemoWrite.NotWritten()
        var removedAny = false
        for (name in entries.filter { it != TOMBSTONE }) {
            val w = files.delete(dir, name)
            if (w !is MemoWrite.Durable) {
                return@withLock if (w is MemoWrite.NotWritten && !removedAny) w else MemoWrite.Indeterminate
            }
            removedAny = true
        }
        files.deleteDir(dir)
    }

    override suspend fun pendingPurges(scope: MemoScope): List<String> = mutex.withLock {
        val root = scopeToken(scope)
        val ids = files.listDirs(root) ?: return@withLock emptyList()
        ids.filter { MemoPaths.isToken(it) && files.listFiles("$root/$it")?.contains(TOMBSTONE) == true }
    }

    private fun readDocument(dir: String, scope: MemoScope, memoId: String): MemoRead<MemoDocument> =
        when (val raw = files.read(dir, DOCUMENT)) {
            is MemoRead.Found -> decodeDocument(raw.value, scope, memoId)
            is MemoRead.Missing -> MemoRead.Missing
            is MemoRead.Unreadable -> raw
            is MemoRead.UnsupportedVersion -> raw
        }

    companion object {
        internal const val DOCUMENT = "document.json"
        internal const val AUDIO = "audio.m4a"
        internal const val TOMBSTONE = "deleted"

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** A stable, path-safe name for one binding as seen from one device; neither id reaches the file system. */
        fun scopeToken(scope: MemoScope): String =
            VoiceMemoHash.sha256Hex((scope.bindingId + "\u0000" + scope.deviceId).encodeToByteArray()).take(32)

        internal fun memoDir(scope: MemoScope, memoId: String) = scopeToken(scope) + "/" + memoId

        /** schemaVersion is read on its own first, so a newer build's document is never decoded with defaults. */
        internal fun decodeDocument(bytes: ByteArray, scope: MemoScope, memoId: String): MemoRead<MemoDocument> {
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (e: Exception) {
                return MemoRead.Unreadable("encoding")
            }
            val version = try {
                json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            } catch (e: Exception) {
                return MemoRead.Unreadable("json")
            } ?: return MemoRead.Unreadable("no schemaVersion")
            if (version != MEMO_SCHEMA_VERSION) return MemoRead.UnsupportedVersion(version)
            val doc = try {
                json.decodeFromString(MemoDocument.serializer(), text)
            } catch (e: Exception) {
                return MemoRead.Unreadable("shape")
            }
            if (doc.memoId != memoId || doc.scope != scope) return MemoRead.Unreadable("identity")
            return MemoRead.Found(doc)
        }
    }
}

/**
 * True once the memo holds a result the user can act on (a summary, to-dos, an edit, an accepted attempt, or a
 * transcript whose audio was already released) — the memo then opens on its result page and the list shows no
 * processing stage. The audio is released only after a result was stored, so "transcript, no audio" keeps a
 * transcribe-only or degraded memo a result while a later organise attempt runs or fails.
 */
internal fun MemoDocument.hasResult(): Boolean =
    content.summary != null || todos.isNotEmpty() || editRevision > 0 || processing?.accepted == true ||
        (content.audio == null && content.transcript != null)

internal fun MemoDocument.header(): MemoHeader {
    val states = todos.map { todoState(it.todoId) }
    val showStage = !hasResult()
    return MemoHeader(
        memoId = memoId,
        // An untitled memo (never organised, never titled by hand) is listed by its transcript's first sentence.
        title = content.title.ifBlank { memoTitleFallback(content.transcript) },
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        stage = if (showStage) processing?.stage else null,
        errorCode = if (showStage) processing?.errorCode else null,
        drafts = states.count { it == MemoTodoState.DRAFT },
        sending = states.count { it == MemoTodoState.SENDING },
        delivered = states.count { it == MemoTodoState.DELIVERED },
        unknown = states.count { it == MemoTodoState.UNKNOWN },
        failed = states.count { it == MemoTodoState.FAILED },
    )
}
