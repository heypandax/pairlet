package dev.ccpocket.daemon.feishu

/**
 * What a bridged Feishu session is told about its own situation (issue #242), split by LIFETIME:
 *
 *  - [bridgeContextPreamble] is stable for the whole session (which chat, which project, what it cannot
 *    see), so it rides the agent's SYSTEM prompt — written once at launch, carried across every relaunch
 *    and resume, and absent from the transcript and from the owner's approval card.
 *  - [senderLine] is the only per-turn part, because a topic's session outlives the message that opened it
 *    and a later turn can come from someone else.
 *
 * Without either, the session knows neither where it is nor what it CANNOT see: it has no group history, no
 * announcement and no member list, only the message it was handed plus whatever quoted context resolved.
 * Left unsaid, the agent reaches for a Feishu tool that isn't there and then guesses. The capability line
 * therefore also states the way out (quote the message and resend).
 *
 * Both are top-level pure functions (like [trustedEnabledReply]) so the exact wording has regression tests
 * without constructing an engine or an SDK client.
 */
internal fun bridgeContextPreamble(
    chatName: String?,
    isGroup: Boolean,
    projectName: String,
): String {
    // The chat name is attacker-controllable text: any member with rename rights picks it, and it lands in
    // a structured block the model reads as trusted framing — hence [sanitizeChatName] before interpolation.
    val source = when {
        !isGroup -> "飞书私聊"
        else -> sanitizeChatName(chatName)?.let { "飞书群「$it」" } ?: "飞书群"
    }
    return buildString {
        appendLine("[会话背景]")
        appendLine("来源：$source（cc-pocket 桥接，项目：$projectName）")
        append(CAPABILITY_LINE)
    }
}

/** The per-turn identity line prepended to the prompt itself — one line, so the transcript stays readable. */
internal fun senderLine(senderOpenId: String, isOwner: Boolean): String =
    "发起人：${senderOpenId.ifBlank { "未知飞书用户" }}（${if (isOwner) "机主" else "群成员"}）"

/** Null when there is no usable name — the preamble then degrades to a bare 「飞书群」 rather than blocking. */
private fun sanitizeChatName(raw: String?): String? {
    // ISO control chars cover CR/LF, the only ones that could forge an extra preamble line; the rest are
    // replaced too because they render as garbage rather than as the name someone actually chose.
    val flattened = raw.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    if (flattened.isEmpty()) return null
    return if (flattened.length > MAX_CHAT_NAME_CHARS) flattened.take(MAX_CHAT_NAME_CHARS) + "…" else flattened
}

private const val MAX_CHAT_NAME_CHARS = 64

private const val CAPABILITY_LINE =
    "能力边界：你能看到的群上下文仅限本条注入内容（用户指令，及如有的引用消息/话题起始消息）；" +
        "你无法读取群历史、群公告或群成员列表。需要群里的其他信息时，请让用户引用相关消息后重发。"
