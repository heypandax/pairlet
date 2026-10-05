package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PocketJson

/**
 * One transcript-replay answer with its cursor metadata (issue #147 incremental reattach).
 *
 * [lastSeq] is an opaque transcript CURSOR at read time. Most backends use the 1-based count of source
 * `.jsonl` lines; DSH also encodes its persistence generation to invalidate pre-migration cursors.
 * Within one generation it is stable because transcripts are append-only: a later read of the
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
    /** Cursor of the first included message — the `beforeSeq` anchor for older-history paging. */
    val firstSeq: Long? = null,
    /** The opaque transcript cursor after this read — echo it to get a delta next time. */
    val lastSeq: Long? = null,
    val delta: Boolean = false,
    /** Rows older than [firstSeq] exist (shed by the count/byte caps or the page bound). */
    val hasMore: Boolean = false,
    /** Read quality is independent of the deliberately bounded history window. */
    val quality: String = "unknown",
    val sourceRows: Long? = null,
    val failedRows: Long? = null,
    /** Daemon-internal read failure, sent as PocketError rather than replacement conversation history. */
    val readError: String? = null,
    /** Rows of the count-capped window that a byte budget left out (daemon-internal, for the open log). */
    val budgetDropped: Int = 0,
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
    data class Row(val msg: HistoryMessage, val line: Long, val patchLine: Long = 0L) {
        /** The row as it goes on the wire: its transcript cursor stamped into [HistoryMessage.seq] when the
         *  backend left it unset (only the Claude replay stamps its own, for #282 rewind anchors). Every replay
         *  frame's `firstSeq` is a row line, so a consumer that has to re-anchor a window on a row — the
         *  per-client frame fitter dropping its oldest rows — needs the same number on the row itself, for
         *  any backend that pages. A compact summary stays unstamped on purpose: it is never a rewind anchor
         *  and the fitter drops it rather than anchor on it. */
        fun stamped(): HistoryMessage = if (msg.seq == null && !msg.compactSummary) msg.copy(seq = line) else msg
    }

    /**
     * The (re)open replay: a DELTA continuation when [sinceSeq] can be honored cleanly, else the same
     * full tail window as before #147. Fallback (full) triggers whenever:
     *  - [sinceSeq] is null (first open / old client), non-positive, or PAST [cursor] (the file
     *    shrank or this is a different transcript — the cursor is provably stale);
     *  - a patch line > [sinceSeq] mutated a row at line <= [sinceSeq] (already on the client);
     *  - the delta itself would need count/byte trimming (a huge away-window — full replaces wholesale).
     * The delta path still runs [ReplayBudget.fit] so an oversized continuation can never ride through
     * un-guarded — a fit that DROPS rows simply demotes to the full window.
     *
     * "Trimming" here means losing rows. A delta's rows may still be lightened in place: since issue
     * #254 [ReplayBudget.fit] can shed a row's images (and its sub-agent report / answers) while
     * keeping the row itself, which leaves the count intact and so stays a clean delta. That is
     * deliberate — the shed is not silent, it rides out as `HistoryMessage.imagesTruncated` and the
     * client renders the notice on that turn.
     *
     * [firstWindowBytes] (null = off, today's window byte for byte) narrows a FULL window further to the
     * newest rows whose encoded JSON fits it — see [firstWindow]. The delta path never applies it.
     */
    fun slice(
        rows: List<Row>,
        cursor: Long,
        sinceSeq: Long?,
        maxMessages: Int,
        maxBytes: Long,
        firstWindowBytes: Long? = null,
    ): ReplaySlice {
        if (sinceSeq != null && sinceSeq in 1..cursor) {
            val crossPatched = rows.any { it.line <= sinceSeq && it.patchLine > sinceSeq }
            val fresh = rows.filter { it.line > sinceSeq }
            if (!crossPatched && fresh.size <= maxMessages) {
                val msgs = ReplayBudget.fit(fresh.map { it.stamped() }, maxBytes)
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
        val fitted = ReplayBudget.fit(capped.map { it.stamped() }, maxBytes)
        val keep = firstWindowBytes?.let { firstWindow(capped.subList(capped.size - fitted.size, capped.size), fitted, it) }
            ?: fitted.size
        val msgs = if (keep == fitted.size) fitted else fitted.subList(fitted.size - keep, fitted.size)
        val kept = capped.subList(capped.size - msgs.size, capped.size)
        return ReplaySlice(
            msgs,
            firstSeq = kept.firstOrNull()?.line,
            lastSeq = cursor,
            delta = false,
            hasMore = rows.size > msgs.size,
            budgetDropped = capped.size - msgs.size,
        )
    }

    /** Encoded-JSON target for a first window narrowed by [slice]'s `firstWindowBytes`. The 2026-10-05
     *  session-open analysis measured a 6.8 MB Claude session's 100-row window at ~0.40 MB and its relay
     *  path (daemon → HK relay → phone, store-and-forward per message) at tens of KB/s, i.e. seconds per
     *  window; 192 KB is the size it proposed for the first screen, the rest paging in on demand.
     *
     *  NOT applied to any client yet. Every shipped phone build pages older history only from a
     *  `snapshotFlow` on "list parked at the top", which emits on CHANGE: a window too short to scroll is
     *  "at the top" from the first frame on, never changes, and so never pages (ui/App.kt, the
     *  `loadOlderHistory` effect). A narrower first window makes that dead end reachable; enabling it waits
     *  for a client that pages a short window and says so. */
    const val FIRST_WINDOW_BYTES = 192L * 1024

    /** Rows a narrowed first window keeps even when they alone exceed [FIRST_WINDOW_BYTES]: never an empty
     *  first screen. A row count cannot promise a FULL screen (folded tool rows, one-word replies), which is
     *  why [FIRST_WINDOW_BYTES] above stays off for today's clients. */
    const val FIRST_WINDOW_MIN_ROWS = 8

    /** The envelope around the rows (`Envelope` + `ConvoHistory` fields + a diagnostic context) — measured
     *  at a few hundred bytes; reserved out of the budget so the WHOLE encoded frame stays under it. */
    internal const val FIRST_WINDOW_FRAME_RESERVE = 2L * 1024

    /**
     * How many of the newest [msgs] (aligned with [rows]) a narrowed first window keeps: rows are taken whole,
     * newest first, while their encoded JSON (plus a separator each) fits [budget] less the frame reserve —
     * never altered, so whatever is left out pages back in exactly as it is. At least [FIRST_WINDOW_MIN_ROWS]
     * (or every row, when fewer) are kept regardless of size.
     *
     * The cut never splits one source line's rows: paging answers rows STRICTLY before `firstSeq`, so a window
     * starting mid-line would orphan that line's earlier rows. The split line is dropped from the window when
     * that keeps the floor, else taken whole.
     */
    internal fun firstWindow(rows: List<Row>, msgs: List<HistoryMessage>, budget: Long): Int {
        val room = budget - FIRST_WINDOW_FRAME_RESERVE
        var used = 0L
        var n = 0
        while (n < msgs.size) {
            val size = ReplayBudget.utf8Size(PocketJson.encodeToString(HistoryMessage.serializer(), msgs[msgs.size - 1 - n])) + 1
            if (used + size > room) break
            used += size
            n++
        }
        val floor = minOf(FIRST_WINDOW_MIN_ROWS, msgs.size)
        if (n < floor) n = floor
        fun splits(k: Int) = k in 1 until rows.size && rows[rows.size - k].line == rows[rows.size - k - 1].line
        var k = n
        while (splits(k) && k > floor) k--
        if (splits(k)) { k = n; while (splits(k)) k++ }
        return k
    }

    /** One older-history page: the newest [limit] rows strictly BEFORE [beforeSeq], byte-budgeted like
     *  every replay frame. Patches are already applied (the caller parses the whole file), so a page
     *  simply carries the patched rows — no cross-window concern in this direction. */
    fun page(rows: List<Row>, beforeSeq: Long, limit: Int, maxBytes: Long): ReplaySlice {
        val older = rows.filter { it.line < beforeSeq }
        val capped = if (older.size > limit) older.subList(older.size - limit, older.size) else older
        val msgs = ReplayBudget.fit(capped.map { it.stamped() }, maxBytes)
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

/** The parser's existing pass, including safe accounting; no second transcript parse. */
internal data class ReplayRead<T>(val first: List<T>, val second: Long, val quality: String,
    val failedRows: Long = 0)
