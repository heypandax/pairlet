package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Technical receipt only: no install paths, command lines or user identity enter the report. A
 * switched artifact is not healthy until a different process of that version authenticates relay. */
object UpgradeReceipt {
    private fun path(): Path = DaemonDiagnostics.configPath().resolveSibling("upgrade-receipt")
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,95}")
    fun switched(version: String) {
        if (!versionPattern.matches(version) || !DaemonDiagnostics.enabled()) return
        runCatching {
            val target = path()
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, "upgrade-receipt-", ".tmp")
            try {
                Files.writeString(temp, "$version\n${ProcessHandle.current().pid()}\n${System.currentTimeMillis()}\n")
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } // Best effort; diagnostic storage must never turn a successful upgrade into failure.
    }
    @Synchronized fun authenticated(version: String) {
        runCatching {
            val target = path()
            if (!Files.exists(target)) return
            if (!DaemonDiagnostics.enabled() || Files.size(target) > 256) { Files.deleteIfExists(target); return }
            val fields = Files.readAllLines(target)
            if (fields.size != 3 || !versionPattern.matches(fields[0])) { Files.deleteIfExists(target); return }
            val age = fields[2].toLongOrNull()?.let { System.currentTimeMillis() - it }
            if (age == null || age !in 0..86_400_000L) { Files.deleteIfExists(target); return }
            if (fields[0] != version || fields[1] == ProcessHandle.current().pid().toString()) return
            Files.deleteIfExists(target)
            Diagnostics.report(ErrorPath.UPDATE, Stage.RESTART, ErrorCode.OK,
                metrics = SafeMetrics(resultQuality = ResultQuality.COMPLETE))
        }
    }
    fun clear() { runCatching { Files.deleteIfExists(path()) } }
}
