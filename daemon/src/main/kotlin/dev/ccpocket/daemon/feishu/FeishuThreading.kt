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
 * message starts an independent topic, so two unrelated discussions in the group never share a transcript
 * while follow-ups inside one topic keep the original agent context.
 */
internal object FeishuThreading {
    fun replyTarget(messageId: String, chatType: String?): FeishuReplyTarget =
        FeishuReplyTarget(messageId, inThread = isGroup(chatType))

    /**
     * 一条群消息属于哪个话题（#262）。飞书自己的话题锚点是 [threadId]——话题里的每条消息都带同一个值，
     * 包括在话题面板里**直接发言**（不点回复）的那些。[rootId] 只在回复／引用链路上才被填，拿它当唯一锚点
     * 时话题内的直发消息各自成键，等于每问一句就换一个会话、上下文全丢。
     * 所以优先级是：话题锚点 → 回复根 → 本消息自己的 id（这条就是话题的开头）。
     */
    fun topicAnchor(messageId: String, threadId: String?, rootId: String?): String =
        threadId?.takeIf { it.isNotBlank() }
            ?: rootId?.takeIf { it.isNotBlank() }
            ?: messageId

    fun conversationKey(
        chatId: String,
        chatType: String?,
        messageId: String,
        threadId: String?,
        rootId: String?,
        ownerTurn: Boolean,
    ): String = conversationKeyOf(chatId, chatType, topicAnchor(messageId, threadId, rootId), ownerTurn)

    /**
     * 同一个键，但话题锚点已经算好了——`/menu` 卡片回调走这条：卡片记住的是它被贴进去的那个话题，不该拿
     * 卡片自己的消息 id 再解析一遍，否则一次按钮点击就漂到一个新话题上去。一个话题只有一种身份。
     */
    fun conversationKeyOf(
        chatId: String,
        chatType: String?,
        topicAnchor: String,
        ownerTurn: Boolean,
    ): String {
        val base = if (isGroup(chatType)) "$chatId\u0000topic\u0000$topicAnchor" else chatId
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

    /**
     * 执行串行化用的键（#265）。会话仍按话题隔离，但**同一个群的所有话题共用一把执行锁**：它们写的是同一
     * 棵工作区，两个 agent 并发改同一批文件会互相踩掉对方的编辑。#234 把锁跟着会话键一起切成 per-topic，
     * 等于把这道保护拆了；这里还原成 #234 之前的群级粒度。
     *
     * 直聊没有话题，沿用它自己的会话键：统一收件箱按项目分流，不同项目本就是不同 workdir，串起来只会让
     * 「问完 A 再问 B」白等。owner bypass 仍走独立一路（它本就是一条独立会话），维持既有行为。
     */
    fun executionLockKey(chatId: String, chatType: String?, convoKey: String, ownerTurn: Boolean): String =
        if (!isGroup(chatType)) convoKey else if (ownerTurn) "$chatId\u0000owner" else chatId

    /** 本群每个话题会话键的公共前缀——顶层 `/new` 要一次清干净时按它筛（#265）。 */
    fun topicKeyPrefix(chatId: String): String = "$chatId\u0000topic\u0000"

    /** Group vs. direct chat — also read by the engine, which fetches a chat NAME and says 「飞书群」 in the
     *  injected session header only for groups (#242). One definition, so the two can never disagree. */
    fun isGroup(chatType: String?): Boolean = chatType.equals("group", ignoreCase = true)
}
