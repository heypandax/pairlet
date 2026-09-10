package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.daemon.conversation.*
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.*

class SessionOpenDiagnosticsTest {
    @Test fun lateFailureFromReplacedSenderCannotDeleteTheNewReceipt() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val entered = CompletableDeferred<Unit>()
            val failOld = CompletableDeferred<Unit>()
            val old = KeyedSink("same-owner", OutboundSink {
                entered.complete(Unit); failOld.await(); throw IllegalStateException("fixture send failed")
            }, false)
            val current = KeyedSink("same-owner", OutboundSink {}, false)
            val context = DiagnosticContext("1234567890abcdef1234567890abcdef", 4)
            val sending = launch { runCatching { SessionOpenDiagnostics(old, context).complete("c", 1, true, "complete") } }
            entered.await()
            SessionOpenDiagnostics(current, context).complete("c", 1, true, "complete")
            failOld.complete(Unit); sending.join()
            SessionOpenDiagnostics.applied(HistoryApplied("c", context), current)
            assertEquals(listOf(Outcome.UNKNOWN, Outcome.SUCCESS), records.map { it.outcome })
        } finally { Diagnostics.install(null) }
    }

    @Test fun missingApplyReceiptsExpireWithoutClaimingBusinessFailure() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val original = KeyedSink("owner-expiry", OutboundSink {}, false)
            val context = DiagnosticContext("1234567890abcdef1234567890abcdef", 3)
            SessionOpenDiagnostics(original, context).complete("convo", 2, true, "complete")
            SessionOpenDiagnostics.expireReceipts(System.nanoTime() + 121_000_000_000L)
            SessionOpenDiagnostics.applied(HistoryApplied("convo", context), original)
            assertEquals(1, records.size)
            assertEquals(ErrorCode.INCOMPLETE, records.single().code)
            assertEquals(ResultQuality.UNKNOWN, records.single().metrics.resultQuality)
            assertTrue(records.none { it.kind == DiagnosticKind.ERROR })
        } finally { Diagnostics.install(null) }
    }

    @Test fun onlyTheSameOwnerConversationAndAttemptCanAcknowledgeTheReplay() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val frames = mutableListOf<Frame>()
            val original = KeyedSink("owner-a", OutboundSink { frames += it }, watching = true)
            val context = DiagnosticContext("1234567890abcdef1234567890abcdef", 2)
            val sink = SessionOpenDiagnostics(original, context)
            assertEquals(sinkKey(original), sinkKey(sink))
            assertEquals(original.isWatching(), sink.isWatching())
            sink.complete("convo", 0, false, "not_required")
            sink.complete("convo", 0, false, "not_required")
            assertEquals(1, frames.size)
            val applied = HistoryApplied("convo", context)
            SessionOpenDiagnostics.applied(applied, KeyedSink("owner-b", OutboundSink {}, false))
            SessionOpenDiagnostics.applied(applied.copy(convoId = "other"), original)
            SessionOpenDiagnostics.applied(applied.copy(diagnostic = context.copy(attempt = 1)), original)
            assertTrue(records.isEmpty())
            SessionOpenDiagnostics.applied(applied, original)
            SessionOpenDiagnostics.applied(applied, original)
            assertEquals(1, records.size)
            assertEquals(Stage.APPLY, records.single().stage)
            assertEquals(ResultQuality.COMPLETE, records.single().metrics.resultQuality)
        } finally { Diagnostics.install(null) }
    }

    @Test fun legacySinksNeverGetANewFrameAndWriteErrorsKeepTheirOriginalBehavior() = runTest {
        val frames = mutableListOf<Frame>()
        val legacy = OutboundSink { frames += it }
        legacy.completeInitialHistory("convo")
        assertTrue(frames.isEmpty())
        val error = IllegalStateException("fixture body remains local")
        val wrapper = SessionOpenDiagnostics(OutboundSink { throw error }, DiagnosticContext("abcdef1234567890abcdef1234567890"))
        val thrown = assertFailsWith<IllegalStateException> { wrapper.complete("convo", 0, false, "not_required") }
        assertSame(error, thrown)
    }
}
