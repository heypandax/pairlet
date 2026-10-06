package dev.ccpocket.app.net

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #403: the getifaddrs walk runs on the simulator without crashing and sees lo0. */
class NetworkSnapshotIosTest {
    @Test
    fun snapshotDoesNotCrashAndSeesLoopback() {
        val snap = assertNotNull(localNetworkSnapshot(), "getifaddrs should succeed on the simulator")
        assertTrue(snap.hasLoopback, "lo0 is always up")
        snap.ipv4.forEach { assertTrue(it.prefixLength in 0..32, "prefix from netmask bits: $it") }
    }
}
