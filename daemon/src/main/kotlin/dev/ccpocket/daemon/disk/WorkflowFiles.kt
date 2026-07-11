package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.claude.WorkflowProgressParser
import dev.ccpocket.protocol.WorkflowPhaseInfo
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Reads a session's finished Workflow runs off disk (issue #106) — no claude launch.
 *
 * Layout (probed on claude 2.1.206):
 *  - `<projectDir>/<sessionId>/workflows/<runId>.json` — the run MANIFEST, written ONCE at
 *    completion (never mid-run): runId/taskId/workflowName/status/startTime/durationMs/phases/
 *    result + a `workflowProgress` array in the same item schema as the live wire events
 *    ([WorkflowProgressParser] is shared with the tracker so the two paths can't drift).
 *  - `<projectDir>/<sessionId>/subagents/workflows/<runId>/journal.jsonl` — appended live:
 *    `{type:"started"|"result", key:<prompt-hash>, agentId, result?}`. NOTE the key is a v2:sha256
 *    CACHE key, not the prompt text — the full prompt lives in the agent's own transcript.
 *  - `…/subagents/workflows/<runId>/agent-<agentId>.jsonl` — each agent's full transcript; its
 *    first `user` record carries the prompt verbatim.
 *
 * A run whose manifest is missing (daemon/CLI killed before completion) is simply not listed —
 * the chat card then renders as a plain tool row, never a broken tree.
 */
object WorkflowFiles {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true }

    /** All finished runs recorded for [sessionDir] (`<projectDir>/<sessionId>`), oldest first. */
    fun listRuns(sessionDir: Path): List<WorkflowRun> {
        val dir = sessionDir.resolve("workflows")
        if (!dir.isDirectory()) return emptyList()
        val files = Files.newDirectoryStream(dir, "*.json").use { it.toList() }
        return files.filter { it.isRegularFile() }
            .mapNotNull { f -> runCatching { parseManifest(f) }.getOrNull() }
            .sortedBy { it.startedAt }
    }

    /** One run's manifest by id, or null when it isn't on disk (yet). */
    fun readRun(sessionDir: Path, runId: String): WorkflowRun? {
        val f = sessionDir.resolve("workflows").resolve("$runId.json")
        if (!f.isRegularFile()) return null
        return runCatching { parseManifest(f) }.getOrNull()
    }

    /** The full prompt + return of ONE workflow agent, for the detail sheet. Prompt comes from the
     *  agent transcript's first user record; the return from the journal's `result` record (falls
     *  back to the transcript's last assistant text when the journal has none — e.g. a failed agent). */
    fun readAgentDetail(sessionDir: Path, runId: String, agentId: String): AgentDetail {
        val runDir = sessionDir.resolve("subagents").resolve("workflows").resolve(runId)
        return AgentDetail(
            prompt = runCatching { agentPrompt(runDir.resolve("agent-$agentId.jsonl")) }.getOrNull(),
            result = runCatching { journalResult(runDir.resolve("journal.jsonl"), agentId) }.getOrNull()
                ?: runCatching { lastAssistantText(runDir.resolve("agent-$agentId.jsonl")) }.getOrNull(),
        )
    }

    data class AgentDetail(val prompt: String?, val result: String?)

    // ── manifest ────────────────────────────────────────────────────────────────────────────────

    private fun parseManifest(file: Path): WorkflowRun? {
        val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        val runId = obj.str("runId") ?: file.name.removeSuffix(".json")
        val status = when (obj.str("status")?.lowercase()) {
            "completed" -> WorkflowRunStatus.COMPLETED
            "failed" -> WorkflowRunStatus.FAILED
            "killed", "cancelled", "canceled" -> WorkflowRunStatus.KILLED
            null -> if (obj.str("error") != null) WorkflowRunStatus.FAILED else WorkflowRunStatus.COMPLETED
            else -> WorkflowRunStatus.UNKNOWN
        }
        // meta.phases is an ORDERED title list (1-based implicitly); the progress items carry real
        // indices and win when present — same override order the CLI applies
        val phases = LinkedHashMap<Int, String>()
        (obj["phases"] as? JsonArray)?.forEachIndexed { i, el ->
            (el as? JsonObject)?.str("title")?.let { phases[i + 1] = it }
        }
        val agents = LinkedHashMap<Int, dev.ccpocket.protocol.WorkflowAgentSnap>()
        (obj["workflowProgress"] as? JsonArray)?.forEach { el ->
            val item = el as? JsonObject ?: return@forEach
            WorkflowProgressParser.phaseOrNull(item)?.let { (idx, title) -> phases[idx] = title }
            WorkflowProgressParser.agentOrNull(item)?.let { snap -> agents[snap.index] = snap }
        }
        return WorkflowRun(
            runId = runId,
            name = obj.str("workflowName") ?: obj.str("summary") ?: "workflow",
            status = status,
            toolUseId = null, // replayed cards bind via HistoryMessage.workflowRunId instead
            phases = phases.entries.sortedBy { it.key }.map { WorkflowPhaseInfo(it.key, it.value) },
            agents = agents.values.sortedBy { it.index },
            startedAt = obj.long("startTime") ?: 0,
            durationMs = obj.long("durationMs"),
            finalResult = obj["result"]?.let { renderResult(it) },
            error = obj.str("error")?.take(ERROR_MAX),
        )
    }

    /** The script's return value → display text: strings verbatim, structured values pretty-printed. */
    private fun renderResult(el: JsonElement): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        else -> runCatching { pretty.encodeToString(JsonElement.serializer(), el) }.getOrNull()
    }?.take(FINAL_MAX)?.takeIf { it.isNotBlank() }

    // ── per-agent detail ────────────────────────────────────────────────────────────────────────

    /** journal.jsonl: the LAST `result` record for [agentId] (retries append; last wins). */
    private fun journalResult(journal: Path, agentId: String): String? {
        if (!journal.isRegularFile()) return null
        var last: String? = null
        journal.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || agentId !in line) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "result" || obj.str("agentId") != agentId) continue
                obj["result"]?.let { renderResult(it)?.let { r -> last = r } }
            }
        }
        return last
    }

    /** The agent transcript's first `user` record = the agent() prompt verbatim. */
    private fun agentPrompt(transcript: Path): String? {
        if (!transcript.isRegularFile()) return null
        transcript.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "user") continue
                return extractText((obj["message"] as? JsonObject)?.get("content"))?.take(PROMPT_MAX)
            }
        }
        return null
    }

    /** Fallback return for agents the journal never settled: the transcript's last assistant text. */
    private fun lastAssistantText(transcript: Path): String? {
        if (!transcript.isRegularFile()) return null
        var last: String? = null
        transcript.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (obj.str("type") != "assistant") continue
                extractText((obj["message"] as? JsonObject)?.get("content"))?.takeIf { it.isNotBlank() }?.let { last = it }
            }
        }
        return last?.take(FINAL_MAX)
    }

    private fun extractText(content: JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { el ->
            (el as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
        }.joinToString("\n").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private const val FINAL_MAX = 4000   // same cap family as SUBAGENT_OUTPUT_MAX — phones scroll, not paginate
    private const val PROMPT_MAX = 8000
    private const val ERROR_MAX = 500
}
