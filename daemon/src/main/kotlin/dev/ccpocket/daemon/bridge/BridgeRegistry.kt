package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The daemon-local authority on which paired credentials are RESTRICTED — headless [CredentialKind.BRIDGE]
 * automations (issue #91) or scoped [CredentialKind.GUEST] folder shares (issue #115) — and the only place
 * a credential's kind is decided. The relay's `headless` flag is advisory (push hygiene + replay gating
 * only) and never trusted here. BOTH kinds ride ONE binding chain; only their persistence file and their
 * capability policy (caps + guard) differ.
 *
 * Binding chain (the security anchor, proposal §3 / reviewer item S1 — kind-agnostic):
 *  1. A mint records [recordIntent] with `sha256(ticket) -> spec` (spec carries the kind + scope + expiry).
 *  2. The device redeems that ticket at the relay and completes the first Noise handshake, whose PSK IS
 *     that ticket. A successful decrypt PROVES the device holds exactly that ticket (a wrong PSK fails the
 *     AEAD, fail-closed). [finalize] hashes the CONFIRMED ticket and, on an intent hit, persists
 *     deviceId -> spec (to bridges.json OR guests.json by kind). Exact — no dependency on the LIFO PSK
 *     guess in DeviceSessions.onDevicePaired.
 *  3. While an intent is pending, [recordIntent] refuses another mint (serialization) so tickets can't
 *     cross-bind.
 *
 * Downgrade safety: a BRIDGE lives in bridges.json, a GUEST in guests.json — NEITHER in devices.json. An
 * older daemon has no bridge/guest concept, never loads these files, and refuses that key's handshake
 * (unknown device). A guest key additionally can't leak into a pre-#115 (bridge-only) daemon's world: it
 * lives in guests.json, which that daemon doesn't read. The relay refuses to replay a restricted
 * DevicePaired to a pre-headless daemon, closing the other leak.
 */
class BridgeRegistry(
    private val store: File = BridgeStore.file(),
    // derive the sibling stores from the bridges.json dir so a test that passes only a temp `store` stays
    // fully isolated (production uses ~/.cc-pocket for all three)
    private val guestStore: File = store.parentFile?.let { File(it, "guests.json") } ?: GuestStore.file(),
    private val guestSessionStore: File = store.parentFile?.let { File(it, "guest-sessions.json") }
        ?: File(BridgeStore.file().parentFile, "guest-sessions.json"),
) {
    private val log = logger("BridgeRegistry")
    private val b64enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64dec: Base64.Decoder = Base64.getUrlDecoder()

    private data class Intent(val spec: BridgeSpec, val expiresAt: Long)

    // guarded by `this` — touched from the relay mint path and the device frame pump
    private val intents = HashMap<String, Intent>()          // hex(sha256(ticket)) -> intent
    private val bridgePubs = HashMap<String, ByteArray>()    // deviceId -> X25519 static pub (confirmed, ANY kind)
    private val specs = HashMap<String, BridgeSpec>()        // deviceId -> constraints (carries the kind)
    private val provisionalPub = HashMap<String, ByteArray>() // deviceId -> pub, pre-confirm
    private val guards = HashMap<String, BridgeGuard>()      // deviceId -> BRIDGE enforcement (per E2E session)
    private val guestGuards = HashMap<String, GuestGuard>()  // deviceId -> GUEST enforcement (per E2E session)
    private val createdAts = HashMap<String, Long>()         // deviceId -> when the credential was bound
    // deviceId -> sessionIds the guest started, PERSISTED so "visibility by initiator" survives a restart
    private val guestSessions = HashMap<String, MutableSet<String>>()

    init {
        BridgeStore.load(store).forEach { (id, entry) -> admitLoaded(id, entry, CredentialKind.BRIDGE) }
        GuestStore.load(guestStore).forEach { (id, entry) -> admitLoaded(id, entry, CredentialKind.GUEST) }
        runCatching {
            if (guestSessionStore.exists()) {
                PocketJson.decodeFromString<Map<String, List<String>>>(guestSessionStore.readText())
                    .forEach { (id, sids) -> guestSessions[id] = sids.toMutableSet() }
            }
        }
        val bridges = specs.values.count { it.kind == CredentialKind.BRIDGE }
        val guests = specs.values.count { it.kind == CredentialKind.GUEST }
        if (bridges + guests > 0) log.info("loaded $bridges bridge + $guests guest credential(s)")
    }

    /** File entries carry their own kind in the spec (default BRIDGE for pre-#115 rows); [expected] is the
     *  file's kind, used to skip a mis-filed row (never trust a guests.json row that claims BRIDGE). */
    private fun admitLoaded(id: String, entry: BridgeEntry, expected: CredentialKind) {
        val kind = entry.spec.kind
        val spec = if (expected == CredentialKind.GUEST && kind != CredentialKind.GUEST) {
            // a bridges-shaped row in guests.json (or vice-versa) is nonsense — refuse it rather than
            // silently granting the wrong policy
            log.warn("ignoring ${id.take(8)}… in ${if (expected == CredentialKind.GUEST) "guests" else "bridges"}.json — kind mismatch"); return
        } else if (expected == CredentialKind.BRIDGE && kind == CredentialKind.GUEST) {
            log.warn("ignoring guest row ${id.take(8)}… found in bridges.json"); return
        } else entry.spec
        runCatching { b64dec.decode(entry.pubB64) }.getOrNull()?.let { pub ->
            bridgePubs[id] = pub; specs[id] = spec; createdAts[id] = entry.createdAt
        }
    }

    /** Record a restricted pairing intent for a freshly minted ticket (kind rides in [spec]). Returns
     *  false (refusing) if any intent is already pending — the serialization rule that keeps the
     *  ticket→deviceId binding unambiguous across kinds. [ttlMs] should match the ticket TTL. */
    @Synchronized
    fun recordIntent(ticket: String, spec: BridgeSpec, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        if (intents.isNotEmpty()) return false
        intents[hashHex(ticket.encodeToByteArray())] = Intent(spec, now + ttlMs)
        log.info("${spec.kind.name.lowercase()} pairing intent recorded for \"${spec.name}\" (ttl ${ttlMs / 1000}s)")
        return true
    }

    @Synchronized
    fun intentPending(now: Long = System.currentTimeMillis()): Boolean { purgeExpired(now); return intents.isNotEmpty() }

    @Synchronized
    fun looksHeadless(ticket: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        return intents.containsKey(hashHex(ticket))
    }

    @Synchronized
    fun holdProvisional(deviceId: String, pub: ByteArray) { provisionalPub[deviceId] = pub }

    @Synchronized
    fun dropProvisional(deviceId: String) { provisionalPub.remove(deviceId) }

    /**
     * The device's FIRST transport frame decrypted — [ticket] is the confirmed PSK. If it matches a
     * pending intent, persist deviceId -> spec (to bridges.json or guests.json by kind) and return the
     * spec: this device IS a restricted credential. Null = an ordinary interactive device. Consumes the
     * intent on a hit. A hit with no held pub (daemon restarted mid-pairing) cannot register: fail closed.
     */
    @Synchronized
    fun finalize(deviceId: String, ticket: ByteArray, now: Long = System.currentTimeMillis()): BridgeSpec? {
        purgeExpired(now)
        val intent = intents.remove(hashHex(ticket)) ?: return null
        val pub = provisionalPub.remove(deviceId)
        if (pub == null) {
            log.warn("${intent.spec.kind.name.lowercase()} intent \"${intent.spec.name}\" confirmed by ${deviceId.take(8)}… but no provisional key held — refusing (re-pair)")
            return null
        }
        bridgePubs[deviceId] = pub
        specs[deviceId] = intent.spec
        createdAts[deviceId] = now
        persist()
        log.info("${intent.spec.kind.name.lowercase()} \"${intent.spec.name}\" bound to device ${deviceId.take(8)}… (scope=${intent.spec.workdirs})")
        return intent.spec
    }

    /** All known restricted deviceIds (confirmed only) — the attach-replay reconcile prunes against this. */
    @Synchronized
    fun ids(): Set<String> = specs.keys.toSet()

    /** A CONFIRMED restricted credential of ANY kind (bridge or guest) — the kind-agnostic check the
     *  pairing replay path uses to keep a restricted key out of devices.json. */
    @Synchronized
    fun isRestricted(deviceId: String): Boolean = deviceId in bridgePubs

    @Synchronized
    fun isBridge(deviceId: String): Boolean = specs[deviceId]?.kind == CredentialKind.BRIDGE && deviceId in bridgePubs

    /** issue #115: this deviceId is a confirmed GUEST folder-share credential. */
    @Synchronized
    fun isGuest(deviceId: String): Boolean = specs[deviceId]?.kind == CredentialKind.GUEST && deviceId in bridgePubs

    @Synchronized
    fun kindOf(deviceId: String): CredentialKind? = specs[deviceId]?.kind

    /** True for a CONFIRMED restricted credential OR one still provisional. EGRESS filtering + DaemonInfo
     *  withholding key on this: both bridge and guest are relay-only (no LAN), and the handshake DaemonInfo
     *  is sealed BEFORE the first transport frame confirms the kind, so a provisional restricted candidate
     *  must not be handed the LAN address it can't use anyway. */
    @Synchronized
    fun isBridgeCandidate(deviceId: String): Boolean = deviceId in bridgePubs || deviceId in provisionalPub

    @Synchronized
    fun pubOf(deviceId: String): ByteArray? = bridgePubs[deviceId] ?: provisionalPub[deviceId]

    @Synchronized
    fun specOf(deviceId: String): BridgeSpec? = specs[deviceId]

    /** Begin (or reuse) a live BRIDGE enforcement guard. Null for a non-bridge (guest → [startGuestGuard]). */
    @Synchronized
    fun startGuard(deviceId: String): BridgeGuard? {
        val spec = specs[deviceId]?.takeIf { it.kind == CredentialKind.BRIDGE } ?: return null
        return guards.getOrPut(deviceId) { BridgeGuard(spec) }
    }

    /** Begin (or reuse) a live GUEST enforcement guard (issue #115), seeded with the guest's persisted
     *  owned-session set + a callback that persists newly-minted ones. Null for a non-guest. */
    @Synchronized
    fun startGuestGuard(deviceId: String): GuestGuard? {
        val spec = specs[deviceId]?.takeIf { it.kind == CredentialKind.GUEST } ?: return null
        return guestGuards.getOrPut(deviceId) {
            GuestGuard(spec, seedSessions = guestSessions[deviceId]?.toSet() ?: emptySet(),
                persistSession = { sid -> noteGuestSession(deviceId, sid) })
        }
    }

    @Synchronized
    fun guardOf(deviceId: String): BridgeGuard? = guards[deviceId]

    @Synchronized
    fun guestGuardOf(deviceId: String): GuestGuard? = guestGuards[deviceId]

    /** Record a sessionId the guest started (persisted, bounded) — the ledger [startGuestGuard] seeds from
     *  so "list only the guest's own sessions" survives a daemon restart (issue #115 comment §3). */
    @Synchronized
    fun noteGuestSession(deviceId: String, sessionId: String) {
        if (deviceId !in bridgePubs) return
        val set = guestSessions.getOrPut(deviceId) { LinkedHashSet() }
        if (set.add(sessionId)) {
            if (set.size > MAX_GUEST_SESSIONS) set.iterator().let { it.next(); it.remove() }
            persistGuestSessions()
        }
    }

    /** The sessionIds a guest owns (persisted ledger ∪ live guard) — the router filters ListSessions by it. */
    @Synchronized
    fun guestSessionIds(deviceId: String): Set<String> =
        (guestSessions[deviceId].orEmpty()) + (guestGuards[deviceId]?.ownedSessionIds().orEmpty())

    /** Revoked (or pruned by attach-replay reconcile): forget the credential + its guard + guest ledger. */
    @Synchronized
    fun remove(deviceId: String) {
        val existed = bridgePubs.remove(deviceId) != null || specs.remove(deviceId) != null
        if (existed) {
            provisionalPub.remove(deviceId); guards.remove(deviceId); guestGuards.remove(deviceId); createdAts.remove(deviceId)
            if (guestSessions.remove(deviceId) != null) persistGuestSessions()
            persist()
            log.info("restricted credential ${deviceId.take(8)}… removed")
        }
    }

    /** Confirmed BRIDGE deviceIds + names — for the `bridges` CLI listing (guests excluded). */
    @Synchronized
    fun list(): List<Pair<String, BridgeSpec>> = bridges().map { (id, spec, _) -> id to spec }

    /** Confirmed BRIDGEs — deviceId + spec + when bound — for the owner's management page. The [list]
     *  twin of [guests]; carries the bind time the CLI listing has no use for. */
    @Synchronized
    fun bridges(): List<Triple<String, BridgeSpec, Long>> =
        specs.entries.filter { it.value.kind == CredentialKind.BRIDGE }
            .map { Triple(it.key, it.value, createdAts[it.key] ?: 0L) }

    /** Specs with an outstanding, unexpired intent — minted but not yet redeemed. The owner's page shows
     *  these as "waiting for the adapter to connect"; they vanish on their own when the ticket lapses,
     *  so their absence later is expiry, not failure. */
    @Synchronized
    fun pendingIntents(now: Long = System.currentTimeMillis()): List<BridgeSpec> {
        purgeExpired(now)
        return intents.values.map { it.spec }
    }

    /** Confirmed GUEST shares (issue #115) — deviceId + spec + when bound — for the owner's management page. */
    @Synchronized
    fun guests(): List<Triple<String, BridgeSpec, Long>> =
        specs.entries.filter { it.value.kind == CredentialKind.GUEST }
            .map { Triple(it.key, it.value, createdAts[it.key] ?: 0L) }

    /** Confirmed GUEST deviceIds whose share has expired as of [now] — the reaper cuts + purges these so
     *  an expired share drops the guest immediately and its ticket/credential can't be reused (issue #115 §6). */
    @Synchronized
    fun expiredGuestIds(now: Long = System.currentTimeMillis()): List<String> =
        specs.entries.filter { it.value.kind == CredentialKind.GUEST && it.value.expired(now) }.map { it.key }

    private fun persist() {
        // split by kind: bridges.json holds ONLY bridges, guests.json ONLY guests — the downgrade-isolation
        // invariant (a pre-#115 daemon reading bridges.json must never see a guest key)
        val byKind = bridgePubs.entries.groupBy { specs[it.key]?.kind }
        fun rows(kind: CredentialKind) = (byKind[kind] ?: emptyList()).associate { (id, pub) ->
            // keep each credential's ORIGINAL bind time — an unrelated persist (another bind, a revoke)
            // must not restamp every row's createdAt
            id to BridgeEntry(b64enc.encodeToString(pub), specs[id]!!, createdAts[id] ?: System.currentTimeMillis())
        }
        BridgeStore.save(rows(CredentialKind.BRIDGE), store)
        GuestStore.save(rows(CredentialKind.GUEST), guestStore)
    }

    private fun persistGuestSessions() {
        runCatching {
            guestSessionStore.parentFile?.mkdirs()
            guestSessionStore.writeText(PocketJson.encodeToString(guestSessions.mapValues { it.value.toList() }))
        }
    }

    private fun purgeExpired(now: Long) { intents.entries.removeAll { it.value.expiresAt <= now } }

    @OptIn(ExperimentalStdlibApi::class)
    private fun hashHex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHexString()

    companion object {
        private const val MAX_GUEST_SESSIONS = 512

        /**
         * Grace beyond a redeem ticket's TTL for its intent to stay bindable. The intent is recorded AFTER
         * the mint round-trip, so it already outlives the relay ticket slightly; the grace makes
         * classification robust to redeem→connect→first-frame latency and modest clock skew, so a
         * slow-to-first-frame bridge/guest is never mis-promoted to a full-power device (issue #91).
         *
         * One constant for every mint path — loopback `pair --headless`, the wire [dev.ccpocket.protocol.CreateBridge],
         * and folder-share — because they must classify identically. An abandoned mint blocks re-mint for
         * at most this long.
         */
        const val INTENT_GRACE_MS = 120_000L
    }
}
