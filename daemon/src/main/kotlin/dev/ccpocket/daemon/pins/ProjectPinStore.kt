package dev.ccpocket.daemon.pins

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PROJECT_PINS_MAX
import dev.ccpocket.protocol.isValidProjectPinPath
import dev.ccpocket.protocol.isValidProjectPinToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * The daemon's durable project-pin state (issue #362): the ordered list, its revision, the store incarnation
 * and every stream cursor, persisted as ONE document so a commit can never land the list without the cursor
 * that deduplicates it (or the reverse).
 */
@Serializable
data class PinStoreState(
    val v: Int = VERSION,
    val incarnation: String,
    val revision: Long = 0,
    /** Newest pin first. [StoredPin.key] is the daemon's canonical identity and never leaves the daemon. */
    val pins: List<StoredPin> = emptyList(),
    val cursors: List<StoredCursor> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class StoredPin(val path: String, val key: String)

/** The high-water sequence committed for one stream of one transport-authenticated device. */
@Serializable
data class StoredCursor(val deviceId: String, val streamId: String, val seq: Long)

/** What reading the store found. [Corrupt] and [Unreadable] are never turned into an empty list. */
sealed interface PinStoreRead {
    data object Missing : PinStoreRead
    data class Loaded(val state: PinStoreState) : PinStoreRead
    data class Corrupt(val reason: String) : PinStoreRead
    data class Unreadable(val error: Throwable) : PinStoreRead
}

/** How replacing the stored state ended. Only [Durable] may ever be acknowledged to a client. */
sealed interface PinStoreWrite {
    /** The new document is in place and durable, within the platform limit documented on [DurablePinFiles]. */
    data object Durable : PinStoreWrite

    /** The target provably still holds the previous document — nothing was renamed over it. Safe to retry. */
    data object UnchangedFailure : PinStoreWrite

    /** The new document was renamed into place, or may have been, but its durability is unconfirmed: the file
     *  may hold either document, and only a fresh read of it can tell which. */
    data object IndeterminateFailure : PinStoreWrite
}

interface ProjectPinStore {
    fun read(): PinStoreRead

    /** Replace the stored state; the [PinStoreWrite] says what the outcome proves about what is stored. */
    fun write(state: PinStoreState): PinStoreWrite
}

/** In-memory store for embedded cores and tests that do not exercise persistence. */
class MemoryProjectPinStore(private var state: PinStoreState? = null) : ProjectPinStore {
    @Synchronized override fun read(): PinStoreRead = state?.let { PinStoreRead.Loaded(it) } ?: PinStoreRead.Missing
    @Synchronized override fun write(state: PinStoreState): PinStoreWrite { this.state = state; return PinStoreWrite.Durable }
}

/**
 * `~/.cc-pocket/project-pins.json` (beside identity.json, like every other daemon store). Reading is lazy, so
 * constructing one touches nothing. [files] is injectable only so tests can fail one phase of a write on purpose.
 */
class FileProjectPinStore internal constructor(
    val file: File,
    private val files: DurablePinFiles,
) : ProjectPinStore {

    constructor(file: File) : this(file, DurablePinFiles())

    override fun read(): PinStoreRead {
        if (!file.exists()) return PinStoreRead.Missing
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return PinStoreRead.Unreadable(e)
        }
        val state = try {
            JSON.decodeFromString<PinStoreState>(text)
        } catch (e: Exception) {
            return PinStoreRead.Corrupt("undecodable (${e::class.simpleName})")
        }
        return invariantViolation(state)?.let { PinStoreRead.Corrupt(it) } ?: PinStoreRead.Loaded(state)
    }

    override fun write(state: PinStoreState): PinStoreWrite = files.replace(file, JSON.encodeToString(state).encodeToByteArray())

    companion object {
        private val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = false }

        fun defaultFile(): File = File(Identity.defaultPath().parentFile, "project-pins.json")

        /** A document this build cannot fully vouch for is treated as corrupt: committing on top of a newer
         *  version's file (a daemon downgrade) or of broken invariants would silently rewrite it. */
        internal fun invariantViolation(state: PinStoreState): String? = when {
            state.v != PinStoreState.VERSION -> "unsupported version ${state.v}"
            !isValidProjectPinToken(state.incarnation, minChars = 1) -> "invalid incarnation"
            state.revision < 0 -> "negative revision"
            state.pins.size > PROJECT_PINS_MAX -> "more than $PROJECT_PINS_MAX pins"
            state.pins.any { !isValidProjectPinPath(it.path) || it.key.isEmpty() } -> "invalid pin row"
            state.pins.map { it.key }.toSet().size != state.pins.size -> "duplicate pin identity"
            state.cursors.any { it.deviceId.isBlank() || !isValidProjectPinToken(it.streamId) || it.seq < 1 } -> "invalid cursor row"
            state.cursors.map { it.deviceId to it.streamId }.toSet().size != state.cursors.size -> "duplicate cursor"
            else -> null
        }
    }
}

/**
 * Crash-atomic replacement with a truthful outcome: the bytes go to a sibling temp file created 0600, are
 * flushed with `fsync` ([FileChannel.force]) and renamed over the target with `ATOMIC_MOVE`, so a reader —
 * including this daemon after a crash — sees either the whole previous document or the whole new one. The
 * directory holding the entry is then synced as well, and only then is the write [PinStoreWrite.Durable]. A
 * directory this call has to create is a new entry of ITS parent, which is synced the same way.
 *
 * Deliberately NO non-atomic fallback: a filesystem that refuses an atomic rename fails the write rather than
 * risking a torn store. A failure before the rename leaves the target provably untouched
 * ([PinStoreWrite.UnchangedFailure]). A failure after it — or a rename whose completion cannot be ruled out,
 * because our temp file is no longer where we put it — is [PinStoreWrite.IndeterminateFailure]: the new
 * document may already be the stored one, so nothing is ever "rolled back" over the target. Only this call's
 * own temp file is cleaned up.
 *
 * Platform limit, the one narrower guarantee: the JVM cannot open a directory for syncing on Windows, so there
 * a write is Durable once the file is fsynced and atomically renamed, and the rename's own persistence is left
 * to NTFS. Everywhere else a directory sync failure is reported, never assumed away. Power-loss behaviour of
 * the physical disk is not verified here.
 *
 * The three phase operations are injectable only so tests can fail each one deterministically.
 */
internal class DurablePinFiles(
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val forceFile: (FileChannel) -> Unit = { it.force(true) },
    private val forceDirectory: (Path) -> Unit = PLATFORM_DIRECTORY_SYNC,
) {
    fun replace(target: File, bytes: ByteArray): PinStoreWrite {
        val destination = target.absoluteFile.toPath()
        val dir = destination.parent ?: return PinStoreWrite.UnchangedFailure
        var tmp: Path? = null
        var moveAttempted = false
        try {
            ensureDirectory(dir)
            tmp = Files.createTempFile(dir, ".${target.name}.", ".tmp")
            ownerOnly(tmp)
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                forceFile(channel)
            }
            moveAttempted = true
            atomicMove(tmp, destination)
            tmp = null // renamed into place: no temp file of ours is left to clean up
            forceDirectory(dir)
            return PinStoreWrite.Durable
        } catch (e: Exception) {
            // our temp file still at its own path proves the rename never happened; gone means it may have
            val mayHaveReplaced = moveAttempted && (tmp == null || !Files.exists(tmp, LinkOption.NOFOLLOW_LINKS))
            val why = if (e is AtomicMoveNotSupportedException) "this filesystem refuses an atomic rename" else "${e::class.simpleName}: ${e.message}"
            return if (mayHaveReplaced) {
                log.warn("could not confirm ${target.name} is durable — its stored outcome is unknown: $why")
                PinStoreWrite.IndeterminateFailure
            } else {
                log.warn("could not persist ${target.name}: $why")
                PinStoreWrite.UnchangedFailure
            }
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun ensureDirectory(dir: Path) {
        if (Files.isDirectory(dir)) return
        val created = generateSequence(dir) { it.parent }.takeWhile { !Files.exists(it) }.toList().asReversed()
        Files.createDirectories(dir)
        try {
            for (made in created) made.parent?.let(forceDirectory)
        } catch (e: Exception) {
            // not provably durable: drop the (empty) directories made here so a retry creates and syncs them again
            for (made in created.asReversed()) runCatching { Files.deleteIfExists(made) }
            throw e
        }
    }

    private fun ownerOnly(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }
}

private val log = logger("ProjectPinStore")

/** Windows is the explicit exception (see [DurablePinFiles]); everywhere else a failed directory sync throws. */
private val PLATFORM_DIRECTORY_SYNC: (Path) -> Unit =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        { _ -> }
    } else {
        { dir -> FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
    }
