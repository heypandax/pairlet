package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where a memo job's scratch files live: the recording, its decoded audio and the transcriber's output, for the
 * seconds it takes to process them. Each job cleans up after itself, but a daemon that is killed outright
 * (an update, two instances fighting) never reaches that cleanup — so the scratch space is ONE owner-only
 * directory of the daemon's own, and whatever a previous process left in it is removed when the next one starts.
 */
object MemoWorkDir {
    private val log = logger("VoiceMemo")

    fun defaultRoot(): File = File(Identity.defaultPath().parentFile, "voice-memo-tmp")

    /** Creates [root] owner-only and empties it. Null when it cannot be used; callers then fall back to the
     *  system temp directory, which costs the start-up sweep but nothing else. */
    fun prepare(root: File): Path? = runCatching {
        if (!ManagedSessionStore.createPrivateDirectory(root)) return null
        val stale = root.listFiles().orEmpty()
        stale.forEach { it.deleteRecursively() }
        if (stale.isNotEmpty()) log.info("memo scratch: removed ${stale.size} leftover entr${if (stale.size == 1) "y" else "ies"}")
        root.toPath()
    }.getOrNull()

    private val posix: Boolean = runCatching {
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    }.getOrDefault(false)

    /** A fresh owner-only (0700) directory for ONE call, under [root] (the prepared scratch dir) or, when null,
     *  the system temp directory. The caller deletes it. */
    fun createCallDir(root: Path?, prefix: String): Path =
        if (posix) {
            val attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            if (root != null) Files.createTempDirectory(root, prefix, attr) else Files.createTempDirectory(prefix, attr)
        } else {
            if (root != null) Files.createTempDirectory(root, prefix) else Files.createTempDirectory(prefix)
        }

    /** Creates [path] owner-only (0600 where POSIX) — it must not exist yet — and writes [bytes] to it. */
    fun writePrivateFile(path: Path, bytes: ByteArray) {
        if (posix) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(path)
        }
        Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}
