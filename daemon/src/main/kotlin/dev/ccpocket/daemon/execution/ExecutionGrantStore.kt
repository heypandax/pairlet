package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.diagnostics.storageReadFailed
import dev.ccpocket.daemon.diagnostics.storageWriteFailed
import dev.ccpocket.daemon.review.ReviewFiles
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Target-side persistence for [ExecutionGrant]s: `~/.cc-pocket/execution-grants.json` (0600, atomic via
 * [ReviewFiles.write]) plus an APPEND-ONLY revocation tombstone log beside it (`…json.revoked`, 0600, one
 * JSON object per line, fsync'd per append). Its own files, so an older daemon never loads them.
 *
 * Bound to ONE target identity ([targetDaemonPub]): a row naming any other key is not this daemon's grant.
 *
 * Fail-closed rules:
 *  - UNREADABLE / UNDECODABLE / UNKNOWN VERSION / ANY ROW BREAKING [ExecutionPolicy.violation] / AN
 *    UNDECODABLE TOMBSTONE LINE → the store opens UNAVAILABLE ([unavailableReason] `store_unavailable`): no
 *    grants served, every write refused, files left untouched.
 *  - WIDENING changes (insert, bind, activate, scope revision) are persist-THEN-commit.
 *  - REVOKE (user decision 09-14): the REVOKED state is in force in memory immediately; then the tombstone
 *    is appended and the main file rewritten. If EITHER write fails, the WHOLE store becomes unavailable
 *    (`tombstone_write_failed` / `revoke_persist_pending`) — every handshake, approval and change is refused
 *    — until [retryIfDue] (exponential backoff) has written both. At load, every tombstoned grantId
 *    overrides the main file's state to REVOKED and the reconciled state is written back (unavailable if
 *    that write fails). A torn final tombstone line (no trailing newline) is an append that never returned
 *    success, and is ignored.
 *  - CLOCK HIGH-WATER: every decision reads [observeClock]; the mark is persisted with every main write.
 */
class ExecutionGrantStore private constructor(
    private val file: File?,
    private val tombstoneFile: File?,
    val targetDaemonPub: String,
) {

    @Serializable
    private data class Stored(val v: Int = VERSION, val clockHighWater: Long = 0, val grants: List<ExecutionGrant> = emptyList())

    @Serializable
    private data class Tombstone(val grantId: String, val reason: String, val at: Long)

    sealed interface Write {
        data class Ok(val grant: ExecutionGrant) : Write
        data object NotFound : Write
        data object Unavailable : Write
        data object PersistFailed : Write
        data class Refused(val code: String) : Write
    }

    private val lock = Any()
    private var rows: List<ExecutionGrant> = emptyList()
    private var highWater = 0L
    private var loadFailed = false
    private var pendingPersist = false
    private val pendingTombstones = ArrayList<Tombstone>()
    private var retryAt = 0L
    private var backoffMs = RETRY_INITIAL_MS

    val available: Boolean get() = synchronized(lock) { unavailableReasonLocked() == null }

    /** Why the store refuses everything right now, or null when it is usable. Stable diagnostic codes. */
    val unavailableReason: String? get() = synchronized(lock) { unavailableReasonLocked() }

    private fun unavailableReasonLocked(): String? = when {
        loadFailed -> "store_unavailable"
        pendingTombstones.isNotEmpty() -> "tombstone_write_failed"
        pendingPersist -> "revoke_persist_pending"
        else -> null
    }

    /** max(now, every clock value this store has observed or loaded). */
    fun observeClock(now: Long): Long = synchronized(lock) {
        if (now > highWater) highWater = now
        highWater
    }

    fun all(): List<ExecutionGrant> = synchronized(lock) { rows }

    fun byId(grantId: String): ExecutionGrant? = synchronized(lock) { rows.firstOrNull { it.grantId == grantId } }

    /** The grant (any state) whose link credential is [deviceId]. A deviceId binds at most one grant. */
    fun boundTo(deviceId: String): ExecutionGrant? = synchronized(lock) { rows.firstOrNull { it.sourceDeviceId == deviceId } }

    fun pendingByTicketHash(hash: String): ExecutionGrant? = synchronized(lock) {
        rows.firstOrNull { it.state == ExecutionGrantState.PENDING_REDEEM && it.ticketHash == hash }
    }

    fun insert(grant: ExecutionGrant): Write = synchronized(lock) {
        if (unavailableReasonLocked() != null) return Write.Unavailable
        if (rows.any { it.grantId == grant.grantId }) return Write.Refused("grant_id_conflict")
        ExecutionPolicy.violation(grant, targetDaemonPub)?.let { return Write.Refused("invariant_$it") }
        val next = rows + grant
        if (!persist(next)) return Write.PersistFailed
        rows = next
        Write.Ok(grant)
    }

    /** Persist-then-commit. [change] returning null refuses without writing. */
    fun widen(grantId: String, change: (ExecutionGrant) -> ExecutionGrant?): Write = synchronized(lock) {
        if (unavailableReasonLocked() != null) return Write.Unavailable
        val cur = rows.firstOrNull { it.grantId == grantId } ?: return Write.NotFound
        val upd = change(cur) ?: return Write.Refused("change_refused")
        ExecutionPolicy.violation(upd, targetDaemonPub)?.let { return Write.Refused("invariant_$it") }
        upd.sourceDeviceId?.let { dev ->
            if (rows.any { it.grantId != grantId && it.sourceDeviceId == dev }) return Write.Refused("device_already_bound")
        }
        val next = rows.map { if (it.grantId == grantId) upd else it }
        if (!persist(next)) return Write.PersistFailed
        rows = next
        Write.Ok(upd)
    }

    /**
     * Revoke [grantId]: REVOKED in memory now, then tombstone append, then main rewrite. [Write.Ok] only when
     * both writes landed; otherwise [Write.PersistFailed] and the store is unavailable until [retryIfDue]
     * succeeds. Still allowed while a previous revoke is pending (revocation must never be blocked).
     */
    fun revoke(grantId: String, reason: String, at: Long): Write = synchronized(lock) {
        if (loadFailed) return Write.Unavailable
        val cur = rows.firstOrNull { it.grantId == grantId } ?: return Write.NotFound
        if (cur.state.terminal) return if (unavailableReasonLocked() == null) Write.Ok(cur) else Write.PersistFailed
        val upd = cur.copy(state = ExecutionGrantState.REVOKED, endedReason = reason, ticketHash = null, ticketExpiresAt = null)
        rows = rows.map { if (it.grantId == grantId) upd else it }
        val tomb = Tombstone(grantId, reason, at)
        if (!appendTombstone(tomb)) pendingTombstones += tomb
        if (!persist(rows)) pendingPersist = true
        if (unavailableReasonLocked() == null) return Write.Ok(upd)
        retryAt = at + backoffMs
        log.warn("execution grant revoke for ${grantId.take(10)}… not durable yet (${unavailableReasonLocked()}) — store unavailable, retrying")
        Write.PersistFailed
    }

    /** Retry pending revoke writes once their backoff is due. True when the store is (now) usable. */
    fun retryIfDue(now: Long): Boolean = synchronized(lock) {
        if (loadFailed) return false
        if (pendingTombstones.isEmpty() && !pendingPersist) return true
        if (now < retryAt) return false
        val still = pendingTombstones.filterNot { appendTombstone(it) }
        pendingTombstones.clear()
        pendingTombstones += still
        if (pendingPersist && persist(rows)) pendingPersist = false
        if (unavailableReasonLocked() == null) {
            backoffMs = RETRY_INITIAL_MS
            retryAt = 0
            log.info("execution grant revoke writes recovered — store available again")
            true
        } else {
            backoffMs = minOf(backoffMs * 2, RETRY_MAX_MS)
            retryAt = now + backoffMs
            false
        }
    }

    private fun persist(next: List<ExecutionGrant>): Boolean {
        val f = file ?: return true
        val ok = ReviewFiles.write(f, PocketJson.encodeToString(Stored.serializer(), Stored(clockHighWater = highWater, grants = next)))
        if (ok) ensureTombstoneFile()
        return ok
    }

    /** Create the (empty) tombstone log 0600 while the directory is writable, so a later append needs only
     *  the file — not the directory — to be writable. Best effort. */
    private fun ensureTombstoneFile() {
        val t = tombstoneFile ?: return
        if (t.exists()) return
        runCatching {
            Files.createFile(t.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.recoverCatching { t.createNewFile() }
    }

    private fun appendTombstone(t: Tombstone): Boolean {
        val f = tombstoneFile ?: return true
        return runCatching {
            ensureTombstoneFile()
            val line = (PocketJson.encodeToString(Tombstone.serializer(), t) + "\n").encodeToByteArray()
            FileOutputStream(f, true).use { out ->
                out.write(line)
                out.fd.sync()
            }
            true
        }.getOrElse {
            storageWriteFailed(it)
            false
        }
    }

    companion object {
        const val VERSION = 1
        const val RETRY_INITIAL_MS = 1_000L
        const val RETRY_MAX_MS = 60_000L
        private val GRANT_ID = Regex("^xg_[A-Za-z0-9_-]{8,64}$")
        private val log = logger("ExecutionGrantStore")

        fun defaultPath(): File = ReviewFiles.path("execution-grants.json")

        fun tombstonePathFor(file: File): File = File(file.absoluteFile.parentFile, file.name + ".revoked")

        fun load(file: File, targetDaemonPub: String, tombstoneFile: File = tombstonePathFor(file)): ExecutionGrantStore =
            ExecutionGrantStore(file, tombstoneFile, targetDaemonPub).apply {
                synchronized(lock) {
                    val loaded = runCatching {
                        val stored = if (file.exists()) PocketJson.decodeFromString(Stored.serializer(), file.readText()) else Stored()
                        require(stored.v == VERSION) { "unsupported version" }
                        require(stored.clockHighWater >= 0) { "clock" }
                        stored.grants.forEach { g -> ExecutionPolicy.violation(g, targetDaemonPub)?.let { error("row invariant: $it") } }
                        require(stored.grants.map { it.grantId }.toSet().size == stored.grants.size) { "duplicate grant id" }
                        val bound = stored.grants.mapNotNull { it.sourceDeviceId }
                        require(bound.toSet().size == bound.size) { "device bound twice" }
                        stored to readTombstones(tombstoneFile)
                    }
                    loaded.onFailure {
                        storageReadFailed(it)
                        loadFailed = true
                        // log the class only: a decode message can echo file content
                        log.warn("${file.name} could not be read (${it::class.simpleName}) — execution grants UNAVAILABLE, files left untouched")
                    }.onSuccess { (stored, tombs) ->
                        highWater = stored.clockHighWater
                        val byId = tombs.associateBy { it.grantId }
                        rows = stored.grants.map { g ->
                            val t = byId[g.grantId]
                            if (t == null || g.state == ExecutionGrantState.REVOKED) g
                            else g.copy(state = ExecutionGrantState.REVOKED, endedReason = t.reason, ticketHash = null, ticketExpiresAt = null)
                        }
                        if (rows != stored.grants) {
                            log.warn("revocation tombstones override ${rows.zip(stored.grants).count { (a, b) -> a != b }} grant row(s) — reconciling")
                            if (!persist(rows)) pendingPersist = true
                        }
                    }
                }
            }

        private fun readTombstones(f: File): List<Tombstone> {
            if (!f.exists()) return emptyList()
            val text = f.readText()
            // the final element is "" after a trailing newline, or a torn append that never returned success
            val complete = text.split('\n').dropLast(1)
            return complete.filter { it.isNotBlank() }.map { line ->
                PocketJson.decodeFromString(Tombstone.serializer(), line).also { require(GRANT_ID.matches(it.grantId)) { "tombstone id" } }
            }
        }

        fun inMemory(targetDaemonPub: String): ExecutionGrantStore = ExecutionGrantStore(null, null, targetDaemonPub)
    }
}
