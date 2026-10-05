package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frozen features' CLIENT CACHES of one computer's daemon truth (bridges). Their logic is
 * untouched; only what disconnect() leaves behind is pinned here — each list is re-pulled from the next daemon
 * when its surface opens, so dropping it costs nothing, and keeping it would let a list action on the next
 * computer name the previous computer's rows.
 */
class DisconnectFrozenFeatureCacheTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-a", daemonPub = "pk", deviceId = "dev", credential = "cred")
    }

    @Test
    fun disconnectDropsTheFrozenFeaturesDaemonListings() {
        val r = repo()
        r.receiveForTest(SessionLive("c-a", "/a", "sid-a", executing = false))
        r.receiveForTest(BridgeListing(listOf(BridgeInfo("feishu-a"))))
        assertTrue(r.bridges.isNotEmpty(), "bridges")
        assertTrue(r.bridgesLoaded.value, "bridgesLoaded")

        r.disconnect()

        assertTrue(r.bridges.isEmpty(), "revoking from this list would name A's bridges to B")
        assertFalse(r.bridgesLoaded.value)
        assertFalse(r.bridgesUnavailable.value)
    }
}
