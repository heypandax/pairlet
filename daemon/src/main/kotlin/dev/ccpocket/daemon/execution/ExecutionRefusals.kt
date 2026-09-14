package dev.ccpocket.daemon.execution

/**
 * The per-device "why did this execution link just fail?" ledger (#367).
 *
 * ONE instance is shared by [ExecutionTarget] (bind-time refusals), [ExecutionGuard] (per-frame transport
 * refusals) and the run plane, because the owner's question is about the LINK, not about which layer said
 * no — a diagnostic split across three objects is a diagnostic nobody reads.
 *
 * Only stable, log-safe codes are ever stored: never a key, ticket, invite secret, path or prompt. Bounded
 * so the relay's PLAINTEXT deviceId field cannot be used to grow it without limit (same argument as
 * DeviceSessions' pre-handshake warn map).
 */
class ExecutionRefusals(private val max: Int = MAX_ENTRIES) {
    private val entries = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
    }

    fun note(deviceId: String, code: String): Unit = synchronized(entries) {
        entries.remove(deviceId)
        entries[deviceId] = code
    }

    fun last(deviceId: String): String? = synchronized(entries) { entries[deviceId] }

    companion object { const val MAX_ENTRIES = 256 }
}
