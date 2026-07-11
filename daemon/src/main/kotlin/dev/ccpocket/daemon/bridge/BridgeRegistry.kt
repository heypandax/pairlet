package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.util.logger
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The daemon-local authority on which paired credentials are HEADLESS bridges (issue #91), and the
 * only place a credential's kind is decided. The relay's `headless` flag is advisory (push hygiene +
 * replay gating only) and never trusted here.
 *
 * Binding chain (the security anchor, proposal §3 / reviewer item S1):
 *  1. `pair --headless` mints a ticket; the daemon calls [recordIntent] with `sha256(ticket) -> spec`.
 *  2. The device redeems that ticket at the relay and completes the first Noise handshake, whose PSK
 *     IS that ticket. A successful decrypt PROVES the device holds exactly that ticket (a wrong PSK
 *     fails the AEAD, fail-closed). [finalize] hashes the CONFIRMED ticket and, on an intent hit,
 *     persists deviceId -> spec to bridges.json. This is exact — no dependency on the LIFO PSK-arming
 *     guess in DeviceSessions.onDevicePaired.
 *  3. While a headless intent is pending, [recordIntent] refuses another mint (serialization), so a
 *     phone can't LIFO-grab the headless ticket and a second ticket can't cross-bind.
 *
 * Downgrade safety: bridges live in bridges.json, NOT devices.json — an older daemon has no bridge
 * concept, never loads this file, and would refuse a bridge's handshake (unknown device). The relay
 * refuses to replay a headless DevicePaired to a pre-headless daemon, so a bridge key can't leak into
 * an old daemon's full-power allow-list either.
 */
class BridgeRegistry(private val store: File = BridgeStore.file()) {
    private val log = logger("BridgeRegistry")
    private val b64enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64dec: Base64.Decoder = Base64.getUrlDecoder()

    private data class Intent(val spec: BridgeSpec, val expiresAt: Long)

    // guarded by `this` — touched from the relay mint path and the device frame pump
    private val intents = HashMap<String, Intent>()          // hex(sha256(ticket)) -> intent
    private val bridgePubs = HashMap<String, ByteArray>()    // deviceId -> X25519 static pub (confirmed)
    private val specs = HashMap<String, BridgeSpec>()        // deviceId -> constraints
    private val provisionalPub = HashMap<String, ByteArray>() // deviceId -> pub, pre-confirm
    private val guards = HashMap<String, BridgeGuard>()      // deviceId -> live enforcement (per E2E session)

    init {
        BridgeStore.load(store).forEach { (id, entry) ->
            runCatching { b64dec.decode(entry.pubB64) }.getOrNull()?.let { pub ->
                bridgePubs[id] = pub; specs[id] = entry.spec
            }
        }
        if (bridgePubs.isNotEmpty()) log.info("loaded ${bridgePubs.size} bridge credential(s) from ${store.name}")
    }

    /** Record a headless pairing intent for a freshly minted ticket. Returns false (refusing) if a
     *  headless intent is already pending — the serialization rule that keeps the ticket→deviceId
     *  binding unambiguous. [ttlMs] should match the ticket TTL. */
    @Synchronized
    fun recordIntent(ticket: String, spec: BridgeSpec, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        if (intents.isNotEmpty()) return false
        intents[hashHex(ticket.encodeToByteArray())] = Intent(spec, now + ttlMs)
        log.info("headless pairing intent recorded for \"${spec.name}\" (ttl ${ttlMs / 1000}s)")
        return true
    }

    /** True while any headless pairing is pending — blocks concurrent mints (relay side reads this). */
    @Synchronized
    fun intentPending(now: Long = System.currentTimeMillis()): Boolean { purgeExpired(now); return intents.isNotEmpty() }

    /** Non-consuming lookup: does the ticket armed for a newly-paired device match a headless intent?
     *  DeviceSessions uses this to keep a provisional bridge's pub OUT of devices.json. */
    @Synchronized
    fun looksHeadless(ticket: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        return intents.containsKey(hashHex(ticket))
    }

    /** A device's pub before its handshake confirms (armed by onDevicePaired for a would-be bridge). */
    @Synchronized
    fun holdProvisional(deviceId: String, pub: ByteArray) { provisionalPub[deviceId] = pub }

    /** Discard a provisional bridge that never confirmed (intent lapsed) — fail-closed cleanup so it
     *  can't linger as a candidate and get egress-filtered forever without ever being a real bridge. */
    @Synchronized
    fun dropProvisional(deviceId: String) { provisionalPub.remove(deviceId) }

    /**
     * The device's FIRST transport frame decrypted — [ticket] is the confirmed PSK. If it matches a
     * pending headless intent, persist deviceId -> spec to bridges.json (using the pub held at announce
     * time) and return the spec: this device IS the bridge. Null = an ordinary interactive device
     * (caller handles it as today). Consumes the intent on a hit. A hit with no held pub (daemon
     * restarted mid-pairing — the provisional map is memory-only, like the PSK map) cannot register:
     * fail closed, the owner re-runs `pair --headless`.
     */
    @Synchronized
    fun finalize(deviceId: String, ticket: ByteArray, now: Long = System.currentTimeMillis()): BridgeSpec? {
        purgeExpired(now)
        val intent = intents.remove(hashHex(ticket)) ?: return null
        val pub = provisionalPub.remove(deviceId)
        if (pub == null) {
            log.warn("headless intent \"${intent.spec.name}\" confirmed by ${deviceId.take(8)}… but no provisional key held — refusing (re-pair)")
            return null
        }
        bridgePubs[deviceId] = pub
        specs[deviceId] = intent.spec
        persist()
        log.info("bridge \"${intent.spec.name}\" bound to device ${deviceId.take(8)}… — persisted to ${store.name}")
        return intent.spec
    }

    /** All known bridge deviceIds (confirmed only) — the attach-replay reconcile prunes against this. */
    @Synchronized
    fun ids(): Set<String> = specs.keys.toSet()

    @Synchronized
    fun isBridge(deviceId: String): Boolean = deviceId in bridgePubs

    /** True for a CONFIRMED bridge OR one still provisional (armed at pairing, not yet handshake-proven).
     *  EGRESS filtering keys on this, not [isBridge]: the handshake's DaemonInfo is sealed BEFORE the
     *  first transport frame confirms the bridge, and a provisional bridge must not be handed the LAN
     *  address it can't use anyway (its key isn't in devices.json, so the LAN gate would reject it). */
    @Synchronized
    fun isBridgeCandidate(deviceId: String): Boolean = deviceId in bridgePubs || deviceId in provisionalPub

    /** The pub to run the Noise handshake against — confirmed bridge key, else a provisional one. */
    @Synchronized
    fun pubOf(deviceId: String): ByteArray? = bridgePubs[deviceId] ?: provisionalPub[deviceId]

    @Synchronized
    fun specOf(deviceId: String): BridgeSpec? = specs[deviceId]

    /** Begin (or reuse) a live enforcement guard for this bridge's E2E session. */
    @Synchronized
    fun startGuard(deviceId: String): BridgeGuard? {
        val spec = specs[deviceId] ?: return null
        return guards.getOrPut(deviceId) { BridgeGuard(spec) }
    }

    /** The live guard, if one started. Guards survive E2E re-handshakes ON PURPOSE: the session
     *  ledger (which convos/sessions this bridge owns) and the rate windows must not reset on a relay
     *  blip — only a revoke ([remove]) ends a guard. Bounded by the number of bridges. */
    @Synchronized
    fun guardOf(deviceId: String): BridgeGuard? = guards[deviceId]

    /** Revoked (or pruned by attach-replay reconcile): forget the bridge entirely. */
    @Synchronized
    fun remove(deviceId: String) {
        if (bridgePubs.remove(deviceId) != null || specs.remove(deviceId) != null) {
            provisionalPub.remove(deviceId); guards.remove(deviceId); persist()
            log.info("bridge ${deviceId.take(8)}… removed")
        }
    }

    /** All confirmed bridge deviceIds and their names — for the `bridges` CLI listing. */
    @Synchronized
    fun list(): List<Pair<String, BridgeSpec>> = specs.entries.map { it.key to it.value }

    private fun persist() {
        val map = bridgePubs.mapValues { (id, pub) ->
            BridgeEntry(b64enc.encodeToString(pub), specs[id] ?: return@mapValues null, System.currentTimeMillis())
        }.filterValues { it != null }.mapValues { it.value!! }
        BridgeStore.save(map, store)
    }

    private fun purgeExpired(now: Long) { intents.entries.removeAll { it.value.expiresAt <= now } }

    @OptIn(ExperimentalStdlibApi::class)
    private fun hashHex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHexString()
}
