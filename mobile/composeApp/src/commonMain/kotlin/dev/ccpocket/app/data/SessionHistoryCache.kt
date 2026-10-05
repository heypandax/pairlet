package dev.ccpocket.app.data

/**
 * The replayed history of the last few conversations this screen LEFT, kept in memory so reopening one
 * shows its rows at once and asks the daemon only for what was appended since (the #147 delta) instead of
 * the whole tail window — about 0.4 MB through the relay for a long session, the bulk of a multi-second open.
 *
 * What an entry holds is deliberately narrow: exactly the rows the daemon REPLAYED (full windows, deltas and
 * older pages, as [historyItem] built them), together with the cursor they end at. Nothing the live stream
 * added — pending prompt bubbles, streamed text, Thinking blocks, dividers, approval/question cards — ever
 * enters it, so the rows are by construction the transcript up to [Entry.lastSeq], which is precisely what
 * the daemon's delta continues. A delta it cannot honor cleanly (a rewritten file, a late patch to an
 * already-delivered row, too many new rows) comes back as a full window, which replaces the cached rows.
 *
 * Bounds: at most [maxEntries] sessions and [maxBytes] in total, images counted at their decoded size; a
 * single session over the byte bound is not kept at all. Least recently stored goes first. Memory only —
 * never written to disk — and keyed by the bound computer as well as the session, so nothing crosses
 * machines; the repository also clears it wherever it drops the computer's identity.
 *
 * Not thread-safe, like the rest of the repository's state: every call runs on the frame-handling dispatcher.
 */
internal class SessionHistoryCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** [daemon] names the bound computer (see PocketRepository.historyCacheIdentity); [sessionId] the durable
     *  session id — never a convoId, which the daemon mints anew on each attach. */
    data class Key(val daemon: String, val sessionId: String)

    /** One left session's replayed rows, oldest first, and the paging state that goes with them:
     *  [lastSeq] the cursor the rows end at, [firstSeq]/[hasMore] the older-history anchor. */
    data class Entry(val rows: List<ChatItem>, val lastSeq: Long, val firstSeq: Long?, val hasMore: Boolean, val bytes: Long)

    // insertion order IS the recency order: put re-inserts, take removes (a hit moves back into the live view)
    private val entries = LinkedHashMap<Key, Entry>()
    private var bytes = 0L

    val size: Int get() = entries.size
    val totalBytes: Long get() = bytes

    /**
     * Keep [rows] for [key], replacing an older snapshot of the same session. Returns false when nothing was
     * kept: no rows, or more than [maxBytes] on its own (an older snapshot of it is dropped either way — it
     * is staler than what the caller just held). Evicts the least recently stored until both bounds hold.
     */
    fun put(key: Key, rows: List<ChatItem>, lastSeq: Long, firstSeq: Long?, hasMore: Boolean): Boolean {
        entries.remove(key)?.let { bytes -= it.bytes }
        if (rows.isEmpty()) return false
        val size = rows.sumOf { it.estimatedBytes() }
        if (size > maxBytes) return false
        entries[key] = Entry(rows.toList(), lastSeq, firstSeq, hasMore, size)
        bytes += size
        while (entries.size > maxEntries || bytes > maxBytes) {
            val eldest = entries.keys.first()
            bytes -= entries.remove(eldest)!!.bytes
        }
        return true
    }

    /** Remove and return [key]'s entry: on a hit the rows move into the open conversation, which stores them
     *  back (fresher) when it is left. */
    fun take(key: Key): Entry? = entries.remove(key)?.also { bytes -= it.bytes }

    fun clear() {
        entries.clear()
        bytes = 0L
    }

    companion object {
        const val MAX_ENTRIES = 4
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

/**
 * A rough in-memory footprint of one replayed row: its strings at two bytes a char, its decoded image bytes
 * in full, and a fixed per-object overhead. Only ever compared against the cache's bounds, so it needs to
 * be proportionate, not exact — but images must count, they are what makes a row big.
 */
internal fun ChatItem.estimatedBytes(): Long = ROW_OVERHEAD + when (this) {
    is ChatItem.User -> chars(text, memoTodo, uuid) + images.sumOf { it.size.toLong() } +
        files.sumOf { chars(it.toString()) }
    is ChatItem.Assistant -> chars(text)
    is ChatItem.Thinking -> chars(text)
    is ChatItem.Tool -> chars(tool, preview, taskId, output, lastChild, workflowRunId) + images.sumOf { it.size.toLong() }
    is ChatItem.Sys -> chars(text)
    is ChatItem.RuleChip -> chars(rule)
    is ChatItem.AutoRun -> chars(eventId, summary, basis, tool, grantId)
    is ChatItem.QuestionsAnswered -> items.sumOf { (q, a) -> chars(q, a) }
    ChatItem.QuestionsWithdrawn -> 0L
    is ChatItem.QuestionsUnanswered -> chars(text)
    is ChatItem.OpenCodeQuestion -> chars(questions.toString())
    is ChatItem.TurnEnded -> 0L
}

private const val ROW_OVERHEAD = 64L

private fun chars(vararg s: String?): Long = s.sumOf { 2L * (it?.length ?: 0) }
