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
 * The dsh ask/approval VOCABULARY (issue #291) — pure translation, no state and no IO, so every rule below
 * is unit-testable against the real frames. [DshAskLedger] owns the pending table; [DshBackend] owns the
 * transport.
 *
 * Everything here is source-verified against dsh rc.6 (`@deepseek-ai/dsh-host-apiproxy`,
 * `dsh-user-questions`, `dsh-user-approval`, `dsh-tool-ask-user`) and the `--probe-ask` tier of
 * `scripts/probe-dsh-api.py`. Four facts drive the shapes and each one fails as a silent `bad-response`
 * if ignored:
 *
 *  1. **The answer vocabulary is the option LABEL, verbatim.** Not an index, not an option id — dsh
 *     validates every entry of `selected` against `options[].label` and rejects the whole response
 *     otherwise. There is no option id on the wire at all.
 *  2. **A question is answered as a WHOLE BATCH, positionally, with matching ids.** dsh requires
 *     `answers.length == questions.length` and `answers[i].id == questions[i].id`. Answering only the
 *     questions the human actually picked is rejected — an unanswered one rides as `selected: []`, which
 *     is exactly what dsh's own web UI sends for a skipped question.
 *  3. **`custom` and `selected` are mutually exclusive on a SINGLE-select question** (and `selected` is
 *     capped at one entry there); on a multi-select they may ride together. A blank `custom` is rejected.
 *  4. **The approval id is spelled differently on the wire and on disk.** `approvalId` in the
 *     `approval/requested` / `approval/resolved` frames and in the `/api/respond` payload; plain `id` in
 *     the `approval/asked` / `approval/decided` session records. Same value, two names.
 */
internal object DshAsk {

    /** dsh's own name for the question tool, as it appears in `tool/call.name` on disk. */
    const val QUESTION_TOOL = "ask_user_question"

    /** The only two outcomes a CLIENT may send. `cancelled` / `unavailable` are host-minted and are
     *  rejected as `bad-response` if a client tries to claim them. dsh has NO "always allow". */
    const val OUTCOME_ALLOW = "allowed-once"
    const val OUTCOME_REJECT = "rejected"

    /** askId namespace, mirroring the shell service's `sh-` convention. The rpcId follows it verbatim. */
    const val ASK_ID_PREFIX = "dsh-"

    fun askIdOf(rpcId: String): String = ASK_ID_PREFIX + rpcId

    /** The rpcId inside an askId we minted, or null for an askId that was never ours. */
    fun rpcIdOf(askId: String): String? = askId.removePrefix(ASK_ID_PREFIX).takeIf { it != askId && it.isNotEmpty() }

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

    /**
     * The Claude-shaped `AskUserQuestion` tool input, so [dev.ccpocket.daemon.agent.AskQuestions.parse]
     * builds the protocol's `AskQuestion` list with the code every other backend already uses. dsh's
     * question `id` is deliberately absent — it never goes to the phone.
     */
    fun askQuestionInput(questions: List<Question>): JsonObject = buildJsonObject {
        putJsonArray("questions") {
            questions.forEach { q ->
                addJsonObject {
                    put("question", q.question)
                    q.header?.let { put("header", it) }
                    if (q.multiSelect) put("multiSelect", true)
                    putJsonArray("options") {
                        q.options.forEach { opt ->
                            addJsonObject {
                                put("label", opt.label)
                                opt.description?.let { put("description", it) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The approval ask's tool input. dsh sends NO tool arguments with an approval — only the model's own
     *  `reason` for wanting the escalation, which is the sentence a human actually needs. Put under
     *  `description` because that is the generic key [dev.ccpocket.daemon.agent.ToolMetadata] previews. */
    fun approvalInput(reason: String?, callId: String?): JsonObject = buildJsonObject {
        reason?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        callId?.let { put("callId", it) }
    }

    // ---- outbound: verdict → `/api/respond` value ----

    /**
     * `{sessionId, answer:{answers:[…]}}` for a question verdict.
     *
     * [answers] is the protocol's question-TEXT → answer-string map; [response] is the card's "reply
     * instead of answering" free text, which applies to every question that has no pick of its own.
     * The answer string is the phone's comma-joined form ("Red, Blue", or an "Other…" text appended
     * last), so each part is matched against the question's real labels: what matches rides in
     * `selected`, whatever is left over is the human's own words and rides in `custom`.
     *
     * ALWAYS one entry per question, in question order — see rule 2 in the class comment.
     */
    fun answerValue(
        sessionId: String?,
        questions: List<Question>,
        answers: Map<String, String>?,
        response: String?,
    ): JsonObject {
        val freeform = response?.trim()?.takeIf { it.isNotEmpty() }
        return buildJsonObject {
            sessionId?.let { put("sessionId", it) }
            put(
                "answer",
                buildJsonObject {
                    putJsonArray("answers") {
                        questions.forEach { q ->
                            val raw = answers?.get(q.question)?.trim()?.takeIf { it.isNotEmpty() }
                            addJsonObject { putAnswer(q, raw ?: freeform) }
                        }
                    }
                },
            )
        }
    }

    /** One `{id, selected, custom?}` entry, obeying dsh's single-vs-multi exclusivity rules. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putAnswer(q: DshAsk.Question, raw: String?) {
        put("id", q.id)
        if (raw == null) { // skipped / unanswered — dsh's own UI sends exactly this
            putJsonArray("selected") {}
            return
        }
        val labels = q.options.map { it.label }
        // Whole-string match first: a label may legitimately contain ", " and splitting would destroy it.
        val exact = raw in labels
        val parts = if (exact) listOf(raw) else raw.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val picked = parts.filter { it in labels }.distinct()
        val leftover = parts.filterNot { it in labels }.joinToString(", ").takeIf { it.isNotEmpty() }
        // Single-select: `custom` and `selected` may not ride together, and `selected` holds at most one.
        // The human's own words win when there are any — a typed "Other…" answer is what they meant, and
        // silently dropping it to keep a partially-matched label would answer a question they didn't.
        if (!q.multiSelect) {
            if (leftover != null) {
                putJsonArray("selected") {}
                put("custom", leftover)
            } else {
                putJsonArray("selected") { picked.take(1).forEach { add(it) } }
            }
            return
        }
        putJsonArray("selected") { picked.forEach { add(it) } }
        leftover?.let { put("custom", it) }
    }

    /** `{sessionId, approvalId, outcome}` for an approval verdict. dsh validates BOTH ids, so both are
     *  echoed from the request that minted the card rather than re-derived. */
    fun approvalValue(sessionId: String?, approvalId: String, allow: Boolean): JsonObject = buildJsonObject {
        sessionId?.let { put("sessionId", it) }
        put("approvalId", approvalId)
        put("outcome", if (allow) OUTCOME_ALLOW else OUTCOME_REJECT)
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

/** What `/api/respond` said. `reason` is dsh's own word — `not-pending` (someone else claimed it, or the
 *  turn was cancelled) is a BENIGN race; `bad-response` means WE built the wrong shape and is a bug the
 *  user must be told about rather than a silence. */
data class DshRespond(val accepted: Boolean, val reason: String?) {
    val benign: Boolean get() = accepted || reason == NOT_PENDING

    companion object {
        const val NOT_PENDING = "not-pending"
        val UNREACHABLE = DshRespond(false, "unreachable")
    }
}
