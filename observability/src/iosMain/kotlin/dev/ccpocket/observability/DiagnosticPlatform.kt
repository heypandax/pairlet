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
    // K/N frames include native addresses and paths. Keep only project symbols and, when present,
    // source basenames/lines; never retain raw frames, exception headers, messages, or causes.
    val frames = error.getStackTrace().asSequence().mapNotNull { raw ->
        safeNativeFrame(raw)
    }.take(24).toList()
    return SafeException(SafeSymbols.type(error::class.simpleName), frames)
}
