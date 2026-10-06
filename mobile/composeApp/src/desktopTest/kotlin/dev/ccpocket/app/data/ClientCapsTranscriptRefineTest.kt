package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Voice input v2: every connection declares `ClientCaps.supportsTranscriptRefine` — the daemon neither runs a refine
 * for, nor sends `pocket/transcript.refined` to, a connection that did not. Both places the declaration goes out:
 * when a transport is launched, and again on the session the daemon's [DaemonInfo] proved live.
 */
class ClientCapsTranscriptRefineTest {
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(TestCoroutineScheduler()))

    @AfterTest fun tearDown() = scope.cancel()

    @Test fun theLaunchDeclarationIncludesTranscriptRefine() {
        val sent = mutableListOf<Frame>()
        PocketRepository(scope).apply {
            paired.value = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct-caps-refine", daemonPub = "pk",
                deviceId = "dev", credential = "cred")
            onSendForTest = { sent += it }
            dialForTest = { _, _ -> awaitCancellation() }
            startRelay()
        }
        val caps = sent.filterIsInstance<ClientCaps>()
        assertTrue(caps.isNotEmpty(), "a launched transport declares its capabilities")
        assertTrue(caps.all { it.supportsTranscriptRefine })
    }

    @Test fun theRedeclarationOnDaemonInfoIncludesTranscriptRefine() {
        val sent = mutableListOf<Frame>()
        PocketRepository(scope).apply {
            paired.value = null
            onSendForTest = { sent += it }
            receiveForTest(DaemonInfo())
        }
        val caps = sent.filterIsInstance<ClientCaps>()
        assertTrue(caps.isNotEmpty(), "DaemonInfo is answered with the capabilities again")
        assertTrue(caps.all { it.supportsTranscriptRefine })
    }
}
