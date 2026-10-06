package dev.ccpocket.app.data

import dev.ccpocket.app.net.LocalIpv4
import dev.ccpocket.app.net.NetKind
import dev.ccpocket.app.net.NetworkSnapshot
import dev.ccpocket.app.net.RepathController
import dev.ccpocket.app.net.parseIpv4
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Role
import dev.ccpocket.protocol.FetchImage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImageContent
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImageRefs
import dev.ccpocket.protocol.SessionLive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * #404 at the repository: a Ready link on relay, a foreground return, and what the planned switch does (or must
 * not do). The relay "socket" is [PocketRepository.dialForTest] parked forever; once Ready, that seam is lifted so
 * the next launch runs the real direct-first path into [PocketRepository.directConnectForTest].
 */
class RepathRepositoryTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var scope: CoroutineScope
    private val wifi = NetworkSnapshot(listOf(LocalIpv4(NetKind.WIFI, parseIpv4("192.168.1.20")!!, 24)))
    private var relayDials = 0
    private var directDials = 0
    private var probes = 0

    @BeforeTest fun setUp() {
        scheduler = TestCoroutineScheduler()
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    }
    @AfterTest fun tearDown() = scope.cancel()

    private fun readyOnRelay(probeOk: Boolean = true, enabled: Boolean = true) = PocketRepository(scope).apply {
        paired.value = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct-test", daemonPub = "pk",
            deviceId = "dev", credential = "cred", directUrl = "ws://192.168.1.5:8799")
        sameMachineClient = false
        networkSnapshotProvider = { wifi }
        repathEnabledOverride = enabled
        tcpProbeForTest = { _, _ -> probes++; probeOk }
        dialForTest = { _, _ -> relayDials++; awaitCancellation() }
        directConnectForTest = { _, _ -> directDials++; awaitCancellation() }
        startRelay()
        receiveControlForTest(Attached(Role.DEVICE, "acct-test"))
        receiveForTest(Directories(emptyList()))
        assertEquals(ConnPhase.Ready, phase.value)
        dialForTest = null // from here a launch takes the real direct-first path
    }

    private fun advance(ms: Long) { scheduler.advanceTimeBy(ms); scheduler.runCurrent() }

    @Test fun idleAndReachableSwitchesOnceWithoutAReconnectingBanner() {
        val r = readyOnRelay()
        r.onAppForeground()
        advance(RepathController.FOREGROUND_DELAY_MS + 1)
        assertEquals(1, probes)
        assertEquals(1, directDials, "the planned reconnect is direct-first")
        assertEquals(ConnPhase.Ready, r.phase.value, "the fresh Ready-hold covers the switch — no Reconnecting flash")
    }

    @Test fun probeFailureLeavesTheRelayLinkUntouched() {
        val r = readyOnRelay(probeOk = false)
        r.onAppForeground()
        advance(RepathController.FOREGROUND_DELAY_MS + 1)
        assertEquals(1, probes)
        assertEquals(0, directDials)
        assertEquals(1, relayDials, "no relaunch of any kind")
        assertEquals(ConnPhase.Ready, r.phase.value)
    }

    @Test fun busyDefersTheSwitch() {
        val r = readyOnRelay()
        r.streaming.value = true
        r.onAppForeground()
        advance(RepathController.FOREGROUND_DELAY_MS + 1)
        assertEquals(1, probes)
        assertEquals(0, directDials)
        r.streaming.value = false
        advance(RepathController.BUSY_RECHECK_MS)
        assertEquals(1, directDials, "the 15 s recheck switches once the turn ended")
    }

    @Test fun aFullImageReplyMustArriveBeforeThePlannedSwitch() {
        val r = readyOnRelay()
        val sent = mutableListOf<Frame>()
        r.onSendForTest = { sent += it }
        r.receiveForTest(SessionLive("c1", "/w", "s1", executing = false))
        val ref = ImageRefs.of(byteArrayOf(1, 2, 3))
        r.requestFullImage(ref, 10L, 0)
        val request = sent.filterIsInstance<FetchImage>().single()
        r.onAppForeground()
        advance(RepathController.FOREGROUND_DELAY_MS + 1)
        assertEquals(0, directDials, "the image reply still belongs to the relay socket")

        r.receiveForTest(ImageContent("c1", ref, requestId = request.requestId, image = ImageData("image/png", "AQID")))
        assertTrue(ref in r.fullImages)
        assertFalse(ref in r.fullImagePending)
        advance(RepathController.BUSY_RECHECK_MS)
        assertEquals(1, directDials, "a completed image no longer prevents repathing")
    }

    @Test fun anImageTimeoutReleasesThePlannedSwitch() {
        val r = readyOnRelay()
        r.onSendForTest = {}
        r.receiveForTest(SessionLive("c1", "/w", "s1", executing = false))
        val ref = ImageRefs.of(byteArrayOf(1, 2, 3))
        r.requestFullImage(ref, 10L, 0)
        r.onAppForeground()
        advance(RepathController.FOREGROUND_DELAY_MS + 1)
        assertEquals(0, directDials)
        advance(PocketRepository.FULL_IMAGE_TIMEOUT_MS + RepathController.BUSY_RECHECK_MS)
        assertFalse(ref in r.fullImagePending)
        assertTrue(ref in r.fullImageUnavailable)
        assertEquals(1, directDials, "an unanswered image cannot pin the relay forever")
    }

    @Test fun disabledDoesNothing() {
        val r = readyOnRelay(enabled = false)
        r.onAppForeground()
        advance(RepathController.TIMER_CAP_MS * 2)
        assertEquals(0, probes); assertEquals(0, directDials); assertEquals(1, relayDials)
    }

    @Test fun disconnectVoidsTheRelayTimer() {
        val r = readyOnRelay(probeOk = false)
        advance(RepathController.TIMER_START_MS)
        assertEquals(1, probes, "the relay timer fires at 60 s")
        r.disconnect()
        advance(RepathController.TIMER_CAP_MS * 2)
        assertEquals(1, probes, "after leaving the computer the old timer never fires again")
    }
}
