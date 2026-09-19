package dev.ccpocket.app.data

import dev.ccpocket.app.net.RelayE2EConnection
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.DiagnosticId
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.PeerPresence
import dev.ccpocket.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a connection episode may claim (2026-09 production review): most EP-03/EP-05 issues sat exactly on
 * the UI grace timers — a slow dial that attached seconds later, or the user's computer simply being off.
 * Neither is a fault; only an attempt that really died, in front of the user, spends the error budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDiagnosticTest {
    private class Harness : AutoCloseable {
        val clock = TestCoroutineScheduler()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(clock))
        // accepts the TCP handshake (kernel backlog) and never answers the upgrade: a dial that stays in flight
        private val blackHole = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val records = mutableListOf<DiagnosticRecord>()
        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon("ws://127.0.0.1:${blackHole.localPort}", "acct-test", "pk", "dev", "cred")
            useRelay = true
            sessionActive.value = true
        }
        init {
            Diagnostics.install(DiagnosticReporter(Component.DESKTOP, Environment.STAGING, "test",
                DiagnosticSink { synchronized(records) { records.add(it) } }))
        }
        fun connection() = synchronized(records) { records.filter { it.path == ErrorPath.CONNECTION } }
        fun elapse(ms: Long) { clock.advanceTimeBy(ms); clock.runCurrent() }
        override fun close() { scope.cancel(); blackHole.close(); Diagnostics.install(null) }
    }

    @Test fun anOfflineComputerIsAResultNotAnIssue() = Harness().use { h ->
        h.repo.retryConnection()
        h.repo.receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        h.repo.receiveControlForTest(PeerPresence(false))
        assertEquals(ConnPhase.ComputerOffline, h.repo.phase.value)
        val record = h.connection().single { it.outcome == Outcome.FAILURE }
        assertEquals(DiagnosticKind.RESULT, record.kind)
        assertEquals(Stage.WAIT, record.stage, "attached fine — what is missing is the computer")
        assertEquals(ErrorCode.UNAVAILABLE, record.code)
        assertTrue(h.connection().none { it.kind == DiagnosticKind.ERROR })
    }

    @Test fun theUnreachableGraceIsNotAVerdictWhileTheDialIsInFlight() = Harness().use { h ->
        h.repo.retryConnection()
        h.elapse(PocketRepository.RECONNECT_GRACE_MS)
        assertEquals(ConnPhase.RelayUnreachable, h.repo.phase.value, "the screen still tells the user what it sees")
        assertTrue(h.connection().none { it.outcome == Outcome.FAILURE }, "a slow dial has not failed yet")

        // …and the usual ending: it attaches a moment later. Nothing about this episode is an error.
        h.repo.receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        h.repo.receiveForTest(Directories(emptyList()))
        assertEquals(ConnPhase.Ready, h.repo.phase.value)
        assertTrue(h.connection().none { it.kind == DiagnosticKind.ERROR })
    }

    @Test fun anAttemptThatReallyDiedIsStillReported() = Harness().use { h ->
        h.repo.retryConnection()
        h.elapse(PocketRepository.CONNECT_TIMEOUT_MS) // the wedge watchdog tears the silent dial down
        val failure = h.connection().single { it.outcome == Outcome.FAILURE }
        assertEquals(DiagnosticKind.ERROR, failure.kind)
        assertEquals(Stage.CONNECT, failure.stage)
        assertEquals(ErrorCode.UNAVAILABLE, failure.code)
    }

    @Test fun aBackgroundedRetryLadderReportsNoIssues() = Harness().use { h ->
        h.repo.onAppBackground()
        h.repo.retryConnection()
        h.elapse(PocketRepository.CONNECT_TIMEOUT_MS)
        assertTrue(h.connection().none { it.kind == DiagnosticKind.ERROR }, "nobody is looking at a backgrounded dial")
        assertTrue(h.connection().any { it.code == ErrorCode.TIMEOUT && it.kind == DiagnosticKind.LOG }, "the wedge itself stays on the log trail")
    }

    @Test fun aLabelledAttachWithoutAPeerMeansTheComputerIsOffline() {
        val own = DiagnosticId("0123456789abcdef0123456789abcdef")
        val peer = DiagnosticId("fedcba9876543210fedcba9876543210")
        assertTrue(RelayE2EConnection.peerOfflineAtAttach(Attached(Role.DEVICE, "a", connectionId = own)))
        assertFalse(RelayE2EConnection.peerOfflineAtAttach(Attached(Role.DEVICE, "a", connectionId = own, peerConnectionId = peer)))
        assertFalse(RelayE2EConnection.peerOfflineAtAttach(Attached(Role.DEVICE, "a")), "an old relay labels nothing: unknown, not offline")
    }
}
