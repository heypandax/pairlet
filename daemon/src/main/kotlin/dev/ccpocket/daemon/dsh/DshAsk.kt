package dev.ccpocket.daemon.dsh

import dev.ccpocket.protocol.QuestionAnswer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The dsh ask/approval vocabulary as it appears ON DISK (issue #291; the live half retired by the dsh 0.1.2 ACP switch) — pure translation, no state
 * and no IO, so every rule below is unit-testable against real records.
 *
 * SCOPE: this is now the REPLAY half only. The live half spoke the `web` profile's `question/requested` /
 * `approval/requested` frames and answered them over `/api/respond`; dsh 0.1.2-rc.1 removed that API and
 * its ACP replacement carries approvals as `session/request_permission` instead (see [DshBackend]). Old
 * transcripts still hold the records below, so reading them stays this file's job — and the shapes stay
 * source-verified against rc.6 (`dsh-user-questions`, `dsh-user-approval`, `dsh-tool-ask-user`).
 *
 * Two facts drive what is left, and both are easy to get wrong from the wire's spelling alone:
 *
 *  1. **The answer vocabulary is the option LABEL, verbatim** — not an index and not an option id; there
 *     is no option id on this wire at all, so a replayed answer is matched back by label text.
 *  2. **The approval id is spelled differently on the wire and on disk.** `approvalId` in the frames;
 *     plain `id` in the `approval/asked` / `approval/decided` session records. Same value, two names.
 */
internal object DshAsk {

    /** dsh's own name for the question tool, as it appears in `tool/call.name` on disk. */
    const val QUESTION_TOOL = "ask_user_question"

    /** The outcome a decided approval carries in its durable record. dsh has NO "always allow". */
    const val OUTCOME_ALLOW = "allowed-once"

    data class Option(val label: String, val description: String?)

    /** One question as it arrives on the wire. [id] is deliberately NOT carried to the phone: it is dsh's
     *  correlation token, the client answers by question TEXT (the protocol's existing shape), and this
     *  ledger translates back. */
    data class Question(
        val id: String,
        val question: String,
        val header: String?,
        val multiSelect: Boolean,
        val options: List<Option>,
    )

    // ---- inbound: frame → domain ----

    /**
     * `question/requested.questions[]` → [Question]s. Questions without an id or text are dropped: the id
     * is what the answer must echo, so one we cannot echo is unanswerable and would poison the whole batch.
     *
     * ⚠️ TWO SPELLINGS OF THE SAME FLAG. The wire frame and dsh's core types use `multiSelect`; the TOOL
     * INPUT the model writes (and therefore the `tool/call.arguments` blob on disk) uses `multi_select`.
     * Both are accepted here so the live path and the replay path can share one parser.
     */
    fun questionsOf(input: JsonObject?): List<Question> {
        val arr = input?.get("questions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val q = el as? JsonObject ?: return@mapNotNull null
            val id = q.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = q.str("question")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Question(
                id = id,
                question = text,
                header = q.str("header")?.takeIf { it.isNotBlank() },
                multiSelect = ((q["multiSelect"] ?: q["multi_select"]) as? JsonPrimitive)?.booleanOrNull == true,
                options = (q["options"] as? JsonArray).orEmpty().mapNotNull { o ->
                    val opt = o as? JsonObject ?: return@mapNotNull null
                    val label = opt.str("label") ?: return@mapNotNull null
                    // dsh DOES carry an optional per-option description (dsh-user-questions types.d.ts) —
                    // the design draft assumed it did not. Passing it through costs nothing and the
                    // phone's question card already renders it.
                    Option(label, opt.str("description")?.takeIf { it.isNotBlank() })
                },
            )
        }
    }

    // ---- disk replay helpers ----

    /** One durable `tool/result` block. [failed] is nullable because rc.6's successful records do not
     *  need to spell an outcome; callers may treat a present result with no error marker as success. */
    data class DiskToolResult(val callId: String, val text: String, val failed: Boolean?)

    /** `tool/call.arguments` is the model's RAW, UNPARSED JSON string — not an object. Null when it is
     *  absent or is not parseable JSON (a truncated live tail routinely is). */
    fun toolCallArgs(data: JsonObject?): JsonObject? {
        val raw = data?.str("arguments") ?: return data?.obj("arguments")
        return DshTranscript.parseLine(raw)
    }

    /**
     * (toolCallId → concatenated result text) for every block of a `tool/result` record. The nesting is
     * two levels deep and easy to get wrong: `data.message.content[]` are TOOL-RESULT blocks carrying
     * `toolCallId`, and each of those has its own `content[]` of `{type:"text", text}` parts.
     */
    fun toolResults(data: JsonObject?): List<DiskToolResult> {
        val blocks = (data?.obj("message")?.get("content") as? JsonArray) ?: return emptyList()
        return blocks.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            val callId = block.str("toolCallId") ?: return@mapNotNull null
            val text = (block["content"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == "text" }?.str("text") }
                .joinToString("")
            val status = block.str("status")?.lowercase()
            val failed = (block["isError"] as? JsonPrimitive)?.booleanOrNull
                ?: (block["is_error"] as? JsonPrimitive)?.booleanOrNull
                ?: when (status) {
                    "error", "failed", "failure" -> true
                    "ok", "success", "completed" -> false
                    else -> null
                }
            DiskToolResult(callId, text, failed)
        }
    }

    /**
     * The replayed (question → answer) pairs of an answered `ask_user_question`, from its tool/result
     * text — `{"answers":[{"id","selected",["custom"]}]}` (the tool serializes its return value verbatim).
     *
     * The ids are dsh's, and the phone shows question TEXT, so the pairs are matched POSITIONALLY against
     * [prompts] — the question texts read from the SAME tool/call, deliberately not re-split out of the
     * row's rendered label (a question containing a newline would shift every later pair by one). Null
     * when the text is not that shape, so the row falls back to the unanswered form rather than rendering
     * raw JSON.
     */
    fun replayAnswers(text: String?, prompts: List<String>): List<QuestionAnswer>? {
        val arr = DshTranscript.parseLine(text ?: return null)?.get("answers") as? JsonArray ?: return null
        val out = arr.mapIndexedNotNull { i, el ->
            val a = el as? JsonObject ?: return@mapIndexedNotNull null
            val selected = (a["selected"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            val custom = a.str("custom")?.takeIf { it.isNotBlank() }
            val answer = (selected + listOfNotNull(custom)).joinToString(", ").takeIf { it.isNotEmpty() }
                ?: return@mapIndexedNotNull null // a skipped question adds no row
            QuestionAnswer(prompts.getOrNull(i)?.take(MAX_ANSWER_CHARS).orEmpty(), answer.take(MAX_ANSWER_CHARS))
        }
        return out.ifEmpty { null }
    }

    /** `approval/decided.outcome`, or null. */
    fun outcomeOf(data: JsonObject?): String? = data?.str("outcome")

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else content.takeIf { it != "null" }

    private const val MAX_ANSWER_CHARS = 2000
}
