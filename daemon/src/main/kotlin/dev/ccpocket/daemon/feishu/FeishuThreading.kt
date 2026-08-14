package dev.ccpocket.daemon.feishu

/** The exact Feishu message to reply to, plus whether that reply must stay inside a topic. */
internal data class FeishuReplyTarget(
    val messageId: String,
    val inThread: Boolean,
)

/**
 * Pure routing rules for issue #234.
 *
 * A direct chat keeps its historical one-conversation-per-chat behaviour. In a group, each top-level
 * message starts an independent topic: its message id is the durable anchor, and Feishu puts that same id
 * in [rootId] on every later message in the topic. That gives follow-ups the original agent context without
 * allowing two unrelated discussions in the group to share a transcript or a turn lock.
 */
internal object FeishuThreading {
    fun replyTarget(messageId: String, chatType: String?): FeishuReplyTarget =
        FeishuReplyTarget(messageId, inThread = isGroup(chatType))

    fun conversationKey(
        chatId: String,
        chatType: String?,
        messageId: String,
        rootId: String?,
        ownerTurn: Boolean,
    ): String {
        val base = if (isGroup(chatType)) {
            val topicRoot = rootId?.takeIf { it.isNotBlank() } ?: messageId
            "$chatId\u0000topic\u0000$topicRoot"
        } else {
            chatId
        }
        return if (ownerTurn) "$base\u0000owner" else base
    }

    /**
     * An AUTO-routing DIRECT chat (the unified inbox) runs ONE conversation per (chat, project): switching
     * projects switches conversations without destroying either, so「问完 A 再问 B」round-trips keep both
     * contexts. A pinned (/bind <project>) direct chat keeps the historical [conversationKey] shape instead.
     */
    fun directProjectKey(chatId: String, workdir: String, ownerTurn: Boolean): String {
        val base = "$chatId\u0000proj\u0000$workdir"
        return if (ownerTurn) "$base\u0000owner" else base
    }

    /** Group vs. direct chat — also read by the engine, which fetches a chat NAME and says 「飞书群」 in the
     *  injected session header only for groups (#242). One definition, so the two can never disagree. */
    fun isGroup(chatType: String?): Boolean = chatType.equals("group", ignoreCase = true)
}
