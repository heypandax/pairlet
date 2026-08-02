package dev.ccpocket.app.pairing

import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PairCodePayload
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.e2e.E2ECrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** What the daemon's QR encodes: ccpocket://pair?relay=<wss>&acct=<id>&dpk=<daemon e2e pub>&ticket=<one-time>. */
data class PairingInfo(val relay: String, val accountId: String, val daemonPub: String, val ticket: String)

/**
 * What a local binding's credential is (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.2.1). Purely a LOCAL
 * routing/display fact — the daemon enforces the real authority — but the app must not treat three very
 * different credentials as one "computer":
 *
 *  - [OWNER]        a computer you paired: directories, sessions, settings, the lot;
 *  - [GUEST]        a folder-share invite you redeemed (issue #115): one folder on someone else's machine;
 *  - [COLLABORATOR] a Collaborator Link (§4.1): ZERO session access — an inbox for Handoff offers only.
 *
 * Persisted as a plain STRING with a tolerant decode: records written before this field existed have no
 * `role` at all (the default applies) and a value only a NEWER build knows degrades to [OWNER] — the
 * pre-role reading — instead of failing the whole list decode and unpairing every computer.
 */
@Serializable(with = BindingRoleSerializer::class)
enum class BindingRole { OWNER, GUEST, COLLABORATOR }

private object BindingRoleSerializer : KSerializer<BindingRole> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BindingRole", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BindingRole) = encoder.encodeString(value.name.lowercase())
    override fun deserialize(decoder: Decoder): BindingRole {
        val s = decoder.decodeString()
        return BindingRole.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: BindingRole.OWNER
    }
}

/** The durable result of pairing — everything needed to reconnect end-to-end without re-pairing. */
@Serializable
data class PairedDaemon(
    val relay: String,
    val accountId: String,
    val daemonPub: String,   // base64url P-256, learned out-of-band from the QR (authenticates the daemon)
    val deviceId: String,
    val credential: String,  // relay bearer credential
    val label: String? = null, // user-assigned local nickname; wins over hostName/accountId in displayName()
    // daemon-advertised direct (LAN/loopback) ws URL, learned from DaemonInfo after each handshake and
    // tried BEFORE the relay on later connects. Same Noise-authenticated channel, zero relay/proxy legs;
    // a failed attempt falls back to the relay silently. Null = daemon has no direct listener (or predates it).
    val directUrl: String? = null,
    // daemon-reported OS computer name, learned from DaemonInfo — the DEFAULT display name until the user
    // sets a nickname, so a binding reads as "Pandas-MacBook-Pro" not an account-id hash (issue #62).
    val hostName: String? = null,
    // what this credential is allowed to be (§3.2.1). Trailing + defaulted so records written by a build
    // that predates the field still decode — as OWNER, which is exactly what they were.
    val role: BindingRole = BindingRole.OWNER,
)

/** What this binding shows in the device list: the user's nickname, else the daemon's reported computer
 *  name, else the truncated account id (issue #62 — the hostName default replaces the bare hash). */
fun PairedDaemon.displayName(): String =
    label?.takeIf { it.isNotBlank() } ?: hostName?.takeIf { it.isNotBlank() } ?: "${accountId.take(12)}…"

object Pairing {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(url: String): PairingInfo? {
        if (!url.contains("?")) return null
        val m = url.substringAfter("?").split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val relay = m["relay"]; val acct = m["acct"]; val dpk = m["dpk"]; val ticket = m["ticket"]
        return if (relay != null && acct != null && dpk != null && ticket != null) PairingInfo(relay, acct, dpk, ticket) else null
    }

    /** This device's long-term P-256 keypair, generated once and persisted. */
    fun deviceKeys(): E2ECrypto.KeyPair {
        val priv = SecureStore.getString(K_PRIV)
        val pub = SecureStore.getString(K_PUB)
        if (priv != null && pub != null) return E2ECrypto.KeyPair(B64Url.decode(priv), B64Url.decode(pub))
        val kp = E2ECrypto.generateKeyPair()
        SecureStore.putString(K_PRIV, B64Url.encode(kp.privateRaw))
        SecureStore.putString(K_PUB, B64Url.encode(kp.publicRaw))
        return kp
    }

    /** The relay-side half of every redeem: register our pubkey, receive a credential. Persists NOTHING —
     *  the caller decides which store the resulting binding belongs in (a computer vs. a contact inbox). */
    private suspend fun redeemCredential(
        info: PairingInfo,
        keys: E2ECrypto.KeyPair,
        client: HttpClient,
        role: BindingRole,
    ): PairedDaemon {
        val httpBase = info.relay.replace("wss://", "https://").replace("ws://", "http://")
        val resp = client.post("$httpBase/v1/pair/redeem") {
            setBody("""{"ticket":"${info.ticket}","devicePubKey":"${B64Url.encode(keys.publicRaw)}"}""")
        }.bodyAsText()
        val cred = runCatching { json.decodeFromString<PairCredential>(resp) }.getOrElse { error("pairing failed: $resp") }
        return PairedDaemon(info.relay, info.accountId, info.daemonPub, cred.deviceId, cred.credential, role = role)
    }

    /** Redeem a scanned ticket: register our pubkey, receive a credential, persist the paired record. */
    suspend fun redeem(
        info: PairingInfo,
        keys: E2ECrypto.KeyPair,
        client: HttpClient,
        role: BindingRole = BindingRole.OWNER,
    ): PairedDaemon =
        redeemCredential(info, keys, client, role).also { upsert(it); setActive(it.accountId) }

    /**
     * Redeem a Collaborator Link ticket (§4.1). Deliberately NOT [redeem]: a collaborator credential is
     * not "a computer you control" — it grants zero session access and must never join the machine list,
     * the fleet, the switcher, or [active]. It lands in its own store ([collaboratorLinks]) and leaves the
     * active account exactly where the user left it.
     */
    suspend fun redeemCollaboratorLink(info: PairingInfo, keys: E2ECrypto.KeyPair, client: HttpClient): PairedDaemon =
        redeemCredential(info, keys, client, BindingRole.COLLABORATOR).also { upsertCollaborator(it) }

    /** The relay this app pairs against (the daemon dials the same one). Override in Advanced if self-hosting. */
    const val DEFAULT_RELAY = "wss://pocket.ark-nexus.cc"

    /** Resolve a 6-digit code typed by the user into the full pairing info (relay-assisted path). */
    suspend fun resolveCode(code: String, client: HttpClient): PairingInfo {
        val httpBase = DEFAULT_RELAY.replace("wss://", "https://").replace("ws://", "http://")
        val resp = client.post("$httpBase/v1/pair/code") { setBody("""{"code":"$code"}""") }.bodyAsText()
        val payload = runCatching { json.decodeFromString<PairCodePayload>(resp) }.getOrElse { error("invalid or expired code") }
        return PairingInfo(DEFAULT_RELAY, payload.accountId, payload.daemonPub, payload.ticket)
    }

    // ── paired-daemon store: a list of bindings + which one is active ───────────────────────────────
    // The phone can bind several computers; it talks to exactly one at a time (the "active" account).

    /** Every bound computer. Migrates the legacy single-record key into the list the first time it runs. */
    fun loadAll(): List<PairedDaemon> {
        SecureStore.getString(K_PAIRED_LIST)?.let {
            return runCatching { json.decodeFromString<List<PairedDaemon>>(it) }.getOrDefault(emptyList())
        }
        val legacy = SecureStore.getString(K_PAIRED)?.let { runCatching { json.decodeFromString<PairedDaemon>(it) }.getOrNull() }
        return if (legacy != null) {
            saveAll(listOf(legacy)); setActive(legacy.accountId); SecureStore.remove(K_PAIRED); listOf(legacy)
        } else emptyList()
    }

    private fun saveAll(list: List<PairedDaemon>) = SecureStore.putString(K_PAIRED_LIST, json.encodeToString(list))

    /**
     * Add or replace a binding. The key is `(accountId, deviceId)` PLUS `(accountId, role)` (§3.2.2):
     *
     *  - `(accountId, deviceId)` is the identity — the relay mints a FRESH random deviceId per redeem, so
     *    two credentials for one daemon are genuinely two bindings and must not silently overwrite;
     *  - `(accountId, role)` keeps "re-pairing the same computer refreshes its credential in place" true:
     *    a second OWNER redeem supersedes the first rather than leaving a dead duplicate row.
     *
     * Together they stop the bug this rule exists for: redeeming a folder-share (GUEST) invite for a daemon
     * you already own used to REPLACE the owner binding, downgrading the whole machine to one folder.
     * (Collaborator links never reach this list at all — see [upsertCollaborator].)
     */
    fun upsert(p: PairedDaemon): List<PairedDaemon> =
        (loadAll().filterNot { it.accountId == p.accountId && (it.deviceId == p.deviceId || it.role == p.role) } + p)
            .also(::saveAll)

    /** Drop one binding; if it was the active one, hand "active" to whatever remains (or clear it). */
    fun remove(accountId: String): List<PairedDaemon> =
        loadAll().filterNot { it.accountId == accountId }.also {
            saveAll(it); if (activeAccount() == accountId) setActive(it.lastOrNull()?.accountId)
        }

    /** Set/clear a binding's local nickname (blank clears it back to the accountId fallback). */
    fun rename(accountId: String, label: String?): List<PairedDaemon> =
        loadAll().map { if (it.accountId == accountId) it.copy(label = label?.ifBlank { null }) else it }.also(::saveAll)

    /** Persist the daemon-advertised direct (LAN) URL for a binding — tried before the relay on reconnects. */
    fun setDirectUrl(accountId: String, url: String?): List<PairedDaemon> =
        loadAll().map { if (it.accountId == accountId) it.copy(directUrl = url) else it }.also(::saveAll)

    /** Persist the daemon-reported computer name for a binding — the default display name (issue #62). */
    fun setHostName(accountId: String, name: String?): List<PairedDaemon> =
        loadAll().map { if (it.accountId == accountId) it.copy(hostName = name?.ifBlank { null }) else it }.also(::saveAll)

    fun activeAccount(): String? = SecureStore.getString(K_ACTIVE)
    fun setActive(accountId: String?) { if (accountId == null) SecureStore.remove(K_ACTIVE) else SecureStore.putString(K_ACTIVE, accountId) }

    /** The active binding: the one pinned by [activeAccount], else the most-recently paired, else null.
     *  With several bindings on one account (you own the machine AND hold a folder-share on it) the OWNER
     *  one wins — the richer credential is what "switch to that computer" means. */
    fun active(): PairedDaemon? = loadAll().let { all ->
        val pinned = all.filter { it.accountId == activeAccount() }
        pinned.firstOrNull { it.role == BindingRole.OWNER } ?: pinned.firstOrNull() ?: all.lastOrNull()
    }

    // ── collaborator links (SESSION-HANDOFF.md §4.1) — a SEPARATE store, deliberately ──────────────
    // A Collaborator Link is an inbox, not a computer: it can receive Handoff offers and nothing else.
    // Keeping it out of [loadAll] keeps it out of the machine list, the fleet satellites, the ⌘K switcher
    // and [active] by construction, instead of by every reader remembering to filter.

    /** Every Collaborator Link this device holds (contacts who can hand work to us). Never a "computer". */
    fun collaboratorLinks(): List<PairedDaemon> =
        SecureStore.getString(K_COLLAB_LINKS)?.let {
            runCatching { json.decodeFromString<List<PairedDaemon>>(it) }.getOrDefault(emptyList())
        }.orEmpty()

    private fun saveCollaborators(list: List<PairedDaemon>) =
        SecureStore.putString(K_COLLAB_LINKS, json.encodeToString(list))

    /** Add or refresh a Collaborator Link. Keyed on accountId alone: re-scanning a colleague's QR mints a
     *  new deviceId for the SAME contact, and the old credential is dead the moment the new one exists —
     *  keeping both would leave a permanently failing link in the inbox. */
    fun upsertCollaborator(p: PairedDaemon): List<PairedDaemon> =
        (collaboratorLinks().filterNot { it.accountId == p.accountId } + p.copy(role = BindingRole.COLLABORATOR))
            .also(::saveCollaborators)

    /** Drop a Collaborator Link (the local half of "remove contact"). */
    fun removeCollaborator(accountId: String): List<PairedDaemon> =
        collaboratorLinks().filterNot { it.accountId == accountId }.also(::saveCollaborators)

    /** Persist a Collaborator Link's daemon-advertised direct URL / computer name — same write-through the
     *  computer list gets, so an inbox link also skips the relay on a LAN and reads as a name, not a hash. */
    fun setCollaboratorDirectUrl(accountId: String, url: String?): List<PairedDaemon> =
        collaboratorLinks().map { if (it.accountId == accountId) it.copy(directUrl = url) else it }.also(::saveCollaborators)

    fun setCollaboratorHostName(accountId: String, name: String?): List<PairedDaemon> =
        collaboratorLinks().map { if (it.accountId == accountId) it.copy(hostName = name?.ifBlank { null }) else it }.also(::saveCollaborators)

    private const val K_PRIV = "device_priv"
    private const val K_PUB = "device_pub"
    private const val K_PAIRED = "paired_daemon"       // legacy single record (migrated into K_PAIRED_LIST, then removed)
    private const val K_PAIRED_LIST = "paired_daemons" // JSON array of every bound computer
    private const val K_ACTIVE = "active_account"      // accountId of the binding we currently talk to
    private const val K_COLLAB_LINKS = "collab_links"  // JSON array of Collaborator Links — NOT computers
}
