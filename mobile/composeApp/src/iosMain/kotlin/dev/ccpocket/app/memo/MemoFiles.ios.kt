@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.ccpocket.app.memo

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.EINVAL
import platform.posix.ENOENT
import platform.posix.ENOTSUP
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.open
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink
import kotlin.random.Random

actual fun platformMemoFiles(): MemoFiles = IosMemoFiles

/**
 * `Library/Application Support/voice-memos/` inside the app container. The root is marked
 * `NSURLIsExcludedFromBackupKey` (no iCloud / Finder backup) before anything is written into it, and every
 * directory and file gets `NSFileProtectionCompleteUntilFirstUserAuthentication`, so memo audio and text are
 * encrypted at rest until the first unlock after a reboot. A write that cannot establish the exclusion is refused
 * rather than written somewhere a backup would pick it up.
 *
 * Bytes move through posix: a fresh 0600 temp file (`O_EXCL`) is written fully, `fsync`ed and closed, `rename`d
 * over the target (atomic on APFS), then the directory is fsynced. Failure before the rename is
 * [MemoWrite.NotWritten]; a failed directory fsync after it is [MemoWrite.Indeterminate], except EINVAL/ENOTSUP.
 * iOS `fsync` does not force the drive cache like `F_FULLFSYNC`; power loss on a device is not verified.
 */
private object IosMemoFiles : MemoFiles {

    private val lock = NSRecursiveLock()
    private var excluded = false

    private val root: String? by lazy {
        val base = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull()?.let { (it as? NSURL)?.path }
        base?.let { "$it/voice-memos" }
    }

    private fun path(root: String, dir: String): String = if (dir.isEmpty()) root else "$root/$dir"

    private val protection: Map<Any?, Any?> = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    override fun read(dir: String, name: String): MemoRead<ByteArray> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoRead.Unreadable("invalid path")
        val r = root ?: return MemoRead.Unreadable("no application support directory")
        val file = "${path(r, dir)}/$name"
        val fd = open(file, O_RDONLY)
        if (fd < 0) {
            val error = errno
            return if (error == ENOENT) MemoRead.Missing else MemoRead.Unreadable("open errno $error")
        }
        try {
            val size = memScoped {
                val info = alloc<stat>()
                if (fstat(fd, info.ptr) != 0) return MemoRead.Unreadable("fstat errno $errno")
                info.st_size
            }
            if (size < 0 || size > Int.MAX_VALUE) return MemoRead.Unreadable("size")
            val bytes = ByteArray(size.toInt())
            var offset = 0
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    while (offset < bytes.size) {
                        val n = platform.posix.read(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (n <= 0) break
                        offset += n.toInt()
                    }
                }
            }
            return if (offset == bytes.size) MemoRead.Found(bytes) else MemoRead.Unreadable("short read")
        } finally {
            close(fd)
        }
    }

    override fun write(dir: String, name: String, bytes: ByteArray): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoWrite.NotWritten()
        val r = root ?: return MemoWrite.NotWritten()
        val d = path(r, dir)
        val created = ensureDirectory(r, d) ?: return MemoWrite.NotWritten()
        val target = "$d/$name"
        val tmp = "$d/.$name.${Random.nextLong().toULong().toString(16)}.tmp"
        val fd = open(tmp, O_WRONLY or O_CREAT or O_EXCL, 384) // 0600
        if (fd < 0) return MemoWrite.NotWritten()
        var ok = true
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = platform.posix.write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (written <= 0) { ok = false; break }
                        offset += written.toInt()
                    }
                }
            }
            if (ok && fsync(fd) != 0) ok = false
        } finally {
            if (close(fd) != 0) ok = false
        }
        // Files inherit the directory's class; set it explicitly anyway so a pre-existing directory cannot leave
        // a memo weaker than intended.
        if (ok) NSFileManager.defaultManager.setAttributes(protection, ofItemAtPath = tmp, error = null)
        if (!ok || rename(tmp, target) != 0) {
            unlink(tmp)
            return MemoWrite.NotWritten()
        }
        val synced = syncDirectory(d) && created.all { syncDirectory(it.substringBeforeLast('/')) }
        return if (synced) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    override fun delete(dir: String, name: String): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir) || !MemoPaths.isName(name)) return MemoWrite.NotWritten()
        val r = root ?: return MemoWrite.NotWritten()
        val d = path(r, dir)
        if (unlink("$d/$name") != 0) {
            return if (errno == ENOENT) MemoWrite.Durable(Unit) else MemoWrite.NotWritten()
        }
        return if (syncDirectory(d)) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    override fun listDirs(dir: String): List<String>? = list(dir, S_IFDIR)

    override fun listFiles(dir: String): List<String>? = list(dir, S_IFREG)

    override fun deleteDir(dir: String): MemoWrite<Unit> {
        if (!MemoPaths.isDir(dir)) return MemoWrite.NotWritten()
        val r = root ?: return MemoWrite.NotWritten()
        val d = path(r, dir)
        when (kind(d)) {
            null -> return MemoWrite.Durable(Unit)
            -1 -> return MemoWrite.NotWritten()
        }
        if (!NSFileManager.defaultManager.removeItemAtPath(d, error = null)) return MemoWrite.Indeterminate
        return if (syncDirectory(d.substringBeforeLast('/'))) MemoWrite.Durable(Unit) else MemoWrite.Indeterminate
    }

    private fun list(dir: String, type: Int): List<String>? {
        if (!MemoPaths.isDir(dir, allowRoot = true)) return null
        val r = root ?: return null
        val d = path(r, dir)
        when (kind(d)) {
            null -> return emptyList()
            -1 -> return null
            S_IFDIR -> Unit
            else -> return null
        }
        val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath(d, null)?.mapNotNull { it as? String } ?: return null
        return entries.filter { MemoPaths.isVisible(it) && kind("$d/$it") == type }.sorted()
    }

    /** `S_IFMT` bits of [p]; null = definitely absent; -1 = could not inspect (never read as "absent"). */
    private fun kind(p: String): Int? = memScoped {
        val info = alloc<stat>()
        if (stat(p, info.ptr) != 0) {
            return if (errno == ENOENT) null else -1
        }
        info.st_mode.toInt() and S_IFMT
    }

    /** Creates [d] (and the root) with file protection, and marks the root excluded from backup. Returns the
     *  directories it created, or null when the directory or the backup exclusion could not be established. */
    private fun ensureDirectory(r: String, d: String): List<String>? = lockedOrNull {
        val missing = generateSequence(d) { p -> p.substringBeforeLast('/', "").takeIf { it.length >= r.length } }
            .takeWhile { kind(it) == null }.toList()
        if (missing.isNotEmpty() &&
            !NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = protection, error = null)
        ) return@lockedOrNull null
        if (!excluded) {
            val url = NSURL.fileURLWithPath(r)
            if (!url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)) return@lockedOrNull null
            excluded = true
        }
        missing
    }

    private inline fun <T> lockedOrNull(block: () -> T?): T? {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** True when the directory entry is durable, or the filesystem explicitly does not support syncing it. */
    private fun syncDirectory(d: String): Boolean {
        val dirFd = open(d, O_RDONLY)
        if (dirFd < 0) return false
        val synced = fsync(dirFd) == 0
        val error = if (synced) 0 else errno
        close(dirFd)
        return synced || error == EINVAL || error == ENOTSUP
    }
}
