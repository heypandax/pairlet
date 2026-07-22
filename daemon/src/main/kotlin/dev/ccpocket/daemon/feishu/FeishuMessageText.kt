package dev.ccpocket.daemon.feishu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turn a Feishu message's `msg_type` + `body.content` into plain text for the QUOTED-CONTEXT feature
 * (requirement 2): when a group message replies to an earlier one, the earlier message's text is carried
 * into the Claude session so the model sees the original the user is pointing at.
 *
 * Pure + IO-free so it's unit-testable without Feishu. Best-effort by design — it extracts human-readable
 * text where the shape is known (text / rich-text post), and returns a short bracketed placeholder for
 * non-text kinds (image / file / audio / …) rather than dumping raw JSON into the prompt. A parse failure
 * degrades to a placeholder, never throws — a malformed quote must not break the turn it rides on.
 */
internal object FeishuMessageText {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [msgType] is the message's `msg_type`; [content] is the raw `body.content` JSON string. */
    fun plainText(msgType: String?, content: String?): String = when (msgType) {
        "text" -> textField(content) ?: PLACEHOLDER_OTHER
        "post" -> richPostText(content).ifBlank { placeholderFor("post") }
        else -> placeholderFor(msgType)
    }

    private fun textField(content: String?): String? = runCatching {
        json.parseToJsonElement(content ?: return null).let { it as? JsonObject }
            ?.get("text")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** A rich-text `post` nests text across locale wrappers and paragraph arrays. Recursively harvest every
     *  `title` / `text` primitive in document order and join — robust to the locale-wrapped and bare shapes
     *  both, without needing to model the whole schema. */
    private fun richPostText(content: String?): String = runCatching {
        val root = json.parseToJsonElement(content ?: return "")
        val out = StringBuilder()
        collect(root, out)
        out.toString().trim()
    }.getOrDefault("")

    private fun collect(el: JsonElement, out: StringBuilder) {
        when (el) {
            is JsonObject -> {
                // a title/text primitive on THIS node contributes once; recursion below skips primitives,
                // so nothing is double-counted
                (el["title"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) out.appendLine(it) }
                (el["text"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) out.append(it).append(' ') }
                el.values.forEach { if (it is JsonObject || it is JsonArray) collect(it, out) }
            }
            is JsonArray -> el.forEach { collect(it, out) }
            else -> {} // bare primitive — captured by its parent object above
        }
    }

    private fun placeholderFor(msgType: String?): String = when (msgType) {
        "image" -> "[图片]"
        "file" -> "[文件]"
        "audio" -> "[语音]"
        "media" -> "[视频]"
        "sticker" -> "[表情]"
        "post" -> "[富文本]"
        "share_chat" -> "[群名片]"
        "share_user" -> "[个人名片]"
        "interactive" -> "[卡片]"
        null, "" -> PLACEHOLDER_OTHER
        else -> "[$msgType]"
    }

    private const val PLACEHOLDER_OTHER = "[非文本消息]"
}
