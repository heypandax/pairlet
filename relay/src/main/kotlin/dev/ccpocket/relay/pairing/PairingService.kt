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

    /** [headless] (issue #91) is the AUTHORITATIVE bridge marker, known only to the minting daemon and
     *  stamped onto the ticket here; the redeemed device inherits it in [redeem], regardless of what the
     *  redeeming client self-declares. */
    suspend fun mint(accountId: String, headless: Boolean = false): MintResult {
        if (store.countUnredeemedTickets(accountId, clock()) >= MAX_UNREDEEMED) return MintResult.Err("too_many_tickets")
        val raw = ByteArray(32).also(rng::nextBytes) // 256-bit: brute force infeasible by entropy alone
        val now = clock()
        store.insertTicket(Codec.sha256(raw), accountId, now, now + TTL_MS, headless = headless)
        return MintResult.Ok(Codec.b64uEnc(raw), (TTL_MS / 1000).toInt())
    }

    sealed interface RedeemResult {
        /** secret is the bearer credential handed to the device; the relay keeps only sha256(secret). */
        data class Ok(val accountId: String, val deviceId: String, val secret: String, val devicePubKey: String) : RedeemResult
        data class Err(val code: String) : RedeemResult
    }

    /** The device's headless flag is derived AUTHORITATIVELY from the claimed ticket ([ClaimedTicket.headless],
     *  stamped by the minting daemon), and the redeeming client's self-declared [clientHeadless] is IGNORED
     *  (issue #91): a client redeeming a bridge ticket while lying `headless=false` still lands as a bridge,
     *  and — the reverse — a client cannot mark a PHONE ticket headless to make its own device presence-
     *  invisible. This relay always stamps the ticket (0 or 1), so the ticket is the sole authority.
     *  [clientHeadless] is retained only for wire/signature compatibility. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun redeem(ticket: String, devicePubKeyB64: String, clientHeadless: Boolean = false): RedeemResult {
        val raw = runCatching { Codec.b64uDec(ticket) }.getOrNull() ?: return RedeemResult.Err("bad_ticket")
        // opaque to the relay; just guard against junk. P-256 pub = 65B; allow a sane range.
        val devicePub = runCatching { Codec.b64uDec(devicePubKeyB64) }.getOrNull() ?: return RedeemResult.Err("bad_pubkey")
        if (devicePub.size !in 32..133) return RedeemResult.Err("bad_pubkey")

        val claimed = store.claimTicket(Codec.sha256(raw), clock()) ?: return RedeemResult.Err("invalid_or_expired")
        if (store.countDevices(claimed.accountId) >= MAX_DEVICES) return RedeemResult.Err("too_many_devices")

        val deviceId = Codec.b64uEnc(ByteArray(16).also(rng::nextBytes))
        val secretBytes = ByteArray(32).also(rng::nextBytes)
        // authoritative: the ticket's marker only, never the client's self-declaration
        store.insertDevice(Device(deviceId, claimed.accountId, devicePub, Codec.sha256(secretBytes), clock(), null, revoked = false, headless = claimed.headless))
        return RedeemResult.Ok(claimed.accountId, deviceId, Codec.b64uEnc(secretBytes), devicePubKeyB64)
    }

    private companion object {
        const val TTL_MS = 120_000L
        const val MAX_UNREDEEMED = 3
        const val MAX_DEVICES = 50
    }
}
