package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Who a rewound/forked session came from (issue #282, docs/design/REWIND-FORK.md §5).
 *
 * The CLI's `--fork-session` writes NO blood line into the copy — probed on 2.1.228, the parent session
 * id appears nowhere in the forked transcript, and the only residue is that early chain entries share
 * their uuids with the original. So a branch is indistinguishable from an unrelated session unless the
 * daemon records the edge itself, and without that record the session list grows a row nobody can
 * account for (§2 铁律 2) and a rewind's superseded original can never be folded away (铁律 3).
 *
 * One `parentSid<TAB>childSid<TAB>anchorSeq<TAB>mode` line per branch, in the same directory and with
 * the same handling as [SpawnedSessions]' journal — owner-only permissions (it maps session ids to each
 * other), a [MAX_ENTRIES] cap so a long-lived daemon can't grow it without bound, a lock around every
 * access, and a strict id pattern applied on the way IN as well as on the way out, so a tampered line
 * can never steer a later lookup. Append-only and idempotent per edge: a branch is written exactly once,
 * at the [dev.ccpocket.protocol.SessionLive] where the CLI first reports the forked id.
 *
 * Losing this file degrades gracefully and on purpose: the sessions themselves are untouched, they
 * simply read as unrelated peers again. Nothing here is authoritative over a transcript.
 */
object RewindLineage {
    private val log = logger("RewindLineage")
    private const val MAX_ENTRIES = 500

    /** Guards ledger access: [note] appends from conversation pumps while scans read. */
    private val lock = Any()

    fun defaultLedger(): File = File(Identity.defaultPath().parentFile, "rewind-lineage.tsv")

    // session ids are UUIDs; anything else is tampering or corruption. Filtered on write AND on read
    // (mirrors SpawnedSessions' SESSION_ID guard) so a hostile line can never reach a consumer.
    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * One recorded branch.
     *
     * [anchorSeq] is the LINE NUMBER — as the parent transcript stood at the moment of the operation — of
     * the user message the cut was made at. Here in the ledger it is an audit/display value only ("forked
     * at turn N", diagnosing a bad cut after the fact). Two ways it has already been misread:
     *
     *  - **It is not a count of retained lines.** It addresses a row; it does not measure a length. Lining
     *    it up against the child transcript's line count and finding it "one off" is comparing two
     *    different quantities, not evidence of an off-by-one.
     *  - **It drifts, legitimately.** [TranscriptPatcher.unhide] rewrites a transcript in place and drops
     *    harness noise (pure task-notification turns, `queue-operation` rows, skill/command injections),
     *    so a line number journalled before that pass no longer addresses the same row after it. A stored
     *    seq that no longer matches the file is expected, not corruption.
     *
     * Truncation correctness never rests on this number. The cut handed to the CLI is
     * `--resume-session-at <uuid>` ([RewindPlanner.Plan.anchorUuid]) — a message uuid, which survives the
     * rewrite. The seq is load-bearing in exactly ONE place, and it is not this one: [RewindPlanner.plan]
     * requires the caller's (uuid, seq) pair to match the SAME row, as a staleness guard against a phone
     * acting on a replay that has since moved, and refuses with [dev.ccpocket.protocol.RewindRefusal.STALE]
     * otherwise. So: if this recorded number ever disagrees with a transcript, the number is the stale
     * thing — do not "fix" the cut to match it.
     */
    data class Entry(val parentSid: String, val childSid: String, val anchorSeq: Long, val mode: String)

    /** Journal one branch. Idempotent per (parent, child): a relaunch of the same conversation that
     *  re-reports the same forked id must not add a second line. Never throws — a ledger write failing
     *  is a cosmetic loss (the sessions are fine), so it must not take a live rewind down with it. */
    fun note(parentSid: String, childSid: String, anchorSeq: Long, mode: String, ledger: File = defaultLedger()) {
        if (!SESSION_ID.matches(parentSid) || !SESSION_ID.matches(childSid)) return
        if (parentSid == childSid) return // an in-place resume is not a branch — nothing to record
        val m = when (mode) {
            dev.ccpocket.protocol.RewindMode.REWIND, dev.ccpocket.protocol.RewindMode.FORK -> mode
            else -> return // an unknown mode must not enter the ledger and make a row fold unpredictably
        }
        synchronized(lock) {
            runCatching {
                val line = "$parentSid\t$childSid\t$anchorSeq\t$m"
                val existing = if (ledger.exists()) ledger.readLines() else emptyList()
                // match on the EDGE, not the whole line: a re-record with a different anchorSeq is still
                // the same branch, and two lines for one child would make the fold order-dependent
                val prefix = "$parentSid\t$childSid\t"
                if (existing.any { it.startsWith(prefix) }) return
                ledger.parentFile?.mkdirs()
                ledger.writeText((existing + line).takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
                runCatching { Files.setPosixFilePermissions(ledger.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }.onFailure { log.warn("lineage write failed: ${it.message}") }
        }
    }

    /** Every well-formed edge in the ledger, oldest first. Malformed / tampered lines are skipped
     *  silently rather than failing the read — a corrupt ledger must still let the list render. */
    fun entries(ledger: File = defaultLedger()): List<Entry> = synchronized(lock) {
        if (!ledger.exists()) return emptyList()
        runCatching { ledger.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val (parent, child, seqText, mode) = parts
            if (!SESSION_ID.matches(parent) || !SESSION_ID.matches(child)) return@mapNotNull null
            if (mode != dev.ccpocket.protocol.RewindMode.REWIND && mode != dev.ccpocket.protocol.RewindMode.FORK) return@mapNotNull null
            val seq = seqText.toLongOrNull() ?: return@mapNotNull null
            Entry(parent, child, seq, mode)
        }
    }

    /** childSid -> the branch that produced it. A child has at most one parent by construction; if a
     *  corrupt ledger somehow holds two, the FIRST (oldest) wins so the answer is stable across reads
     *  rather than flipping with file order. */
    fun byChild(ledger: File = defaultLedger()): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (e in entries(ledger)) out.putIfAbsent(e.childSid, e)
        return out
    }
}
