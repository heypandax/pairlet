package dev.ccpocket.relay

import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.protocol.DeviceHello
import dev.ccpocket.protocol.Role
import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.auth.DaemonAuthenticator
import dev.ccpocket.relay.auth.DeviceAuthenticator
import dev.ccpocket.relay.net.RateLimiter
import dev.ccpocket.relay.pairing.PairingService
import dev.ccpocket.relay.store.Db
import dev.ccpocket.relay.store.InMemoryRelayStore
import dev.ccpocket.relay.store.SqliteRelayStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayCoreTest {

    // ---- a daemon's Ed25519 identity, mirrored in test to drive the signed-challenge handshake ----
    private class DaemonKeys {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val rawPub: ByteArray = kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) } // SPKI tail = raw key
        val accountId: String = Codec.accountId(rawPub)
        fun sign(accountId: String, nonceB64: String): String {
            val msg = "ccpocket/daemon-auth/v1".toByteArray() + byteArrayOf(0) + accountId.toByteArray() + Codec.b64uDec(nonceB64)
            val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() }
            return Codec.b64uEnc(sig)
        }
    }

    private fun clockOf(ref: LongArray): () -> Long = { ref[0] }

    // ===================== daemon auth =====================

    @Test fun daemon_auth_happy_path_tofu_then_known() = runBlocking {
        val store = InMemoryRelayStore()
        val auth = DaemonAuthenticator(store)
        val d = DaemonKeys()

        val ch1 = auth.issueChallenge()
        val r1 = auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign(d.accountId, ch1.nonce)), ch1.nonce)
        assertEquals(DaemonAuthenticator.Result.Ok(d.accountId, firstSeen = true), r1)

        val ch2 = auth.issueChallenge()
        val r2 = auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign(d.accountId, ch2.nonce)), ch2.nonce)
        assertEquals(DaemonAuthenticator.Result.Ok(d.accountId, firstSeen = false), r2)
    }

    @Test fun daemon_auth_rejects_fingerprint_mismatch() = runBlocking {
        val auth = DaemonAuthenticator(InMemoryRelayStore())
        val d = DaemonKeys()
        val ch = auth.issueChallenge()
        val r = auth.verify(DaemonHello("not-my-fingerprint", Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign("not-my-fingerprint", ch.nonce)), ch.nonce)
        assertEquals(DaemonAuthenticator.Result.Err("fingerprint_mismatch"), r)
    }

    @Test fun daemon_auth_rejects_bad_signature() = runBlocking {
        val auth = DaemonAuthenticator(InMemoryRelayStore())
        val d = DaemonKeys()
        val ch = auth.issueChallenge()
        val wrong = d.sign(d.accountId, Codec.b64uEnc(ByteArray(32))) // signed over a different nonce
        val r = auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(wrong), ch.nonce)
        assertEquals(DaemonAuthenticator.Result.Err("bad_signature"), r)
    }

    @Test fun daemon_auth_nonce_is_single_use() = runBlocking {
        val auth = DaemonAuthenticator(InMemoryRelayStore())
        val d = DaemonKeys()
        val ch = auth.issueChallenge()
        auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign(d.accountId, ch.nonce)), ch.nonce)
        // replay the very same (hello, auth, nonce): nonce was consumed
        val replay = auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign(d.accountId, ch.nonce)), ch.nonce)
        assertEquals(DaemonAuthenticator.Result.Err("bad_nonce"), replay)
    }

    @Test fun daemon_auth_nonce_expires() = runBlocking {
        val t = longArrayOf(1_000)
        val auth = DaemonAuthenticator(InMemoryRelayStore(), clockOf(t))
        val d = DaemonKeys()
        val ch = auth.issueChallenge()
        t[0] = 1_000 + 31_000 // > 30s TTL
        val r = auth.verify(DaemonHello(d.accountId, Codec.b64uEnc(d.rawPub)), DaemonAuth(d.sign(d.accountId, ch.nonce)), ch.nonce)
        assertEquals(DaemonAuthenticator.Result.Err("nonce_expired"), r)
    }

    // ===================== pairing + device auth =====================

    @Test fun pairing_then_device_auth_roundtrip() = runBlocking {
        val store = InMemoryRelayStore()
        val d = DaemonKeys()
        store.insertAccount(d.accountId, d.rawPub, 0)
        val pairing = PairingService(store)
        val devPub = Codec.b64uEnc(ByteArray(32) { 7 })

        val mint = assertIs<PairingService.MintResult.Ok>(pairing.mint(d.accountId))
        val red = assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(mint.ticket, devPub))
        assertEquals(d.accountId, red.accountId)

        val devAuth = DeviceAuthenticator(store)
        assertEquals(DeviceAuthenticator.Result.Ok(d.accountId), devAuth.verify(DeviceHello(red.deviceId, red.secret)))
        assertEquals(DeviceAuthenticator.Result.Err("bad_credential"), devAuth.verify(DeviceHello(red.deviceId, Codec.b64uEnc(ByteArray(32)))))
        assertEquals(DeviceAuthenticator.Result.Err("unknown_device"), devAuth.verify(DeviceHello("ghost", "x")))

        assertTrue(store.revokeDevice(d.accountId, red.deviceId))
        assertEquals(DeviceAuthenticator.Result.Err("revoked"), devAuth.verify(DeviceHello(red.deviceId, red.secret)))
    }

    @Test fun ticket_is_single_use_and_expires() = runBlocking {
        val t = longArrayOf(1_000)
        val store = InMemoryRelayStore()
        store.insertAccount("acct", ByteArray(32), 0)
        val pairing = PairingService(store, clockOf(t))
        val devPub = Codec.b64uEnc(ByteArray(32) { 1 })

        val mint = assertIs<PairingService.MintResult.Ok>(pairing.mint("acct"))
        assertIs<PairingService.RedeemResult.Ok>(pairing.redeem(mint.ticket, devPub))
        assertEquals(PairingService.RedeemResult.Err("invalid_or_expired"), pairing.redeem(mint.ticket, devPub)) // single-use

        val mint2 = assertIs<PairingService.MintResult.Ok>(pairing.mint("acct"))
        t[0] = 1_000 + 121_000 // > 120s TTL
        assertEquals(PairingService.RedeemResult.Err("invalid_or_expired"), pairing.redeem(mint2.ticket, devPub))
    }

    @Test fun ticket_claim_is_atomic_under_concurrency() = runBlocking {
        val conn = Db.open(":memory:")
        val store = SqliteRelayStore(conn)
        store.insertAccount("acct", ByteArray(32), 0)
        val raw = ByteArray(32) { 9 }
        store.insertTicket(Codec.sha256(raw), "acct", 0, Long.MAX_VALUE)

        val winners = (1..50).map { async { store.claimTicket(Codec.sha256(raw), 1) } }.awaitAll().filterNotNull()
        assertEquals(1, winners.size) // exactly one claim wins the race
    }

    // ===================== rate limiter =====================

    @Test fun rate_limiter_windows_and_locks_out() {
        val t = longArrayOf(0)
        val rl = RateLimiter(clockOf(t))
        assertTrue(rl.check("k", limit = 2, windowMs = 1_000))
        assertTrue(rl.check("k", 2, 1_000))
        assertFalse(rl.check("k", 2, 1_000)) // 3rd in window blocked
        t[0] = 1_001
        assertTrue(rl.check("k", 2, 1_000)) // window rolled over

        // lockout: once breached, even a rolled window stays denied until the lockout elapses
        val rl2 = RateLimiter(clockOf(t))
        t[0] = 0
        assertTrue(rl2.check("a", 1, 1_000, lockoutOnBreach = true))
        assertFalse(rl2.check("a", 1, 1_000, lockoutOnBreach = true)) // breach -> lockout engaged
        t[0] = 2_000 // window passed, but still inside the >=1min lockout
        assertFalse(rl2.check("a", 1, 1_000, lockoutOnBreach = true))
    }

    // ===================== broker routing =====================

    @Test fun broker_routes_data_plane_and_supersedes_daemon() = runBlocking {
        val broker = Broker()
        val toDaemon = mutableListOf<ByteArray>()
        val toDevice = mutableListOf<ByteArray>()
        var oldClosed = false

        val daemon1 = Conn("acct", Role.DAEMON, null, sendText = {}, sendBinary = { toDaemon += it }, close = { oldClosed = true })
        val device = Conn("acct", Role.DEVICE, "dev1", sendText = {}, sendBinary = { toDevice += it }, close = {})
        assertNull(broker.attachDaemon(daemon1))
        broker.attachDevice(device)

        broker.toDaemonFrom("acct", "dev1", byteArrayOf(1, 2, 3)) // arrives wrapped with the source deviceId
        broker.toDevice("acct", "dev1", byteArrayOf(4, 5, 6))     // routed to that device only
        assertEquals(1, toDaemon.size); assertEquals(1, toDevice.size)
        assertEquals("dev1", dev.ccpocket.protocol.e2e.Wire.unwrapDevice(toDaemon[0])?.first)

        // newest daemon supersedes the previous one
        val daemon2 = Conn("acct", Role.DAEMON, null, sendText = {}, sendBinary = {}, close = {})
        val superseded = broker.attachDaemon(daemon2)
        assertEquals(daemon1, superseded)
        superseded!!.close("superseded")
        assertTrue(oldClosed)
    }
}
