package dev.ccpocket.app.pins

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual fun platformProjectPinPersistence(): ProjectPinPersistence = FileProjectPinPersistence(defaultProjectPinDirectory())

/**
 * `~/.cc-pocket-app/project-pins/`, beside the desktop store. A Test task never gets that path: Gradle already
 * points `ccpocket.secureStore.file` at a per-task temp file, and pin documents go into a sibling directory of
 * it, so repository/UI tests can never read or write the developer's real pins.
 */
internal fun defaultProjectPinDirectory(): File {
    System.getProperty("ccpocket.projectPins.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    System.getProperty("ccpocket.secureStore.file")?.takeIf { it.isNotBlank() }?.let {
        return File(File(it).absoluteFile.parentFile, "project-pins")
    }
    return File(System.getProperty("user.home"), ".cc-pocket-app/project-pins")
}

actual class PinLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

/**
 * One JSON document per scope. A write goes to a sibling temp file (0600 where POSIX permissions exist), is
 * flushed with `fsync` ([FileChannel.force]) and renamed over the target with `ATOMIC_MOVE`, then the directory
 * is fsynced — a reader, including this app after a crash, sees the whole old or the whole new document. There is
 * deliberately no non-atomic fallback. A failure before the rename is [PinFileWrite.NotWritten]; a failed directory
 * fsync after it is [PinFileWrite.Indeterminate].
 *
 * Windows cannot open a directory for fsync, so there the rename is the last step and the documented guarantee is
 * weaker (NTFS journals the rename's metadata, but its durability is not forced). `fsync` on macOS does not force the
 * drive cache the way `F_FULLFSYNC` would; physical power-loss behaviour is not verified on any platform.
 */
class FileProjectPinPersistence(private val dir: File) : ProjectPinPersistence {

    private val directorySyncSupported = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun file(name: String) = File(dir, "$name.json")

    override fun read(name: String): PinFileRead = try {
        val path = file(name).toPath()
        if (Files.notExists(path)) PinFileRead.Missing else PinFileRead.Found(Files.readString(path))
    } catch (e: NoSuchFileException) {
        PinFileRead.Missing
    } catch (e: Exception) {
        PinFileRead.Failed(e::class.simpleName ?: "io")
    }

    override fun write(name: String, text: String): PinFileWrite = replaceDurably(file(name), text.encodeToByteArray())

    override fun recover(name: String): PinFileRead {
        if (Files.notExists(dir.toPath())) return PinFileRead.Missing
        if (!syncDirectory()) return PinFileRead.Failed("directory sync")
        return read(name)
    }

    override fun list(): PinFileListing = try {
        if (Files.notExists(dir.toPath())) {
            PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false)
        } else {
            val entries = Files.newDirectoryStream(dir.toPath()).use { stream -> stream.map { it.fileName.toString() } }
            listing(entries)
        }
    } catch (e: NoSuchFileException) {
        PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false)
    } catch (e: Exception) {
        PinFileListing.Failed
    }

    private fun syncDirectory(): Boolean {
        if (!directorySyncSupported) return true
        return try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun replaceDurably(target: File, bytes: ByteArray): PinFileWrite {
        var tmp: Path? = null
        try {
            Files.createDirectories(dir.toPath())
            tmp = Files.createTempFile(dir.toPath(), ".${target.name}.", ".tmp")
            if (Files.getFileAttributeView(tmp, PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
        } catch (e: Exception) {
            return PinFileWrite.NotWritten
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
        return if (syncDirectory()) PinFileWrite.Durable else PinFileWrite.Indeterminate
    }
}

/** Documents are `<name>.json`; `<name>.json.corrupt-*` are what an earlier build set aside and must stay findable.
 *  Hidden entries are in-progress temp files, never documents. */
internal fun listing(entries: List<String>): PinFileListing.Ready = PinFileListing.Ready(
    names = entries.filter { it.endsWith(".json") && !it.startsWith(".") }.map { it.removeSuffix(".json") },
    hasRecoveryArtifacts = entries.any { !it.startsWith(".") && it.contains(".json.corrupt-") },
)
