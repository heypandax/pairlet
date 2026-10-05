package dev.ccpocket.daemon.identity

import java.util.concurrent.ConcurrentHashMap

/**
 * When each paired device last completed an E2E handshake with this daemon process, and over which path —
 * shown by `pairlet devices` (pairing security phase 0) so the owner can spot a key that is active while all
 * of their own devices are off. Both entry points record here: the relay path (DeviceSessions) and the
 * direct-LAN gate (WsConnection).
 *
 * In memory only, since this process started. Display only — never an authorization input.
 */
object DeviceActivity {
    const val VIA_RELAY = "relay"
    const val VIA_DIRECT = "direct"

    class Seen(val at: Long, val via: String)

    private val last = ConcurrentHashMap<String, Seen>()

    fun noteHandshake(deviceId: String, via: String, at: Long = System.currentTimeMillis()) {
        // bounded the same way the relay path bounds its per-id maps: device ids come off the wire
        if (last.size >= MAX_ENTRIES && !last.containsKey(deviceId)) last.clear()
        last[deviceId] = Seen(at, via)
    }

    fun lastHandshake(deviceId: String): Seen? = last[deviceId]

    private const val MAX_ENTRIES = 512
}
