package dev.ccpocket.app.pins

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.ccpocket.app.voice.VoiceHost
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual fun platformProjectPinPersistence(): ProjectPinPersistence = AndroidProjectPinPersistence

actual class PinLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

/**
 * `<app files dir>/project-pins/`: app-private internal storage. The application context is the one MainActivity
 * already installs (before any repository exists) for the other platform seams; until it is available every read
 * and listing reports a failure and every write is refused, so nothing silently falls back to an empty store.
 *
 * Writes use a sibling temp file, `fsync` ([FileChannel.force]), then `rename` with `ATOMIC_MOVE` (API 26+,
 * which is minSdk) and a directory `fsync`. A failure before the rename is [PinFileWrite.NotWritten]; a failed
 * directory fsync after it is [PinFileWrite.Indeterminate], except that a filesystem reporting EINVAL/ENOTSUP
 * (directory fsync unsupported) gets the documented weaker guarantee. Physical power-loss behaviour on a device is
 * not verified.
 */
private object AndroidProjectPinPersistence : ProjectPinPersistence {

    private fun dir(): File? = runCatching { File(VoiceHost.appContext.filesDir, "project-pins") }.getOrNull()

    override fun read(name: String): PinFileRead {
        val d = dir() ?: return PinFileRead.Failed("storage not initialized")
        return try {
            val path = File(d, "$name.json").toPath()
            if (Files.notExists(path)) PinFileRead.Missing else PinFileRead.Found(String(Files.readAllBytes(path), Charsets.UTF_8))
        } catch (e: NoSuchFileException) {
            PinFileRead.Missing
        } catch (e: Exception) {
            PinFileRead.Failed(e::class.simpleName ?: "io")
        }
    }

    override fun write(name: String, text: String): PinFileWrite {
        val d = dir() ?: return PinFileWrite.NotWritten
        val target = File(d, "$name.json")
        var tmp: Path? = null
        try {
            Files.createDirectories(d.toPath())
            tmp = Files.createTempFile(d.toPath(), ".${target.name}.", ".tmp")
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(text.encodeToByteArray())
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
        return if (syncDirectory(d)) PinFileWrite.Durable else PinFileWrite.Indeterminate
    }

    override fun recover(name: String): PinFileRead {
        val d = dir() ?: return PinFileRead.Failed("storage not initialized")
        if (Files.notExists(d.toPath())) return PinFileRead.Missing
        if (!syncDirectory(d)) return PinFileRead.Failed("directory sync")
        return read(name)
    }

    override fun list(): PinFileListing {
        val d = dir() ?: return PinFileListing.Failed
        return try {
            if (Files.notExists(d.toPath())) return PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false)
            val entries = Files.newDirectoryStream(d.toPath()).use { stream -> stream.map { it.fileName.toString() } }
            PinFileListing.Ready(
                names = entries.filter { it.endsWith(".json") && !it.startsWith(".") }.map { it.removeSuffix(".json") },
                hasRecoveryArtifacts = entries.any { !it.startsWith(".") && it.contains(".json.corrupt-") },
            )
        } catch (e: NoSuchFileException) {
            PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false)
        } catch (e: Exception) {
            PinFileListing.Failed
        }
    }

    /** True when the directory entry is durable, or the filesystem explicitly does not support syncing it. */
    private fun syncDirectory(d: File): Boolean {
        val fd = try {
            Os.open(d.path, OsConstants.O_RDONLY, 0)
        } catch (e: ErrnoException) {
            return false
        }
        return try {
            Os.fsync(fd)
            true
        } catch (e: ErrnoException) {
            e.errno == OsConstants.EINVAL || e.errno == OsConstants.ENOTSUP
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
