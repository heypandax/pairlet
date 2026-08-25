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
}
