package dev.ccpocket.daemon.identity

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The persisted allow-list of paired device static public keys (deviceId -> X25519 pub), written by the
 * relay path's DeviceSessions on pairing and read by BOTH E2E entry points: relay reconnect handshakes
 * and the direct-LAN listener's gate. Re-read from disk per handshake (no cache) so a device paired over
 * the relay is immediately accepted on the LAN listener of the same daemon process.
 */
object PairedDevices {
    private val b64enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64dec: Base64.Decoder = Base64.getUrlDecoder()
    private val log = logger("PairedDevices")

    /** Bumped on every [save]. Live direct-LAN connections watch it and re-verify their device is still
     *  allow-listed on the next frame — so a revocation cuts an ESTABLISHED socket too, instead of
     *  grandfathering it until it happens to disconnect. */
    @Volatile
    var epoch: Long = 0L
        private set

    fun file(): File {
        val dir = System.getenv("CC_POCKET_IDENTITY")?.let { File(it).parentFile }
            ?: File(System.getProperty("user.home"), ".cc-pocket")
        return File(dir, "devices.json")
    }

    fun load(store: File = file()): Map<String, ByteArray> = runCatching {
        if (!store.exists()) return emptyMap()
        PocketJson.decodeFromString<Map<String, String>>(store.readText()).mapValues { b64dec.decode(it.value) }
    }.getOrDefault(emptyMap())

    /**
     * Replace the allow-list ATOMICALLY: write a sibling temp file, flush it to disk, rename it over [store].
     * A truncate-then-write left a window where a reader (the LAN gate loads per handshake) or a kill -9
     * (two daemons killing each other — see AGENTS.md) met an empty or half file; [load] turns that into
     * "no devices" and every phone has to re-pair. Callers pass a snapshot they own, taken under their lock.
     */
    fun save(map: Map<String, ByteArray>, store: File = file()) {
        runCatching {
            val dir = store.absoluteFile.parentFile
            dir.mkdirs()
            val tmp = File.createTempFile("${store.name}.", ".tmp", dir)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(PocketJson.encodeToString(map.mapValues { b64enc.encodeToString(it.value) }).encodeToByteArray())
                    out.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete() // no-op once moved
            }
        }.onFailure { log.warn("devices.json write failed (${it.message}) — the previous allow-list stays on disk") }
        epoch++
    }
}
