package dev.ccpocket.daemon.identity

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
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

    fun save(map: Map<String, ByteArray>, store: File = file()) {
        runCatching {
            store.parentFile?.mkdirs()
            store.writeText(PocketJson.encodeToString(map.mapValues { b64enc.encodeToString(it.value) }))
        }
        epoch++
    }
}
