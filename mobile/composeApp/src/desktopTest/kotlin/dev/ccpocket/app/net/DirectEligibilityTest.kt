package dev.ccpocket.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** #403 / TRANSPORT-AUTO-REPATH-V1 3.2: the pre-dial eligibility table. */
class DirectEligibilityTest {
    private fun ip(s: String) = parseIpv4(s)!!
    private fun snap(vararg nets: LocalIpv4) = NetworkSnapshot(nets.toList())
    private val wifi24 = LocalIpv4(NetKind.WIFI, ip("192.168.1.20"), 24)
    private val cell = LocalIpv4(NetKind.CELLULAR, ip("10.64.3.7"), 30)
    private val skip = { r: SkipReason -> DirectEligibility.Skip(r) }

    @Test fun loopbackOnlyFromTheSameMachine() {
        for (url in listOf("ws://127.0.0.1:8799/e2e", "ws://localhost:8799", "ws://[::1]:8799", "ws://127.5.6.7:1")) {
            assertEquals(DirectEligibility.Dial, directEligibility(url, snap(wifi24), sameMachineClient = true), url)
            assertEquals(skip(SkipReason.LOOPBACK_NOT_SAME_MACHINE), directEligibility(url, snap(wifi24), sameMachineClient = false), url)
        }
    }

    @Test fun sameSubnetDialsOtherSubnetSkips() {
        assertEquals(DirectEligibility.Dial, directEligibility("ws://192.168.1.5:8799", snap(wifi24), false))
        assertEquals(skip(SkipReason.OTHER_SUBNET), directEligibility("ws://192.168.2.5:8799", snap(wifi24), false))
        assertEquals(DirectEligibility.Dial,
            directEligibility("ws://10.1.2.3:8799", snap(LocalIpv4(NetKind.WIRED, ip("10.9.0.1"), 8)), false))
    }

    @Test fun prefixLengthDecides() {
        val wide = LocalIpv4(NetKind.WIFI, ip("172.16.4.1"), 12)
        val narrow = LocalIpv4(NetKind.WIFI, ip("172.16.4.1"), 28)
        assertEquals(DirectEligibility.Dial, directEligibility("ws://172.20.0.9:1", snap(wide), false))
        assertEquals(skip(SkipReason.OTHER_SUBNET), directEligibility("ws://172.20.0.9:1", snap(narrow), false))
        assertEquals(DirectEligibility.Dial, directEligibility("ws://172.16.4.14:1", snap(narrow), false))
        // a /0 or out-of-range prefix must never turn every address into "local"
        assertEquals(skip(SkipReason.OTHER_SUBNET),
            directEligibility("ws://10.0.0.1:1", snap(LocalIpv4(NetKind.WIFI, ip("192.168.1.2"), 0)), false))
    }

    @Test fun cellularOnlyIsItsOwnReasonAndWifiPlusCellularUsesWifi() {
        assertEquals(skip(SkipReason.CELLULAR_ONLY), directEligibility("ws://10.64.3.5:8799", snap(cell), false),
            "a cellular interface sharing the private range never qualifies")
        assertEquals(DirectEligibility.Dial, directEligibility("ws://192.168.1.9:8799", snap(wifi24, cell), false))
        assertEquals(skip(SkipReason.OTHER_SUBNET), directEligibility("ws://10.64.3.5:8799", snap(wifi24, cell), false))
        assertEquals(skip(SkipReason.NO_LOCAL_NETWORK), directEligibility("ws://192.168.1.9:8799", snap(), false))
        assertEquals(skip(SkipReason.NO_LOCAL_NETWORK),
            directEligibility("ws://192.168.1.9:1", snap(LocalIpv4(NetKind.OTHER, ip("192.168.1.1"), 24)), false),
            "tunnels/VMs never vouch for an address")
    }

    @Test fun linkLocalIsTreatedLikePrivate() {
        assertEquals(skip(SkipReason.OTHER_SUBNET), directEligibility("ws://169.254.10.1:1", snap(wifi24), false))
        assertEquals(DirectEligibility.Dial,
            directEligibility("ws://169.254.10.1:1", snap(LocalIpv4(NetKind.WIRED, ip("169.254.3.3"), 16)), false))
    }

    @Test fun publicHostnamesIpv6AndMalformedAlwaysDial() {
        for (url in listOf("ws://8.8.8.8:8799", "wss://my-mac.local:8799", "ws://[fe80::1]:8799", "ws://192.168.1:8799",
            "ws://192.168.1.300:8799", "not a url", "ws://:8799")) {
            assertEquals(DirectEligibility.Dial, directEligibility(url, snap(cell), false), url)
        }
    }

    @Test fun noSnapshotAllowsTheDial() {
        assertEquals(DirectEligibility.Dial, directEligibility("ws://192.168.7.7:8799", null, false))
        // loopback rule doesn't need a snapshot
        assertEquals(skip(SkipReason.LOOPBACK_NOT_SAME_MACHINE), directEligibility("ws://127.0.0.1:1", null, false))
    }

    @Test fun interfaceNamesMapToKinds() {
        assertEquals(NetKind.WIFI, interfaceKind("wlan0", mobile = true))
        assertEquals(NetKind.WIRED, interfaceKind("eth0", mobile = true))
        for (n in listOf("rmnet_data0", "ccmni1", "pdp0")) assertEquals(NetKind.CELLULAR, interfaceKind(n, mobile = true), n)
        assertEquals(NetKind.OTHER, interfaceKind("tun0", mobile = true))
        assertEquals(NetKind.WIRED, interfaceKind("en0", mobile = false))
        for (n in listOf("utun3", "tailscale0", "wg0", "docker0", "bridge100", "vmnet8", "tap1"))
            assertEquals(NetKind.OTHER, interfaceKind(n, mobile = false), n)
        assertEquals(NetKind.WIFI, iosInterfaceKind("en0"))
        assertEquals(NetKind.CELLULAR, iosInterfaceKind("pdp_ip0"))
        assertEquals(NetKind.OTHER, iosInterfaceKind("utun2"))
    }

    @Test fun desktopSnapshotSeesLoopback() {
        val s = localNetworkSnapshot()
        assertEquals(true, s?.hasLoopback)
    }
}
