package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.Component
import dev.ccpocket.observability.sentry.SentryRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Separate from identity/relay configuration: no E2E credentials or account data are read here. */
object DaemonDiagnostics {
    fun configPath(): Path = Path.of(System.getProperty("user.home"), ".cc-pocket", "diagnostics.properties")

    fun enabled(path: Path = configPath()): Boolean = runCatching {
        val p = Properties()
        if (Files.exists(path)) Files.newInputStream(path).use(p::load)
        p.getProperty("enabled") == "true"
    }.getOrDefault(false)

    fun setEnabled(value: Boolean, path: Path = configPath()) {
        if (!value && path == configPath()) UpgradeReceipt.clear()
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "diagnostics-", ".tmp")
        try {
            Files.writeString(tmp, "enabled=$value\n")
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(tmp) }
    }

    @Volatile private var watcher: java.util.concurrent.ScheduledExecutorService? = null

    @Synchronized fun start(version: String) {
        if (watcher != null) return
        var previous = enabled()
        SentryRuntime.configure(Component.DAEMON, version, previous)
        watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cc-pocket-diagnostic-preference").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                SessionOpenDiagnostics.expireReceipts()
                val current = enabled()
                if (current != previous) {
                    if (!current) UpgradeReceipt.clear()
                    SentryRuntime.configure(Component.DAEMON, version, current)
                    previous = current
                }
            }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
