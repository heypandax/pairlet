package dev.ccpocket.relay.auth

import dev.ccpocket.protocol.Challenge
import dev.ccpocket.protocol.DaemonAuth
import dev.ccpocket.protocol.DaemonHello
import dev.ccpocket.relay.store.RelayStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticates a daemon by a signed challenge over its Ed25519 static key, then TOFU-pins the key
 * to the account id. The account id is the public fingerprint of that key, so a daemon can only
 * claim the account it actually holds the private key for — closing the `?token=`-is-the-account hole.
 */
class DaemonAuthenticator(
    private val store: RelayStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rng = SecureRandom()
    private val issued = ConcurrentHashMap<String, Long>() // nonceB64 -> issuedAt (single-use, TTL-checked)

    sealed interface Result {
        /** firstSeen = this account id was just registered (TOFU). */
        data class Ok(val accountId: String, val firstSeen: Boolean) : Result
        data class Err(val code: String) : Result
    }

    /** Issue a fresh single-use nonce. The caller binds it to its own socket and passes it back to [verify]. */
    fun issueChallenge(): Challenge {
        val nonce = ByteArray(32).also(rng::nextBytes)
        val b64 = Codec.b64uEnc(nonce)
        issued[b64] = clock()
        return Challenge(b64, clock())
    }

    /**
     * @param expectedNonceB64 the nonce THIS socket issued — binds the auth to this connection so a
     * captured [DaemonAuth] can't be replayed onto another socket (whose nonce differs → bad signature).
     */
    suspend fun verify(hello: DaemonHello, auth: DaemonAuth, expectedNonceB64: String): Result {
        val pub = runCatching { Codec.b64uDec(hello.ed25519Pub) }.getOrNull() ?: return Result.Err("bad_pubkey")
        if (Codec.accountId(pub) != hello.accountId) return Result.Err("fingerprint_mismatch")

        val issuedAt = issued.remove(expectedNonceB64) ?: return Result.Err("bad_nonce")
        if (clock() - issuedAt > NONCE_TTL_MS) return Result.Err("nonce_expired")

        val nonce = runCatching { Codec.b64uDec(expectedNonceB64) }.getOrNull() ?: return Result.Err("bad_nonce")
        val sig = runCatching { Codec.b64uDec(auth.sig) }.getOrNull() ?: return Result.Err("bad_sig")
        if (!Ed25519.verify(pub, transcript(hello.accountId, nonce), sig)) return Result.Err("bad_signature")

        val now = clock()
        val existing = store.getAccount(hello.accountId)
        if (existing == null) {
            store.insertAccount(hello.accountId, pub, now)
            return Result.Ok(hello.accountId, firstSeen = true)
        }
        if (!existing.staticPubkey.contentEquals(pub)) return Result.Err("pubkey_mismatch")
        store.touchAccount(hello.accountId, now)
        return Result.Ok(hello.accountId, firstSeen = false)
    }

    /** Drop expired nonces so the map can't grow without bound under churn. */
    fun sweep() {
        val cutoff = clock() - NONCE_TTL_MS
        issued.entries.removeAll { it.value < cutoff }
    }

    private companion object {
        const val NONCE_TTL_MS = 30_000L
        val DOMAIN = "ccpocket/daemon-auth/v1".toByteArray()
        fun transcript(accountId: String, nonce: ByteArray): ByteArray =
            DOMAIN + byteArrayOf(0) + accountId.toByteArray() + nonce
    }
}
