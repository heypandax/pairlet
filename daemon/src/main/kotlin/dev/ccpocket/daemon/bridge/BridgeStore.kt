package dev.ccpocket.daemon.bridge

import dev.ccpocket.daemon.diagnostics.storageReadFailed
import dev.ccpocket.daemon.diagnostics.storageWriteFailed
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
 *  - [GUEST]: the folder-share guest (issue #115), RETIRED 2026-10 (已下线，仅为清除存量凭据保留). Nothing
 *    mints one any more; guests.json is still loaded so the transport recognises the kind and refuses every
 *    frame from it, and so the relay link can retire each one (tombstone, key cleared, relay revoke — see
 *    [BridgeRegistry.tombstoneGuests]). Never remove it, for the same coercion reason as [COLLABORATOR].
 *  - [EXECUTION] (issue #367): the link a PEER DAEMON holds so its owner's agent can ask THIS machine to
 *    run a task. Its baseline is ZERO of the existing surface — no session plane at all, only the typed
 *    execution frames ([dev.ccpocket.daemon.execution.ExecutionCaps]) — and every authority it has lives
 *    in the target-owner's [dev.ccpocket.daemon.execution.ExecutionGrant] named by [BridgeSpec.grantId],
 *    never in this spec. It is the only kind that is barred from the direct-LAN gate EXPLICITLY as well as
 *    structurally, and the only one that never receives a [dev.ccpocket.protocol.DaemonInfo].
 *  - [COLLABORATOR]: the Collaborator Link credential of session handoff / review contacts, RETIRED
 *    2026-10. Nothing mints, loads or honours one any more; the value stays so the startup cleanup can
 *    recognise old rows ([BridgeRegistry]) and the transport can refuse the kind outright. Never remove it:
 *    PocketJson coerces an unknown enum value to the property default, which would read an old
 *    `"kind":"COLLABORATOR"` row as a [BRIDGE].
 *
 * Old bridges.json entries (pre-#115) carry no `kind` → default [BRIDGE], so #91 credentials keep their
 * exact behaviour. A GUEST is persisted to a SEPARATE file (guests.json), so a downgraded daemon that
 * predates it never loads it and fails that key closed — the same downgrade-safety argument that keeps
 * bridge keys out of devices.json. An EXECUTION credential extends the same chain one file further
 * (execution-credentials.json).
 */
@Serializable
enum class CredentialKind { BRIDGE, GUEST, COLLABORATOR, EXECUTION }

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
    /** Absolute workdir roots the credential may open sessions under (canonical, no trailing sep). */
    val workdirs: List<String>,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val opensPerMin: Int = DEFAULT_OPENS_PER_MIN,
    val promptsPerMin: Int = DEFAULT_PROMPTS_PER_MIN,
    val kind: CredentialKind = CredentialKind.BRIDGE,
    /** Set only on a GUEST row (issue #115, retired): when that share expired (epoch ms). Kept so stored rows
     *  keep decoding; nothing reads it any more. null for every live kind. */
    val expiresAt: Long? = null,
    /** The autonomy tier the owner granted — the permission-mode CEILING (never bypass), enforced via
     *  [TierClamp]. Always set explicitly at mint: [clamped] defaults a bridge to the strictest.
     *
     *  The default here is REVIEW because it is the FALLBACK, and a fallback must fail safe: it applies to
     *  a spec built without naming a tier (a stored entry from before the field existed, a test, a future
     *  call site). Defaulting to COLLABORATE would mean forgetting to pass a tier silently grants silent
     *  file edits — the failure would be invisible until something wrote without asking. Live data is
     *  unaffected either way: PocketJson has encodeDefaults=true, so every persisted spec names its tier. */
    val tier: AccessTier = AccessTier.REVIEW,
    /**
     * BRIDGE only (issue #91 "一次授权跑完全程"): owner-configured Bash command prefixes that auto-run with
     * NO phone prompt on this bridge's sessions. Widens [BridgeCommandPolicy]'s tiny built-in read-only set;
     * it can never override the DANGEROUS deny-list, and a command with shell metacharacters still routes to
     * the owner. Held here (an owner-only file the credential holder can't write), so it's an owner grant,
     * not something the IM side can raise. Empty for a guest and for the pre-field default (safe: nothing
     * extra auto-runs). Normalized (trimmed, blanks dropped, deduped, capped) by [clamped].
     */
    val allowedCommands: List<String> = emptyList(),
    /**
     * [CredentialKind.EXECUTION] only (issue #367): which [dev.ccpocket.daemon.execution.ExecutionGrant]
     * this link credential belongs to. It is a POINTER, not an authority — every scope decision (workspaces,
     * agents, ceiling, limits, expiry, revision) is re-read from the target owner's grant store on every
     * frame, and a credential whose grantId names no live grant can do nothing at all.
     *
     * Null for every other kind, and for a pre-#367 row (which by construction is not an execution link).
     */
    val grantId: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_SESSIONS = 2
        const val DEFAULT_OPENS_PER_MIN = 6
        const val DEFAULT_PROMPTS_PER_MIN = 20

        /**
         * Clamp owner-supplied bridge overrides into sane bounds — a typo'd `--max-sessions 999` must not
         * turn one credential into a fork bomb.
         *
         * [tier] is the granted permission-mode CEILING (issue #91's "configurable default execution
         * mode"), and it defaults to the STRICTEST: a bridge relays prompts from ANYONE in an IM chat, so
         * silent file edits are a bad default, and an owner who wants that for a bot has to say so at mint
         * time.
         */
        fun clamped(
            name: String,
            workdirs: List<String>,
            maxSessions: Int?,
            opensPerMin: Int?,
            promptsPerMin: Int?,
            tier: AccessTier = AccessTier.REVIEW,
            allowedCommands: List<String> = emptyList(),
        ) = BridgeSpec(
            name = name,
            workdirs = workdirs,
            maxSessions = (maxSessions ?: DEFAULT_MAX_SESSIONS).coerceIn(1, 8),
            opensPerMin = (opensPerMin ?: DEFAULT_OPENS_PER_MIN).coerceIn(1, 30),
            promptsPerMin = (promptsPerMin ?: DEFAULT_PROMPTS_PER_MIN).coerceIn(1, 120),
            tier = tier.takeUnless { it == AccessTier.UNKNOWN } ?: AccessTier.REVIEW, // unknown → safest
            allowedCommands = normalizeAllowedCommands(allowedCommands),
        )

        /** Owner input hygiene for the Bash allow-list: trim, drop blanks, dedupe (case-sensitive — command
         *  names are), and cap the count so a pasted mega-list can't bloat the spec. Not a security check —
         *  BridgeCommandPolicy still gates every entry against DANGEROUS + metacharacters at match time. */
        fun normalizeAllowedCommands(raw: List<String>): List<String> =
            raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_ALLOWED_COMMANDS)

        const val MAX_ALLOWED_COMMANDS = 64

        /**
         * Build an EXECUTION spec (issue #367 G1). Its baseline is ZERO: [workdirs] is
         * empty so [PathScope.contains] is false for every path, and every rate bound is the floor — none of
         * them is ever consulted, because an execution link reaches no session/bridge/guest code path at all
         * (its own whitelist, [dev.ccpocket.daemon.execution.ExecutionCaps], admits only the execution frames,
         * and every other whitelist denies those). The single meaningful field is [grantId]: the pointer the
         * run plane re-authorises against on every frame. No [expiresAt] here either — the GRANT expires, and
         * a lapsed grant refuses every frame while the credential is being revoked.
         */
        fun execution(label: String, grantId: String) = BridgeSpec(
            name = label,
            workdirs = emptyList(),
            maxSessions = 1,
            opensPerMin = 1,
            promptsPerMin = 1,
            kind = CredentialKind.EXECUTION,
            expiresAt = null,
            tier = AccessTier.REVIEW,
            grantId = grantId,
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
 * (kind = GUEST), in `~/.cc-pocket/guests.json`. Folder sharing is RETIRED (2026-10, 已下线): nothing writes a
 * new row; the file is only read, so stored guests stay recognised (and refused) until each one is retired,
 * and rewritten without them as they are ([BridgeRegistry.forgetRetiredGuests]). A SEPARATE file from
 * bridges.json AND devices.json for the same downgrade-safety reason #91 keeps bridges out of devices.json:
 * a guest key must never leak into devices.json (full power) or bridges.json (bridge power).
 *
 * Same owner-only permissions as bridges.json / the identity key.
 */
object GuestStore {
    fun file(): File = credentialFile("guests.json")

    fun load(store: File = file()): Map<String, BridgeEntry> = loadCredentials(store)

    fun save(map: Map<String, BridgeEntry>, store: File = file()) = saveCredentials(map, store)
}

/**
 * The file the RETIRED Collaborator Link credentials (session handoff / review contacts, retired 2026-10)
 * were kept in: deviceId -> [BridgeEntry] (kind = COLLABORATOR), `~/.cc-pocket/collaborator-keys.json`.
 * Nothing loads these keys any more. [BridgeRegistry] reads the file once at startup, moves every row's
 * deviceId into [RetiredCredentialStore] and empties the file — see [BridgeRegistry] for the order.
 */
object CollaboratorKeyStore {
    fun file(): File = credentialFile("collaborator-keys.json")

    fun load(store: File = file()): Map<String, BridgeEntry> = loadCredentials(store)

    fun save(map: Map<String, BridgeEntry>, store: File = file()) = saveCredentials(map, store)
}

/**
 * Tombstones of retired restricted credentials, device ids ONLY — no key, no spec, no kind. Two retired
 * features share it: the Collaborator Link (session handoff / review contacts) and the folder-share GUEST
 * (#115), both retired 2026-10. A full-power device the owner revoked (`pairlet devices revoke`) is
 * tombstoned here too, from just before it leaves devices.json: the list carries no kind because every
 * entry means the same thing — "the relay may still honour this id; never let it in until it confirms".
 * An id stays here from the moment its key is cleared until the relay has
 * confirmed the credential is revoked, and for that whole time the daemon treats the id as KNOWN: a relay
 * announce for it is never armed with a pairing ticket, so it can never be written into the full-power
 * devices.json during someone's pairing window. Written atomically, owner-only.
 *
 * The file is still `retired-collaborators.json` with the format it was introduced with, because the
 * collaborator retirement shipped first: tombstones an earlier build wrote keep loading, and a build that
 * only knows collaborators reads guest ids as tombstones too (the list carries no kind), so a downgrade
 * keeps holding them off the allow-list.
 */
object RetiredCredentialStore {
    fun file(): File = credentialFile(FILE_NAME)

    const val FILE_NAME = "retired-collaborators.json"

    @Serializable
    private data class Stored(val v: Int = 1, val ids: List<String> = emptyList())

    /** The stored ids; empty when the file is absent. Throws when the file exists but can neither be read
     *  nor quarantined — the caller must then not overwrite it. */
    fun load(store: File = file()): Set<String> =
        dev.ccpocket.daemon.peer.AtomicStoreFiles.read(store) {
            PocketJson.decodeFromString(Stored.serializer(), it)
        }?.ids?.toSet().orEmpty()

    /** Replace the stored ids atomically; false when the write failed (nothing was half-written). */
    fun save(ids: Collection<String>, store: File = file()): Boolean =
        dev.ccpocket.daemon.peer.AtomicStoreFiles.write(
            store, PocketJson.encodeToString(Stored.serializer(), Stored(ids = ids.sorted())),
        )
}

/**
 * The persisted registry of EXECUTION link credentials (issue #367 G1): deviceId -> [BridgeEntry]
 * (kind = EXECUTION), in `~/.cc-pocket/execution-credentials.json`. A FOURTH separate file, for the same
 * downgrade-safety chain as guests.json / collaborator-keys.json: a daemon that predates #367 never loads
 * it, so the key is an unknown device → handshake refused, fail closed — and an execution key can never be
 * mis-read as a full device (devices.json), a bridge, a guest or a collaborator.
 *
 * This file holds only the E2E key + spec (whose [BridgeSpec.grantId] points at the grant). The GRANT
 * itself — scope, ceiling, limits, revision, expiry, the pinned source link key — lives in
 * `execution-grants.json` ([dev.ccpocket.daemon.execution.ExecutionGrantStore]) and is the only authority.
 */
object ExecutionCredentialStore {
    fun file(): File = credentialFile("execution-credentials.json")

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
}.onFailure(::storageReadFailed).getOrDefault(emptyMap())

private fun saveCredentials(map: Map<String, BridgeEntry>, store: File) {
    runCatching {
        store.parentFile?.mkdirs()
        store.writeText(PocketJson.encodeToString(map))
        runCatching { // best-effort 0600 (POSIX only; Windows ACLs inherit the profile dir)
            Files.setPosixFilePermissions(store.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }.onFailure(::storageWriteFailed)
}
