package dev.ccpocket.observability

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

actual fun diagnosticBudgetStore(component: Component, environment: Environment, directory: String?, counters: Boolean): DiagnosticBudgetStore {
    // Android must supply its Context.filesDir; a JVM user.home fallback is not app-private on Android.
    if (component == Component.ANDROID && directory == null) return DiagnosticBudgetStore { false }
    val root = directory ?: Paths.get(System.getProperty("user.home"), ".cc-pocket", if (counters) "diagnostic-counters" else "diagnostic-budgets").toString()
    return FileDiagnosticBudgetStore(Paths.get(root, "${component.name.lowercase()}-${environment.name.lowercase()}.json"))
}

internal class FileDiagnosticBudgetStore(private val path: Path) : DiagnosticBudgetStore {
    override fun update(transform: (String?) -> String): Boolean = runCatching {
        Files.createDirectories(path.parent)
        // tryLock fails closed instead of waiting for another process. Only diagnostic workers do I/O.
        FileChannel.open(path.resolveSibling("${path.fileName}.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lease = channel.tryLock() ?: return false
            lease.use {
                val raw = if (!Files.exists(path)) null else readBounded()
                val next = transform(raw).toByteArray(Charsets.UTF_8)
                require(next.size <= 4096)
                val pending = path.resolveSibling("${path.fileName}.pending")
                try {
                    FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                        val buffer = ByteBuffer.wrap(next)
                        while (buffer.hasRemaining()) output.write(buffer)
                        output.force(true)
                    }
                    Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(pending) }
                true
            }
        }
    }.getOrDefault(false)

    private fun readBounded(): String = Files.newInputStream(path).use { input ->
        val bytes = ByteArray(4097)
        var used = 0
        while (used < bytes.size) {
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) break
            used += count
        }
        if (used > 4096) "invalid" else String(bytes, 0, used, Charsets.UTF_8)
    }
}
