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
    // issue #91: self-declared at redeem. ADVISORY presence metadata, not a security boundary (the
    // capability restriction is enforced daemon-side, anchored to the pairing ticket): the relay uses
    // it to (a) keep an always-on bridge from counting as "a phone is attached" — else pushes go
    // permanently silent and the daemon's idle reaper never runs — and (b) withhold this device from
    // the attach replay to daemons whose protoV predates bridges, which would otherwise file the key
    // into their FULL-POWER allow-list on downgrade.
    val headless: Boolean = false,
    /**
     * §3.4: this device is a Collaborator Link INBOX — someone else's phone, holding a credential that can
     * only ever see Handoff offers addressed to it. Always minted alongside [headless]=true (it must not
     * count as "the owner is watching" nor sit in the owner's push fan-out), so this second marker only
     * re-opens the two doors an inbox genuinely needs:
     *  - it may register its OWN push token ([RelayServer.handleDeviceControl] refuses a plain bridge);
     *  - it may be addressed by [dev.ccpocket.protocol.NotifyPush.deviceId] ([pushTargetFor]).
     * It is still excluded from [pushTargets] — the owner's session alerts never reach a contact.
     * Authoritative from the MINTING daemon's ticket, exactly like [headless].
     */
    val collaborator: Boolean = false,
) {
    /**
     * May this device register its OWN push token over the relay's cleartext control plane?
     *
     * A plain HEADLESS bridge may not (issue #91): that plane bypasses the E2E bridge ingress gate, so a
     * leaked bridge credential could otherwise subscribe itself to the owner's turn-complete alerts, which
     * carry workdir/path/reply-first-line for ANY session.
     *
     * A COLLABORATOR inbox may (§3.4) — it is a real phone whose entire job is being woken for an offer.
     * That is safe only because the two exclusions are independent: a collaborator is still absent from
     * [pushTargets], so the token it registers is reachable ONLY by a targeted push naming its deviceId.
     */
    val mayRegisterPush: Boolean get() = !headless || collaborator
}

/** A device the relay can push to: a non-revoked device that has registered a token. */
data class PushTarget(val deviceId: String, val platform: String, val token: String)

/** A successfully claimed pairing ticket: its account plus the minting daemon's [headless]/[collaborator]
 *  markers (issue #91, §3.4) — the authoritative source the redeemed device's flags are set from. */
data class ClaimedTicket(val accountId: String, val headless: Boolean, val collaborator: Boolean = false)

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
    /** [headless] (issue #91) / [collaborator] (§3.4) are stamped by the MINTING daemon (PairBegin) and are
     *  the authoritative markers — the redeemed device inherits both, never the redeeming client's
     *  self-declaration. */
    suspend fun insertTicket(
        ticketHash: ByteArray,
        accountId: String,
        createdAt: Long,
        expiresAt: Long,
        headless: Boolean = false,
        collaborator: Boolean = false,
    )
    /** Atomically consume an unused, unexpired ticket. Returns (accountId, its markers), or null. */
    suspend fun claimTicket(ticketHash: ByteArray, now: Long): ClaimedTicket?
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
    /** Registered, non-revoked, INTERACTIVE push targets for an account (devices holding a token).
     *  HEADLESS bridges are excluded (issue #91): a bridge must never receive the owner's turn-complete
     *  pushes (which carry workdir/path/reply-first-line for ANY session), even if it registered a token
     *  over the control plane — that plane bypasses the E2E bridge ingress gate. COLLABORATOR inboxes are
     *  excluded on the same terms and by their own predicate (§3.4): the owner's session pushes are never a
     *  contact's business, and the exclusion must not silently depend on collaborators also being headless. */
    suspend fun pushTargets(accountId: String): List<PushTarget>

    /** §3.4 TARGETED push: the single registered target for [deviceId], or null when it does not exist, is
     *  revoked, holds no token — or does not belong to [accountId]. The account check is what makes a
     *  daemon-supplied deviceId safe to honor: a daemon can only ever wake ITS OWN devices.
     *
     *  A plain BRIDGE is excluded here too, by the same [Device.mayRegisterPush] rule that refuses it at the
     *  control plane. The refusal alone is not enough: it arrived with issue #91 and no migration cleared
     *  tokens a bridge had already registered, so such a row is still sitting in the wild with a live token.
     *  A COLLABORATOR inbox is of course allowed — being addressable one-by-one is the whole point. */
    suspend fun pushTargetFor(accountId: String, deviceId: String): PushTarget?

    // ---- maintenance ----
    suspend fun sweepExpired(now: Long)
}
