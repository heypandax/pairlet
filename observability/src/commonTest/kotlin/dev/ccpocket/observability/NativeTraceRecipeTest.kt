package dev.ccpocket.observability

import kotlin.test.*

class NativeTraceRecipeTest {
    private fun record(outcome: Outcome, id: String = "00000064000000000000000000000001"): DiagnosticRecord {
        var record: DiagnosticRecord? = null
        val reporter = DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test", DiagnosticSink { record = it; true },
            epochMs = { 1_800_000_000_000L }, successSamplePercent = 100)
        reporter.begin(ErrorPath.SESSION_OPEN, id).finish(outcome)
        return record!!
    }

    @Test fun unknownAndRecoveryCannotBecomeSuccessfulManualTransactions() {
        val unknown = record(Outcome.UNKNOWN)
        assertEquals(DiagnosticKind.RESULT, unknown.kind)
        assertNull(NativeTraceRecipe.from(unknown))
        val complete = record(Outcome.SUCCESS)
        assertNotNull(NativeTraceRecipe.from(complete))
        assertNull(NativeTraceRecipe.from(complete.copy(kind = DiagnosticKind.RECOVERY, outcome = Outcome.RECOVERED)))
        assertNull(NativeTraceRecipe.from(record(Outcome.SUCCESS, "00000065000000000000000000000001")))
    }

    @Test fun childCountAndTimestampsAreBoundedWithoutLosingParentIdentity() {
        val record = record(Outcome.FAILURE).copy(elapsedMs = 2000,
            parentSpanId = "1234567890abcdef",
            steps = List(40) { DiagnosticStep(Stage.READ, if (it % 2 == 0) Long.MAX_VALUE else -1, ErrorCode.OK) })
        val recipe = assertNotNull(NativeTraceRecipe.from(record))
        assertTrue(recipe.failed)
        assertEquals("1234567890abcdef", recipe.parentSpanId)
        assertEquals(15, recipe.children.size)
        assertTrue(recipe.children.all { it.startMs in recipe.startMs..recipe.endMs && it.endMs in it.startMs..recipe.endMs })
    }
}
