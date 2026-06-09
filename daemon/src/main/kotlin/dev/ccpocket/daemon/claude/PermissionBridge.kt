package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates Anthropic `can_use_tool` control requests into protocol [PermissionAsk]s, awaits a
 * [PermissionVerdict] (or times out -> deny), and writes the `control_response` back. The other
 * half of the Anthropic-schema firewall. `askId` == Anthropic `request_id`.
 */
class PermissionBridge(
    private val convoId: String,
    private val mode: PermissionMode,
    private val scope: CoroutineScope,
    private val writeToClaude: suspend (String) -> Unit,
    private val emit: suspend (Frame) -> Unit,
    private val verdictTimeoutMs: Long = 30_000,
) {
    private class Pending(val input: JsonObject?, val timeoutJob: Job)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val autoAllow = mode == PermissionMode.AUTO || mode == PermissionMode.BYPASS_PERMISSIONS

    suspend fun onControlRequest(ev: ClaudeEvent.ControlRequest) {
        if (autoAllow) {
            writeAllow(ev.requestId, ev.input, null)
            return
        }
        val askId = ev.requestId
        val timeout = scope.launch {
            delay(verdictTimeoutMs)
            if (pending.remove(askId) != null) writeDeny(askId, "timed out")
        }
        pending[askId] = Pending(ev.input, timeout)
        emit(PermissionAsk(convoId, askId, ev.toolName, previewOf(ev.toolName, ev.input), mode))
    }

    suspend fun onVerdict(v: PermissionVerdict) {
        val p = pending.remove(v.askId) ?: return
        p.timeoutJob.cancel()
        when (v.decision) {
            Decision.ALLOW -> writeAllow(v.askId, p.input, v.updatedInput)
            Decision.DENY -> writeDeny(v.askId, v.message ?: "denied")
        }
    }

    fun onCancel(ev: ClaudeEvent.ControlCancel) {
        pending.remove(ev.requestId)?.timeoutJob?.cancel()
    }

    fun cancelAll() {
        pending.values.forEach { it.timeoutJob.cancel() }
        pending.clear()
    }

    private suspend fun writeAllow(requestId: String, original: JsonObject?, updated: String?) {
        val updatedInput: JsonElement =
            updated?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?: original
                ?: JsonObject(emptyMap())
        writeToClaude(
            buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", requestId)
                    putJsonObject("response") {
                        put("behavior", "allow")
                        put("updatedInput", updatedInput)
                    }
                }
            }.toString(),
        )
    }

    private suspend fun writeDeny(requestId: String, message: String) {
        writeToClaude(
            buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", requestId)
                    putJsonObject("response") {
                        put("behavior", "deny")
                        put("message", message)
                    }
                }
            }.toString(),
        )
    }

    private fun previewOf(tool: String, input: JsonObject?): String {
        if (input == null) return tool
        for (k in listOf("command", "file_path", "path", "pattern", "url", "description", "content")) {
            val v = (input[k] as? JsonPrimitive)?.contentOrNull
            if (!v.isNullOrBlank()) return "$tool: ${v.take(280)}"
        }
        return "$tool: ${input.toString().take(280)}"
    }
}
