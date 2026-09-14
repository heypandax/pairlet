@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ccpocket.app.pins

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.posix.EINVAL
import platform.posix.ENOENT
import platform.posix.ENOTSUP
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

actual fun platformProjectPinPersistence(): ProjectPinPersistence = IosProjectPinPersistence

actual class PinLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * `Library/Application Support/project-pins/` inside the app container (app-private, not user-visible). A write
 * creates a fresh 0600 temp file with `O_EXCL`, writes it fully, `fsync`s and closes it, `rename`s it over the
 * target (atomic on APFS) and fsyncs the directory. A failure before the rename is [PinFileWrite.NotWritten]; a
 * failed directory fsync after it is [PinFileWrite.Indeterminate], except EINVAL/ENOTSUP (unsupported) which keeps
 * the documented weaker guarantee. iOS `fsync` does not force the drive cache like `F_FULLFSYNC`; physical
 * power-loss behaviour on a device is not verified.
 */
private object IosProjectPinPersistence : ProjectPinPersistence {

    private val dir: String? by lazy {
        val base = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull()?.let { (it as? NSURL)?.path }
        base?.let { "$it/project-pins" }
    }

    override fun read(name: String): PinFileRead {
        val d = dir ?: return PinFileRead.Failed("no application support directory")
        val path = "$d/$name.json"
        return readProbed(probe(path)) {
            NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.let { PinFileRead.Found(it) }
                ?: PinFileRead.Failed("unreadable")
        }
    }

    override fun write(name: String, text: String): PinFileWrite {
        val d = dir ?: return PinFileWrite.NotWritten
        if (!NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)) {
            return PinFileWrite.NotWritten
        }
        val target = "$d/$name.json"
        val tmp = "$d/.$name.json.${randomPinToken()}.tmp"
        val bytes = text.encodeToByteArray()
        val fd = open(tmp, O_WRONLY or O_CREAT or O_EXCL, 384) // 0600
        if (fd < 0) return PinFileWrite.NotWritten
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
        if (!ok || rename(tmp, target) != 0) {
            unlink(tmp)
            return PinFileWrite.NotWritten
        }
        return if (syncDirectory(d)) PinFileWrite.Durable else PinFileWrite.Indeterminate
    }

    override fun recover(name: String): PinFileRead {
        val d = dir ?: return PinFileRead.Failed("no application support directory")
        return readProbed(probe(d)) {
            if (!syncDirectory(d)) PinFileRead.Failed("directory sync") else read(name)
        }
    }

    override fun list(): PinFileListing {
        val d = dir ?: return PinFileListing.Failed
        return listProbed(probe(d)) {
            val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath(d, null)?.mapNotNull { it as? String }
            if (entries == null) {
                PinFileListing.Failed
            } else {
                PinFileListing.Ready(
                    names = entries.filter { it.endsWith(".json") && !it.startsWith(".") }.map { it.removeSuffix(".json") },
                    hasRecoveryArtifacts = entries.any { !it.startsWith(".") && it.contains(".json.corrupt-") },
                )
            }
        }
    }

    /** `stat` with its errno, so "cannot inspect" (EACCES, EIO, …) is never read as "absent" the way a Boolean
     *  `fileExistsAtPath` would. */
    private fun probe(path: String): PinPathProbe = memScoped {
        val info = alloc<stat>()
        val status = stat(path, info.ptr)
        PinPathProbe.classify(status, if (status == 0) 0 else errno, ENOENT)
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
