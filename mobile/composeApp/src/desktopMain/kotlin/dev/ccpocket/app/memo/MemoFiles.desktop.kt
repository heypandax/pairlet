package dev.ccpocket.app.memo

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

actual fun platformMemoFiles(): MemoFiles = FileMemoFiles(defaultMemoDirectory())

/**
 * `~/.cc-pocket-app/voice-memos/`, beside the desktop store. The desktop build has no memo entry point; this
 * exists so commonMain compiles and the JVM tests exercise real files. A Test task never gets the developer's
 * path: Gradle points `ccpocket.secureStore.file` at a per-task temp file and memos go into a sibling of it.
 */
internal fun defaultMemoDirectory(): File {
    System.getProperty("ccpocket.voiceMemos.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    System.getProperty("ccpocket.secureStore.file")?.takeIf { it.isNotBlank() }?.let {
        return File(File(it).absoluteFile.parentFile, "voice-memos")
    }
    return File(System.getProperty("user.home"), ".cc-pocket-app/voice-memos")
}

/**
 * `voice-memos/<dir>/<name>` over java.nio. A write goes to a hidden sibling temp file (0600 where POSIX
 * permissions exist), is flushed with `fsync` ([FileChannel.force]), renamed over the target with `ATOMIC_MOVE`,
 * and the directory is fsynced — so a reader sees the whole old or the whole new file. A failure before the rename
 * is [MemoWrite.NotWritten]; a failed directory fsync after it is [MemoWrite.Indeterminate]. Directories this call
 * had to create are fsynced into their parents as well, or the new file could vanish with its directory.
 *
 * Windows cannot open a directory for fsync, so there the rename is the last step (weaker, as for project pins).
 * macOS `fsync` does not force the drive cache the way `F_FULLFSYNC` would; power loss is not verified.
 */
class FileMemoFiles(private val root: File) : MemoFiles {

    private val directorySyncSupported = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun dirFile(dir: String): File = if (dir.isEmpty()) root else File(root, dir)

    override fun read(dir: String, name: String): MemoRead<ByteArray> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoRead.Unreadable("invalid path")
        return try {
            val path = File(dirFile(dir), name).toPath()
            if (Files.notExists(path)) MemoRead.Missing else MemoRead.Found(Files.readAllBytes(path))
        } catch (e: NoSuchFileException) {
            MemoRead.Missing
        } catch (e: Exception) {
            MemoRead.Unreadable(e::class.simpleName ?: "io")
        }
    }

    override fun write(dir: String, name: String, bytes: ByteArray): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoWrite.NotWritten()
        val d = dirFile(dir)
        val created = try {
            createDirectories(d)
        } catch (e: Exception) {
            return MemoWrite.NotWritten()
        }
        var tmp: Path? = null
        try {
            tmp = Files.createTempFile(d.toPath(), ".$name.", ".tmp")
            if (Files.getFileAttributeView(tmp, PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
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
        val d = dirFile(dir)
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
        if (!MemoPaths.isDir(dir)) return MemoWrite.NotWritten() // never the root
        val d = dirFile(dir)
        if (!d.exists()) return MemoWrite.Durable(Unit)
        val ok = try {
            d.walkBottomUp().all { it.delete() || !it.exists() }
        } catch (e: Exception) {
            false
        }
        // Something may already be gone, so a failure here is not a clean "nothing changed".
        if (!ok) return MemoWrite.Indeterminate
        return if (syncDirectory(d.parentFile)) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    private fun list(dir: String, keep: (Path) -> Boolean): List<String>? {
        if (!MemoPaths.isDir(dir, allowRoot = true)) return null
        val d = dirFile(dir).toPath()
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

    /** Creates [d] and any missing ancestors under the memo root's parent; returns the ones it created. */
    private fun createDirectories(d: File): List<File> {
        val missing = generateSequence(d.absoluteFile) { it.parentFile }.takeWhile { !it.exists() }.toList().asReversed()
        Files.createDirectories(d.toPath())
        return missing
    }

    private fun syncDirectory(d: File?): Boolean {
        if (!directorySyncSupported || d == null) return true
        return try {
            FileChannel.open(d.toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
