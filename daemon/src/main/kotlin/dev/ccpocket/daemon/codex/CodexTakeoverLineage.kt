package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.util.logger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which Codex thread a take-over branch came from, and what to call it (issue #347).
 *
 * When the phone takes over a Codex session the ChatGPT desktop app may still hold, [CodexBackend] mints a
 * protective branch through the app-server's native `thread/fork` (see `SessionRegistry`'s single-writer
 * decision). The fork INHERITS the parent's name, so ChatGPT's sidebar shows two rows with identical titles
 * and the user cannot tell which one their phone is now writing to. This object decides the branch's
 * user-facing name and remembers the edge that produced it.
 *
 * DELIBERATELY NOT [dev.ccpocket.daemon.disk.RewindLineage]. That ledger exists to let a rewind's
 * superseded original be FOLDED AWAY and REPLACED by its child — exactly the wrong semantics here: a
 * take-over branch does not supersede anything, the parent stays a first-class session that the desktop
 * keeps using, and collapsing it would hide a session nobody asked to hide. This record is in-memory only
 * and purely advisory (naming + logs); losing it on restart costs nothing, because the authoritative name
 * is already persisted in Codex's own `session_index.jsonl` and is read back by [CodexTranscriptScanner].
 */
object CodexTakeoverLineage {
    private val log = logger("CodexTakeoverLineage")

    /** Marks a branch as cc-pocket's work. Deliberately NOT a status word like "taking over": the name is
     *  permanent, and a status would be a lie the moment the take-over finishes. */
    const val PREFIX_MARK: String = "CC Pocket ·"
    private const val PREFIX = "$PREFIX_MARK "

    /** Names longer than this get elided — a sidebar row shows a prefix, not a paragraph. */
    private const val MAX_BASE_CHARS = 60

    /** Bound on the in-memory edge log: a long-lived daemon must not grow it without limit. */
    private const val MAX_ENTRIES = 200

    /** One recorded take-over branch: [childId] was forked off [parentId] and named [name] at [atMs]. */
    data class Entry(val parentId: String, val childId: String, val name: String, val atMs: Long)

    // insertion-ordered; the oldest edge is evicted at MAX_ENTRIES. Guarded by [lock].
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>() // childId → edge

    /** Record that [childId] is cc-pocket's take-over branch of [parentId]. Idempotent per child. */
    fun note(parentId: String, childId: String, name: String, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries[childId] = Entry(parentId, childId, name, atMs)
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
        log.info("codex take-over branch ${parentId.take(8)}… → ${childId.take(8)}… named \"$name\"")
    }

    /** Every branch recorded for [parentId] in this daemon run, oldest first. */
    fun children(parentId: String): List<Entry> = synchronized(lock) {
        entries.values.filter { it.parentId == parentId }
    }

    internal fun clearForTest() = synchronized(lock) { entries.clear() }

    /**
     * The name to write onto a take-over branch of [parentId].
     *
     * [parentName] is the name the fork response carried (Codex copies the parent's), [indexTitle] the
     * title cc-pocket already knows from `session_index.jsonl`. With neither, the parent's id prefix at
     * least stays traceable — better than a bare `CC Pocket ·` on every branch.
     *
     * A parent that is ITSELF a `CC Pocket ·` branch is never re-prefixed (stacking reads as noise and
     * would push the real subject off the row). That makes the branch name equal to its parent's, so the
     * same [taken] collision check that disambiguates a SECOND branch of one parent also covers it: the
     * short local time marker is appended only when the name would otherwise be a duplicate.
     */
    fun nameFor(
        parentId: String,
        parentName: String?,
        indexTitle: String?,
        atMs: Long = System.currentTimeMillis(),
        taken: Collection<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val base = parentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: indexTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: parentId.take(8)
        val elided = if (base.length > MAX_BASE_CHARS) base.take(MAX_BASE_CHARS).trimEnd() + "…" else base
        val candidate = if (elided.startsWith(PREFIX_MARK)) elided else PREFIX + elided
        // The parent's OWN name counts as taken: naming a branch exactly what its parent is called
        // recreates the very ambiguity this exists to remove (it is the shape an already-prefixed parent
        // produces). Earlier branches of the same parent come from this run's log; after a restart the
        // persisted index names in [taken] carry the same information.
        val clash = taken.asSequence().map { it.trim() }.toSet() +
            listOfNotNull(parentName?.trim(), indexTitle?.trim()) +
            children(parentId).map { it.name.trim() }
        if (candidate !in clash) return candidate
        // Same parent, second branch (or a branch of a branch): distinguish by WHEN the take-over happened.
        // Minutes are enough — two take-overs of one session inside the same minute is not a real case, and
        // seconds would spend row width on noise.
        return "$candidate · ${TIME.format(Instant.ofEpochMilli(atMs).atZone(zone))}"
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
