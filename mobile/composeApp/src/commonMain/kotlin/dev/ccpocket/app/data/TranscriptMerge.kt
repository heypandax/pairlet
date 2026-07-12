package dev.ccpocket.app.data

/**
 * Merges a replayed transcript (ConvoHistory — the daemon's authoritative on-disk view) into the
 * message list the app accumulated live (issue #107).
 *
 * Why merge instead of the old `clear()+addAll()` replace: the daemon replays history on every
 * (re)attach — including the automatic re-open after a transport reconnect — and that replay is the
 * ONLY channel that can backfill output streamed while the phone's link was down (the relay is a
 * zero-buffer forwarder; offline AssistantChunks are gone). But a blind replace
 *  (a) dropped every local-only row the transcript never contains — pending prompt bubbles,
 *      TurnEnded dividers, Thinking blocks, question chips, receipt state (promptId/delivered);
 *  (b) discarded scrollback older than the replay's tail window (TranscriptReplay caps ~100 rows);
 *  (c) regressed the live streaming bubble whenever the replay lagged the stream (mid-turn, the
 *      current API call's block is on neither disk nor wire yet).
 *
 * Rules (chronological order is always preserved):
 *  - an empty replay still clears — that is the daemon's explicit /clear wipe;
 *  - the replay is anchored inside the local list; local rows OLDER than the anchor survive;
 *  - matched rows are enriched, never duplicated: User keeps promptId/delivered/images, Tool keeps
 *    taskId/childCount/lastChild and gains ok/output, Assistant keeps the longer of the two texts;
 *  - transcript-invisible local rows inside the overlap are carried through in place;
 *  - a live bubble glued across a dropped-chunk gap is replaced by the replay's full run (the disk
 *    is the truth), while a bubble AHEAD of a lagging replay is kept (never regress the stream);
 *  - on a hard divergence the replay wins from that point (it can contain turns from other devices
 *    this app never saw live), but trailing undelivered prompt bubbles are still rescued.
 */
object TranscriptMerge {

    /** Merge [replay] (authoritative for what it covers) into [local]. Pure; safe to call repeatedly. */
    fun merge(local: List<ChatItem>, replay: List<ChatItem>): List<ChatItem> {
        if (replay.isEmpty()) return emptyList() // the daemon's /clear wipe must still clear
        if (local.isEmpty()) return replay
        val anchor = findAnchor(local, replay)
            ?: return replay + rescuePending(local, replay) // nothing lines up — replay wholesale
        val out = ArrayList<ChatItem>(local.size + replay.size)
        out.addAll(local.subList(0, anchor)) // scrollback older than the replay window survives
        var li = anchor
        var ri = 0
        while (li < local.size && ri < replay.size) {
            val l = local[li]
            val r = replay[ri]
            // a pending bubble whose text the replay now carries WAS delivered (the ack got lost):
            // resolve it in place — checked before the pass-through so it can't duplicate below
            if (l is ChatItem.User && l.pending && r is ChatItem.User && l.text == r.text) {
                out.add(l.copy(pending = false)); li++; ri++
                continue
            }
            if (isLocalOnly(l)) { // transcript-invisible row: carry it through in place
                out.add(l); li++
                continue
            }
            if (!couldPair(l, r)) {
                // hard divergence: the on-disk replay wins from here — but typed-and-undelivered
                // bubbles in the dropped region are rescued (input must never vanish)
                out.addAll(replay.subList(ri, replay.size))
                out.addAll(rescuePending(local.subList(li, local.size), replay))
                return out
            }
            when (l) {
                is ChatItem.Assistant -> {
                    // one live bubble may span several replay rows (appendChunk concatenates
                    // consecutive text blocks that TranscriptReplay keeps as separate rows) —
                    // consume the run ONLY while the local text still has unmatched remainder;
                    // once covered, further replay rows are genuinely new content (own bubbles)
                    var acc = (r as ChatItem.Assistant).text
                    while (ri + 1 < replay.size && acc.length < l.text.length) {
                        val next = replay[ri + 1] as? ChatItem.Assistant ?: break
                        val cand = acc + next.text
                        if (l.text.startsWith(cand) || cand.startsWith(l.text)) { acc = cand; ri++ } else break
                    }
                    val text = when {
                        // replay caught up with (or passed) the bubble — adopt it
                        acc.startsWith(l.text) -> acc
                        // local extends past the replay's run. More assistant rows following in the
                        // replay = the local bubble was glued across a dropped-chunk gap → trust the
                        // replay; run genuinely over = the disk read lagged the stream → keep local.
                        replay.getOrNull(ri + 1) is ChatItem.Assistant -> acc
                        else -> l.text
                    }
                    out.add(ChatItem.Assistant(text))
                    li++; ri++
                }
                is ChatItem.User -> {
                    // same prompt row: keep the receipt fields (promptId/delivered/images) the
                    // transcript never carries — a late PromptAck still finds its bubble
                    out.add(l.copy(pending = false)); li++; ri++
                }
                is ChatItem.Tool -> {
                    val rt = r as ChatItem.Tool
                    out.add(
                        l.copy(
                            // replay carries a longer input preview (1000 vs the live 280 cut) — take
                            // whichever extends the other; unrelated shapes (ExitPlanMode's plan text
                            // vs raw JSON) keep the display-optimized local preview
                            preview = if (extendsEither(l.preview, rt.preview)) longer(l.preview, rt.preview) else l.preview,
                            ok = rt.ok ?: l.ok,
                            output = rt.output ?: l.output,
                        ),
                    )
                    li++; ri++
                }
                else -> { out.add(r); li++; ri++ } // unreachable: couldPair admits only the three types
            }
        }
        while (li < local.size) { out.add(local[li]); li++ } // replay exhausted → local is ahead; keep it
        if (ri < replay.size) out.addAll(replay.subList(ri, replay.size)) // the reconnect gap — the backfill
        return out
    }

    /**
     * True when [text], arriving as the FIRST stream event after a merged replay, is already the tail
     * of the last content bubble — i.e. the replay's disk read caught the very block the pump then
     * delivered live (the reattach race). Trailing transcript-invisible rows are skipped so a rescued
     * pending bubble doesn't hide the tail.
     */
    fun isEchoText(messages: List<ChatItem>, text: String): Boolean {
        if (text.isEmpty()) return false
        val last = messages.getOrNull(lastContentIndex(messages))
        return last is ChatItem.Assistant && last.text.endsWith(text)
    }

    /**
     * Index of the tool card a just-arrived START would duplicate (same race as [isEchoText]): the
     * last content row, when it is a replayed card (no taskId yet) for the same tool with a
     * compatible preview. -1 when there is nothing to fold into. The caller patches that card with
     * the live toolUseId instead of appending a twin.
     */
    fun echoToolIndex(messages: List<ChatItem>, tool: String, preview: String?): Int {
        val i = lastContentIndex(messages)
        val t = (messages.getOrNull(i) as? ChatItem.Tool) ?: return -1
        if (t.taskId != null || t.tool != tool) return -1
        val p = preview.orEmpty()
        return if (p.isEmpty() || t.preview.isEmpty() || extendsEither(t.preview, p)) i else -1
    }

    // ---- internals ----

    /** Rows the transcript never contains — carried through a merge, invisible to pairing. */
    private fun isLocalOnly(item: ChatItem): Boolean = when (item) {
        is ChatItem.Thinking, is ChatItem.Sys, is ChatItem.RuleChip,
        is ChatItem.QuestionsAnswered, ChatItem.QuestionsWithdrawn, is ChatItem.TurnEnded,
        -> true
        is ChatItem.User -> item.pending // an undelivered bubble has no transcript row yet
        else -> false
    }

    private fun couldPair(l: ChatItem, r: ChatItem): Boolean = when {
        l is ChatItem.Assistant && r is ChatItem.Assistant -> extendsEither(l.text, r.text)
        l is ChatItem.User && r is ChatItem.User -> l.text == r.text
        l is ChatItem.Tool && r is ChatItem.Tool -> l.tool == r.tool
        else -> false
    }

    /**
     * Where does [replay] start inside [local]? Candidates pair replay[0] against a local content
     * row; the best candidate is the one whose pairwise run reaches furthest (ties → the LATEST
     * position: a replay window is a transcript tail, and the deeper anchor keeps more scrollback).
     * Null when nothing pairs — the caller falls back to wholesale replacement.
     */
    private fun findAnchor(local: List<ChatItem>, replay: List<ChatItem>): Int? {
        val r0 = replay.first()
        var best: Int? = null
        var bestScore = 0
        for (k in local.indices) {
            if (isLocalOnly(local[k]) || !couldPair(local[k], r0)) continue
            val score = pairRun(local, k, replay)
            if (score >= bestScore) { best = k; bestScore = score }
        }
        return best
    }

    /** Length of the pairwise-compatible run walking local[k..] × replay (local-only rows skipped). */
    private fun pairRun(local: List<ChatItem>, k: Int, replay: List<ChatItem>): Int {
        var li = k
        var ri = 0
        var n = 0
        while (li < local.size && ri < replay.size) {
            if (isLocalOnly(local[li])) { li++; continue }
            if (!couldPair(local[li], replay[ri])) break
            n++; li++; ri++
        }
        return n
    }

    /** Undelivered prompt bubbles from a dropped region — re-appended so typed input never vanishes.
     *  A bubble whose text already appears among the replay's trailing user rows WAS delivered (only
     *  its ack was lost) — the replayed row covers it, so it is not duplicated. */
    private fun rescuePending(dropped: List<ChatItem>, replay: List<ChatItem>): List<ChatItem> {
        val delivered = replay.takeLast(RESCUE_WINDOW).filterIsInstance<ChatItem.User>().mapTo(HashSet()) { it.text }
        return dropped.filter { it is ChatItem.User && it.pending && it.text !in delivered }
    }

    private fun lastContentIndex(messages: List<ChatItem>): Int {
        for (i in messages.indices.reversed()) if (!isLocalOnly(messages[i])) return i
        return -1
    }

    private fun extendsEither(a: String, b: String): Boolean = a.startsWith(b) || b.startsWith(a)

    private fun longer(a: String, b: String): String = if (a.length >= b.length) a else b

    private const val RESCUE_WINDOW = 8
}
