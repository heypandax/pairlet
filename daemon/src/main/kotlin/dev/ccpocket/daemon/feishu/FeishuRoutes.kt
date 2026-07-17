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

    private fun flush() {
        path.parentFile?.mkdirs()
        val tmp = File(path.parentFile, ".${path.name}.tmp")
        tmp.writeText(PocketJson.encodeToString(map.toMap()))
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** The chat-facing name of a workdir: its basename. /bind uses this, never the full path. */
        fun projectName(workdir: String): String = workdir.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

        /** Map a /bind argument back to an allow-listed workdir. Exact basename first, then a UNIQUE
         *  case-insensitive match — an ambiguous name must fail loudly rather than pick for the user. */
        fun resolveProject(name: String, workdirs: List<String>): String? {
            val n = name.trim()
            if (n.isEmpty()) return null
            workdirs.firstOrNull { projectName(it) == n }?.let { return it }
            val folded = workdirs.filter { projectName(it).equals(n, ignoreCase = true) }
            return folded.singleOrNull()
        }
    }
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
            // a non-bridge slash → run it in the bound session (or teach if this chat has none yet)
            else -> routes.workdirFor(chatId)?.let { ChatAction.Ask(it, text) }
                ?: ChatAction.Reply("本群还没有绑定项目，无法执行 /$cmd。\n\n$HELP")
        }
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
        """.trimIndent()
    }
}
