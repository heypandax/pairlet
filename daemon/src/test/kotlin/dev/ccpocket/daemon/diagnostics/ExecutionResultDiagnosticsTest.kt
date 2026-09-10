package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ExecutionResultDiagnosticsTest {
    @Test fun unknownBackgroundReceiptsAreNotFailuresAndCannotSettleAnotherExecution() {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val operation = BackgroundExecutionDiagnostics()
            operation.frame(PromptProgress("c", operation.context.copy(attempt = 1), "complete", "failure"))
            assertTrue(records.isEmpty())
            operation.frame(PromptProgress("c", operation.context, "complete", "future_terminal"))
            operation.frame(PromptProgress("c", operation.context, "complete", "success"))
            assertEquals(Outcome.UNKNOWN, records.single().outcome)
            assertEquals(ResultQuality.UNKNOWN, records.single().metrics.resultQuality)
            assertEquals(DiagnosticKind.RESULT, records.single().kind)
            assertEquals(ErrorCode.INCOMPLETE, records.single().code)
        } finally { Diagnostics.install(null) }
    }

    @Test fun handledAndMissingExecutionEvidenceStayUnknownWhileActualFailureIsAnError() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val prompts = PromptDiagnostics("c", backgroundScope) {}
            fun context() = DiagnosticContext(Diagnostics.newId())
            prompts.register("handled", context()); prompts.handled("handled")
            prompts.register("unknown", context()); prompts.consumed("unknown", 1)
            prompts.complete(1, "future_terminal")
            prompts.register("failed", context()); prompts.consumed("failed", 2)
            prompts.complete(2, "failure")
            prompts.close()
            assertEquals(listOf(Outcome.UNKNOWN, Outcome.UNKNOWN, Outcome.FAILURE), records.map { it.outcome })
            assertTrue(records.take(2).all { it.kind == DiagnosticKind.RESULT && it.code == ErrorCode.INCOMPLETE })
            assertEquals(DiagnosticKind.ERROR, records.last().kind)
            assertEquals(ErrorCode.PROCESS_EXITED, records.last().code)
        } finally { Diagnostics.install(null) }
    }
}
