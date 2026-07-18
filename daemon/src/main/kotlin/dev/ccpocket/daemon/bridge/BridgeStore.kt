package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Which class of restricted credential a [BridgeSpec] describes — the single discriminator the daemon
 * dispatches capability policy on. Both classes share ONE binding chain (ticket-PSK anchor, mint
 * serialization, provisional handling; [BridgeRegistry]) but different frame whitelists / guards:
 *
 *  - [BRIDGE] (issue #91): a headless external automation. It never SEES a permission ask (approvals
 *    route to the OWNER) and can only open/prompt/cancel/close. Long-lived, no expiry.
 *  - [GUEST] (issue #115): a scoped INTERACTIVE collaborator on one shared folder. It approves its OWN
 *    asks, lists + browses inside the shared root, but never reaches the daemon's management plane or any
 *    path outside the root. Expires; the owner can revoke.
 *
 * Old bridges.json entries (pre-#115) carry no `kind` → default [BRIDGE], so #91 credentials keep their
 * exact behaviour. A GUEST is persisted to a SEPARATE file (guests.json) so a downgraded daemon that
 * predates guests never loads it and fails that key closed — the same downgrade-safety argument that
 * keeps bridge keys out of devices.json.
 */
@Serializable
enum class CredentialKind { BRIDGE, GUEST }

/**
 * Constraints minted with a restricted credential — decided by the OWNER at mint time and enforced
 * daemon-side on every frame. The credential holder never gets to relax them: they live in a
 * [BridgeStore]/[GuestStore] file the holder can't write.
 *
 * Named [BridgeSpec] for #91 lineage; generalized in #115 to also describe a [CredentialKind.GUEST]
 * (adding [kind]/[expiresAt]/[tier]). All additive with backward-compatible defaults, so a pre-#115
 * bridges.json entry decodes unchanged.
 */
@Serializable
data class BridgeSpec(
    val name: String,
    /** Absolute workdir roots the credential may open sessions under (canonical, no trailing sep).
     *  For a GUEST this is the single shared folder (its subtree) — the whole scope boundary. */
    val workdirs: List<String>,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val opensPerMin: Int = DEFAULT_OPENS_PER_MIN,
    val promptsPerMin: Int = DEFAULT_PROMPTS_PER_MIN,
    val kind: CredentialKind = CredentialKind.BRIDGE,
    /** GUEST only: when this share expires (epoch ms). null = no expiry (a bridge). Past → the guest is
     *  cut and the credential purged (issue #115 §6). */
    val expiresAt: Long? = null,
    /** The autonomy tier the owner granted — the permission-mode CEILING (never bypass), enforced for
     *  BOTH kinds via [TierClamp]. Always set explicitly at mint: [guest] takes the owner's chosen tier,
     *  [clamped] defaults a bridge to the strictest.
     *
     *  The default here is REVIEW because it is the FALLBACK, and a fallback must fail safe: it applies to
     *  a spec built without naming a tier (a stored entry from before the field existed, a test, a future
     *  call site). Defaulting to COLLABORATE would mean forgetting to pass a tier silently grants silent
     *  file edits — the failure would be invisible until something wrote without asking. Live data is
     *  unaffected either way: PocketJson has encodeDefaults=true, so every persisted spec names its tier. */
    val tier: AccessTier = AccessTier.REVIEW,
) {
    val isGuest: Boolean get() = kind == CredentialKind.GUEST

    /** True if this is a GUEST share whose lifetime has lapsed as of [now]. */
    fun expired(now: Long): Boolean = expiresAt?.let { it <= now } == true

    companion object {
        const val DEFAULT_MAX_SESSIONS = 2
        const val DEFAULT_OPENS_PER_MIN = 6
        const val DEFAULT_PROMPTS_PER_MIN = 20

        // a GUEST is interactive (a human at a phone), so its caps are looser than a bridge's but still
        // bounded — one collaborator must not be able to fork-bomb the owner's machine.
        const val GUEST_MAX_SESSIONS = 4
        const val GUEST_OPENS_PER_MIN = 12
        const val GUEST_PROMPTS_PER_MIN = 60

        /**
         * Clamp owner-supplied bridge overrides into sane bounds — a typo'd `--max-sessions 999` must not
         * turn one credential into a fork bomb.
         *
         * [tier] is the granted permission-mode CEILING (issue #91's "configurable default execution
         * mode"), and it defaults to the STRICTEST — unlike a guest share, which defaults to COLLABORATE.
         * The asymmetry is deliberate: a folder share is handed to a specific person the owner chose,
         * whereas a bridge relays prompts from ANYONE in an IM chat. Silent file edits are a reasonable
         * default for the former and a bad one for the latter, so an owner who wants that for a bot has
         * to say so at mint time.
         */
        fun clamped(
            name: String,
            workdirs: List<String>,
            maxSessions: Int?,
            opensPerMin: Int?,
            promptsPerMin: Int?,
            tier: AccessTier = AccessTier.REVIEW,
        ) = BridgeSpec(
            name = name,
            workdirs = workdirs,
            maxSessions = (maxSessions ?: DEFAULT_MAX_SESSIONS).coerceIn(1, 8),
            opensPerMin = (opensPerMin ?: DEFAULT_OPENS_PER_MIN).coerceIn(1, 30),
            promptsPerMin = (promptsPerMin ?: DEFAULT_PROMPTS_PER_MIN).coerceIn(1, 120),
            tier = tier.takeUnless { it == AccessTier.UNKNOWN } ?: AccessTier.REVIEW, // unknown → safest
        )

        /** Build a GUEST spec (issue #115): a single canonical shared root, an access tier, and an expiry. */
        fun guest(name: String, root: String, tier: AccessTier, expiresAt: Long) = BridgeSpec(
            name = name,
            workdirs = listOf(root),
            maxSessions = GUEST_MAX_SESSIONS,
            opensPerMin = GUEST_OPENS_PER_MIN,
            promptsPerMin = GUEST_PROMPTS_PER_MIN,
            kind = CredentialKind.GUEST,
            expiresAt = expiresAt,
            tier = tier,
        )
    }
}

/** One registered restricted credential: its E2E static key + the constraints minted with it. */
@Serializable
data class BridgeEntry(
    val pubB64: String, // base64url(no-pad) X25519 static public key — the handshake identity
    val spec: BridgeSpec,
    val createdAt: Long,
)

/**
 * The persisted registry of HEADLESS bridge credentials (issue #91): deviceId -> [BridgeEntry],
 * in `~/.cc-pocket/bridges.json`. DELIBERATELY a separate file from devices.json:
 *
 *  - devices.json is the FULL-POWER allow-list, read by both the relay handshake path and the
 *    direct-LAN gate. A bridge key must never appear there — the LAN gate and every pre-#91 daemon
 *    would honor it as a complete device.
 *  - A DOWNGRADED daemon therefore fails closed: it never loads this file, treats the bridge as an
 *    unknown device, and refuses its handshake outright.
 *  - The direct-LAN listener consults devices.json only, so a bridge credential structurally cannot
 *    use the LAN path (its enforcement gates live on the relay path) — no gate code to keep in sync.
 *
 * Written with owner-only permissions where the filesystem supports it (same directory as the
 * daemon's identity key, which carries the same sensitivity).
 */
object BridgeStore {
    fun file(): File = credentialFile("bridges.json")

    fun load(store: File = file()): Map<String, BridgeEntry> = loadCredentials(store)

    fun save(map: Map<String, BridgeEntry>, store: File = file()) = saveCredentials(map, store)
}

/**
 * The persisted registry of GUEST folder-share credentials (issue #115): deviceId -> [BridgeEntry]
 * (kind = GUEST), in `~/.cc-pocket/guests.json`. A SEPARATE file from bridges.json AND devices.json for
 * the same downgrade-safety reason #91 keeps bridges out of devices.json, now applied one level deeper:
 *
 *  - A daemon that predates #115 (bridge-aware but guest-unaware) never loads guests.json, so a guest
 *    key is an unknown device to it → handshake refused, fail closed. It CANNOT mis-file a guest as a
 *    full-power bridge either (guests never touch bridges.json).
 *  - A guest key must never leak into devices.json (full power) or bridges.json (bridge power) — its
 *    strictly-scoped enforcement lives only on the relay guest path, so there is no other gate to keep
 *    in sync.
 *
 * Same owner-only permissions as bridges.json / the identity key.
 */
object GuestStore {
    fun file(): File = credentialFile("guests.json")

    fun load(store: File = file()): Map<String, BridgeEntry> = loadCredentials(store)

    fun save(map: Map<String, BridgeEntry>, store: File = file()) = saveCredentials(map, store)
}

private fun credentialFile(name: String): File {
    val dir = System.getenv("CC_POCKET_IDENTITY")?.let { File(it).parentFile }
        ?: File(System.getProperty("user.home"), ".cc-pocket")
    return File(dir, name)
}

private fun loadCredentials(store: File): Map<String, BridgeEntry> = runCatching {
    if (!store.exists()) return emptyMap()
    PocketJson.decodeFromString<Map<String, BridgeEntry>>(store.readText())
}.getOrDefault(emptyMap())

private fun saveCredentials(map: Map<String, BridgeEntry>, store: File) {
    runCatching {
        store.parentFile?.mkdirs()
        store.writeText(PocketJson.encodeToString(map))
        runCatching { // best-effort 0600 (POSIX only; Windows ACLs inherit the profile dir)
            Files.setPosixFilePermissions(store.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
