package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real repository watchdogs: distinguish receipt loss from acknowledged-but-silent execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptDiagnosticTest {
    private class Harness : AutoCloseable {
        val clock = TestCoroutineScheduler()
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clock))
        val records = mutableListOf<DiagnosticRecord>()
        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
            convoId.value = "c1"
            workdir.value = "/tmp/diagnostic-fixture"
            promptReceiptTimeoutMs = 50
            promptTurnTimeoutMs = 80
            onSendForTest = {}
        }
        init {
            Diagnostics.install(DiagnosticReporter(Component.DESKTOP, Environment.STAGING, "test",
                DiagnosticSink { records.add(it) }))
        }
        fun send() {
            assertTrue(repo.sendPrompt("fixture content must stay local"))
            clock.runCurrent()
        }
        fun ack() {
            val id = repo.messages.filterIsInstance<ChatItem.User>().last().promptId!!
            repo.receiveForTest(PromptAck("c1", id))
            clock.runCurrent()
        }
        fun output() { repo.receiveForTest(AssistantChunk("c1", 0, StreamPiece.Text("fixture output"))) }
        fun elapse() { clock.advanceTimeBy(200); clock.runCurrent() }
        override fun close() { scope.cancel(); Diagnostics.install(null) }
    }

    @Test fun missingReceiptIsAnAckTimeout() = Harness().use { h ->
        h.send()
        h.elapse()
        assertTrue(h.repo.sendStalled.value)
        val record = h.records.single { it.path == ErrorPath.PROMPT && it.kind == DiagnosticKind.ERROR }
        assertEquals(Stage.ACK, record.stage)
        assertEquals(ErrorCode.TIMEOUT, record.code)
        assertEquals(DiagnosticKind.ERROR, record.kind)
    }

    @Test fun receivedAckWithNoOutputIsAnExecutionTimeout() = Harness().use { h ->
        h.send()
        h.ack()
        h.elapse()
        assertFalse(h.repo.sendStalled.value)
        assertTrue(h.repo.turnStalled.value)
        val record = h.records.single { it.path == ErrorPath.PROMPT && it.kind == DiagnosticKind.ERROR }
        assertEquals(Stage.EXECUTE, record.stage)
        assertEquals(ErrorCode.TIMEOUT, record.code)
    }

    @Test fun outputAndExpectedQueueWaitDoNotReportTimeouts() = Harness().use { h ->
        h.send()
        h.ack()
        h.output()
        h.elapse()
        assertFalse(h.repo.turnStalled.value)
        h.send()
        h.ack()
        h.elapse()
        assertTrue(h.repo.turnQueued.value)
        assertFalse(h.repo.turnStalled.value)
        assertTrue(h.records.none { it.path == ErrorPath.PROMPT && it.kind == DiagnosticKind.ERROR })
    }
}
