package dev.ccpocket.observability

import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

internal actual class DiagnosticLock {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
internal actual fun diagnosticId(): String = NSUUID().UUIDString.replace("-", "").lowercase()
internal actual fun diagnosticEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun safeException(error: Throwable): SafeException {
    // K/N frames include native addresses and paths. Extract only our package-qualified symbol;
    // never retain the exception header, message, cause, or a formatted exception string.
    val symbols = Regex("dev\\.ccpocket\\.[A-Za-z0-9_.$<>#-]+")
    val frames = error.getStackTrace().asSequence().mapNotNull { raw ->
        symbols.find(raw)?.value?.let { SafeSymbols.frame(it.replace('#', '.'), null, null) }
    }.take(24).toList()
    return SafeException(SafeSymbols.type(error::class.simpleName), frames)
}
