package dev.ccpocket.relay.store

/** A tenant: identified purely by its daemon's Ed25519 static key (no login, no PII). */
class Account(
    val accountId: String,        // base32(sha256(staticPubkey)) — the public fingerprint
    val staticPubkey: ByteArray,  // 32-byte raw Ed25519 relay-auth pubkey (TOFU-pinned)
    val createdAt: Long,
    val lastSeen: Long?,
)

/** A paired device. devicePubkey is the opaque X25519 E2E static the relay never interprets. */
class Device(
    val deviceId: String,         // public 128-bit id (base64url)
    val accountId: String,
    val devicePubkey: ByteArray,  // opaque to the relay; forwarded to the daemon as a hint only
    val credentialHash: ByteArray,// sha256(secret); the secret itself is never stored
    val createdAt: Long,
    val lastSeen: Long?,
    val revoked: Boolean,
    val pushPlatform: String? = null, // "apns"/"apns_sandbox"/"fcm"/… ; null until the device registers
    val pushToken: String? = null,    // opaque APNs/FCM token; cleared (null) when the user opts out
)

/** A device the relay can push to: a non-revoked device that has registered a token. */
data class PushTarget(val deviceId: String, val platform: String, val token: String)

/**
 * Durable, multi-tenant state for the relay. Stores ONLY fingerprints, public keys, and hashes —
 * never message content, never any private/session key. Implementations must be safe for concurrent
 * suspend callers.
 */
interface RelayStore {
    // ---- accounts (TOFU: first sig-verified daemon for an id owns it) ----
    suspend fun getAccount(accountId: String): Account?
    suspend fun insertAccount(accountId: String, staticPubkey: ByteArray, now: Long)
    suspend fun touchAccount(accountId: String, now: Long)

    // ---- pairing tickets (only an authenticated daemon mints; single-use) ----
    suspend fun insertTicket(ticketHash: ByteArray, accountId: String, createdAt: Long, expiresAt: Long)
    /** Atomically consume an unused, unexpired ticket. Returns its account id, or null if none. */
    suspend fun claimTicket(ticketHash: ByteArray, now: Long): String?
    suspend fun countUnredeemedTickets(accountId: String, now: Long): Int

    // ---- devices ----
    suspend fun insertDevice(device: Device)
    suspend fun getDevice(deviceId: String): Device?
    /** All non-revoked devices for an account — re-announced to the daemon each time it attaches. */
    suspend fun devicesForAccount(accountId: String): List<Device>
    suspend fun countDevices(accountId: String): Int
    /** Mark a device revoked. Returns true if it existed under [accountId] and was not already revoked. */
    suspend fun revokeDevice(accountId: String, deviceId: String): Boolean
    suspend fun touchDevice(deviceId: String, now: Long)

    // ---- push notifications ----
    /** Store (or clear, when [token] is blank) a device's push token + platform for offline wake-ups. */
    suspend fun setPushToken(deviceId: String, platform: String, token: String, now: Long)
    /** Drop a device's push token after the gateway reported it permanently dead (APNs 410 / FCM 404) —
     *  but only if it still equals [platform]/[token], so a device that re-registered a fresh token in the
     *  meantime keeps it. Returns true if a row was actually cleared. */
    suspend fun clearPushToken(deviceId: String, platform: String, token: String, now: Long): Boolean
    /** Registered, non-revoked push targets for an account (devices that currently hold a token). */
    suspend fun pushTargets(accountId: String): List<PushTarget>

    // ---- maintenance ----
    suspend fun sweepExpired(now: Long)
}
