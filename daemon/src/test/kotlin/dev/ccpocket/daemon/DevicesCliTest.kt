package dev.ccpocket.daemon

import com.github.ajalt.clikt.testing.test
import dev.ccpocket.daemon.control.LocalControlToken
import dev.ccpocket.daemon.control.LocalOwnerDevice
import dev.ccpocket.daemon.control.OwnerDeviceRow
import dev.ccpocket.daemon.control.OwnerDevicesPlane
import dev.ccpocket.daemon.control.installOwnerDevicesControl
import dev.ccpocket.daemon.relay.LegacyLoopbackGuard
import dev.ccpocket.daemon.relay.LoopbackPair
import dev.ccpocket.daemon.relay.OwnerPairingWatch
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.PairingFingerprint
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * `pairlet devices` and the waiting `pairlet pair` (pairing security phase 0), end to end against the real
 * routes and gate on an ephemeral port, with a fake plane behind them. The token is the one in the test JVM's
 * isolated home (`build/test-home`), exactly the file the commands read — never the real `~/.cc-pocket`.
 */
class DevicesCliTest {

    private class FakePlane : OwnerDevicesPlane {
        val computer = E2ECrypto.generateKeyPair().publicRaw
        val rows = CopyOnWriteArrayList<OwnerDeviceRow>()
        val revoked = CopyOnWriteArrayList<String>()
        var outcome: OwnerPairingWatch.Outcome = OwnerPairingWatch.Outcome.Pending
        override val computerPub: ByteArray get() = computer
        override suspend fun devices() = rows.toList()
        override suspend fun revoke(deviceId: String) = rows.removeIf { it.deviceId == deviceId }.also { if (it) revoked += deviceId }
        override suspend fun awaitPairing(pairingId: String, waitMs: Long) = outcome
        override fun pairingRemainingMs(pairingId: String) = 1_000L
    }

    /** A loopback server shaped like PairLoopback: the guarded legacy `/pair` plus the owner-device routes. */
    private fun <T> serving(plane: FakePlane, pairReply: LoopbackPair? = null, block: (Int) -> T): T {
        val token = LocalControlToken.loadOrCreate()
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                val legacy = LegacyLoopbackGuard.routes(this, token)
                legacy.post("/pair") {
                    call.respondText(PocketJson.encodeToString(LoopbackPair.serializer(), pairReply!!), ContentType.Application.Json)
                }
                installOwnerDevicesControl(plane, token)
            }
        }
        server.start(wait = false)
        try {
            return block(kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0)
        }
    }

    private fun row(id: String) = OwnerDeviceRow(id, E2ECrypto.generateKeyPair().publicRaw, pairedAt = null, firstContactPending = false)

    @Test
    fun devices_lists_every_device_with_its_fingerprint_and_the_computers() {
        val plane = FakePlane().apply { rows += row("AAAAaaaa1111"); rows += row("BBBBbbbb2222") }
        serving(plane) { port ->
            val res = devicesCommand().test(listOf("--pair-port", "$port"))
            assertEquals(0, res.statusCode, res.output)
            assertTrue(PairingFingerprint.of(plane.computer) in res.output, res.output)
            plane.rows.forEach { assertTrue(PairingFingerprint.of(it.pub) in res.output && it.deviceId.take(8) in res.output, res.output) }
            assertTrue("pairlet devices revoke" in res.output)
            // `devices list` is the same listing
            assertTrue(PairingFingerprint.of(plane.computer) in devicesCommand().test(listOf("list", "--pair-port", "$port")).output)
        }
    }

    @Test
    fun revoke_resolves_a_prefix_asks_first_and_refuses_an_ambiguous_one() {
        val plane = FakePlane().apply { rows += row("Qx7v3k2aZZZZ"); rows += row("Qx9aaaaaYYYY") }
        val first = plane.rows[0]
        serving(plane) { port ->
            val ambiguous = devicesCommand().test(listOf("revoke", "Qx", "--pair-port", "$port", "--yes"))
            assertEquals(1, ambiguous.statusCode)
            assertTrue("matches 2 devices" in ambiguous.output, ambiguous.output)
            assertEquals(emptyList(), plane.revoked)

            val none = devicesCommand().test(listOf("revoke", "zzz", "--pair-port", "$port", "--yes"))
            assertEquals(1, none.statusCode)
            assertEquals(emptyList(), plane.revoked)

            val declined = devicesCommand().test(listOf("revoke", "Qx7", "--pair-port", "$port"), stdin = "n\n")
            assertEquals(1, declined.statusCode, declined.output)
            assertEquals(emptyList(), plane.revoked, "answering no revokes nothing")

            // by fingerprint prefix, dashes and case ignored, confirmed with y
            val fp = PairingFingerprint.of(first.pub).replace("-", "").take(6).uppercase()
            val confirmed = devicesCommand().test(listOf("revoke", fp, "--pair-port", "$port"), stdin = "y\n")
            assertEquals(0, confirmed.statusCode, confirmed.output)
            assertEquals(listOf(first.deviceId), plane.revoked)
            assertTrue(PairingFingerprint.of(first.pub) in confirmed.output, confirmed.output)
        }
    }

    @Test
    fun device_matching_rules() {
        val a = LocalOwnerDevice("abcDEF123", "1111-2222-3333-4444-5555")
        val b = LocalOwnerDevice("abcXYZ789", "1111-9999-3333-4444-5555")
        val list = listOf(a, b)
        assertEquals(a, assertIs<DeviceMatch.One>(DeviceMatch.resolve("abcDEF123", list)).device)
        assertEquals(a, assertIs<DeviceMatch.One>(DeviceMatch.resolve("abcD", list)).device)
        assertEquals(a, assertIs<DeviceMatch.One>(DeviceMatch.resolve("abcDEF12…", list)).device, "the list's own short form works")
        assertEquals(b, assertIs<DeviceMatch.One>(DeviceMatch.resolve("1111-9", list)).device)
        assertIs<DeviceMatch.Ambiguous>(DeviceMatch.resolve("1111", list))
        assertIs<DeviceMatch.Ambiguous>(DeviceMatch.resolve("abc", list))
        assertIs<DeviceMatch.None>(DeviceMatch.resolve("", list))
        assertIs<DeviceMatch.None>(DeviceMatch.resolve("zz", list))
    }

    @Test
    fun devices_against_a_daemon_without_the_route_says_so() {
        // an older daemon: the token gate passes, the route does not exist
        val token = LocalControlToken.loadOrCreate()
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) { routing { LegacyLoopbackGuard.routes(this, token) } }
        server.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
            val res = devicesCommand().test(listOf("--pair-port", "$port"))
            assertEquals(1, res.statusCode)
            assertTrue("older than this CLI" in res.output, res.output)
        } finally {
            server.stop(0, 0)
        }
    }

    private fun pairReply(pairingId: String?) = LoopbackPair("acct", "dpub", "ticket", "123456", 120, "wss://relay", pairingId)

    @Test
    fun pair_waits_and_prints_the_new_devices_fingerprint() {
        val pub = E2ECrypto.generateKeyPair().publicRaw
        val plane = FakePlane().apply { outcome = OwnerPairingWatch.Outcome.Paired("NewPhone1234", pub) }
        serving(plane, pairReply("p-1")) { port ->
            val res = PairCmd().test(listOf("--pair-port", "$port"))
            assertEquals(0, res.statusCode, res.output)
            assertTrue("123 456" in res.output, "the code is still shown: ${res.output}")
            assertTrue("Paired: device NewPhone…" in res.output, res.output)
            assertTrue(PairingFingerprint.of(pub) in res.output, res.output)
            assertTrue(PairingFingerprint.of(plane.computer) in res.output, "the computer's own value too: ${res.output}")
            assertTrue("This device's fingerprint" in res.output && "Computer fingerprint" in res.output, res.output)
        }
    }

    @Test
    fun pair_exits_non_zero_when_nobody_pairs_or_the_device_is_refused() {
        val plane = FakePlane().apply { outcome = OwnerPairingWatch.Outcome.Expired }
        serving(plane, pairReply("p-1")) { port ->
            val expired = PairCmd().test(listOf("--pair-port", "$port"))
            assertEquals(1, expired.statusCode, expired.output)
            assertTrue("expired" in expired.output, expired.output)
            plane.outcome = OwnerPairingWatch.Outcome.Refused
            val refused = PairCmd().test(listOf("--pair-port", "$port"))
            assertEquals(1, refused.statusCode, refused.output)
            assertTrue("refused" in refused.output, refused.output)
        }
    }

    @Test
    fun pair_against_an_older_daemon_still_shows_the_code_and_says_it_cannot_confirm() {
        serving(FakePlane(), pairReply(null)) { port ->
            val res = PairCmd().test(listOf("--pair-port", "$port"))
            assertEquals(0, res.statusCode, res.output)
            assertTrue("123 456" in res.output)
            assertTrue("older than this CLI" in res.output, res.output)
        }
    }
}
