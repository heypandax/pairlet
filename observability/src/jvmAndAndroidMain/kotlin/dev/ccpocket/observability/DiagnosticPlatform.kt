package dev.ccpocket.observability

import java.util.UUID

internal actual class DiagnosticLock {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}
internal actual fun diagnosticId(): String = UUID.randomUUID().toString().replace("-", "")
internal actual fun diagnosticEpochMs(): Long = System.currentTimeMillis()
internal actual fun safeException(error: Throwable): SafeException = SafeException(
    SafeSymbols.type(error.javaClass.name),
    error.stackTrace.asSequence().mapNotNull {
        SafeSymbols.frame("${it.className}.${it.methodName}", it.fileName, it.lineNumber)
    }.take(24).toList(),
)
