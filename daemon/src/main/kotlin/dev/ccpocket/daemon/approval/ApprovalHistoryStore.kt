package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ApprovalHistoryItem
import kotlinx.serialization.json.Json
import java.io.File

/**
 * §18.2 P2-2: the RECOVERABLE approval history — every human decision and every grant/rule-covered
 * auto-run lands here as one JSONL line, so a client that was offline (or a daemon that restarted) can
 * still answer "what ran under which authorization" ([recent] serves the additive
 * `FetchApprovalHistory` frame).
 *
 * Minimization is structural: entries carry the same REDACTED summary the autorun chip does (the rule —
 * two tokens / tool family), the basis, the decision and ids — never stdout, file bodies, diffs,
 * prompts, full command lines or secrets. Capped: the file rotates at [MAX_LINES], keeping the newest
 * half; approval history is an audit aid, not an unbounded archive (design §11.3).
 */
class ApprovalHistoryStore(private val file: File) {
    private val log = logger("ApprovalHistory")
    private val lock = Any()

    fun append(item: ApprovalHistoryItem) {
        runCatching {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText(JSON.encodeToString(ApprovalHistoryItem.serializer(), item) + "\n")
                if (countLines() > MAX_LINES) rotate()
            }
        }.onFailure { log.warn("history append failed: ${it.message}") }
    }

    /** Newest-first page for the account-wide history view. Corrupt lines are skipped, never a crash. */
    fun recent(limit: Int): List<ApprovalHistoryItem> = runCatching {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            file.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSON.decodeFromString(ApprovalHistoryItem.serializer(), line) }.getOrNull() }
                .take(limit.coerceIn(1, MAX_PAGE))
                .toList()
        }
    }.getOrElse { emptyList() }

    private fun countLines(): Int = if (file.exists()) file.readLines().size else 0

    private fun rotate() {
        val keep = file.readLines().takeLast(MAX_LINES / 2)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val MAX_LINES = 5_000
        const val MAX_PAGE = 500

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "approval-history.jsonl")
        fun load(path: File = defaultPath()): ApprovalHistoryStore = ApprovalHistoryStore(path)
    }
}
