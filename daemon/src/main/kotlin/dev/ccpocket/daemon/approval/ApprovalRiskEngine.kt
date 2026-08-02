package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.util.logger
import java.util.ArrayDeque

/**
 * Deterministic risk radar (SMART-APPROVAL M3 / P1-P2): rule-based, sequence-aware, and ADVISORY —
 * its output rides [dev.ccpocket.protocol.PermissionRiskUpdated] as a card badge and never changes an
 * approval outcome, authority, or deadline. The design ladder is explicit: hard policies deny first,
 * these rules label what they can, and an optional model assessor (not wired in M3) only ever fills the
 * gap between them — no result here can downgrade a DENY or promote an auto-allow.
 *
 * Sequence awareness is the point (design: "单看无害、组合起来危险"): the engine keeps a short
 * per-conversation ledger of what already happened this turn window — sensitive reads, env harvesting,
 * compress/encode steps — so a later network egress upgrades to the exfil-chain code instead of being
 * judged alone. State is bounded (last [HISTORY_CAP] actions per conversation) and dies with the
 * conversation ([forget]).
 *
 * The ledger stores DERIVED flags only (which rule categories matched), never command text, file
 * contents or env values — the same minimization contract as History.
 */
class ApprovalRiskEngine {
    data class Risk(val level: String, val reasonCodes: List<String>, val reason: String)

    private class Seen(
        val credRead: Boolean,
        val envHarvest: Boolean,
        val packed: Boolean,
        val network: Boolean,
    )

    private val log = logger("Risk")
    private val history = HashMap<String, ArrayDeque<Seen>>()
    private val lock = Any()

    /**
     * Record + assess one action. Returns a [Risk] ONLY when a rule fired (MEDIUM and up) — quiet
     * actions produce no badge (advisory mode must not spray LOW on every card). Any internal failure
     * returns UNKNOWN, never a silent LOW (design铁律: 未知不等于安全).
     */
    fun observe(convoId: String, tool: String, command: String?, targets: List<String>): Risk? = runCatching {
        val text = command.orEmpty()
        val paths = targets + extractPathsFromCommand(text)

        val credRead = paths.any { sensitivePath(it) } || CRED_READ_CMD.containsMatchIn(text)
        val envHarvest = ENV_HARVEST.containsMatchIn(text)
        val packed = PACK_OR_ENCODE.containsMatchIn(text)
        val network = tool == "WebFetch" || tool == "WebSearch" || NET_EGRESS.containsMatchIn(text)
        val persistence = paths.any { persistencePath(it) } || PERSISTENCE_CMD.containsMatchIn(text)
        val forcePush = FORCE_PUSH.containsMatchIn(text)

        val prior = synchronized(lock) {
            val q = history.getOrPut(convoId) { ArrayDeque() }
            val snapshot = Seen(
                credRead = q.any { it.credRead } || credRead,
                envHarvest = q.any { it.envHarvest } || envHarvest,
                packed = q.any { it.packed } || packed,
                network = q.any { it.network } || network,
            )
            q.addLast(Seen(credRead, envHarvest, packed, network))
            while (q.size > HISTORY_CAP) q.removeFirst()
            snapshot
        }

        val codes = mutableListOf<String>()
        if (credRead) codes += "cred-read"
        if (envHarvest) codes += "env-harvest"
        if (packed) codes += "pack-encode"
        if (network) codes += "net-egress"
        if (persistence) codes += "persistence-write"
        if (forcePush) codes += "force-push"

        // ── sequence upgrades (the whole point): earlier steps color the current one ──
        val exfilChain = network && (prior.credRead || prior.envHarvest || prior.packed)
        if (exfilChain) codes += "exfil-chain"

        when {
            exfilChain || persistence ->
                Risk("high", codes, if (persistence) "writes a file that executes for the owner later" else "network egress after sensitive read/collect steps")
            credRead || envHarvest || forcePush ->
                Risk("medium", codes, when {
                    forcePush -> "force-overwrite push semantics"
                    envHarvest -> "collects environment/credential material"
                    else -> "touches a credential-sensitive path"
                })
            network && packed ->
                Risk("medium", codes, "network access after a compress/encode step")
            else -> null // nothing fired — no badge (advisory mode stays quiet on ordinary work)
        }
    }.getOrElse { e ->
        log.warn("risk assessment failed for $convoId/$tool — reporting UNKNOWN, never silent-LOW", e)
        Risk("unknown", listOf("assess-error"), "risk could not be reliably assessed")
    }

    /** Conversation closed — its sequence ledger dies with it. */
    fun forget(convoId: String) {
        synchronized(lock) { history.remove(convoId) }
    }

    private fun sensitivePath(p: String): Boolean = SENSITIVE_PATH.containsMatchIn(p)
    private fun persistencePath(p: String): Boolean = PERSISTENCE_PATH.containsMatchIn(p)

    /** Cheap token scan: absolute/tilde path-looking tokens inside a shell command line. */
    private fun extractPathsFromCommand(cmd: String): List<String> =
        if (cmd.isEmpty()) emptyList()
        else cmd.split(' ', '\t', '\n').filter { it.startsWith("/") || it.startsWith("~") || it.startsWith("$HOME_VAR") }

    private companion object {
        const val HISTORY_CAP = 50
        const val HOME_VAR = "\$HOME"

        val SENSITIVE_PATH = Regex(
            """(^|/)(\.ssh|\.aws|\.gnupg|\.netrc|\.npmrc|\.pypirc|\.docker/config\.json|\.kube|\.cc-pocket|\.env(\.[A-Za-z0-9]+)?$|credentials|id_rsa|id_ed25519|keychain)""",
            RegexOption.IGNORE_CASE,
        )
        val PERSISTENCE_PATH = Regex(
            """(^|/)(\.git/hooks|\.git/config$|\.claude/settings(\.local)?\.json|\.zshrc$|\.bashrc$|\.bash_profile$|\.profile$|\.envrc$|authorized_keys$)""",
        )
        val CRED_READ_CMD = Regex("""security\s+find-generic-password|gpg\s+--export|ssh-add\s+-L""")
        val ENV_HARVEST = Regex("""(^|[;&|]\s*)(env|printenv)(\s|$)|cat\s+[^ ]*\.env""")
        val PACK_OR_ENCODE = Regex("""\b(tar|zip|gzip|xz|base64|openssl\s+enc)\b""")
        val NET_EGRESS = Regex("""\b(curl|wget|scp|rsync|nc|ncat|ftp|sftp)\b|git\s+push""")
        val FORCE_PUSH = Regex("""git\s+push\s+[^|;&]*(--force|-f\b)|--force-with-lease""")
        val PERSISTENCE_CMD = Regex("""git\s+config\s+(--global\s+)?core\.(pager|editor|sshCommand)|crontab\s""")
    }
}
