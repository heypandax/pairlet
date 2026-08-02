package dev.ccpocket.daemon.feishu

/**
 * Deterministic, CONSERVATIVE prescreen ahead of the Guardian Reviewer (design §7.3). Its one power is to
 * force a request to the owner's card; it can never auto-pass anything, and a hit is NOT a malice verdict —
 * it means "a human should read this one", which costs the requester a wait, not a refusal. That asymmetry
 * is why plain keyword matching is acceptable here at all: a false positive degrades to the pre-feature
 * behaviour (owner approves), a false negative still faces the Guardian AND the runtime tool walls.
 *
 * Deliberately NOT the tool-level risk engine: at this point nothing knows what tools the agent would
 * actually run — pretending otherwise would dress a text match up as an execution-path analysis.
 */
object PromptThreatSignals {
    fun scan(prompt: String): List<String> = buildList {
        if (prompt.length > PromptReviewPolicy.MAX_REVIEW_PROMPT_CHARS) add(PromptReviewPolicy.PROMPT_TOO_LARGE)
        for ((code, patterns) in SIGNALS) {
            if (patterns.any { it.containsMatchIn(prompt) }) add(code)
        }
    }

    // Patterns aim at ARTIFACTS and phrasings that rarely appear in ordinary in-project dev requests.
    // Wide nets like a bare "token" or "url" are avoided on purpose — an over-eager prescreen that flags
    // every second request would train the owner to rubber-stamp, which is worse than missing a phrase
    // the Guardian and the tool walls still catch.
    private val SIGNALS: Map<String, List<Regex>> = mapOf(
        PromptReviewPolicy.CREDENTIAL_OR_SECRET_REQUEST to listOf(
            re("""\.env\b"""),
            re("""\bid_rsa\b|\bid_ed25519\b|authorized_keys|\bssh[-_ ]?key"""),
            re("""private[ _-]?key|\bapi[ _-]?key|(access|auth|bearer|refresh)[ _-]?token|credential"""),
            re("""密码|密钥|私钥|凭证|凭据|令牌|钥匙串|keychain"""),
        ),
        PromptReviewPolicy.EXTERNAL_PATH_REQUEST to listOf(
            re("""(^|[\s"'`(=])~/"""),
            re("""(^|[\s"'`(=])/(etc|Users|home|root|var|private)/"""),
            re("""[A-Za-z]:\\(Users|Windows)"""),
            re("""家目录|用户目录|项目外|home directory|outside the project"""),
        ),
        PromptReviewPolicy.DATA_EXFILTRATION_REQUEST to listOf(
            re("""(upload|send|post|exfiltrat|发送|上传|外发|发到|传到|寄到)[^\n]{0,40}(https?://|url|外部|服务器|远端|remote server)"""),
            re("""curl[^\n]{0,60}(-d|--data|-F|--upload-file|-T)\b"""),
        ),
        PromptReviewPolicy.PRIVILEGE_ESCALATION_REQUEST to listOf(
            re("""\bsudo\b|\bdoas\b|as root|setuid|提权|root 权限|管理员权限"""),
            re("""chmod\s+[0-7]*7{2,}"""),
        ),
        PromptReviewPolicy.PERSISTENCE_REQUEST to listOf(
            re("""crontab|\bcron\b|launchd|LaunchAgents?|LaunchDaemons?|systemd|rc\.local|login item"""),
            re("""开机自启|定时任务|持久化|自启动"""),
            re("""(git|pre-commit|post-checkout)[ -]?hooks?"""),
        ),
        PromptReviewPolicy.APPROVAL_BYPASS_REQUEST to listOf(
            re("""(绕过|跳过|规避)[^\n]{0,8}(审批|审核|批准)|不要(等|走)?审批"""),
            re("""(?i)(bypass|skip|without)[^\n]{0,20}(approval|review|permission)"""),
            re("""(?i)ignore[^\n]{0,20}(previous|above|system|rules|instructions)|忽略[^\n]{0,10}(规则|指令|系统|以上)"""),
        ),
        PromptReviewPolicy.DESTRUCTIVE_OR_IRREVERSIBLE_REQUEST to listOf(
            re("""rm\s+-[a-z]*r[a-z]*f|\bmkfs\b|\bdd\s+if=|drop\s+table|truncate\s+table"""),
            re("""(git\s+)?push\s+(-f\b|--force)|reset\s+--hard\s+origin"""),
            re("""删库|全部删除|清空[^\n]{0,6}(目录|仓库|数据|磁盘)|不可逆|格式化(磁盘|硬盘)"""),
        ),
        PromptReviewPolicy.OBFUSCATED_INTENT to listOf(
            re("""base64\s*(-d|--decode|解码)[^\n]{0,30}(执行|运行|sh\b|bash\b|eval)"""),
            re("""(?i)reverse shell|反弹\s*shell|nc\s+-e\b"""),
            re("""[A-Za-z0-9+/=]{200,}"""), // an opaque blob this long has no place in a dev request
        ),
    )

    private fun re(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))
}
