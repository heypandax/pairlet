package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.identity.Identity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The shared secret that gates the daemon's whole loopback API: the LOCAL CONTROL routes (`/v1/local/…` —
 * #367 remote execution, owner devices, pairing results) and, since pairing security phase 0, the legacy
 * routes too (`/pair`, `/pair/headless`, `/bridges`, `/bridge/revoke`, `/status`; see
 * [dev.ccpocket.daemon.relay.LegacyLoopbackGuard]).
 *
 * "Can reach 127.0.0.1" is not local-user authority: any process on the machine — another OS user's, a
 * sandboxed one, a browser tab executing someone else's JavaScript — can reach loopback, and `/pair` alone
 * hands out a full-power pairing ticket. So every route requires this token, stored in a file only the OS
 * user can read. A browser or another user can send the request; they cannot read
 * `~/.cc-pocket/local-control-token` to sign it.
 *
 * The cost: a `pairlet` CLI older than the running daemon sends no token and is refused, with a reply that
 * tells it to use the CLI that came with the daemon.
 */
object LocalControlToken {

    /** The header the CLI presents. Not `Authorization`, so nothing mistakes it for a bearer scheme
     *  something else might forward upstream. */
    const val HEADER = "X-CC-Pocket-Local"

    fun defaultPath(): File = File(Identity.defaultPath().parentFile, "local-control-token")

    /** Read the token, or mint and persist one. Called by the daemon at startup and by the CLI on every
     *  command — same file, same value, no handshake. */
    fun loadOrCreate(path: File = defaultPath()): String {
        read(path)?.let {
            ownerOnly(path)
            return it
        }
        val token = B64.encodeToString(ByteArray(32).also(RNG::nextBytes))
        val parent = path.absoluteFile.parentFile
        Files.createDirectories(parent.toPath())
        val tmp = Files.createTempFile(parent.toPath(), ".${path.name}.", ".tmp").toFile()
        try {
            // 0600 on the EMPTY file first: the token must never exist at the umask default
            ownerOnly(tmp)
            tmp.writeText(token)
            runCatching {
                // No REPLACE_EXISTING: if daemon and CLI race, exactly one token wins.
                Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                read(path)?.let { return it }
                Files.move(tmp.toPath(), path.toPath())
            }.getOrElse {
                read(path)?.let { return it }
                throw IOException("could not create ${path.name}", it)
            }
            ownerOnly(path)
            return read(path) ?: throw IOException("created ${path.name}, but could not read it back")
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    /** The persisted token, or null when there is none (no daemon has ever run as this user). */
    fun read(path: File = defaultPath()): String? =
        runCatching { path.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    /**
     * Constant-time comparison. A byte-by-byte early exit here would leak the token one character at a
     * time to a local process that can time its own loopback requests — cheap to avoid, so avoid it.
     */
    fun matches(expected: String, presented: String?): Boolean {
        if (presented == null) return false
        return MessageDigest.isEqual(expected.toByteArray(), presented.toByteArray())
    }

    private val RNG = SecureRandom()
    private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun ownerOnly(file: File) {
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
