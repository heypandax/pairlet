package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.QuestionAnswer
import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/** Reads a session `.jsonl` into a flat list of [HistoryMessage]s for replaying a resumed chat. */
object TranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val ASK_TOOL = "AskUserQuestion"
    // the CLI records an answered AskUserQuestion as `… "<question>"="<answer>" …` pairs (verified CLI
    // 2.1.206 via scripts/probe-claude-wire.py `ask`); one pair per question, comma-joined for multiSelect
    private val QA_PAIR = Regex("\"([^\"]+)\"=\"([^\"]*)\"")
    private const val MAX_TOOL_TEXT = 1000 // tool label / input preview cap (display-on-tap, not the reply body)

    /** Count-capped, then byte-budgeted (issue #81) so one ConvoHistory frame stays under the relay's 4 MiB cap. */
    fun read(file: Path, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> =
        slice(file, sinceSeq = null, maxMessages = maxMessages, maxFrameTextBytes = maxFrameTextBytes).messages

    /** The (re)open replay with cursor metadata (issue #147): a DELTA past [sinceSeq] when it can be
     *  honored, else the same full tail window as [read] — see [ReplaySlicer.slice] for the fallbacks. */
    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, cursor) = parse(file)
        return ReplaySlicer.slice(rows, cursor, sinceSeq, maxMessages, maxFrameTextBytes)
    }

    /** One page of history OLDER than [beforeSeq] — the scroll-to-top lazy load (issue #147). */
    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, _) = parse(file)
        return ReplaySlicer.page(rows, beforeSeq, limit, maxFrameTextBytes)
    }

    /** A parsed row still open to patching, finalized into [ReplaySlicer.Row] once the file is read. */
    private class MutableRow(var msg: HistoryMessage, val line: Long) {
        var patchLine: Long = 0L
    }

    /** Parse the whole transcript into rows tagged with their source line (the #147 seq) + the total
     *  line count (the cursor). Every raw line — noise included — advances the cursor, so it equals
     *  the file's line count and stays stable under append-only growth. */
    private fun parse(file: Path): Pair<List<ReplaySlicer.Row>, Long> {
        if (!file.exists()) return emptyList<ReplaySlicer.Row>() to 0L
        val out = ArrayList<MutableRow>()
        val taskIdx = HashMap<String, Int>() // sub-agent tool_use id -> its card's index in `out` (issue #77)
        val questionIdx = HashMap<String, Int>() // AskUserQuestion tool_use id -> its row's index (issue #110)
        var lineNo = 0L
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    lineNo += 1
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    // a sub-agent's inner records share the file with isSidechain:true — they fold into
                    // the Task card (live: parent-tagged tool events), never the main transcript (issue #77)
                    if ((obj["isSidechain"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    when (obj.str("type")) {
                        // drop harness plumbing (standalone task-notifications, the resume nudge) so the
                        // phone replays the real conversation, not background-shell chatter
                        "user" -> {
                            attachSubagentResults(obj, out, taskIdx, lineNo)
                            attachQuestionAnswers(obj, out, questionIdx, lineNo)
                            if (isRealUserTurn(obj)) userText(obj)
                                .takeIf { it.isNotBlank() && !TranscriptNoise.isNoiseUserText(it) }
                                ?.let { out += MutableRow(HistoryMessage(ChatRole.USER, it), lineNo) }
                        }
                        // the id keys the AskUserQuestion row (issue #110) or the sub-agent card (issue #77);
                        // the tool name says which map so its later tool_result patches the right one
                        "assistant" -> assistantBlocks(obj).forEach { (msg, id) ->
                            id?.let { (if (msg.tool == ASK_TOOL) questionIdx else taskIdx)[it] = out.size }
                            out += MutableRow(msg, lineNo)
                        }
                    }
                }
            }
        }
        return out.map { ReplaySlicer.Row(it.msg, it.line, it.patchLine) } to lineNo
    }

    /** One history row + (for a sub-agent tool_use) its tool_use id, so the reader can key the card. */
    private fun assistantBlocks(obj: JsonObject): List<Pair<HistoryMessage, String?>> {
        val message = obj["message"] as? JsonObject
        val content = message?.get("content") as? JsonArray ?: return emptyList()
        // `<synthetic>` = the CLI's API-failure placeholder, not a real reply — flag it so the phone
        // replays it as an error row instead of a normal answer (issue #65; live turns get the same
        // treatment via StreamParser). Old clients ignore the flag and render the text as before.
        val synthetic = message.str("model") == "<synthetic>"
        val items = ArrayList<Pair<HistoryMessage, String?>>()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { items += HistoryMessage(ChatRole.ASSISTANT, it, error = synthetic) to null }
                "tool_use" -> {
                    val name = block.str("name") ?: "tool"
                    val input = block["input"] as? JsonObject
                    when {
                        isSubagentTool(name) -> {
                            // sub-agent card: same "<type>: <description>" label as the live ToolEvent (issue #77);
                            // its ok/output are patched in when the matching tool_result scrolls past
                            val label = listOfNotNull(input.str("subagent_type"), input.str("description"))
                                .joinToString(": ").ifBlank { "sub-agent" }
                            items += HistoryMessage(ChatRole.TOOL, label.take(MAX_TOOL_TEXT), tool = name) to block.str("id")
                        }
                        isWorkflowTool(name) -> {
                            // Workflow run card (issue #106): keyed like a sub-agent so the launch ack's
                            // tool_result can patch in the run id (the separately-replayed WorkflowUpdate
                            // then binds the full progress tree to this row)
                            val label = input.str("description")?.ifBlank { null } ?: "workflow"
                            items += HistoryMessage(ChatRole.TOOL, label.take(MAX_TOOL_TEXT), tool = name) to block.str("id")
                        }
                        name == ASK_TOOL -> {
                            // AskUserQuestion (issue #110): NOT the raw questions JSON (which read like a Bash
                            // dump). Carry the question text for the rare unanswered replay; `answers` is patched
                            // in from the matching tool_result so the phone shows the same compact answered row
                            // the live path leaves behind. Keyed by tool_use id like a sub-agent card.
                            val questions = (input?.get("questions") as? JsonArray).orEmpty()
                                .mapNotNull { (it as? JsonObject).str("question") }
                            val label = questions.joinToString("\n").ifBlank { "Question" }
                            items += HistoryMessage(ChatRole.TOOL, label.take(MAX_TOOL_TEXT), tool = name) to block.str("id")
                        }
                        else -> items += HistoryMessage(
                            ChatRole.TOOL,
                            text = input?.toString()?.take(MAX_TOOL_TEXT) ?: "", // full-ish input; the app shows it on tap-to-expand
                            tool = name,
                        ) to null
                    }
                }
            }
        }
        return items
    }

    /** Patch a sub-agent/workflow card with its outcome when the main-chain tool_result shows up.
     *  A Workflow launch's record also carries a root-level `toolUseResult {taskType:"local_workflow",
     *  runId}` — that run id binds the card to its separately-replayed [WorkflowFiles] run (#106).
     *  Stamps [MutableRow.patchLine] so a delta read knows the mutation's source line (issue #147). */
    private fun attachSubagentResults(obj: JsonObject, out: ArrayList<MutableRow>, taskIdx: HashMap<String, Int>, lineNo: Long) {
        if (taskIdx.isEmpty()) return
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        val workflowRunId = (obj["toolUseResult"] as? JsonObject)
            ?.takeIf { it.str("taskType") == "local_workflow" }?.str("runId")
        for (el in content) {
            val block = el as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val idx = block.str("tool_use_id")?.let(taskIdx::remove) ?: continue
            val row = out.getOrNull(idx) ?: continue
            row.msg = row.msg.copy(
                ok = (block["is_error"] as? JsonPrimitive)?.booleanOrNull != true,
                output = subagentReport(toolResultText(block["content"])),
                workflowRunId = workflowRunId ?: row.msg.workflowRunId,
            )
            row.patchLine = lineNo
        }
    }

    /** Patch an AskUserQuestion row with the user's picks when its main-chain tool_result shows up
     *  (issue #110) — the mirror of [attachSubagentResults] for the question card. */
    private fun attachQuestionAnswers(obj: JsonObject, out: ArrayList<MutableRow>, questionIdx: HashMap<String, Int>, lineNo: Long) {
        if (questionIdx.isEmpty()) return
        val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        for (el in content) {
            val block = el as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val idx = block.str("tool_use_id")?.let(questionIdx::remove) ?: continue
            val row = out.getOrNull(idx) ?: continue
            val answers = parseAnswers(toolResultText(block["content"])) ?: continue
            row.msg = row.msg.copy(answers = answers)
            row.patchLine = lineNo
        }
    }

    /** Pull the (question → answer) pairs out of AskUserQuestion's tool_result text. The structured
     *  picks arrive as `"<question>"="<answer>"` pairs; a freeform reply arrives after a "responded:"
     *  marker (kept as a blank-question answer). Returns null when neither matches, so the row falls
     *  back to showing the question text — never the raw JSON. */
    private fun parseAnswers(content: String?): List<QuestionAnswer>? {
        content ?: return null
        val pairs = QA_PAIR.findAll(content)
            .map { QuestionAnswer(it.groupValues[1].trim().take(2000), it.groupValues[2].trim().take(2000)) }
            .toList()
        if (pairs.isNotEmpty()) return pairs
        val freeform = content.substringAfter("responded:", "").trim().trim('"', ' ', '.').ifBlank { null }
        return freeform?.let { listOf(QuestionAnswer("", it.take(2000))) }
    }

    /** tool_result `content` is either a raw string or an array of {type:text,text:…} blocks. */
    private fun toolResultText(el: Any?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n").ifBlank { null }
        else -> null
    }

    /** The report minus the CLI's trailing "agentId: …" continuation plumbing, capped like the live path. */
    private fun subagentReport(content: String?, maxLen: Int = 4000): String? {
        content ?: return null
        val cut = content.indexOf("\nagentId: ")
        val body = if (cut >= 0) content.substring(0, cut) else content
        return body.trim().take(maxLen).ifBlank { null }
    }

    private fun isRealUserTurn(obj: JsonObject): Boolean {
        // harness-injected user records (a Skill load writing the whole SKILL.md, slash-command
        // wrappers) are tagged isMeta:true at the root — never the user typing, so never a bubble
        // (issue #126; live streams don't surface these, only the replay path saw them)
        if ((obj["isMeta"] as? JsonPrimitive)?.booleanOrNull == true) return false
        if (obj.containsKey("toolUseResult")) return false
        val content = (obj["message"] as? JsonObject)?.get("content")
        if (content is JsonArray && content.isNotEmpty()) {
            val allToolResult = content.all { (it as? JsonObject)?.str("type") == "tool_result" }
            if (allToolResult) return false
        }
        return true
    }

    private fun userText(obj: JsonObject): String {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
            } ?: ""
            else -> ""
        }
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
}
