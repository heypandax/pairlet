package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.util.logger
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The daemon-local authority on which paired credentials are RESTRICTED — headless [CredentialKind.BRIDGE]
 * automations (issue #91) and [CredentialKind.EXECUTION] links (issue #367), plus the retired kinds still
 * recognised so they can be refused and cleared (see [CredentialKind]) — and the only place a credential's
 * kind is decided. The relay's `headless` flag is advisory (push hygiene + replay gating only) and never
 * trusted here. Every kind rides ONE binding chain; only its persistence file and its capability policy
 * differ.
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
    // the retired folder-share GUEST keys (issue #115): still loaded, so they can be refused and then retired
    private val guestStore: File = store.parentFile?.let { File(it, "guests.json") } ?: GuestStore.file(),
    private val collaboratorKeyStore: File = store.parentFile?.let { File(it, "collaborator-keys.json") }
        ?: CollaboratorKeyStore.file(),
    // issue #367: the fourth credential file, derived the same way so a temp-dir test stays isolated
    private val executionKeyStore: File = store.parentFile?.let { File(it, "execution-credentials.json") }
        ?: ExecutionCredentialStore.file(),
    // the retired-credential tombstones (see [retireCollaborators] / [tombstoneGuests]), derived the same way
    private val retiredStore: File = store.parentFile?.let { File(it, RetiredCredentialStore.FILE_NAME) }
        ?: RetiredCredentialStore.file(),
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
    private val createdAts = HashMap<String, Long>()         // deviceId -> when the credential was bound

    // deviceIds of retired credentials (Collaborator Links, folder-share guests) whose relay-side revoke is not
    // confirmed yet (see [retireCollaborators] / [tombstoneGuests]); guarded by `this`
    private val retired = LinkedHashSet<String>()
    // false when the tombstone file exists but could not be read at startup: it is then never overwritten,
    // and nothing whose retirement depends on writing it is retired this run; guarded by `this`
    private var tombstonesWritable = true

    init {
        BridgeStore.load(store).forEach { (id, entry) -> admitLoaded(id, entry, CredentialKind.BRIDGE) }
        GuestStore.load(guestStore).forEach { (id, entry) -> admitLoaded(id, entry, CredentialKind.GUEST) }
        ExecutionCredentialStore.load(executionKeyStore).forEach { (id, entry) -> admitLoaded(id, entry, CredentialKind.EXECUTION) }
        val bridges = specs.values.count { it.kind == CredentialKind.BRIDGE }
        val guests = specs.values.count { it.kind == CredentialKind.GUEST }
        val executions = specs.values.count { it.kind == CredentialKind.EXECUTION }
        if (bridges + guests + executions > 0) {
            log.info("loaded $bridges bridge + $guests guest + $executions execution credential(s)")
        }
        retireCollaborators()
    }

    /**
     * Session handoff and review contacts were retired (2026-10), and with them the Collaborator Link
     * credential. Its keys are never loaded again. This runs once per start and does, in this order:
     *
     *  1. read the deviceIds out of collaborator-keys.json (every COLLABORATOR row — a row this daemon holds
     *     as a live credential of another kind is left alone, since revoking it at the relay would cut that
     *     credential);
     *  2. merge them into the tombstone file ([RetiredCredentialStore]) — ids only, no key material;
     *  3. only once that write has succeeded, empty collaborator-keys.json.
     *
     * A failed step 2 keeps the keys file as it is (fail closed: the next start retries); the ids are still
     * tombstoned in memory, so this run treats them exactly as if the write had worked. A tombstoned id is
     * KNOWN to [isRetiredCredential] until the relay confirms its revoke ([confirmRetired]) — the transport
     * relies on that to keep a still-valid relay credential out of the full-power allow-list. Nothing here
     * touches any other credential file.
     */
    private fun retireCollaborators() {
        val stored = runCatching { RetiredCredentialStore.load(retiredStore) }
            .onFailure { log.warn("retired-credential tombstones unreadable (${it.message}) — leaving them and the collaborator keys as they are") }
        synchronized(this) { retired += stored.getOrNull().orEmpty(); tombstonesWritable = stored.isSuccess }
        val rows = CollaboratorKeyStore.load(collaboratorKeyStore)
        if (rows.isEmpty()) return
        val ids = rows.filter { (id, entry) -> entry.spec.kind == CredentialKind.COLLABORATOR && id !in bridgePubs }.keys
        val all = synchronized(this) { retired += ids; retired.toList() }
        if (stored.isFailure || !RetiredCredentialStore.save(all, retiredStore)) {
            log.warn("could not record ${ids.size} retired collaborator credential(s) — keeping collaborator-keys.json for the next start")
            return
        }
        CollaboratorKeyStore.save(emptyMap(), collaboratorKeyStore)
        log.info("retired ${ids.size} collaborator credential(s): keys cleared, relay revoke pending")
    }

    /** File entries carry their own kind in the spec (default BRIDGE for pre-#115 rows); [expected] is the
     *  file's kind, used to skip a mis-filed row (never trust a guests.json row that claims BRIDGE — a
     *  mis-filed row would silently grant the wrong policy, so refuse it outright). */
    private fun admitLoaded(id: String, entry: BridgeEntry, expected: CredentialKind) {
        if (entry.spec.kind != expected) {
            log.warn("ignoring ${id.take(8)}… (${entry.spec.kind.name.lowercase()} row) found in the ${expected.name.lowercase()} store — kind mismatch")
            return
        }
        // issue #367: an execution row whose grantId is missing points at no authority at all — nothing
        // could ever re-authorise it, so it is not a credential this build can police. Refuse it outright,
        // exactly like the kind mismatch above (a widened row must never fall back to "some other policy").
        if (expected == CredentialKind.EXECUTION && entry.spec.grantId.isNullOrBlank()) {
            log.warn("ignoring execution credential ${id.take(8)}… with no grantId — refusing (re-approve)")
            return
        }
        runCatching { b64dec.decode(entry.pubB64) }.getOrNull()?.let { pub ->
            bridgePubs[id] = pub; specs[id] = entry.spec; createdAts[id] = entry.createdAt
        }
    }

    // A claimed-but-not-yet-recorded mint slot (issue #207): non-zero while some mint's relay round-trip
    // is in flight, so no second mint (of ANY class) can interleave into the suspension window. Expiry is
    // a safety net for a caller that never reaches its `finally` releaseMint (process-level accidents) —
    // the normal lifecycle is reserve → recordIntent/failure → release.
    private var mintReservedUntil = 0L

    /**
     * Claim the ONE mint slot BEFORE the suspending relay round-trip (issue #207). The old shape —
     * check [intentPending], suspend on the mint, then [recordIntent] — let two overlapping mints both
     * pass the check: both tickets got PSK-armed (LIFO) while only one intent could be recorded, so the
     * armed ticket and the recorded intent disagreed, the exact mis-promotion race #207 describes.
     * Returns false while another mint holds the slot or an intent is pending; the winner MUST
     * [releaseMint] in a `finally` once its [recordIntent] (or failure) settles.
     */
    @Synchronized
    fun reserveMint(now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        if (now < mintReservedUntil || intents.isNotEmpty()) return false
        mintReservedUntil = now + MINT_RESERVE_MS
        return true
    }

    /** Release the [reserveMint] slot — idempotent, call in `finally` on every mint path. */
    @Synchronized
    fun releaseMint() { mintReservedUntil = 0L }

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

    /** True while a mint is CLAIMED ([reserveMint], round-trip in flight) or an intent is RECORDED —
     *  the one predicate every "may another pairing start now?" gate reads, so a mint's suspension
     *  window reads as busy exactly like its recorded intent does (issue #207). */
    @Synchronized
    fun intentPending(now: Long = System.currentTimeMillis()): Boolean {
        purgeExpired(now)
        return now < mintReservedUntil || intents.isNotEmpty()
    }

    /**
     * How much longer the ONE mint slot stays busy (0 = free). #207 admits a single pairing at a time, so
     * a caller refused for that reason can be told WHEN to try again instead of being left to poll — a
     * burned invite blocks the next approval for its whole ticket TTL + grace, and nothing shortens it.
     */
    @Synchronized
    fun mintBusyRemainingMs(now: Long = System.currentTimeMillis()): Long {
        purgeExpired(now)
        val reserved = (mintReservedUntil - now).coerceAtLeast(0)
        val pending = intents.values.maxOfOrNull { (it.expiresAt - now).coerceAtLeast(0) } ?: 0
        return maxOf(reserved, pending)
    }

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

    /** issue #115: this deviceId is a confirmed GUEST folder-share credential — a retired kind, still loaded until
     *  it is retired. Remote execution's collision check ([dev.ccpocket.daemon.relay.DeviceSessions.isKnownDevice])
     *  relies on it. */
    @Synchronized
    fun isGuest(deviceId: String): Boolean = specs[deviceId]?.kind == CredentialKind.GUEST && deviceId in bridgePubs

    /** A retired credential (a Collaborator Link or a folder-share guest) whose relay-side revoke is not
     *  confirmed yet: its key is gone, but the relay may still announce the id, so it must be treated as known
     *  (never armed, never allow-listed, never bound to another credential). */
    @Synchronized
    fun isRetiredCredential(deviceId: String): Boolean = deviceId in retired

    /** Every tombstoned id the relay still has to be asked to revoke. */
    @Synchronized
    fun retiredCredentialIds(): Set<String> = retired.toSet()

    /** The relay no longer honours [deviceId] (it confirmed the revoke, or its authoritative replay left the id
     *  out): drop the tombstone. True when [deviceId] was one. A failed write only means the next start asks
     *  the relay again, which it answers by doing nothing. */
    @Synchronized
    fun confirmRetired(deviceId: String): Boolean {
        if (!retired.remove(deviceId)) return false
        if (!RetiredCredentialStore.save(retired.toList(), retiredStore)) {
            log.warn("retired credential ${deviceId.take(8)}… confirmed revoked, but the tombstone file could not be updated")
        }
        log.info("retired credential ${deviceId.take(8)}… revoked at the relay — tombstone removed")
        return true
    }

    /** Every confirmed folder-share GUEST credential still held — the ones still to be retired. */
    @Synchronized
    fun guestIds(): List<String> =
        specs.entries.filter { it.value.kind == CredentialKind.GUEST && it.key in bridgePubs }.map { it.key }

    /*
     * Folder sharing (#115) was retired (2026-10), and with it the GUEST credential. Unlike a Collaborator Link a
     * guest key is still loaded at startup — so the transport keeps recognising the kind (and refusing it), and the
     * relay's replay of the id takes the restricted early-return instead of looking like a new device. Once the
     * relay's device set is known the transport retires them in the same order as [retireCollaborators]:
     *
     *  1. [tombstoneGuests]: merge the ids into the tombstone file ([RetiredCredentialStore]) — ids only;
     *  2. only for the ids that write covered, [forgetRetiredGuests]: drop each key and rewrite guests.json
     *     without it. ONLY guests.json is written — bridges.json, execution-credentials.json and every other
     *     file stay byte for byte;
     *  3. the relay is asked to revoke each tombstoned id, and the tombstone goes once it confirms
     *     ([confirmRetired]).
     */

    /** Step 1 of retiring guests: tombstone those of [ids] that are confirmed GUEST credentials. Returns the ids
     *  now tombstoned — empty when there were none or the write failed, in which case nothing changed at all (the
     *  keys stay loaded, still refused at ingress, and the next attach tries again). */
    @Synchronized
    fun tombstoneGuests(ids: Collection<String>): List<String> {
        val guests = ids.filter { specs[it]?.kind == CredentialKind.GUEST && it in bridgePubs }
        if (guests.isEmpty()) return emptyList()
        if (!tombstonesWritable || !RetiredCredentialStore.save((retired + guests).toList(), retiredStore)) {
            log.warn("could not record ${guests.size} retired guest credential(s) — keeping their keys for the next attach")
            return emptyList()
        }
        retired += guests
        return guests
    }

    /** Step 2 of retiring guests: drop the key of every one of [ids] that is a TOMBSTONED guest and rewrite
     *  guests.json without them — no other file. Closing their sessions is the transport's job, by deviceId. */
    @Synchronized
    fun forgetRetiredGuests(ids: Collection<String>) {
        val guests = ids.filter { it in retired && specs[it]?.kind == CredentialKind.GUEST }
        if (guests.isEmpty()) return
        guests.forEach { id ->
            bridgePubs.remove(id); specs.remove(id); createdAts.remove(id); provisionalPub.remove(id)
        }
        GuestStore.save(rows(CredentialKind.GUEST), guestStore)
        log.info("retired ${guests.size} folder-share guest credential(s): keys cleared, relay revoke pending")
    }

    /** issue #367: this deviceId is a confirmed EXECUTION link credential (a peer daemon's run link). */
    @Synchronized
    fun isExecution(deviceId: String): Boolean = specs[deviceId]?.kind == CredentialKind.EXECUTION && deviceId in bridgePubs

    /** The [ExecutionGrant][dev.ccpocket.daemon.execution.ExecutionGrant] id a confirmed EXECUTION
     *  credential points at (issue #367). Null for every other kind — and for an execution row whose
     *  grantId is blank, which [admitLoaded] already refuses to load. */
    @Synchronized
    fun executionGrantIdOf(deviceId: String): String? =
        specs[deviceId]?.takeIf { it.kind == CredentialKind.EXECUTION && deviceId in bridgePubs }?.grantId?.takeIf { it.isNotBlank() }

    @Synchronized
    fun kindOf(deviceId: String): CredentialKind? = specs[deviceId]?.kind

    /** True for a CONFIRMED restricted credential OR one still provisional. EGRESS filtering + DaemonInfo
     *  withholding key on this: every restricted kind is relay-only (no LAN), and the handshake DaemonInfo
     *  is sealed BEFORE the first transport frame confirms the kind, so a provisional restricted candidate
     *  must not be handed the LAN address it can't use anyway. */
    @Synchronized
    fun isBridgeCandidate(deviceId: String): Boolean = deviceId in bridgePubs || deviceId in provisionalPub

    @Synchronized
    fun pubOf(deviceId: String): ByteArray? = bridgePubs[deviceId] ?: provisionalPub[deviceId]

    @Synchronized
    fun specOf(deviceId: String): BridgeSpec? = specs[deviceId]

    /** Begin (or reuse) a live BRIDGE enforcement guard. Null for a non-bridge. */
    @Synchronized
    fun startGuard(deviceId: String): BridgeGuard? {
        val spec = specs[deviceId]?.takeIf { it.kind == CredentialKind.BRIDGE } ?: return null
        return guards.getOrPut(deviceId) { BridgeGuard(spec) }
    }

    @Synchronized
    fun guardOf(deviceId: String): BridgeGuard? = guards[deviceId]

    /** Revoked (or pruned by attach-replay reconcile): forget the credential + its guard. */
    @Synchronized
    fun remove(deviceId: String) {
        val existed = bridgePubs.remove(deviceId) != null || specs.remove(deviceId) != null
        if (existed) {
            provisionalPub.remove(deviceId); guards.remove(deviceId); createdAts.remove(deviceId)
            persist()
            log.info("restricted credential ${deviceId.take(8)}… removed")
        }
    }

    /** Confirmed BRIDGE deviceIds + names — for the `bridges` CLI listing (guests excluded). */
    @Synchronized
    fun list(): List<Pair<String, BridgeSpec>> = bridges().map { (id, spec, _) -> id to spec }

    /** Confirmed BRIDGEs — deviceId + spec + when bound — for the owner's management page. Carries the bind
     *  time the CLI listing ([list]) has no use for. */
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

    private fun persist() {
        // split by kind: bridges.json holds ONLY bridges, guests.json ONLY guests, execution-credentials.json
        // ONLY execution links — the downgrade-isolation invariant (an older daemon reading its own files
        // must never see a newer kind's key). A retired COLLABORATOR row is never written anywhere: the
        // keys file is only ever emptied, by [retireCollaborators].
        BridgeStore.save(rows(CredentialKind.BRIDGE), store)
        GuestStore.save(rows(CredentialKind.GUEST), guestStore)
        ExecutionCredentialStore.save(rows(CredentialKind.EXECUTION), executionKeyStore) // issue #367

    }

    /** The persisted rows of one [kind], in the shape its file holds. Caller holds `this`. */
    private fun rows(kind: CredentialKind): Map<String, BridgeEntry> =
        bridgePubs.entries.filter { specs[it.key]?.kind == kind }.associate { (id, pub) ->
            // keep each credential's ORIGINAL bind time — an unrelated persist (another bind, a revoke)
            // must not restamp every row's createdAt
            id to BridgeEntry(b64enc.encodeToString(pub), specs[id]!!, createdAts[id] ?: System.currentTimeMillis())
        }

    private fun purgeExpired(now: Long) { intents.entries.removeAll { it.value.expiresAt <= now } }

    @OptIn(ExperimentalStdlibApi::class)
    private fun hashHex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHexString()

    companion object {
        /** How long a [reserveMint] claim survives without release — a safety net only (the relay mint
         *  round-trip is bounded at ~10s and every caller releases in `finally`); generous enough that no
         *  live mint ever loses its slot mid-flight, short enough that an orphaned claim can't wedge
         *  minting for long. */
        const val MINT_RESERVE_MS = 30_000L

        /**
         * Grace beyond a redeem ticket's TTL for its intent to stay bindable. The intent is recorded AFTER
         * the mint round-trip, so it already outlives the relay ticket slightly; the grace makes
         * classification robust to redeem→connect→first-frame latency and modest clock skew, so a
         * slow-to-first-frame bridge/guest is never mis-promoted to a full-power device (issue #91).
         *
         * One constant for every mint path — loopback `pair --headless` and the wire
         * [dev.ccpocket.protocol.CreateBridge] — because they must classify identically. An abandoned mint
         * blocks re-mint for at most this long.
         */
        const val INTENT_GRACE_MS = 120_000L
    }
}
