package dev.ccpocket.app.data

import dev.ccpocket.protocol.isSubagentTool
import dev.ccpocket.protocol.isWorkflowTool

/**
 * Issue #380 — "collapse tool process": a DISPLAY projection over a conversation's [ChatItem]s.
 *
 * The transcript itself is never touched. Execution, approvals, copy-all, rewind and every repository rule
 * keep consuming the raw `messages`; only the two chat lists (phone/iPad [dev.ccpocket.app.ui.ChatScreen]
 * and the desktop ChatPane) render through this, and they share this one algorithm so the two cannot drift.
 *
 * What folds (always — the per-session switch was removed 2026-09-15): a run of at least [MIN_GROUP]
 * CONSECUTIVE rows that
 * are each [isFoldableProcess] — an ordinary tool whose outcome is known to be success, or a finished
 * thinking block. Everything else stays its own row AND ends the run: prose (User / Assistant), a failed,
 * running or outcome-unknown tool (`ok == null` is never read as success), sub-agent / workflow / plan
 * cards, errors and notices, approvals' audit chips, question residue and turn dividers. Rows are never
 * moved together across one of those. There is no guess at a "final answer" — no backend marks one.
 */

/** Fewer rows than this are not worth a fold: a single tool row is already one line. */
const val MIN_PROCESS_GROUP = 2

private val PLAN_TOOLS = setOf("ExitPlanMode", "exit_plan_mode")

/** May [item] join a folded process run? See the file doc for why each exclusion exists. */
fun isFoldableProcess(item: ChatItem): Boolean = when (item) {
    is ChatItem.Tool -> item.ok == true &&
        item.workflowRunId == null &&
        !isWorkflowTool(item.tool) &&
        !isSubagentTool(item.tool) &&
        item.tool !in PLAN_TOOLS &&
        item.tool != ASK_QUESTION_TOOL
    // a streaming block (no duration yet) keeps its live feedback on screen
    is ChatItem.Thinking -> item.seconds != null
    else -> false
}

/**
 * Client-only OCCURRENCE identity for transcript rows.
 *
 * [ChatItem]s are immutable data classes rebuilt on every change (a RESULT copies the tool card, a chunk
 * copies the bubble, a replay merge rebuilds rows), their equality is value equality (two identical `ls`
 * calls are equal), and their index shifts on every page of older history — so none of text, index or the
 * nullable taskId can name a row. Adding an id field to ChatItem would have leaked into every `==` the
 * merge rules and tests rely on, so identity lives beside the list instead: [assign] diffs each new snapshot
 * against the previous one and carries ids across.
 *
 *  - rows that are the same instance keep their id (common prefix/suffix — a streamed tail, a prepend);
 *  - in the changed middle, value-equal rows are matched in order, then remaining rows are paired in place
 *    when they are plausibly the same record updated (same tool with an extending preview, a bubble whose
 *    text extends, …) — a RESULT, a merge enrichment, a growing bubble;
 *  - anything else is a new occurrence with a fresh id. Ids come from one process-wide counter, so they
 *    never repeat across trackers and are safe as lazy-list keys in two windows at once.
 *
 * [generation] advances when the history is replaced rather than updated: the list was cleared, or a
 * non-empty list shared nothing with its predecessor. Group keys embed it, so an expanded fold from the
 * previous history can never re-open on a different one.
 *
 * Not thread-safe; used from the UI thread only.
 */
class ChatRowIdentity {
    var generation: Int = 0
        private set
    private var items: List<ChatItem> = emptyList()
    private var ids: LongArray = LongArray(0)

    fun assign(current: List<ChatItem>): LongArray {
        val old = items
        val oldIds = ids
        val n = current.size
        if (n == 0) {
            if (old.isNotEmpty()) generation++
            items = emptyList()
            ids = LongArray(0)
            return ids
        }
        val out = LongArray(n)
        val min = minOf(old.size, n)
        var p = 0
        while (p < min && current[p] === old[p]) { out[p] = oldIds[p]; p++ }
        var oe = old.size
        var ne = n
        while (oe > p && ne > p && current[ne - 1] === old[oe - 1]) { oe--; ne--; out[ne] = oldIds[oe] }
        var retained = p + (n - ne)
        if (p < oe && p < ne) retained += matchMiddle(old, oldIds, p, oe, current, p, ne, out)
        if (old.isNotEmpty() && retained == 0) generation++
        for (i in 0 until n) if (out[i] == 0L) out[i] = nextId++
        items = current.toList()
        ids = out
        return out
    }

    private fun matchMiddle(
        old: List<ChatItem>, oldIds: LongArray, os: Int, oe: Int,
        new: List<ChatItem>, ns: Int, ne: Int, out: LongArray,
    ): Int {
        val paired = IntArray(ne - ns) { -1 }
        // 1) value-equal rows, in order (a merge that rebuilt an unchanged row)
        if ((oe - os).toLong() * (ne - ns) <= 64) {
            var from = os
            for (j in ns until ne) {
                var i = from
                while (i < oe && old[i] != new[j]) i++
                if (i < oe) { paired[j - ns] = i; from = i + 1 }
            }
        } else {
            val byValue = HashMap<ChatItem, ArrayDeque<Int>>()
            for (i in os until oe) byValue.getOrPut(old[i]) { ArrayDeque() }.addLast(i)
            var last = os - 1
            for (j in ns until ne) {
                val q = byValue[new[j]] ?: continue
                while (q.isNotEmpty() && q.first() <= last) q.removeFirst()
                val i = q.removeFirstOrNull() ?: continue
                paired[j - ns] = i
                last = i
            }
        }
        // 2) between those anchors, rows updated in place
        var oi = os
        var nj = ns
        fun pairGap(oEnd: Int, nEnd: Int) {
            var a = oi
            var b = nj
            while (a < oEnd && b < nEnd && sameRecord(old[a], new[b])) { paired[b - ns] = a; a++; b++ }
        }
        for (j in ns until ne) {
            val i = paired[j - ns]
            if (i >= 0) { pairGap(i, j); oi = i + 1; nj = j + 1 }
        }
        pairGap(oe, ne)
        var matched = 0
        for (j in ns until ne) {
            val i = paired[j - ns]
            if (i >= 0) { out[j] = oldIds[i]; matched++ }
        }
        return matched
    }

    private fun sameRecord(a: ChatItem, b: ChatItem): Boolean = when {
        a is ChatItem.Tool && b is ChatItem.Tool -> a.tool == b.tool &&
            (a.taskId == null || b.taskId == null || a.taskId == b.taskId) &&
            extendsEither(a.preview, b.preview)
        a is ChatItem.Assistant && b is ChatItem.Assistant -> extendsEither(a.text, b.text)
        a is ChatItem.Thinking && b is ChatItem.Thinking -> extendsEither(a.text, b.text)
        a is ChatItem.User && b is ChatItem.User -> a.text == b.text || (a.promptId != null && a.promptId == b.promptId)
        a is ChatItem.AutoRun && b is ChatItem.AutoRun -> a.eventId == b.eventId
        else -> false
    }

    private fun extendsEither(a: String, b: String) = a.startsWith(b) || b.startsWith(a)

    private companion object {
        var nextId = 1L
    }
}

/** What a folded run holds — rendered as "Process · 8 tools · 2 thoughts · ▣ 3". Counts only, read from
 *  fields the rows already carry; nothing is parsed or summarized. */
data class ProcessSummary(val tools: Int, val thoughts: Int, val images: Int, val imagesTruncated: Boolean)

/** One display row. [key] is a stable lazy-list key: it survives in-place updates and prepends. */
sealed interface ChatRow {
    val key: String

    /** A transcript row shown as itself. [groupKey] is set when it is a member of an EXPANDED fold. */
    data class Original(val sourceIndex: Int, val sourceKey: Long, val groupKey: String? = null) : ChatRow {
        override val key: String get() = "m:$sourceKey"
    }

    /** The fold's own row. Expanded, it is a header followed by its members as [Original] rows. */
    data class ProcessGroup(
        val groupKey: String,
        val sourceIndices: IntRange,
        val summary: ProcessSummary,
        val expanded: Boolean,
    ) : ChatRow {
        override val key: String get() = groupKey
    }
}

/** First visible source row + pixel offset: the reading position, in coordinates a projection change can't move. */
data class ReadingAnchor(val sourceKey: Long, val onGroupHeader: Boolean, val offset: Int)

/**
 * What a laid-out viewport proves (the two halves of `onHistoryLaidOut`): [hasVisibleContent] — transcript
 * content reached the screen (a folded tool row counts); [lastVisibleOutput] — the highest SOURCE index of an
 * output row the reader can actually see. Rows hidden inside a collapsed fold never count as seen output.
 */
data class LayoutEvidence(val hasVisibleContent: Boolean, val lastVisibleOutput: Int)

class ChatPresentation private constructor(
    val generation: Int,
    val collapsed: Boolean,
    val items: List<ChatItem>,
    val rows: List<ChatRow>,
    private val ids: LongArray,
    private val rowOfSourceIndex: IntArray,
) {
    val sourceSize: Int get() = items.size

    private val sourceOfKey: Map<Long, Int> by lazy {
        HashMap<Long, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
    }

    fun sourceKey(sourceIndex: Int): Long = ids[sourceIndex]

    /** The source rows display row [row] stands for (a header names its whole fold, even when expanded). */
    fun sourceIndicesAt(row: Int): IntRange = when (val r = rows.getOrNull(row)) {
        is ChatRow.Original -> r.sourceIndex..r.sourceIndex
        is ChatRow.ProcessGroup -> r.sourceIndices
        null -> IntRange.EMPTY
    }

    fun sourceKeysAt(row: Int): List<Long> = sourceIndicesAt(row).map { ids[it] }

    /** The display row that currently shows source row [sourceIndex] (its fold, when folded); -1 if none. */
    fun rowOfSource(sourceIndex: Int): Int = rowOfSourceIndex.getOrElse(sourceIndex) { -1 }

    fun rowOfKey(sourceKey: Long): Int = sourceOfKey[sourceKey]?.let(::rowOfSource) ?: -1

    fun anchorAt(row: Int, offset: Int): ReadingAnchor? = when (val r = rows.getOrNull(row)) {
        is ChatRow.Original -> ReadingAnchor(r.sourceKey, false, offset)
        is ChatRow.ProcessGroup -> ReadingAnchor(ids[r.sourceIndices.first], true, offset)
        null -> null
    }

    /** Where [anchor] lives in THIS projection; -1 when its row is gone. An anchor taken on a fold's header
     *  lands on the header of whatever expanded fold now holds that record — even when the fold gained a new
     *  first member meanwhile (a page, or an earlier tool finishing late) and so has a new key. */
    fun rowFor(anchor: ReadingAnchor): Int {
        val s = sourceOfKey[anchor.sourceKey] ?: return -1
        val r = rowOfSourceIndex[s]
        if (anchor.onGroupHeader && (rows[r] as? ChatRow.Original)?.groupKey != null) {
            var h = r - 1
            while (h >= 0 && rows[h] !is ChatRow.ProcessGroup) h--
            if (h >= 0) return h
        }
        return r
    }

    /** The display row an "earlier messages" seam above SOURCE row [sourceIndex] belongs on (its fold's row
     *  when folded, the fold header when it OPENS an expanded fold); -1 when there is no such row. */
    fun seamRow(sourceIndex: Int): Int {
        if (sourceIndex !in 0 until sourceSize) return -1
        val r = rowOfSourceIndex[sourceIndex]
        val header = rows.getOrNull(r - 1)
        return if (header is ChatRow.ProcessGroup && header.expanded && header.sourceIndices.first == sourceIndex) r - 1 else r
    }

    fun layoutEvidence(visibleRows: Iterable<Int>, items: List<ChatItem> = this.items): LayoutEvidence {
        var content = false
        var lastOutput = -1
        for (index in visibleRows) {
            when (val r = rows.getOrNull(index)) {
                is ChatRow.Original -> {
                    val m = items.getOrNull(r.sourceIndex)
                    if (m is ChatItem.User || m is ChatItem.Assistant || m is ChatItem.Tool) content = true
                    if (m is ChatItem.Assistant || m is ChatItem.Tool) lastOutput = maxOf(lastOutput, r.sourceIndex)
                }
                is ChatRow.ProcessGroup -> if (r.summary.tools > 0) content = true
                null -> Unit
            }
        }
        return LayoutEvidence(content, lastOutput)
    }

    companion object {
        /**
         * Pure: [ids] must be aligned with [items] (see [ChatRowIdentity.assign]).
         *
         * A fold is expanded when its key is in [expanded], or when ANY of its members is in
         * [expandedMembers]. The member form is what a list uses: a fold the reader opened stays open when
         * it grows at the tail, gains a new head (a page of older tools, an earlier call finishing late) or
         * is re-keyed for any other reason — the records they opened are still in it.
         */
        fun build(
            items: List<ChatItem>,
            ids: LongArray,
            generation: Int,
            collapse: Boolean,
            expanded: Set<String> = emptySet(),
            expandedMembers: Set<Long> = emptySet(),
        ): ChatPresentation {
            require(ids.size == items.size) { "ids (${ids.size}) must align with items (${items.size})" }
            val n = items.size
            val rows = ArrayList<ChatRow>(n)
            val rowOf = IntArray(n)
            var i = 0
            while (i < n) {
                if (!collapse || !isFoldableProcess(items[i])) {
                    rowOf[i] = rows.size
                    rows += ChatRow.Original(i, ids[i])
                    i++
                    continue
                }
                var j = i
                var tools = 0
                var thoughts = 0
                var images = 0
                var truncated = false
                while (j < n && isFoldableProcess(items[j])) {
                    when (val m = items[j]) {
                        is ChatItem.Tool -> { tools++; images += m.images.size; truncated = truncated || m.imagesTruncated }
                        is ChatItem.Thinking -> thoughts++
                        else -> Unit
                    }
                    j++
                }
                if (j - i < MIN_PROCESS_GROUP) {
                    for (k in i until j) { rowOf[k] = rows.size; rows += ChatRow.Original(k, ids[k]) }
                    i = j
                    continue
                }
                val key = "g:$generation:${ids[i]}"
                val open = key in expanded ||
                    (expandedMembers.isNotEmpty() && (i until j).any { ids[it] in expandedMembers })
                val header = rows.size
                rows += ChatRow.ProcessGroup(key, i until j, ProcessSummary(tools, thoughts, images, truncated), open)
                for (k in i until j) {
                    if (open) { rowOf[k] = rows.size; rows += ChatRow.Original(k, ids[k], key) } else rowOf[k] = header
                }
                i = j
            }
            return ChatPresentation(generation, collapse, items, rows, ids, rowOf)
        }
    }
}
