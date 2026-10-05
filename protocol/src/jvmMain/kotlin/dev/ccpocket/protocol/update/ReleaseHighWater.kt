package dev.ccpocket.protocol.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Anti-rollback memory for ENFORCED mode: the newest signed manifest (version + publishedAt) this client
 * accepted, persisted in its own state directory (daemon `~/.cc-pocket/`, desktop `~/.cc-pocket-app/`).
 *
 * A compromised mirror can otherwise offer any OLD signed release that is still newer than the running
 * version — including the pre-hotfix manifest of a version that was re-signed in place. Once a client has
 * accepted a newer manifest, [ReleaseSignature.verifyManifest] refuses anything below this mark.
 *
 * The file is written atomically (temp file + rename in the same directory) and only ever raised. An
 * unreadable or corrupt file counts as "no mark yet" and is reported once through the caller's log — it
 * must never block every future update.
 */
object ReleaseHighWater {
    const val FILE_NAME = "update-signature-highwater.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** The stored mark, or null when there is none; [log] hears about a file that exists but is unusable. */
    fun read(file: Path, log: (String) -> Unit = {}): ReleaseSignature.HighWater? {
        if (!Files.exists(file)) return null
        return runCatching {
            val obj = json.parseToJsonElement(Files.readString(file)) as JsonObject
            val version = (obj["version"] as JsonPrimitive).contentOrNull!!
            check(ReleaseSignature.isValidVersion(version))
            ReleaseSignature.HighWater(version, Instant.parse((obj["publishedAt"] as JsonPrimitive).contentOrNull!!))
        }.getOrElse {
            log("update signature high-water mark at $file is unreadable — treating it as absent (${it::class.simpleName})")
            null
        }
    }

    /** Raise the mark to [manifest] if it is newer than [current] (or there is none). Best effort: a failed
     *  write is logged and never fails the update itself. */
    fun raise(file: Path, current: ReleaseSignature.HighWater?, manifest: ReleaseSignature.Manifest, log: (String) -> Unit = {}) {
        val next = ReleaseSignature.HighWater.of(manifest)
        if (current != null && !isAbove(next, current)) return // only ever raised
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            val body = buildJsonObject {
                put("version", next.version)
                put("publishedAt", next.publishedAt.toString())
            }.toString() + "\n"
            val tmp = Files.createTempFile(file.toAbsolutePath().parent, ".highwater", ".tmp")
            try {
                Files.writeString(tmp, body)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }.onFailure { log("could not record the update signature high-water mark at $file: ${it.message}") }
    }

    /** Strictly newer mark? */
    private fun isAbove(a: ReleaseSignature.HighWater, b: ReleaseSignature.HighWater): Boolean =
        ReleaseVersions.isNewer(a.version, b.version) ||
            (!ReleaseVersions.isNewer(b.version, a.version) && a.publishedAt.isAfter(b.publishedAt))
}
