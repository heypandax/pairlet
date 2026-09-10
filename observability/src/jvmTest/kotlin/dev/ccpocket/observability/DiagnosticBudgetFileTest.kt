package dev.ccpocket.observability

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.*

class DiagnosticBudgetFileTest {
    @Test fun actualFilesSurviveRestartAndRecoverCorruptionWithoutResettingAllowance() {
        val dir = Files.createTempDirectory("diagnostic-budget-test")
        try {
            val path = dir.resolve("budget.json")
            fun fresh() = DiagnosticBudget(Component.DESKTOP, FileDiagnosticBudgetStore(path))
            repeat(10) { assertTrue(fresh().admit(DiagnosticKind.ERROR, 100)) }
            assertFalse(fresh().admit(DiagnosticKind.ERROR, 100))
            Files.writeString(path, "damaged counter")
            assertFalse(fresh().admit(DiagnosticKind.LOG, 100))
            assertFalse(fresh().admit(DiagnosticKind.ERROR, 100))
            assertTrue(Files.size(path) < 4096)
            assertFalse(Files.exists(dir.resolve("budget.json.pending")))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun competingWriterOrUnavailableDirectoryFailsClosed() {
        val dir = Files.createTempDirectory("diagnostic-budget-lock")
        try {
            val path = dir.resolve("budget.json")
            FileChannel.open(dir.resolve("budget.json.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    assertFalse(DiagnosticBudget(Component.DAEMON, FileDiagnosticBudgetStore(path)).admit(DiagnosticKind.ERROR, 100))
                    assertFalse(Files.exists(path))
                }
            }
            val file = Files.createFile(dir.resolve("not-a-directory"))
            assertFalse(DiagnosticBudget(Component.RELAY, FileDiagnosticBudgetStore(file.resolve("budget"))).admit(DiagnosticKind.ERROR, 100))
            assertFalse(DiagnosticBudget(Component.ANDROID,
                diagnosticBudgetStore(Component.ANDROID, Environment.PRODUCTION)).admit(DiagnosticKind.ERROR, 100))
        } finally { dir.toFile().deleteRecursively() }
    }
}
