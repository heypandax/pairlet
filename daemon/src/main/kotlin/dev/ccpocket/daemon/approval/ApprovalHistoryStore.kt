package dev.ccpocket.daemon.approval

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.feishu.SecretRedactor
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ApprovalHistoryItem
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * §18.2 P2-2: the RECOVERABLE approval history — every human decision and every grant/rule-covered
 * auto-run lands here as one JSONL line, so a client that was offline (or a daemon that restarted) can
 * still answer "what ran under which authorization" ([recent] serves the additive
 * `FetchApprovalHistory` frame).
 *
 * Minimization is enforced again at THIS persistence boundary: Bash arguments are discarded (even a
 * generic argument can be a secret that pattern redaction cannot recognize), every other string is
 * scrubbed and capped, and old rows are re-sanitized on load. The file is born 0600 where POSIX permits.
 * It rotates at [MAX_LINES], keeping the newest half; history is an audit aid, not an unbounded archive.
 */
class ApprovalHistoryStore(private val file: File) {
    private val log = logger("ApprovalHistory")
    private val lock = Any()

    fun append(item: ApprovalHistoryItem) {
        runCatching {
            synchronized(lock) {
                ensureSecureFile()
                file.appendText(JSON.encodeToString(ApprovalHistoryItem.serializer(), sanitize(item)) + "\n")
                if (countLines() > MAX_LINES) rotate()
            }
        }.onFailure { log.warn("history append failed: ${it.message}") }
    }

    /** Newest-first page for the account-wide history view. Corrupt lines are skipped, never a crash. */
    fun recent(limit: Int, maxBytes: Int = MAX_PAGE_BYTES): List<ApprovalHistoryItem> = runCatching {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val newest = file.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSON.decodeFromString(ApprovalHistoryItem.serializer(), line) }.getOrNull() }
                .map(::sanitize) // also protects rows written by a pre-hardening daemon
                .take(limit.coerceIn(1, MAX_PAGE))
            val page = ArrayList<ApprovalHistoryItem>()
            var encodedBytes = PAGE_OVERHEAD_BYTES
            for (item in newest) {
                val itemBytes = JSON.encodeToString(ApprovalHistoryItem.serializer(), item).encodeToByteArray().size + 1
                if (encodedBytes + itemBytes > maxBytes.coerceAtLeast(PAGE_OVERHEAD_BYTES + 1)) break
                page += item
                encodedBytes += itemBytes
            }
            page
        }
    }.getOrElse { emptyList() }

    private fun countLines(): Int = if (file.exists()) file.readLines().size else 0

    private fun rotate() {
        val keep = file.readLines().takeLast(MAX_LINES / 2)
        replaceSecurely(keep)
    }

    /** Existing installations may contain pre-hardening rows and a 0644 file. Fix both at daemon load. */
    private fun hardenExisting() = runCatching {
        synchronized(lock) {
            if (!file.exists()) return@synchronized
            setOwnerOnly(file)
            val safeLines = file.readLines().mapNotNull { line ->
                runCatching { JSON.decodeFromString(ApprovalHistoryItem.serializer(), line) }.getOrNull()
                    ?.let(::sanitize)
                    ?.let { JSON.encodeToString(ApprovalHistoryItem.serializer(), it) }
            }.takeLast(MAX_LINES)
            replaceSecurely(safeLines)
        }
    }.onFailure { log.warn("history hardening failed: ${it.message}") }

    private fun ensureSecureFile() {
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        if (!file.exists()) {
            val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            runCatching { Files.createFile(file.toPath(), ownerOnly) }
                .getOrElse { if (file.exists()) file.toPath() else Files.createFile(file.toPath()) }
        }
        setOwnerOnly(file)
    }

    /** Temp + atomic replace where supported, so rotation/migration never leaves a half-written audit file. */
    private fun replaceSecurely(lines: List<String>) {
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val tmp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            runCatching { Files.createFile(tmp.toPath(), ownerOnly) }
                .getOrElse { if (tmp.exists()) tmp.toPath() else Files.createFile(tmp.toPath()) }
            tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            setOwnerOnly(tmp)
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.recoverCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
            setOwnerOnly(file)
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    private fun sanitize(item: ApprovalHistoryItem): ApprovalHistoryItem = item.copy(
        eventId = scrub(item.eventId, ID_MAX),
        convoId = scrub(item.convoId, ID_MAX),
        source = scrub(item.source, LABEL_MAX),
        tool = scrub(item.tool, LABEL_MAX),
        summary = safeSummary(item.tool, item.summary),
        basis = scrub(item.basis, BASIS_MAX),
        decision = scrub(item.decision, LABEL_MAX),
        taskId = item.taskId?.let { scrub(it, ID_MAX) },
        grantId = item.grantId?.let { scrub(it, ID_MAX) },
    )

    private fun scrub(value: String, max: Int): String =
        SecretRedactor.redact(value).first.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").take(max)

    private fun setOwnerOnly(target: File) {
        runCatching {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val MAX_LINES = 5_000
        const val MAX_PAGE = 500
        const val MAX_PAGE_BYTES = 3 * 1024 * 1024 // leaves ample room below relay MAX_FRAME=4 MiB
        private const val PAGE_OVERHEAD_BYTES = 1024
        private const val ID_MAX = 160
        private const val LABEL_MAX = 80
        private const val BASIS_MAX = 160

        /** Persisted/display summary. Bash command text is never retained; shell syntax makes even
         *  "executable-only" extraction unsafe (for example `DB_CREDENTIAL=secret ./deploy`). */
        fun safeSummary(tool: String, @Suppress("UNUSED_PARAMETER") raw: String?): String {
            val safeTool = SecretRedactor.redact(tool).first
                .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").take(LABEL_MAX)
                .ifBlank { "Tool" }
            return safeTool
        }

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "approval-history.jsonl")
        fun load(path: File = defaultPath()): ApprovalHistoryStore =
            ApprovalHistoryStore(path).apply { hardenExisting() }
    }
}
