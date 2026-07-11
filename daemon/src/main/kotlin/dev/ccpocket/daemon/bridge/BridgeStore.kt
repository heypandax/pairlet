package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Constraints minted with a headless bridge credential (issue #91) — decided by the OWNER at
 * `pair --headless` time and enforced daemon-side on every frame. The bridge itself never gets to
 * relax them: they live in [BridgeStore]'s file, not in anything the credential holder can write.
 */
@Serializable
data class BridgeSpec(
    val name: String,
    /** Absolute workdir roots the bridge may open sessions under (canonical, no trailing sep). */
    val workdirs: List<String>,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val opensPerMin: Int = DEFAULT_OPENS_PER_MIN,
    val promptsPerMin: Int = DEFAULT_PROMPTS_PER_MIN,
) {
    companion object {
        const val DEFAULT_MAX_SESSIONS = 2
        const val DEFAULT_OPENS_PER_MIN = 6
        const val DEFAULT_PROMPTS_PER_MIN = 20

        /** Clamp owner-supplied overrides into sane bounds — a typo'd `--max-sessions 999` must not
         *  turn one credential into a fork bomb. */
        fun clamped(name: String, workdirs: List<String>, maxSessions: Int?, opensPerMin: Int?, promptsPerMin: Int?) = BridgeSpec(
            name = name,
            workdirs = workdirs,
            maxSessions = (maxSessions ?: DEFAULT_MAX_SESSIONS).coerceIn(1, 8),
            opensPerMin = (opensPerMin ?: DEFAULT_OPENS_PER_MIN).coerceIn(1, 30),
            promptsPerMin = (promptsPerMin ?: DEFAULT_PROMPTS_PER_MIN).coerceIn(1, 120),
        )
    }
}

/** One registered bridge credential: its E2E static key + the constraints minted with it. */
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
    fun file(): File {
        val dir = System.getenv("CC_POCKET_IDENTITY")?.let { File(it).parentFile }
            ?: File(System.getProperty("user.home"), ".cc-pocket")
        return File(dir, "bridges.json")
    }

    fun load(store: File = file()): Map<String, BridgeEntry> = runCatching {
        if (!store.exists()) return emptyMap()
        PocketJson.decodeFromString<Map<String, BridgeEntry>>(store.readText())
    }.getOrDefault(emptyMap())

    fun save(map: Map<String, BridgeEntry>, store: File = file()) {
        runCatching {
            store.parentFile?.mkdirs()
            store.writeText(PocketJson.encodeToString(map))
            runCatching { // best-effort 0600 (POSIX only; Windows ACLs inherit the profile dir)
                Files.setPosixFilePermissions(store.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
