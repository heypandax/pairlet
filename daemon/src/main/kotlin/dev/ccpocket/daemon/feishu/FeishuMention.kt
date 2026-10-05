package dev.ccpocket.daemon.feishu

/**
 * 群聊「完成回报」的文本装配（issue #284）。
 *
 * Why：群里发起的任务跑完后，机器人把结果直接发进群，消息一多发起人根本感知不到自己那条跑完了。
 * 飞书 text 消息原生解析 `<at user_id="...">` 标记，所以只要在**回报文本**头部拼一个 at 前缀，
 * 就能把结果精准推到发起人的 badge/通知上，不需要新造任何身份体系（open_id 是飞书 attested 的）。
 *
 * 范围刻意收窄到「完成回报」这一个时点：ack、审批提示、nudge、拒绝、回执一律不 @，
 * 否则一次任务会把发起人叮四五次，反而比不 @ 更吵。单聊天然定向，调用方传 null 即可。
 *
 * 纯函数、无 IO，便于单测——这层唯一的风险就是前缀拼错位置，值得钉住。
 */
internal object FeishuMention {
    /**
     * 完成回报的最终文本：[atUser] 为群聊发起人的 open_id 时在头部拼 at 前缀，否则原样返回。
     *
     * 空白等价于「无人可 @」（单聊、或飞书没给出 sender open_id 的退化场景），此时绝不能拼出
     * `<at user_id="">` —— 那在群里会渲染成一个指向不存在用户的死链。
     */
    fun completionText(atUser: String?, text: String): String {
        val id = atUser?.trim().orEmpty()
        if (id.isEmpty()) return text
        return "<at user_id=\"$id\"></at> $text"
    }

    enum class GroupGate { ACCEPT, DROP, AWAIT_IDENTITY }

    /**
     * 群消息是否是发给机器人的（审计 F7）。没有 @ 一律不是；知道自己的 open_id 时只认 @ 了自己的；
     * 还不知道时**不猜**——以前退化成「任意 @ 都算」，「@同事 看下」在完全信任的群里会被直接执行。
     * 交给调用方等身份到手后再判（[AWAIT_IDENTITY]），而不是当场丢掉，免得启动瞬间的正常消息被吞。
     */
    fun groupGate(mentionedOpenIds: List<String?>, botOpenId: String?): GroupGate = when {
        mentionedOpenIds.isEmpty() -> GroupGate.DROP
        botOpenId == null -> GroupGate.AWAIT_IDENTITY
        mentionedOpenIds.any { it == botOpenId } -> GroupGate.ACCEPT
        else -> GroupGate.DROP
    }

    /** 取 open_id 失败后第 [attempt] 次（0 起）重试前的等待：5s 起翻倍，封顶 5 分钟。 */
    fun botIdentityRetryDelayMs(attempt: Int): Long =
        (5_000L shl attempt.coerceIn(0, 6)).coerceAtMost(5 * 60_000L)
}
