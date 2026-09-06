package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One RPC call against a DeepSeek Harness host — the seam [DshModelService] talks through so it can be
 * pointed at a LIVE session's host or a throwaway one without caring which (issue #333).
 *
 * Same contract as [DshApiClient.rpc]: returns the `result` object (`{ok:true,value:…}` /
 * `{ok:false,error:…}`), or null when the CARRIER failed. A non-null answer is NOT a success.
 */
fun interface DshRpc {
    suspend fun rpc(method: String, payload: JsonObject): JsonObject?
}

/**
 * The dsh hosts this daemon currently drives (issue #333).
 *
 * WHY IT EXISTS: `agentPreset.list` and `llm.models` are HOST-level, not session-level — any running dsh
 * web host can answer them. When the user already has a dsh session open, booting a SECOND dsh just to
 * read a catalogue would cost a Node start-up (~seconds) and, worse, briefly double the number of dsh
 * hosts writing to the same `$DSH_HOME` store. Reusing the live one is both faster and quieter; the
 * transient boot in [DshModelService] is only the fallback for "no dsh session is open right now".
 *
 * Registration is by the backend that owns the client, and is UNREGISTERED on teardown — a stale entry
 * would make the model picker hang on a dead port instead of booting a fresh host. Ordering is
 * registration order and [first] takes the oldest live one; any of them answers identically, since the
 * catalogue is a property of the user's dsh install, not of a session.
 */
object DshHosts {
    private val live = CopyOnWriteArrayList<DshApiClient>()

    fun register(client: DshApiClient) {
        live.addIfAbsent(client)
    }

    fun unregister(client: DshApiClient) {
        live.remove(client)
    }

    /** The oldest still-registered host, as a [DshRpc]; null when the daemon drives no dsh session. */
    fun first(): DshRpc? = live.firstOrNull()?.let { client -> DshRpc { m, p -> client.rpc(m, p) } }

    /** VISIBLE FOR TESTS: a leaked registration from one test would silently satisfy the next one's
     *  "no live host" branch. */
    internal fun clearForTest() {
        live.clear()
    }
}
