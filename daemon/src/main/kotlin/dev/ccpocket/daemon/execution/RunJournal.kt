package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.diagnostics.storageReadFailed
import dev.ccpocket.daemon.diagnostics.storageWriteFailed
import dev.ccpocket.daemon.review.ReviewFiles
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.EXECUTION_OUTPUT_MAX_BYTES
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Issue #367 G1 — the TARGET's authoritative record of one remote run.
 *
 * ```
 * ACCEPTED ─► STARTING ─► RUNNING ⇄ WAITING_APPROVAL
 *                              └──► COMPLETED | FAILED | CANCELLED | EXPIRED
 * ```
 * A process that dies while a run is STARTING or RUNNING cannot prove whether the work happened, so
 * recovery moves it to [ExecutionRunState.INTERRUPTED_UNKNOWN] and NEVER re-runs it (handoff doc
 * "可靠任务与重试": exactly-once over arbitrary command side effects is not something this can promise).
 *
 * Terminal states are MONOTONIC: a late RUNNING packet can never overwrite a COMPLETED row.
 */
@Serializable
enum class ExecutionRunState {
    @SerialName("accepted") ACCEPTED,
    @SerialName("starting") STARTING,
    @SerialName("running") RUNNING,
    @SerialName("waiting_approval") WAITING_APPROVAL,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("expired") EXPIRED,
    @SerialName("interrupted_unknown") INTERRUPTED_UNKNOWN,
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED || this == INTERRUPTED_UNKNOWN

    val wire: String get() = ExecutionRunState.serializer().descriptor.getElementName(ordinal)
}

/**
 * One journaled run. Deliberately WITHOUT the prompt: the source's task text is private data the target
 * has no reason to keep past the live session, and [payloadHash] is all the idempotency check needs
 * ("same key, different payload → conflict"). [output] is the assistant's final text, capped at
 * [EXECUTION_OUTPUT_MAX_BYTES] whole UTF-8 bytes with [truncated] set — never the full transcript.
 */
@Serializable
data class ExecutionRun(
    val v: Int = RunJournal.VERSION,
    val runId: String,
    val grantId: String,
    val requestId: String,
    /** The grant revision the submission was authorised under. A revision bump invalidates queued work. */
    val revision: Long,
    val workspaceAlias: String,
    val agent: AgentKind,
    /** min(requested, grant ceiling); never BYPASS_PERMISSIONS (see [ExecutionPolicy.ALLOWED_CEILINGS]). */
    val mode: PermissionMode,
    val model: String? = null,
    val payloadHash: String,
    val state: ExecutionRunState,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** Wall-clock cut-off derived from the grant's runTimeoutMs when the run actually STARTS. */
    val deadlineAt: Long? = null,
    val convoId: String? = null,
    val sessionId: String? = null,
    val approvalPending: Boolean = false,
    /** When cancel was RECEIVED — distinct from [endedAt], which means the process is provably gone. */
    val cancelRequestedAt: Long? = null,
    /** A stable, log-safe code or a short agent-supplied summary. Never a prompt, key or absolute path. */
    val error: String? = null,
    val output: String = "",
    val truncated: Boolean = false,
) {
    val outputBytes: Int get() = output.toByteArray(Charsets.UTF_8).size
}

/**
 * `~/.cc-pocket/execution-runs/<grantId>/<runId>.json` (0600, atomic + fsync) plus an APPEND-ONLY
 * `<runId>.events` line log beside it, and one per-grant `_budget.json` counter that retention never
 * resets (a spent request budget must not come back by outliving its journal row).
 *
 * Every mutation persists BEFORE it is visible: [accept] returns only once the accept record and its
 * idempotency key are on disk, which is what makes "ACK after durable accept" true rather than hopeful.
 */
class RunJournal(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = logger("RunJournal")
    private val lock = Any()

    /** runId -> row. Loaded once at construction; the disk is the truth, this is the index over it. */
    private val rows = LinkedHashMap<String, ExecutionRun>()

    sealed interface Accept {
        /** [duplicate] = this exact (grantId, requestId) was already accepted; [run] is the ORIGINAL row. */
        data class Ok(val run: ExecutionRun, val duplicate: Boolean) : Accept
        data class Refused(val code: String) : Accept
    }

    init {
        recover()
    }

    // ------------------------------------------------------------------ reads

    fun byId(runId: String): ExecutionRun? = synchronized(lock) { rows[runId] }

    fun byKey(grantId: String, requestId: String): ExecutionRun? = synchronized(lock) {
        rows.values.firstOrNull { it.grantId == grantId && it.requestId == requestId }
    }

    fun ofGrant(grantId: String): List<ExecutionRun> = synchronized(lock) { rows.values.filter { it.grantId == grantId } }

    /** Every non-terminal run of [grantId] — queued (ACCEPTED) included, so the caller can split them. */
    fun liveOfGrant(grantId: String): List<ExecutionRun> = synchronized(lock) {
        rows.values.filter { it.grantId == grantId && !it.state.terminal }
    }

    /** Every non-terminal run across all grants (the maintenance tick's working set). */
    fun live(): List<ExecutionRun> = synchronized(lock) { rows.values.filterNot { it.state.terminal } }

    fun budgetUsed(grantId: String): Int = synchronized(lock) { readBudget(grantId) }

    // ------------------------------------------------------------------ writes

    /**
     * Durably record an accepted submission under the idempotency key (grantId, requestId).
     *
     *  - the SAME key with the SAME payload returns the original row with `duplicate = true` — a retried
     *    submit must never start a second run;
     *  - the same key with a DIFFERENT payload is `run_conflict`: silently re-pointing an existing run at
     *    another prompt/workspace/agent is the one thing a retry must never be able to do;
     *  - a failed disk write is `run_persist_failed` and NOTHING is accepted (no in-memory row survives).
     */
    fun accept(
        grantId: String,
        requestId: String,
        revision: Long,
        workspaceAlias: String,
        agent: AgentKind,
        mode: PermissionMode,
        model: String?,
        payloadHash: String,
        budgetCeiling: Int,
    ): Accept = synchronized(lock) {
        if (!ExecutionInvite.validGrantId(grantId)) return Accept.Refused("grant_unknown")
        if (!validRequestId(requestId)) return Accept.Refused("request_id_invalid")
        rows.values.firstOrNull { it.grantId == grantId && it.requestId == requestId }?.let { existing ->
            return if (existing.payloadHash == payloadHash) Accept.Ok(existing, duplicate = true)
            else Accept.Refused("run_conflict")
        }
        val used = readBudget(grantId)
        if (used >= budgetCeiling) return Accept.Refused("budget_exhausted")
        val t = now()
        val run = ExecutionRun(
            runId = newRunId(),
            grantId = grantId,
            requestId = requestId,
            revision = revision,
            workspaceAlias = workspaceAlias,
            agent = agent,
            mode = mode,
            model = model,
            payloadHash = payloadHash,
            state = ExecutionRunState.ACCEPTED,
            createdAt = t,
            updatedAt = t,
        )
        // budget FIRST: a crash between the two leaves a spent budget slot and no run, which is the safe
        // direction. The reverse would let a crash loop mint unlimited runs against a spent grant.
        if (!writeBudget(grantId, used + 1)) return Accept.Refused("run_persist_failed")
        if (!persist(run)) return Accept.Refused("run_persist_failed")
        rows[run.runId] = run
        event(run, "accepted")
        Accept.Ok(run, duplicate = false)
    }

    /**
     * Move [runId] to [to], applying the legality table and terminal monotonicity. Returns the row in
     * force AFTER the call (unchanged when the transition was refused), or null when there is no such run.
     *
     * Repeating a transition already in force is a NO-OP that still returns Ok — duplicate cancels and
     * duplicate completions are ordinary on a retrying link.
     */
    fun transition(
        runId: String,
        to: ExecutionRunState,
        error: String? = null,
        convoId: String? = null,
        sessionId: String? = null,
        approvalPending: Boolean? = null,
        deadlineAt: Long? = null,
        note: String? = null,
    ): ExecutionRun? = synchronized(lock) {
        val cur = rows[runId] ?: return null
        if (cur.state == to && approvalPending == null && convoId == null && sessionId == null && error == null) return cur
        if (!legal(cur.state, to)) {
            log.info("run ${runId.take(10)}… refused ${cur.state.wire} → ${to.wire} (terminal monotonicity)")
            return cur
        }
        val t = now()
        val upd = cur.copy(
            state = to,
            updatedAt = t,
            startedAt = cur.startedAt ?: t.takeIf { to == ExecutionRunState.STARTING },
            endedAt = cur.endedAt ?: t.takeIf { to.terminal },
            deadlineAt = deadlineAt ?: cur.deadlineAt,
            convoId = convoId ?: cur.convoId,
            sessionId = sessionId ?: cur.sessionId,
            approvalPending = if (to.terminal) false else approvalPending ?: (to == ExecutionRunState.WAITING_APPROVAL),
            error = error ?: cur.error,
        )
        if (!persist(upd)) {
            // The row is NOT updated in memory: a state nobody could write is a state that did not happen.
            log.warn("run ${runId.take(10)}… could not persist ${to.wire} — keeping ${cur.state.wire}")
            return cur
        }
        rows[runId] = upd
        event(upd, note ?: to.wire)
        upd
    }

    /** Record that a cancel REQUEST arrived (distinct from the process provably ending). */
    fun noteCancelRequested(runId: String): ExecutionRun? = synchronized(lock) {
        val cur = rows[runId] ?: return null
        if (cur.state.terminal || cur.cancelRequestedAt != null) return cur
        val upd = cur.copy(cancelRequestedAt = now(), updatedAt = now())
        if (!persist(upd)) return cur
        rows[runId] = upd
        event(upd, "cancel_requested")
        upd
    }

    /**
     * Append the agent's final text. Truncates at [EXECUTION_OUTPUT_MAX_BYTES] on a whole UTF-8 CHARACTER
     * boundary (a byte-count cut can split a multi-byte sequence and produce a replacement char on the
     * source's screen) and sets [ExecutionRun.truncated]; the overflow is never written to disk.
     */
    fun appendOutput(runId: String, text: String): ExecutionRun? = synchronized(lock) {
        if (text.isEmpty()) return rows[runId]
        val cur = rows[runId] ?: return null
        val (merged, cut) = clampUtf8(cur.output + text, EXECUTION_OUTPUT_MAX_BYTES)
        if (merged == cur.output && cut == cur.truncated) return cur
        val upd = cur.copy(output = merged, truncated = cur.truncated || cut, updatedAt = now())
        if (!persist(upd)) return cur
        rows[runId] = upd
        upd
    }

    /**
     * Boot recovery. Every STARTING/RUNNING/WAITING_APPROVAL row belongs to a process that is gone: it
     * becomes INTERRUPTED_UNKNOWN and is never restarted. An ACCEPTED row never reached a backend, so it
     * is honestly FAILED (`interrupted_before_start`).
     */
    private fun recover() = synchronized(lock) {
        rows.clear()
        val dirs = root.listFiles()?.filter { it.isDirectory && ExecutionInvite.validGrantId(it.name) } ?: emptyList()
        for (dir in dirs) {
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.name != BUDGET } ?: emptyList()
            for (f in files) {
                val row = runCatching { PocketJson.decodeFromString(ExecutionRun.serializer(), f.readText()) }
                    .onFailure { storageReadFailed(it) }
                    .getOrNull()
                if (row == null || row.v != VERSION || row.grantId != dir.name || row.runId != f.nameWithoutExtension) {
                    // Quarantine rather than delete: a row we cannot read is still the only evidence that
                    // something ran on this machine.
                    runCatching { Files.move(f.toPath(), File(dir, f.name + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
                    log.warn("execution run journal row ${f.name} unreadable — quarantined")
                    continue
                }
                rows[row.runId] = row
            }
        }
        val orphans = rows.values.filter { !it.state.terminal }
        for (o in orphans) {
            val to = if (o.state == ExecutionRunState.ACCEPTED) ExecutionRunState.FAILED else ExecutionRunState.INTERRUPTED_UNKNOWN
            val reason = if (to == ExecutionRunState.FAILED) "interrupted_before_start" else "interrupted_unknown"
            val upd = o.copy(state = to, error = o.error ?: reason, endedAt = o.endedAt ?: now(), updatedAt = now(), approvalPending = false)
            if (persist(upd)) {
                rows[o.runId] = upd
                event(upd, reason)
            }
        }
        if (orphans.isNotEmpty()) {
            log.warn("${orphans.size} execution run(s) did not survive the last shutdown — marked interrupted, NOT re-run")
        }
    }

    /** Drop terminal rows older than [retentionMs]. The per-grant budget counter is deliberately kept. */
    fun purge(retentionMs: Long = RETENTION_MS): Int = synchronized(lock) {
        val cutoff = now() - retentionMs
        val gone = rows.values.filter { it.state.terminal && (it.endedAt ?: it.updatedAt) < cutoff }
        for (r in gone) {
            runCatching { recordFile(r.grantId, r.runId).delete() }
            runCatching { eventsFile(r.grantId, r.runId).delete() }
            rows.remove(r.runId)
        }
        gone.size
    }

    // ------------------------------------------------------------------ disk

    private fun grantDir(grantId: String) = File(root, grantId)
    private fun recordFile(grantId: String, runId: String) = File(grantDir(grantId), "$runId.json")
    private fun eventsFile(grantId: String, runId: String) = File(grantDir(grantId), "$runId.events")

    private fun persist(run: ExecutionRun): Boolean =
        writeAtomic(recordFile(run.grantId, run.runId), PocketJson.encodeToString(ExecutionRun.serializer(), run))

    /** One append-only line per state change: the row says WHERE a run is, this says HOW it got there. */
    private fun event(run: ExecutionRun, note: String) {
        val f = eventsFile(run.grantId, run.runId)
        val line = PocketJson.encodeToString(
            RunEvent.serializer(),
            RunEvent(at = run.updatedAt, state = run.state.wire, note = note),
        ) + "\n"
        runCatching {
            ensureOwnerOnly(f)
            FileOutputStream(f, true).use { out ->
                out.write(line.encodeToByteArray())
                out.fd.sync()
            }
        }.onFailure { storageWriteFailed(it) }
    }

    @Serializable
    private data class RunEvent(val at: Long, val state: String, val note: String)

    @Serializable
    private data class Budget(val v: Int = VERSION, val accepted: Int = 0)

    private fun budgetFile(grantId: String) = File(grantDir(grantId), BUDGET)

    private fun readBudget(grantId: String): Int {
        val f = budgetFile(grantId)
        if (!f.isFile) return 0
        return runCatching { PocketJson.decodeFromString(Budget.serializer(), f.readText()) }
            .getOrNull()?.takeIf { it.v == VERSION }?.accepted
        // an unreadable counter must not read as "budget free": treat it as fully spent
            ?: Int.MAX_VALUE
    }

    private fun writeBudget(grantId: String, value: Int): Boolean =
        writeAtomic(budgetFile(grantId), PocketJson.encodeToString(Budget.serializer(), Budget(accepted = value)))

    /**
     * 0600 + atomic + fsync. [ReviewFiles.write] is atomic but deliberately fsync-free; a run journal is
     * the record of what a REMOTE caller made this machine do, so it must survive a power cut, not merely
     * a torn write.
     */
    private fun writeAtomic(file: File, text: String): Boolean = runCatching {
        val parent = file.absoluteFile.parentFile
        Files.createDirectories(parent.toPath())
        ensureOwnerOnly(parent)
        val tmp = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp").toFile()
        ownerOnly(tmp)
        try {
            tmp.writeText(text)
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // fsync the DIRECTORY too: without it the rename itself can be lost on a crash
            runCatching { FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
        ownerOnly(file)
        true
    }.getOrElse {
        storageWriteFailed(it)
        log.warn("could not persist ${file.name}")
        false
    }

    private fun ensureOwnerOnly(f: File) {
        if (!f.exists()) {
            if (f.parentFile != null) Files.createDirectories(f.absoluteFile.parentFile.toPath())
            runCatching { f.createNewFile() }
        }
        ownerOnly(f)
    }

    private fun ownerOnly(file: File) {
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) == null) return
        val perms = if (file.isDirectory) "rwx------" else "rw-------"
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms)) }
    }

    companion object {
        const val VERSION = 1
        /** 7 days (design §4). Private task data has no reason to outlive the caller's interest in it. */
        const val RETENTION_MS = 7L * 24 * 3600_000
        private const val BUDGET = "_budget.json"
        private val REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val RNG = SecureRandom()

        fun defaultRoot(): File = ReviewFiles.path("execution-runs")

        fun validRequestId(id: String): Boolean = REQUEST_ID.matches(id)

        fun newRunId(): String = "xr_" + dev.ccpocket.daemon.review.b64(ByteArray(16).also { RNG.nextBytes(it) })

        /** The idempotency payload digest. Covers everything a retry must not be able to change. */
        @OptIn(ExperimentalStdlibApi::class)
        /**
         * The identity of a SUBMISSION, for the (grantId, requestId) idempotency key.
         *
         * [requestedMode] is the mode the SOURCE ASKED FOR — nullable, and deliberately NOT the mode the
         * target clamped to (wire contract 2 in `Execution.kt`). The clamp is a function of the grant's
         * ceiling, so hashing it would change this value the moment the owner revised the grant, and an
         * honest retry of a byte-identical request would come back `run_conflict`.
         */
        fun payloadHash(
            grantId: String,
            requestId: String,
            workspaceAlias: String,
            agent: AgentKind,
            requestedMode: PermissionMode?,
            model: String?,
            prompt: String,
        ): String {
            val md = MessageDigest.getInstance("SHA-256")
            listOf(grantId, requestId, workspaceAlias, agent.name, requestedMode?.name.orEmpty(), model.orEmpty(), prompt).forEach {
                md.update(it.encodeToByteArray())
                md.update(0)
            }
            return md.digest().toHexString()
        }

        /** Legal edges. Everything else — above all "out of a terminal state" — is refused. */
        fun legal(from: ExecutionRunState, to: ExecutionRunState): Boolean {
            if (from.terminal) return false
            if (from == to) return true
            return when (to) {
                ExecutionRunState.ACCEPTED -> false
                ExecutionRunState.STARTING -> from == ExecutionRunState.ACCEPTED
                ExecutionRunState.RUNNING -> from == ExecutionRunState.STARTING || from == ExecutionRunState.WAITING_APPROVAL
                ExecutionRunState.WAITING_APPROVAL -> from == ExecutionRunState.RUNNING || from == ExecutionRunState.STARTING
                else -> true // any live state may end
            }
        }

        /**
         * Cut [text] to at most [maxBytes] UTF-8 bytes ON A CHARACTER BOUNDARY; second = "it was cut".
         * Walking back off continuation bytes (`10xxxxxx`) is what keeps a truncated CJK/emoji tail from
         * arriving at the source as a replacement character.
         */
        fun clampUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
            if (maxBytes <= 0) return "" to text.isNotEmpty()
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text to false
            var cut = maxBytes
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            return String(bytes, 0, cut, Charsets.UTF_8) to true
        }
    }
}
