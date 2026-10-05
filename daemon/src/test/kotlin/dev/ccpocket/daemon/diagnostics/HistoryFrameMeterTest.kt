package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

/** The open's history log line and the HistoryApplied line — the data behind them, not the log text's layout. */
class HistoryFrameMeterTest {

    private val history = ConvoHistory("c", listOf(HistoryMessage(ChatRole.USER, "hi")))

    /** A transport that seals inline, like the relay's: reports the encoded size of what it sends. */
    private fun sealer(bytes: Int, shrunk: Boolean = false) = OutboundSink { f -> HistoryFrameMeter.record(f, bytes, shrunk) }

    @Test fun the_sealer_reports_through_the_emit_coroutine_and_only_for_history_frames() = runTest {
        val meter = HistoryFrameMeter(readNanos = 12_000_000)
        withContext(meter) {
            sealer(4_000).emit(SessionLive("c", "/w", sessionId = "s")) // not a history frame: ignored
            KeyedSink("dev:a", sealer(401_233)).emit(history) // through a keyed wrapper, as the relay sink is
        }
        assertEquals(401_233L, meter.frameBytes)
        // outside a metered emit nothing is recorded, and nothing throws
        sealer(9).emit(history)
        assertEquals(401_233L, meter.frameBytes)
    }

    @Test fun the_line_names_read_time_transcript_window_and_frame() = runTest {
        val meter = HistoryFrameMeter(readNanos = 14_600_000)
        val slice = ReplaySlice(List(100) { HistoryMessage(ChatRole.ASSISTANT, "r$it") }, firstSeq = 1389, lastSeq = 1723,
            hasMore = true, sourceRows = 1723, sourceBytes = 6_817_580, budgetDropped = 0)
        // a LAN-only open: the writer encodes on its own coroutine, so the size is unknown here
        assertEquals("c history: read 14 ms, transcript 1723 lines / 6817580 B, window 100 rows (byte budget dropped 0, more above), frame ?",
            meter.line("c", slice, sent = true))
        withContext(meter) { sealer(1_200_000, shrunk = true).emit(history); sealer(1_000_000).emit(history) }
        val line = meter.line("c", slice, sent = true)
        assertTrue(line.endsWith("frame 1200000 B (shrunk to the client's frame cap) to 2 clients"), line)
        assertEquals(1_200_000L, meter.frameBytes)
    }

    @Test fun nothing_sent_and_read_failures_say_so() {
        val meter = HistoryFrameMeter(readNanos = 0)
        assertTrue(meter.line("c", ReplaySlice(emptyList(), delta = true, lastSeq = 9), sent = false).endsWith("delta 0 rows (byte budget dropped 0), nothing to send"))
        assertTrue(meter.line("c", ReplaySlice(emptyList(), readError = "gone"), sent = false).endsWith("read failed — not sent"))
        assertNull(meter.frameBytes)
    }

    @Test fun an_acknowledged_open_logs_its_elapsed_time_and_reports_the_frame_size() = runTest {
        val records = mutableListOf<DiagnosticRecord>()
        Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING, "test",
            DiagnosticSink { records.add(it) }, successSamplePercent = 100))
        try {
            val owner = KeyedSink("owner-meter", OutboundSink {}, false)
            val context = DiagnosticContext("1234567890abcdef1234567890abcdef", 5)
            SessionOpenDiagnostics(owner, context).complete("convo", 100, true, "complete", 1723, 0, frameBytes = 401_233)
            // another connection, conversation or attempt: no line
            assertNull(SessionOpenDiagnostics.applied(HistoryApplied("convo", context), KeyedSink("owner-other", OutboundSink {}, false)))
            assertNull(SessionOpenDiagnostics.applied(HistoryApplied("other", context), owner))
            val line = assertNotNull(SessionOpenDiagnostics.applied(HistoryApplied("convo", context), owner))
            assertTrue(line.startsWith("convo HistoryApplied +"), line)
            assertTrue(line.endsWith("frame 401233 B)"), line)
            assertNull(SessionOpenDiagnostics.applied(HistoryApplied("convo", context), owner)) // once
            assertEquals(401_233L, records.single().metrics.byteCount) // the existing APPLY stage carries it
        } finally { Diagnostics.install(null) }
    }

    @Test fun the_applied_line_splits_the_open_into_daemon_and_round_trip() {
        assertEquals("c HistoryApplied +7200 ms after OpenSession (history out at +31 ms, frame 401233 B)",
            SessionOpenDiagnostics.appliedLine("c", openedAt = 1_000_000_000, historySentAt = 1_031_000_000,
                appliedAt = 8_200_000_000, frameBytes = 401_233))
    }
}
