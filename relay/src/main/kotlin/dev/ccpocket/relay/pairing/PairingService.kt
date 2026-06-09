package dev.ccpocket.relay.pairing

import dev.ccpocket.relay.auth.Codec
import dev.ccpocket.relay.store.Device
import dev.ccpocket.relay.store.RelayStore
import java.security.SecureRandom

/**
 * Mints and redeems pairing tickets. Minting is only ever invoked for an already-authenticated
 * daemon (the relay enforces that at the call site); redeeming atomically consumes a single-use,
 * high-entropy ticket and issues a bearer credential. The relay never sees either party's static
 * key as a source of truth — the device's X25519 pubkey is stored only to forward as a hint.
 */
class PairingService(
    private val store: RelayStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rng = SecureRandom()

    sealed interface MintResult {
        data class Ok(val ticket: String, val ttlSec: Int) : MintResult
        data class Err(val code: String) : MintResult
    }

    suspend fun mint(accountId: String): MintResult {
        if (store.countUnredeemedTickets(accountId, clock()) >= MAX_UNREDEEMED) return MintResult.Err("too_many_tickets")
        val raw = ByteArray(32).also(rng::nextBytes) // 256-bit: brute force infeasible by entropy alone
        val now = clock()
        store.insertTicket(Codec.sha256(raw), accountId, now, now + TTL_MS)
        return MintResult.Ok(Codec.b64uEnc(raw), (TTL_MS / 1000).toInt())
    }

    sealed interface RedeemResult {
        /** secret is the bearer credential handed to the device; the relay keeps only sha256(secret). */
        data class Ok(val accountId: String, val deviceId: String, val secret: String, val devicePubKey: String) : RedeemResult
        data class Err(val code: String) : RedeemResult
    }

    suspend fun redeem(ticket: String, devicePubKeyB64: String): RedeemResult {
        val raw = runCatching { Codec.b64uDec(ticket) }.getOrNull() ?: return RedeemResult.Err("bad_ticket")
        // opaque to the relay; just guard against junk. P-256 pub = 65B; allow a sane range.
        val devicePub = runCatching { Codec.b64uDec(devicePubKeyB64) }.getOrNull() ?: return RedeemResult.Err("bad_pubkey")
        if (devicePub.size !in 32..133) return RedeemResult.Err("bad_pubkey")

        val accountId = store.claimTicket(Codec.sha256(raw), clock()) ?: return RedeemResult.Err("invalid_or_expired")
        if (store.countDevices(accountId) >= MAX_DEVICES) return RedeemResult.Err("too_many_devices")

        val deviceId = Codec.b64uEnc(ByteArray(16).also(rng::nextBytes))
        val secretBytes = ByteArray(32).also(rng::nextBytes)
        store.insertDevice(Device(deviceId, accountId, devicePub, Codec.sha256(secretBytes), clock(), null, revoked = false))
        return RedeemResult.Ok(accountId, deviceId, Codec.b64uEnc(secretBytes), devicePubKeyB64)
    }

    private companion object {
        const val TTL_MS = 120_000L
        const val MAX_UNREDEEMED = 3
        const val MAX_DEVICES = 50
    }
}
