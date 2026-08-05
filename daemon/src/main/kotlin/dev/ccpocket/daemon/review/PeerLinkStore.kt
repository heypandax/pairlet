package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One INBOUND collaborator link, from the RECIPIENT's point of view (REVIEW-REQUEST.md §9): "I hold a
 * restricted credential in <peer>'s account, and my daemon keeps an inbox connection to their daemon."
 *
 * Public metadata only. Everything that could impersonate this link lives in [PeerLinkSecret], in a
 * different file, and never leaves the daemon: no list/show/prepare response may contain it.
 */
@Serializable
data class PeerLink(
    /** Our own local handle for the link (`pl_…`). Not the peer's deviceId — that is theirs to key on. */
    val id: String,
    val label: String,
    /** The relay this peer's daemon is attached to (wss base). */
    val relay: String,
    /** The peer's accountId — with [id], what keeps two peers' identical request ids from colliding. */
    val peerAccountId: String,
    /** The peer daemon's static E2E public key, PINNED at join. Every reconnect must meet this key. */
    val peerDaemonPub: String,
    /** The deviceId the peer's relay issued us. It is how their daemon addresses requests to us. */
    val deviceId: String,
    /** Word-group fingerprint of [peerDaemonPub] — what the two humans compare out loud. */
    val fingerprint: String,
    val joinedAt: Long,
    /** Severed links stay listed (terminal): their historical mirrored requests still reference them. */
    val removed: Boolean = false,
)

/**
 * The secret half of a [PeerLink], in `~/.cc-pocket/peer-link-secrets.json` (0600, atomic).
 *
 * [ticket] is the ONE-TIME connect ticket, kept only until the first successful authenticated E2E
 * exchange proves the credential works — after that every reconnect keys off the persisted static
 * keypair with an EMPTY psk, exactly like a re-connecting phone. Holding a burned ticket forever would
 * leave a redeemable secret on disk for no benefit.
 */
@Serializable
data class PeerLinkSecret(
    val id: String,
    /** Relay bearer credential for our deviceId in the peer's account. */
    val credential: String,
    /** Our per-link E2E keypair (base64url raw). Per LINK, never shared between peers. */
    val privateKeyB64: String,
    val publicKeyB64: String,
    val ticket: String? = null,
    /**
     * How many first-contact handshakes this link has ATTEMPTED while still holding [ticket] — the
     * durable "this link has never completed" signal, which survives a restart. Reset (and meaningless)
     * once [ticket] is null: every later connect is an ordinary empty-PSK reconnect.
     */
    val handshakeAttempts: Int = 0,
)

/**
 * Persistence for inbound peer links. Two files on purpose: `peer-links.json` is the address book the
 * local API may render, `peer-link-secrets.json` is key material with a separate lifetime and a
 * separate blast radius. A serializer bug or an over-eager DTO can then leak at most the address book.
 */
class PeerLinkStore private constructor(
    private val publicPath: File?,
    private val secretPath: File?,
) {
    @Serializable
    private data class StoredLinks(val v: Int = 1, val links: List<PeerLink> = emptyList())

    @Serializable
    private data class StoredSecrets(
        val v: Int = 1,
        val secrets: List<PeerLinkSecret> = emptyList(),
        /** Public link rows staged here form a tiny recovery journal for the two-file update. */
        val pendingLinks: List<PeerLink> = emptyList(),
    )

    sealed interface RemoveResult {
        data class Ok(val link: PeerLink) : RemoveResult
        data object NotFound : RemoveResult
        data object PersistFailed : RemoveResult
    }

    private val lock = Any()
    private var links: StoredLinks = StoredLinks()
    private var secrets: StoredSecrets = StoredSecrets()

    fun all(): List<PeerLink> = synchronized(lock) { links.links }

    fun active(): List<PeerLink> = synchronized(lock) { links.links.filterNot { it.removed } }

    fun byId(id: String): PeerLink? = synchronized(lock) { links.links.firstOrNull { it.id == id } }

    fun secretOf(id: String): PeerLinkSecret? = synchronized(lock) { secrets.secrets.firstOrNull { it.id == id } }

    /** Add or replace a link and its secret. The secret file is written first with the desired public
     * row as a recovery journal; once that succeeds the redeemed credential cannot be lost even if the
     * public-file write or the process crashes. [load] completes any journaled update at next boot. */
    fun put(link: PeerLink, secret: PeerLinkSecret): Boolean = synchronized(lock) {
        val nextLinks = links.copy(links = links.links.filterNot { it.id == link.id } + link)
        val stagedSecrets = secrets.copy(
            secrets = secrets.secrets.filterNot { it.id == secret.id } + secret,
            pendingLinks = secrets.pendingLinks.filterNot { it.id == link.id } + link,
        )
        if (!writeSecrets(stagedSecrets)) return@synchronized false
        secrets = stagedSecrets
        links = nextLinks
        if (writeLinks(nextLinks)) clearJournalLocked(link.id)
        true
    }

    fun putLink(link: PeerLink): Boolean = synchronized(lock) {
        val secret = secrets.secrets.firstOrNull { it.id == link.id } ?: return@synchronized false
        put(link, secret)
    }

    /** Burn the one-time ticket once the credential has provably worked. Idempotent. */
    fun clearTicket(id: String): Boolean = synchronized(lock) {
        val s = secrets.secrets.firstOrNull { it.id == id } ?: return@synchronized false
        if (s.ticket == null) return@synchronized true
        val next = secrets.copy(
            secrets = secrets.secrets.map { if (it.id == id) it.copy(ticket = null, handshakeAttempts = 0) else it },
        )
        if (!writeSecrets(next)) return@synchronized false
        secrets = next
        true
    }

    /**
     * Record that a handshake is about to be ATTEMPTED, and answer with the secret to use for it.
     *
     * Counting is cheap and it is the only durable evidence of "this link has tried and got nowhere",
     * which is what a human needs to be told when a first contact never completes. It writes only while
     * a ticket is still held — once burned, every connect is an ordinary reconnect and persisting a
     * counter on each would be pointless IO.
     */
    fun beginHandshake(id: String): PeerLinkSecret? = synchronized(lock) {
        val s = secrets.secrets.firstOrNull { it.id == id } ?: return@synchronized null
        if (s.ticket == null) return@synchronized s
        val bumped = s.copy(handshakeAttempts = s.handshakeAttempts + 1)
        val next = secrets.copy(secrets = secrets.secrets.map { if (it.id == id) bumped else it })
        // a failed write leaves the counter where it was; the attempt still proceeds
        if (!writeSecrets(next)) return@synchronized s
        secrets = next
        bumped
    }

    /**
     * Sever a link: the row turns terminal (kept — mirrored requests reference it) and its KEY MATERIAL
     * IS DELETED. Deleting the secret is what actually stops the sync: without a credential and static
     * key there is nothing left to reconnect with, whatever the flag says.
     */
    fun remove(id: String): RemoveResult = synchronized(lock) {
        val row = links.links.firstOrNull { it.id == id } ?: return@synchronized RemoveResult.NotFound
        if (row.removed && secrets.secrets.none { it.id == id }) return@synchronized RemoveResult.Ok(row)
        val gone = row.copy(removed = true)
        val nextLinks = links.copy(links = links.links.map { if (it.id == id) gone else it })
        val stagedSecrets = secrets.copy(
            secrets = secrets.secrets.filterNot { it.id == id },
            pendingLinks = secrets.pendingLinks.filterNot { it.id == id } + gone,
        )
        // Secret-first is fail closed: once this succeeds the credential is durably gone and the
        // public tombstone can always be recovered from pendingLinks.
        if (!writeSecrets(stagedSecrets)) return@synchronized RemoveResult.PersistFailed
        secrets = stagedSecrets
        links = nextLinks
        if (writeLinks(nextLinks)) clearJournalLocked(id)
        RemoveResult.Ok(gone)
    }

    private fun clearJournalLocked(id: String) {
        val next = secrets.copy(pendingLinks = secrets.pendingLinks.filterNot { it.id == id })
        if (writeSecrets(next)) secrets = next
    }

    private fun writeLinks(next: StoredLinks): Boolean =
        publicPath?.let { ReviewFiles.write(it, PocketJson.encodeToString(StoredLinks.serializer(), next)) } ?: true

    private fun writeSecrets(next: StoredSecrets): Boolean =
        secretPath?.let { ReviewFiles.write(it, PocketJson.encodeToString(StoredSecrets.serializer(), next)) } ?: true

    companion object {
        fun defaultPublicPath(): File = ReviewFiles.path("peer-links.json")
        fun defaultSecretPath(): File = ReviewFiles.path("peer-link-secrets.json")

        fun load(
            publicPath: File = defaultPublicPath(),
            secretPath: File = defaultSecretPath(),
        ): PeerLinkStore = PeerLinkStore(publicPath, secretPath).apply {
            ReviewFiles.read(publicPath) { PocketJson.decodeFromString(StoredLinks.serializer(), it) }?.let { links = it }
            ReviewFiles.read(secretPath) { PocketJson.decodeFromString(StoredSecrets.serializer(), it) }?.let { secrets = it }

            synchronized(lock) {
                if (secrets.pendingLinks.isNotEmpty()) {
                    val pendingById = secrets.pendingLinks.associateBy { it.id }
                    val recovered = links.links.filterNot { it.id in pendingById } + pendingById.values
                    val nextLinks = links.copy(links = recovered)
                    links = nextLinks
                    if (writeLinks(nextLinks)) {
                        val settled = secrets.copy(pendingLinks = emptyList())
                        if (writeSecrets(settled)) secrets = settled
                    }
                }
                // A secret with neither a public row nor a journaled row is unreachable: the daemon
                // cannot display, use or revoke it. Delete that orphaned key material fail-closed (for
                // example after the public address-book file was quarantined as corrupt).
                val reachableIds = (links.links.map { it.id } + secrets.pendingLinks.map { it.id }).toSet()
                if (secrets.secrets.any { it.id !in reachableIds }) {
                    val nextSecrets = secrets.copy(secrets = secrets.secrets.filter { it.id in reachableIds })
                    if (writeSecrets(nextSecrets)) secrets = nextSecrets
                }
                // An active public row with no credential can never reconnect. Render it terminal rather
                // than letting the address book promise a live link that the daemon cannot use.
                val secretIds = secrets.secrets.mapTo(HashSet()) { it.id }
                val normalized = links.links.map { if (!it.removed && it.id !in secretIds) it.copy(removed = true) else it }
                if (normalized != links.links) {
                    val nextLinks = links.copy(links = normalized)
                    if (writeLinks(nextLinks)) links = nextLinks
                }
            }
        }

        fun inMemory(): PeerLinkStore = PeerLinkStore(null, null)
    }
}
