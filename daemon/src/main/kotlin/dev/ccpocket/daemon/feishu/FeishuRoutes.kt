package dev.ccpocket.daemon.feishu

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * chat → project routing for the built-in Feishu bridge — the Kotlin home of what
 * examples/feishu-bridge/routes.py does for the external adapter.
 *
 * The binding is established IN the chat with /bind, never configured ahead of time, because a chat_id
 * (oc_xxx) only exists inside event payloads — no operator can look one up. The table can only ever point
 * at a workdir the bridge's spec already allow-lists, and the daemon re-checks every session.open against
 * that same list (BridgeGuard.BAD_WORKDIR), so a bad row here is denied at open, not merely by good
 * behaviour. Projects are named by basename so nobody in a chat sees an absolute path.
 */
class FeishuRoutes(private val path: File) {
    private val map = LinkedHashMap<String, String>() // chat_id -> absolute workdir

    init {
        if (path.exists()) {
            // a corrupt table must FAIL the engine start, not silently become an empty one — that would
            // read as "all my chats got unbound" with no explanation
            val raw = PocketJson.decodeFromString<Map<String, String>>(path.readText())
            map.putAll(raw)
        }
    }

    @Synchronized fun workdirFor(chatId: String): String? = map[chatId]
    @Synchronized fun bind(chatId: String, workdir: String) { map[chatId] = workdir; flush() }
    @Synchronized fun unbind(chatId: String): Boolean = (map.remove(chatId) != null).also { if (it) flush() }
    @Synchronized fun chatsFor(workdir: String): Int = map.values.count { it == workdir }
    @Synchronized fun size(): Int = map.size

    private fun flush() = writeOwnerOnly(path, PocketJson.encodeToString(map.toMap()))

    companion object {
        /** The chat-facing name of a workdir: its basename. /bind uses this, never the full path. */
        fun projectName(workdir: String): String = workdir.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

        /** Map a /bind argument back to an allow-listed workdir. Exact basename first, then a UNIQUE
         *  case-insensitive match — an ambiguous name must fail loudly rather than pick for the user. */
        fun resolveProject(name: String, workdirs: List<String>): String? {
            val n = name.trim()
            if (n.isEmpty()) return null
            val exact = workdirs.filter { projectName(it) == n }
            if (exact.isNotEmpty()) return exact.singleOrNull()
            val folded = workdirs.filter { projectName(it).equals(n, ignoreCase = true) }
            return folded.singleOrNull()
        }
    }
}

/** Write [text] to [path] atomically, owner-only where the filesystem supports it — the shape every
 *  engine-side state file in this package needs (a torn write reads as "all my chats got unbound", and the
 *  contents name the chats a machine answers to). Best-effort on the permission bit, like the credential
 *  stores: Windows ACLs inherit the profile dir instead. */
internal fun writeOwnerOnly(path: File, text: String) {
    path.parentFile?.mkdirs()
    val tmp = File(path.parentFile, ".${path.name}.tmp")
    tmp.writeText(text)
    runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
    Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** What the engine should do with one inbound chat line. */
sealed interface ChatAction {
    /** Reply [text] to the message (a /command's answer, or a refusal). */
    data class Reply(val text: String) : ChatAction
    /** Run [prompt] as a turn in [workdir] and reply with the final text. Non-null [note] is replied
     *  IMMEDIATELY, before the turn runs — feedback for a side effect (an auto-bind) that must not wait
     *  the minutes a turn can take. A NON-bridge slash command ("/clear", "/compact", "/skill-name") is
     *  carried here verbatim: the daemon's session intercepts the ones it owns and the CLI resolves the
     *  rest, exactly as the phone/desktop app does — the tool-level guard still gates any tool a skill runs. */
    data class Ask(val workdir: String, val prompt: String, val note: String? = null) : ChatAction
    /** Drop this chat's conversation so the next message opens a FRESH session ("/new"). [note] confirms it. */
    data class Reset(val note: String) : ChatAction
    /** Move this chat to [mode] (`/trust confirm`, `/review [purpose]`, `/untrust`)
     *  — authority already verified (machine owner only). [purpose] is the owner-typed Trust Contract for
     *  REVIEWED (null = the default contract). The engine persists it and replies, since only it
     *  knows whether the state actually changed — and whether the write LANDED. */
    data class SetTrust(val mode: FeishuTrustMode, val purpose: String? = null) : ChatAction
    /** Not addressed to us / nothing to do. */
    data object Ignore : ChatAction
}

/**
 * The chat-side command surface (/projects /bind /unbind /help) + admin gating — pure logic, no IO, so it
 * is testable without Feishu. Mirrors the external adapter's `_handle_command` verbatim in behaviour:
 * binding is privileged (only [adminOpenId]); when that's unset we refuse AND echo the caller's own
 * open_id, because that id is otherwise undiscoverable — the one-step bootstrap for a fresh install.
 */
class FeishuCommands(
    private val routes: FeishuRoutes,
    private val workdirs: List<String>,
    private val adminOpenId: String?,
    /** The Feishu GROUP OWNER's open_id for a chat (cached), or null if unknown — the FALLBACK /bind
     *  authority when [adminOpenId] is unset. Injected by the engine; the pure default keeps this class
     *  IO-free and unit-testable. An explicit admin always WINS; the owner only fills the no-admin gap. */
    private val chatOwnerOf: (chatId: String) -> String? = { null },
    /** MASTER enable for the per-chat trust modes (issue #198), read off the owner-only bridge spec. False
     *  (the default) makes /trust and /review refuse: the chat side can never turn the feature on itself. */
    private val noApprovalEnabled: Boolean = false,
    /** This chat's stored trust record, whatever project it names — injected so this class stays IO-free.
     *  A record naming a project OTHER than the current binding is not in effect, but must still be
     *  revocable by /untrust and visible to /trust-status. */
    private val trustRecordOf: (chatId: String) -> FeishuTrustRecord? = { null },
) {
    fun handle(text: String, chatId: String, senderOpenId: String): ChatAction {
        if (!text.startsWith("/")) {
            routes.workdirFor(chatId)?.let { return ChatAction.Ask(it, text) }
            // Unbound chat. The bridge allows exactly one project AND the binding AUTHORITY is the one
            // talking → bind automatically and answer: for the by-far-common single-project setup, the
            // owner's first message just works, no ceremony. Authority = the designated admin, or (when no
            // admin is set) the Feishu group owner. A stranger's message in a random group stays inert, and
            // if neither is known yet it stays inert too (binding is privileged; nobody's proven ownership).
            val authority = adminOpenId?.takeIf { it.isNotBlank() } ?: chatOwnerOf(chatId)
            val only = workdirs.singleOrNull()
            if (only != null && authority != null && senderOpenId == authority) {
                routes.bind(chatId, only)
                return ChatAction.Ask(
                    only, text,
                    note = "✅ 已自动把本群绑定到「${FeishuRoutes.projectName(only)}」，开始处理…",
                )
            }
            // otherwise: teach, with a command that can be copied VERBATIM when there's only one choice
            val bindHint = only?.let { "管理员发送：@我 /bind ${FeishuRoutes.projectName(it)}" } ?: projectsText()
            return ChatAction.Reply("本群还没有绑定项目。\n$bindHint\n\n$HELP")
        }
        val parts = text.drop(1).split(Regex("\\s+"), limit = 2)
        val cmd = parts.getOrNull(0)?.lowercase().orEmpty()
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        // BRIDGE-LOCAL commands (chat/session management) are handled here; everything else that starts
        // with "/" is NOT ours — "/clear", "/compact", "/model", "/skill-name" — and passes through to the
        // bound session verbatim. Intercepting all slashes was the walled garden that made every real
        // command and skill report "unknown command".
        return when (cmd) {
            "help", "?" -> ChatAction.Reply(HELP)
            "projects" -> ChatAction.Reply(projectsText())
            "new", "reset" -> {
                if (routes.workdirFor(chatId) == null) ChatAction.Reply("本群还没有绑定项目。\n\n$HELP")
                else ChatAction.Reset("🆕 已开新会话，之前的上下文已清空。")
            }
            "bind", "unbind" -> ChatAction.Reply(
                run {
                    // authority to bind = the designated admin if set, else the Feishu GROUP OWNER (looked up
                    // live, no env / no restart). Binding only points a chat at an ALREADY allow-listed
                    // workdir and every action still hits owner approval, so the group's own owner is a sound
                    // low-privilege authority; an explicit admin still WINS when configured.
                    val authority = adminOpenId?.takeIf { it.isNotBlank() } ?: chatOwnerOf(chatId)
                    when {
                        authority == null ->
                            "还没法确认谁能绑定：没设管理员，也还没查到群主。\n你的 open_id 是：$senderOpenId\n" +
                                "让群主发一次 /bind（稍候重试，正在确认群主），或在桌面端把某人填进 admin 字段。"
                        senderOpenId != authority -> "只有管理员或群主可以绑定 / 解绑本群。"
                        cmd == "unbind" ->
                            if (routes.unbind(chatId)) "已解绑，本群不再响应。" else "本群本来就没有绑定项目。"
                        else -> {
                            val wd = FeishuRoutes.resolveProject(arg, workdirs)
                            if (wd == null) "找不到项目「$arg」。\n\n${projectsText()}"
                            else {
                                routes.bind(chatId, wd)
                                "✅ 本群已绑定项目「${FeishuRoutes.projectName(wd)}」。\n@我说话就会在该项目下执行；危险操作仍会弹到主人手机审批。"
                            }
                        }
                    }
                },
            )
            // TRUST MODES (issue #198 + reviewed trust). Authority is the MACHINE OWNER alone — deliberately
            // NOT the /bind authority: a Feishu group owner may point their chat at an allow-listed project,
            // but waiving or conditioning the machine owner's review of what runs on their machine is not
            // theirs to grant. So no group-owner fallback here; with no admin configured the answer is
            // "can't", plus the caller's own open_id so the owner can paste it into the desktop field (the
            // same bootstrap /bind uses).
            "trust", "untrust", "review", "full-auto" -> {
                val admin = adminOpenId?.takeIf { it.isNotBlank() }
                val record = trustRecordOf(chatId)
                val bound = routes.workdirFor(chatId)
                val trustConfirmed = cmd == "trust" && arg.equals("confirm", ignoreCase = true)
                when {
                    admin == null -> ChatAction.Reply(
                        "还没设置机主 open_id，无法调整本群的信任模式。\n你的 open_id 是：$senderOpenId\n" +
                            "在桌面端「桥」的配置里填进 admin 字段后再试。",
                    )
                    senderOpenId != admin -> ChatAction.Reply("只有机主可以调整本群的信任模式。")
                    // /untrust always works, even with the master switch off or the chat unbound: turning
                    // trust DOWN must never be blocked by config state, or a bridge whose master switch later
                    // flips back on would silently resurrect an entry the owner meant to drop. The record may
                    // name a project the chat has since been rebound away from — still revocable.
                    cmd == "untrust" ->
                        if (record != null) ChatAction.SetTrust(FeishuTrustMode.UNTRUSTED)
                        else ChatAction.Reply("本群本来就是逐请求审批。")
                    // The historical bare /trust promised a restricted ceiling. It is now read-only so an
                    // old habit or cached instruction cannot silently consent to broader Bash/MCP authority.
                    cmd == "trust" && !trustConfirmed -> ChatAction.Reply(TRUST_WARNING)
                    // FULL_AUTO is no longer a separate mode. Every old spelling is read-only: its old
                    // confirmation included a Guardian gate and cannot silently become unconditional full.
                    cmd == "full-auto" -> ChatAction.Reply(FULL_AUTO_COMPAT)
                    !noApprovalEnabled -> ChatAction.Reply(
                        "电脑上还没允许群信任模式：先在桌面端「桥」的配置里勾上「允许群信任模式」，再在群里发 " +
                            if (cmd == "full-auto") "/full-auto $arg。" else "/$cmd。",
                    )
                    bound == null -> ChatAction.Reply("本群还没有绑定项目，先 /bind 再设置信任模式。")
                    cmd == "trust" -> // exact /trust confirm
                        if (
                            record?.workdir == bound && record.mode == FeishuTrustMode.TRUSTED &&
                            record.fullAuthorityConfirmed
                        ) {
                            ChatAction.Reply("本群已经是完全信任了。")
                        } else {
                            ChatAction.SetTrust(FeishuTrustMode.TRUSTED)
                        }
                    cmd == "review" -> { // /review [purpose]
                        val purpose = arg.takeIf { it.isNotBlank() }?.take(FeishuTrust.MAX_PURPOSE_CHARS)
                        if (record?.workdir == bound && record.mode == FeishuTrustMode.REVIEWED && record.purpose == purpose) {
                            ChatAction.Reply("本群已经是智能审核了。用 /trust-status 查看契约。")
                        } else {
                            ChatAction.SetTrust(FeishuTrustMode.REVIEWED, purpose)
                        }
                    }
                    else -> error("unreachable trust command: $cmd")
                }
            }
            // read-only status — open to any member on purpose: it shows nothing but the mode, the bound
            // project's display name and the owner's declared contract, all of which the group already lives
            "trust-status" -> ChatAction.Reply(trustStatusText(chatId))
            // a non-bridge slash → run it in the bound session (or teach if this chat has none yet)
            else -> routes.workdirFor(chatId)?.let { ChatAction.Ask(it, text) }
                ?: ChatAction.Reply("本群还没有绑定项目，无法执行 /$cmd。\n\n$HELP")
        }
    }

    /** The /trust-status answer: mode + bound project + contract summary — never internal paths. */
    private fun trustStatusText(chatId: String): String {
        val bound = routes.workdirFor(chatId)
        val record = trustRecordOf(chatId)
        if (bound == null) {
            return "本群还没有绑定项目，当前所有请求都不会执行。先 /bind 绑定项目。"
        }
        val effective = record?.takeIf { it.workdir == bound }
        val legacyNeedsConfirmation = effective != null &&
            effective.mode in setOf(FeishuTrustMode.TRUSTED, FeishuTrustMode.FULL_AUTO) &&
            !effective.fullAuthorityConfirmed
        return buildString {
            appendLine("绑定项目：${FeishuRoutes.projectName(bound)}")
            when {
                legacyNeedsConfirmation -> {
                    appendLine("模式：每次审批（旧信任待重新确认）—— 历史授权不会被静默扩大为 full 权限。")
                    appendLine("机主如接受新版权限，请先发送 /trust 查看说明，再发送 /trust confirm；在此之前每条请求仍先发到机主手机。")
                }
                effective?.mode == FeishuTrustMode.TRUSTED || effective?.mode == FeishuTrustMode.FULL_AUTO -> {
                    appendLine("模式：完全信任 —— 机主已授权本群在该项目中直接执行；每条请求获得整轮 full 权限，不经 Guardian，也不逐工具询问。")
                    appendLine("能力：可运行未被确定性规则拦截的 Bash、MCP、网络工具和子代理，可能访问项目外数据或向外发送数据。")
                    appendLine("仍会询问：需要人类作答或确认的交互工具。已开始的一轮不会因 /untrust 被中途撤销。")
                    appendLine("机主可随时发 /untrust，撤销后从下一条请求生效。")
                }
                effective?.mode == FeishuTrustMode.REVIEWED -> {
                    appendLine("模式：智能审核 —— 每条请求先经 Guardian，明确低风险且符合群用途的才在项目内受限执行，其余转机主审批。")
                    appendLine("契约（版本 ${effective.contractVersion}）：${effective.purpose ?: FeishuTrust.DEFAULT_CONTRACT}")
                }
                else -> appendLine("模式：每次审批 —— 每条请求都先发到机主手机。")
            }
            if (record != null && effective == null) {
                append("（另有一条旧信任记录指向别的项目，已不生效；机主可 /untrust 清除。）")
            }
        }.trimEnd()
    }

    private fun projectsText(): String = buildString {
        appendLine("可绑定的项目：")
        for (w in workdirs) {
            val bound = routes.chatsFor(w)
            append("  • ").append(FeishuRoutes.projectName(w))
            if (bound > 0) append("（已绑 $bound 个群）")
            appendLine()
        }
        append("\n绑定：@机器人 /bind <项目名>")
    }

    companion object {
        val HELP = """
            用法：
              @机器人 <你的需求>      在本群绑定的项目下干活
              @机器人 /projects       列出可绑定的项目
              @机器人 /bind <项目>    把本群绑到某个项目（仅管理员）
              @机器人 /unbind         解绑本群（仅管理员）
              @机器人 /trust          查看完全信任的权限说明（只读）
              @机器人 /trust confirm  完全信任本群：每条请求整轮 full 权限直接执行（仅机主，需电脑上先允许）
              @机器人 /review [用途]  本群智能审核：AI 判定低风险才直接执行（仅机主）
              @机器人 /full-auto confirm [用途]
                                      旧命令迁移提示（只读）；请先读 /trust，再明确发送 /trust confirm
              @机器人 /untrust        恢复逐请求审批（仅机主）
              @机器人 /trust-status   查看本群的信任模式与契约
        """.trimIndent()

        val TRUST_WARNING = """
            ⚠️ 完全信任不是沙箱。此命令尚未开启任何状态。
            开启后，本群对当前项目的每条请求都不经 Guardian，并获得整轮 full 权限：可运行未被确定性规则拦截的 Bash、MCP、网络工具和子代理，不再逐工具询问。
            Shell、MCP、网络工具和子代理可能访问项目外数据或向外发送数据；未来未识别工具及需要人类作答或确认的交互工具仍会询问。

            机主确认接受以上权限请精确发送：@机器人 /trust confirm
            开启后可随时发 /untrust 撤销，撤销从下一条请求生效。
        """.trimIndent()

        val FULL_AUTO_COMPAT = """
            ℹ️ full-auto 已合并到完全信任模式，不再单独经过 Guardian。
            请先发送 @机器人 /trust 查看新版 full 权限说明，再精确发送 @机器人 /trust confirm 完成授权。
            旧命令 @机器人 /full-auto confirm 原本包含 Guardian 条件，因此现在只显示本提示，不会静默扩大授权。
            可随时发 /untrust 撤销，撤销从下一条请求生效。
        """.trimIndent()
    }
}
