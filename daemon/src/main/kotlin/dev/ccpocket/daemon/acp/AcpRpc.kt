package dev.ccpocket.daemon.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 framing for an ACP v1 agent on stdio — the request-id sequence and the five line shapes
 * the client writes. ACP REQUIRES the `jsonrpc` field (unlike codex app-server).
 *
 * The id sequence lives as long as the backend, not the process: it is never reset on a relaunch, so a
 * late answer from a dead process can never be mistaken for one of the new process's requests.
 */
class AcpRpc(private val writeLine: suspend (String) -> Unit) {
    private val idSeq = AtomicLong(1)

    /** Allocate a request id without writing — for callers that must register the id BEFORE the request
     *  goes out (a prompt's in-flight mark, a config write's pending record). */
    fun nextId(): Long = idSeq.getAndIncrement()

    suspend fun request(method: String, params: JsonObject?): Long {
        val id = nextId()
        send(id, method, params)
        return id
    }

    /** A request whose id was pre-allocated with [nextId]. */
    suspend fun send(id: Long, method: String, params: JsonObject?) {
        write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        })
    }

    suspend fun notify(method: String, params: JsonObject?) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) } })

    suspend fun respondResult(id: JsonElement, result: JsonObject) =
        write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })

    suspend fun respondError(id: JsonElement, code: Int, message: String) =
        write(
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                putJsonObject("error") { put("code", code); put("message", message) }
            },
        )

    private suspend fun write(obj: JsonObject) = writeLine(obj.toString())

    companion object {
        /** JSON-RPC "method not found" — the answer to every server request the client does not serve. */
        const val METHOD_NOT_FOUND = -32601
    }
}
