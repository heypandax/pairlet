package dev.ccpocket.app.memo

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

actual fun platformMemoFiles(): MemoFiles = AndroidMemoFiles

/**
 * `<noBackupFilesDir>/voice-memos/`: app-private storage that Android never copies into Auto Backup or a device
 * transfer, so recordings and transcripts stay on this phone. The application context is the one MainActivity
 * installs for the other platform seams; until it exists every read/listing fails and every write is refused —
 * nothing silently falls back to an empty library.
 *
 * Writes: hidden temp file → `fsync` → `rename` with `ATOMIC_MOVE` → directory `fsync` (and the parents of any
 * directory the write had to create). A failure before the rename is [MemoWrite.NotWritten]; a failed directory
 * fsync after it is [MemoWrite.Indeterminate], except EINVAL/ENOTSUP (unsupported) which keeps the documented
 * weaker guarantee. Power-loss behaviour on a device is not verified.
 */
private object AndroidMemoFiles : MemoFiles {

    private fun root(): File? = runCatching { File(VoiceHost.appContext.noBackupFilesDir, "voice-memos") }.getOrNull()

    private fun dirFile(root: File, dir: String): File = if (dir.isEmpty()) root else File(root, dir)

    override fun read(dir: String, name: String): MemoRead<ByteArray> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoRead.Unreadable("invalid path")
        val r = root() ?: return MemoRead.Unreadable("storage not initialized")
        return try {
            val path = File(dirFile(r, dir), name).toPath()
            if (Files.notExists(path)) MemoRead.Missing else MemoRead.Found(Files.readAllBytes(path))
        } catch (e: NoSuchFileException) {
            MemoRead.Missing
        } catch (e: Exception) {
            MemoRead.Unreadable(e::class.simpleName ?: "io")
        }
    }

    override fun write(dir: String, name: String, bytes: ByteArray): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoWrite.NotWritten()
        val r = root() ?: return MemoWrite.NotWritten()
        val d = dirFile(r, dir)
        val created = try {
            val missing = generateSequence(d.absoluteFile) { it.parentFile }.takeWhile { !it.exists() }.toList()
            Files.createDirectories(d.toPath())
            missing
        } catch (e: Exception) {
            return MemoWrite.NotWritten()
        }
        var tmp: Path? = null
        try {
            tmp = Files.createTempFile(d.toPath(), ".$name.", ".tmp")
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(tmp, File(d, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
        } catch (e: Exception) {
            return MemoWrite.NotWritten()
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
        val synced = syncDirectory(d) && created.all { syncDirectory(it.parentFile) }
        return if (synced) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    override fun delete(dir: String, name: String): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoWrite.NotWritten()
        val r = root() ?: return MemoWrite.NotWritten()
        val d = dirFile(r, dir)
        val removed = try {
            Files.deleteIfExists(File(d, name).toPath())
        } catch (e: Exception) {
            return MemoWrite.NotWritten()
        }
        if (!removed) return MemoWrite.Durable(Unit)
        return if (syncDirectory(d)) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    override fun listDirs(dir: String): List<String>? = list(dir) { Files.isDirectory(it) }

    override fun listFiles(dir: String): List<String>? = list(dir) { Files.isRegularFile(it) }

    override fun deleteDir(dir: String): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir)) return MemoWrite.NotWritten()
        val r = root() ?: return MemoWrite.NotWritten()
        val d = dirFile(r, dir)
        if (!d.exists()) return MemoWrite.Durable(Unit)
        val ok = try {
            d.walkBottomUp().all { it.delete() || !it.exists() }
        } catch (e: Exception) {
            false
        }
        if (!ok) return MemoWrite.Indeterminate
        return if (syncDirectory(d.parentFile)) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    private fun list(dir: String, keep: (Path) -> Boolean): List<String>? {
        if (!MemoPaths.isDir(dir, allowRoot = true)) return null
        val r = root() ?: return null
        val d = dirFile(r, dir).toPath()
        return try {
            if (Files.notExists(d)) return emptyList()
            Files.newDirectoryStream(d).use { stream ->
                stream.filter { keep(it) }.map { it.fileName.toString() }.filter(MemoPaths::isVisible).sorted()
            }
        } catch (e: NoSuchFileException) {
            emptyList()
        } catch (e: Exception) {
            null
        }
    }

    /** True when the directory entry is durable, or the filesystem explicitly does not support syncing it. */
    private fun syncDirectory(d: File?): Boolean {
        if (d == null) return true
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
