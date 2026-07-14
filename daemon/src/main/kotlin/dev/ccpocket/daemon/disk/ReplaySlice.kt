package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.HistoryMessage

/**
 * One transcript-replay answer with its cursor metadata (issue #147 incremental reattach).
 *
 * [lastSeq] is the transcript CURSOR at read time — the 1-based count of source `.jsonl` lines the
 * parse consumed. It is stable because both agents' transcripts are append-only: a later read of the
 * same session sees the same rows at the same line numbers, plus new ones after the cursor. The
 * client echoes it back as `OpenSession.lastEventSeq`; a cursor that no longer fits the file (the
 * file shrank: /clear-style rewrite, a different session) falls back to a FULL window.
 *
 * [delta] = [messages] are only the rows PAST the requested cursor (a clean continuation — the byte
 * budget did not trim it and no late patch mutated an already-delivered row). false = a full tail
 * window, today's replay shape.
 */
data class ReplaySlice(
    val messages: List<HistoryMessage>,
    /** Source line of the first included message — the `beforeSeq` anchor for older-history paging. */
    val firstSeq: Long? = null,
    /** The transcript cursor after this read (source line count) — echo it to get a delta next time. */
    val lastSeq: Long? = null,
    val delta: Boolean = false,
    /** Rows older than [firstSeq] exist (shed by the count/byte caps or the page bound). */
    val hasMore: Boolean = false,
) {
    companion object {
        val EMPTY = ReplaySlice(emptyList())
    }
}

/**
 * The shared windowing/cursor logic behind both agents' transcript replays ([TranscriptReplay] /
 * Codex's) — one place so the delta/fallback semantics can't drift between backends.
 */
object ReplaySlicer {

    /** One parsed history row + where it came from. [patchLine] is the source line of the LAST record
     *  that mutated this row after the fact (a sub-agent's tool_result, an AskUserQuestion's answers)
     *  — 0 when never patched. A patch that lands PAST a client's cursor while its target row sits
     *  BEFORE it makes a pure-append delta unable to represent the mutation → full fallback. */
    data class Row(val msg: HistoryMessage, val line: Long, val patchLine: Long = 0L)

    /**
     * The (re)open replay: a DELTA continuation when [sinceSeq] can be honored cleanly, else the same
     * full tail window as before #147. Fallback (full) triggers whenever:
     *  - [sinceSeq] is null (first open / old client), non-positive, or PAST [cursor] (the file
     *    shrank or this is a different transcript — the cursor is provably stale);
     *  - a patch line > [sinceSeq] mutated a row at line <= [sinceSeq] (already on the client);
     *  - the delta itself would need count/byte trimming (a huge away-window — full replaces wholesale).
     * The delta path still runs [ReplayBudget.fit] so an oversized continuation can never ride through
     * un-guarded — a trimmed fit simply demotes to the full window.
     */
    fun slice(
        rows: List<Row>,
        cursor: Long,
        sinceSeq: Long?,
        maxMessages: Int,
        maxBytes: Long,
    ): ReplaySlice {
        if (sinceSeq != null && sinceSeq in 1..cursor) {
            val crossPatched = rows.any { it.line <= sinceSeq && it.patchLine > sinceSeq }
            val fresh = rows.filter { it.line > sinceSeq }
            if (!crossPatched && fresh.size <= maxMessages) {
                val msgs = ReplayBudget.fit(fresh.map { it.msg }, maxBytes)
                if (msgs.size == fresh.size) {
                    return ReplaySlice(
                        msgs,
                        firstSeq = fresh.firstOrNull()?.line,
                        lastSeq = cursor,
                        delta = true,
                        hasMore = false,
                    )
                }
            }
            // fall through: the cursor can't be honored cleanly — full window below
        }
        val capped = if (rows.size > maxMessages) rows.subList(rows.size - maxMessages, rows.size) else rows
        val msgs = ReplayBudget.fit(capped.map { it.msg }, maxBytes)
        val kept = capped.subList(capped.size - msgs.size, capped.size)
        return ReplaySlice(
            msgs,
            firstSeq = kept.firstOrNull()?.line,
            lastSeq = cursor,
            delta = false,
            hasMore = rows.size > msgs.size,
        )
    }

    /** One older-history page: the newest [limit] rows strictly BEFORE [beforeSeq], byte-budgeted like
     *  every replay frame. Patches are already applied (the caller parses the whole file), so a page
     *  simply carries the patched rows — no cross-window concern in this direction. */
    fun page(rows: List<Row>, beforeSeq: Long, limit: Int, maxBytes: Long): ReplaySlice {
        val older = rows.filter { it.line < beforeSeq }
        val capped = if (older.size > limit) older.subList(older.size - limit, older.size) else older
        val msgs = ReplayBudget.fit(capped.map { it.msg }, maxBytes)
        val kept = capped.subList(capped.size - msgs.size, capped.size)
        return ReplaySlice(
            msgs,
            firstSeq = kept.firstOrNull()?.line,
            lastSeq = null,
            delta = false,
            hasMore = older.size > msgs.size,
        )
    }
}
