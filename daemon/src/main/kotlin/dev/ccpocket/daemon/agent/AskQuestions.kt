package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * AskUserQuestion wire helpers (verified against claude CLI 2.1.201):
 * - the tool's input is `{"questions":[{question, header, options:[{label,description}], multiSelect}]}`
 * - the user's answers ride BACK inside the control_response's `updatedInput`: the original input plus
 *   `"answers": {"<question text>": "<label | comma-joined labels | free text>"}` and/or a top-level
 *   `"response": "<freeform reply instead of answering>"`. A bare allow with the input unchanged makes
 *   the CLI synthesize the tool_result "The user did not answer the questions."
 */
object AskQuestions {
    const val TOOL = "AskUserQuestion"

    /** Parses the tool input's questions; null when absent/malformed (fall back to the generic ask card). */
    fun parse(input: JsonObject?): List<AskQuestion>? {
        val arr = input?.get("questions") as? JsonArray ?: return null
        val questions = arr.mapNotNull { el ->
            val q = el as? JsonObject ?: return@mapNotNull null
            val text = (q["question"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            AskQuestion(
                question = text,
                header = (q["header"] as? JsonPrimitive)?.contentOrNull,
                multiSelect = (q["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
                options = (q["options"] as? JsonArray)?.mapNotNull { o ->
                    val opt = o as? JsonObject ?: return@mapNotNull null
                    val label = (opt["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    AskOption(label, (opt["description"] as? JsonPrimitive)?.contentOrNull)
                }.orEmpty(),
            )
        }
        return questions.ifEmpty { null }
    }

    /** Original input + the user's answers/response merged in; null when there is nothing to merge. */
    fun answeredInput(input: JsonObject?, answers: Map<String, String>?, response: String?): String? {
        val picked = answers.orEmpty().filterValues { it.isNotBlank() }
        val freeform = response?.takeIf { it.isNotBlank() }
        if (picked.isEmpty() && freeform == null) return null
        return buildJsonObject {
            input?.forEach { (k, v) -> put(k, v) }
            if (picked.isNotEmpty()) putJsonObject("answers") { picked.forEach { (q, a) -> put(q, a) } }
            freeform?.let { put("response", it) }
        }.toString()
    }
}
