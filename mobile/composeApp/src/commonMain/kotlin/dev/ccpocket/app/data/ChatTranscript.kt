package dev.ccpocket.app.data

import dev.ccpocket.observability.*

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.epochMillis
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.isSubagentTool
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One conversation's message stream, and the small amount of state that building it needs.
 *
 * Extracted from [PocketRepository] unchanged (issue #311): the repository still owns exactly one of these
 * for the conversation it drives, but the desktop's split panes need the SAME stream-assembly for the extra
 * conversations they keep live beside it. Duplicating the thinking-block, replay-echo and sub-agent-card
 * rules for a second code path is how those two streams would silently drift apart, so there is one copy
 * and both paths call it.
 *
 * Not thread-safe and not meant to be: every mutation runs on the frame-handling dispatcher, exactly as it
 * did when these were repository fields.
 */
class ChatTranscript {
    val messages = mutableStateListOf<ChatItem>()

    // Session metadata has an explicit owner; never rescue arbitrary Sys rows during replay.
    // Keep its display row at the head so a metadata update cannot split streamed text at the tail.
    private var sessionNotice: ChatItem.Sys? = null

    fun setSessionNotice(text: String?) {
        val next = text?.takeIf { it.isNotBlank() }
        if (sessionNotice?.text == next) return
        sessionNotice?.let { previous -> messages.removeAll { it === previous } }
        sessionNotice = next?.let { ChatItem.Sys(it, isError = false) }
        sessionNotice?.let { messages.add(0, it) }
    }

    /** Clear rows and their metadata together, including callers that preserve other turn state. */
    fun clearMessages() {
        sessionNotice = null
        cachedRows = 0
        messages.clear()
    }

    /**
     * How many rows at the head of [messages] (the session notice aside) came from the in-memory session
     * cache ([SessionHistoryCache]) and have not been answered by a history reply yet — 0 on every
     * conversation the cache did not seed. Those rows are the transcript up to the cursor the open echoed,
     * so the reply can only CONTINUE them (a delta) or REPLACE them (a full window); [mergeHistoryUnchecked]
     * keeps them out of the anchor search either way. Counted by position: everything that inserts or
     * removes rows mid-list touches local-only rows (pending bubbles, memo bubbles, autorun chips), which
     * the live stream only ever appends after this head; an older page lands in front and extends it.
     */
    private var cachedRows = 0

    /** Seed a freshly reset conversation with a left session's cached rows, before its open goes out. NOT a
     *  history arrival: the replay-echo dedupe stays disarmed and no receipt sees it — only the reply is. */
    fun showCached(rows: List<ChatItem>) {
        messages.addAll(rows)
        cachedRows = rows.size
        // the same capability read [mergeHistoryUnchecked] makes on a replay: these rows were replayed by
        // this computer, so if they carry ordinary-tool outcomes its daemon reports them
        if (!toolOutcomesLive.value && rows.any { it is ChatItem.Tool && it.ok != null && !isSubagentTool(it.tool) && it.workflowRunId == null }) {
            toolOutcomesLive.value = true
        }
    }

    /** One page of OLDER history in front of everything (issue #147). Part of a cached head when it lands
     *  on one: those rows sit before the same cursor. */
    fun prependHistory(older: List<ChatItem>) {
        messages.addAll(0, older)
        if (cachedRows > 0) cachedRows += older.size
    }

    /** The last row is still one of the cached head's — a live text block must not glue onto it: a delta
     *  reply will carry that block as its own row, and glued it would show twice. */
    private fun lastRowIsCached(): Boolean =
        cachedRows > 0 && messages.size - (if (sessionNotice != null) 1 else 0) <= cachedRows

    /** Mid-turn right now. Kept here because [appendChunk] is what flips it on. */
    val streaming = mutableStateOf(false)

    /**
     * Has this conversation's daemon been seen reporting an ordinary tool's outcome live (#380 outcome frames,
     * daemon 2.1.1+)? Until it has, a started call with no outcome can't be told "still running" from "done on a
     * daemon that never says", so the live fold treats only the newest such call as running (Tool Process Live v1).
     */
    val toolOutcomesLive = mutableStateOf(false)

    /** Ids of sub-agents' inner calls this device saw start. Their outcome frames carry no parent id, so this
     *  is how one is told from a top-level call's when neither matches a card by id (see [onToolEvent]). */
    private val childCallIds = HashSet<String>()

    /** One-shot dedupe armed by a history replay (issue #107) — see [appendChunk] / [onToolEvent]. */
    var replayEcho = false

    /** Start of the thinking block still being streamed, for the "Thought for 5s" stamp. */
    private var thinkStartMs: Long? = null

    /** Drop everything — a conversation boundary (open/close/clear). */
    fun reset() {
        clearMessages()
        replayEcho = false
        thinkStartMs = null
        streaming.value = false
        toolOutcomesLive.value = false
        childCallIds.clear()
    }

    fun appendCompactSummary(text: String) {
        // A replay/live echo is the same complete summary, never a new answer or prompt receipt.
        if (messages.lastOrNull().let { it is ChatItem.User && it.compactSummary && it.text == text }) return
        messages.add(ChatItem.User(text, compactSummary = true))
    }

    fun appendChunk(c: AssistantChunk) {
        streaming.value = true
        when (val p = c.piece) {
            is StreamPiece.Text -> {
                finishThinking() // prose starting = the thinking block (if any) is done
                // one-shot replay-echo dedupe (issue #107): the first block after a merged ConvoHistory
                // can be the very block the replay already included — appending it would double the
                // bubble's tail. Only an exact tail match is dropped; anything else streams normally.
                val echo = replayEcho && TranscriptMerge.isEchoText(messages, p.text)
                replayEcho = false
                if (echo) return
                val last = messages.lastOrNull()
                if (last is ChatItem.Assistant && !lastRowIsCached()) messages[messages.lastIndex] = last.copy(text = last.text + p.text)
                else messages.add(ChatItem.Assistant(p.text))
            }
            is StreamPiece.Thinking -> {
                replayEcho = false // replay carries no thinking rows — a thinking chunk can't be an echo
                val last = messages.lastOrNull()
                if (last is ChatItem.Thinking && last.seconds == null) {
                    messages[messages.lastIndex] = last.copy(text = last.text + p.text)
                } else {
                    thinkStartMs = epochMillis()
                    messages.add(ChatItem.Thinking(p.text))
                }
            }
        }
    }

    fun onToolEvent(f: ToolEvent) {
        // a tool starting = the thinking block (if any) is done, same as prose starting in [appendChunk].
        // Folded in here rather than left to each caller: it was a hand-paired `finishThinking(); onToolEvent(f)`
        // at both the focused and the split-pane call sites, and a third call site that forgot the pairing
        // would leave an un-stamped "Thinking…" row that the NEXT turn stamps with an absurd duration.
        finishThinking()
        val parent = f.parentToolUseId
        if (f.phase == ToolPhase.START && parent == null) {
            // …and a top-level tool starting is a turn in flight — the same evidence a chunk is in [appendChunk]. A
            // turn begun elsewhere can open with a tool before any prose (Codex does), and the live fold (Tool
            // Process Live v1) must see it running rather than as a step whose outcome never arrived. Top-level
            // START only: a late RESULT, or a background sub-agent's inner call after the turn ended, must never
            // revive a turn that is over.
            streaming.value = true
            // the live line's clock counts from first SIGHT, which is now — not from whenever the line is next drawn
            f.toolUseId?.let { ProcessStepClock.startOf(stepClockKeyOf(it)) }
        }
        if (f.phase == ToolPhase.RESULT && f.outcomeOnly) toolOutcomesLive.value = true
        // one-shot replay-echo dedupe (issue #107), tool flavor: a START right after a merged
        // ConvoHistory may duplicate the replayed tail card (which has no taskId). Fold into it —
        // patching the live toolUseId in even upgrades the card for later RESULT correlation.
        if (replayEcho) {
            replayEcho = false
            if (f.phase == ToolPhase.START && parent == null) {
                val i = TranscriptMerge.echoToolIndex(messages, f.tool, f.inputPreview)
                if (i >= 0) {
                    messages[i] = (messages[i] as ChatItem.Tool).copy(taskId = f.toolUseId)
                    return
                }
            }
        }
        fun cardIndex(taskId: String?) =
            if (taskId == null) -1 else messages.indexOfLast { it is ChatItem.Tool && it.taskId == taskId }
        when {
            f.phase == ToolPhase.RESULT -> {
                var i = cardIndex(f.toolUseId)
                if (i < 0 && f.toolUseId != null && f.toolUseId !in childCallIds) {
                    // the call was already running when this list attached: its card came from the history
                    // replay, which carries no call id, so the outcome finds it by name — the newest card of
                    // this tool still without an outcome or an id — and stamps the id in for anything later.
                    // A sub-agent's inner call is excluded above: it never has a card of its own to patch.
                    i = messages.indexOfLast {
                        it is ChatItem.Tool && it.tool == f.tool && it.ok == null && it.taskId == null && isProcessStep(it)
                    }
                    if (i >= 0) messages[i] = (messages[i] as ChatItem.Tool).copy(taskId = f.toolUseId)
                }
                // still no card: an inner call's outcome, or a call this list never showed
                if (i >= 0) {
                    val card = messages[i] as ChatItem.Tool
                    // UPDATE the existing START card — an ordinary tool that returned a screenshot now
                    // gets a RESULT too (issue #332), and appending a second row for it would show the
                    // same call twice. The taskId match is what makes this an update: an ordinary START
                    // card has carried its toolUseId since the beginning (see the `else` branch below).
                    messages[i] = card.copy(
                        ok = f.ok,
                        // a bare outcome frame (#380 live folding) carries no output: keep what the card has
                        output = if (f.outcomeOnly) card.output else f.output,
                        // absent images never ERASE what the card already had: a sub-agent's RESULT and
                        // an image-bearing RESULT are different frames, and only one of them speaks here
                        images = f.images.takeIf { it.isNotEmpty() }?.let(::decodeImages) ?: card.images,
                    )
                }
            }
            parent != null -> {
                if (childCallIds.size > 4096) childCallIds.clear() // a very long session: start over
                f.toolUseId?.let(childCallIds::add)
                // the parent's card — by id, or, when that card came from the history replay (this list
                // attached while the sub-agent ran) and so carries no id, the newest sub-agent card still
                // running, which then adopts the id so its own outcome can find it. An inner call whose
                // sub-agent this list does not show at all is simply not a row: it never was a step of the
                // main chain, and a plain row for it read as a top-level tool that never finished.
                val i = cardIndex(parent).let { byId ->
                    if (byId >= 0) byId
                    else messages.indexOfLast { it is ChatItem.Tool && isSubagentTool(it.tool) && it.ok == null && it.taskId == null }
                }
                if (i >= 0) {
                    val card = messages[i] as ChatItem.Tool
                    messages[i] = card.copy(taskId = card.taskId ?: parent, childCount = card.childCount + 1, lastChild = f.tool)
                }
            }
            // OpenCode's question tool renders as a read-only question card, not a raw JSON row (issue
            // #210); a parse miss (old truncated preview / malformed) falls back to the plain tool card.
            else -> OpenCodeQuestionParse.parse(f.tool, f.inputPreview)
                ?.let { messages.add(ChatItem.OpenCodeQuestion(it)) }
                ?: messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: "", taskId = f.toolUseId))
        }
    }

    /**
     * A turn boundary: close the thinking block, leave streaming, and show [error] where the reply would be.
     * Returns whether a turn was actually being watched run — the caller's gate for its completion marker,
     * its notification, and anything else that must not fire for a boundary nobody was streaming through.
     *
     * Shared by the focused conversation and every split column (issue #311): the two used to keep private,
     * line-for-line identical copies of this, which is how the replay-echo disarm and the "Thought for 5s"
     * stamp would have drifted apart on the first later change. What stays with each caller is what is
     * genuinely theirs — the focused path's limit offer / notify / sidebar dot / usage, the column's own
     * TurnEnded marker and turn-start mark.
     */
    fun endTurn(error: String?): Boolean {
        replayEcho = false // turn boundary — the next block belongs to a new turn, never a replay echo
        val wasLive = streaming.value
        finishThinking()
        streaming.value = false
        // a FAILED turn (API error / synthetic placeholder — issue #65): show the error row where the
        // reply would be; the caller's completion marker stays off for a turn that produced nothing
        error?.let { messages.add(ChatItem.Sys(it)) }
        return wasLive
    }

    /**
     * Apply one [ConvoHistory] — full replay or #147 delta — and arm the #107 replay-echo dedupe.
     * Returns `f.lastSeq` so the caller can advance ITS reattach cursor (the repository keys the cursor by
     * session, a pane keeps a bare one), or null when the daemon predates #147.
     *
     * MERGED, not replaced (issue #107): the replay is the backfill channel for output streamed while the
     * link was down, but the app may hold rows the transcript doesn't (pending bubbles, dividers, scrollback
     * past the replay window, a bubble ahead of a lagging disk read) — [TranscriptMerge] reconciles without
     * flashing, duplicating, or reordering. [onMerged] sees the before/after lists for the receipt
     * reconciliation the focused path layers on top.
     *
     * An empty delta means "already caught up" (the daemon normally does not even send one): nothing merges
     * and the echo dedupe is NOT armed — there was no replay to echo — but the cursor still advances, which
     * is the focused path's long-standing semantics and now the column's too.
     *
     * A head seeded by [showCached] is answered here too: a delta APPENDS past it (the cached rows sit at or
     * before the cursor the open echoed, so none of them may anchor the delta — a repeated "继续" there would
     * otherwise pull the tail onto an old twin and drop everything after it), and a full window REPLACES it.
     * Either way the rows that arrived after the head are reconciled against the replay the way a cache miss
     * reconciles them, so a hit ends on the rows a miss would have. [onReplay] receives the replay's own rows
     * (empty for an empty delta) — what the repository's cursor snapshot is built from.
     */
    fun mergeHistory(
        f: ConvoHistory,
        onReplay: (rows: List<ChatItem>) -> Unit = {},
        onMerged: (before: List<ChatItem>, after: List<ChatItem>) -> Unit = { _, _ -> },
    ): Long? = try {
        mergeHistoryUnchecked(f, onMerged, onReplay)
    } catch (error: Throwable) {
        Diagnostics.report(ErrorPath.HISTORY_APPLY, Stage.APPLY, ErrorCode.APPLY_FAILED, error,
            SafeMetrics(returnedCount = f.messages.size.toLong()))
        throw error
    }

    private fun mergeHistoryUnchecked(
        f: ConvoHistory,
        onMerged: (List<ChatItem>, List<ChatItem>) -> Unit,
        onReplay: (List<ChatItem>) -> Unit,
    ): Long? {
        // Metadata is neither a history anchor nor evidence that a pending prompt was delivered.
        val all = messages.filterNot { it === sessionNotice }
        val cached = cachedRows.coerceAtMost(all.size)
        val head = all.subList(0, cached)
        val local = all.subList(cached, all.size)
        val replay = f.messages.map(::historyItem)
        // whatever the reply is, it answers the cached head: from here those rows are ordinary history.
        // (Only once the replay decoded — a reply that threw leaves the head guarded for the retry.)
        cachedRows = 0
        val merged = if (f.delta) {
            if (replay.isEmpty()) return f.lastSeq.also { onReplay(replay) }
            // behind a cached head, [local] holds only what arrived since this open (live blocks racing the
            // read, a prompt typed meanwhile) — rows the delta may already carry. They are reconciled exactly
            // as a cache miss reconciles them against its full window ([TranscriptMerge.merge]: replay wins,
            // pending input rescued); [TranscriptMerge.mergeDelta] would APPEND a live block it cannot anchor
            // on the delta's first row, after the very row that already carries it.
            if (cached > 0) head + TranscriptMerge.merge(local, replay) else TranscriptMerge.mergeDelta(local, replay)
        } else {
            TranscriptMerge.merge(local, replay)
        }
        val displayed = sessionNotice?.let { listOf(it) + merged } ?: merged
        if (displayed != messages) {
            messages.clear()
            messages.addAll(displayed)
        }
        // a replay that stamps outcomes on ordinary tool rows comes from a daemon that reports them (#380
        // patched the replay and the live stream together): the live fold may trust a missing outcome as
        // missing from the first frame, instead of waiting to see one reported
        if (!toolOutcomesLive.value && f.messages.any { it.role == ChatRole.TOOL && it.ok != null && it.tool?.let(::isSubagentTool) != true && it.workflowRunId == null && it.answers == null }) {
            toolOutcomesLive.value = true
        }
        onMerged(all, merged)
        onReplay(replay)
        replayEcho = true // arm the one-shot live-stream dedupe for the replay/stream race
        return f.lastSeq
    }

    /** Stamp the duration onto a still-open Thinking block (design: "Thought for 5s"). */
    fun finishThinking() {
        val start = thinkStartMs ?: return
        thinkStartMs = null
        val i = messages.indexOfLast { it is ChatItem.Thinking }
        if (i < 0) return
        val t = messages[i] as ChatItem.Thinking
        if (t.seconds == null) {
            val secs = (((epochMillis() - start) + 500) / 1000).toInt().coerceAtLeast(1)
            messages[i] = t.copy(seconds = secs)
        }
    }
}

/**
 * Wire [ImageData] -> renderable bytes, shared by the replay path and the live RESULT frame (issue
 * #332). A blob the platform refuses to base64-decode is DROPPED rather than passed on as bytes that
 * would fail again inside the image decoder — the renderer's own undecodable card is for bytes that
 * are valid base64 but not a valid image, which is a different (and rarer) failure worth naming.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeImages(images: List<dev.ccpocket.protocol.ImageData>): List<ByteArray> =
    images.mapNotNull { runCatching { Base64.Default.decode(it.base64) }.getOrNull() }

/** One replayed history row as the stream item it should render as. Moved here with [ChatTranscript] so a
 *  split pane replays its backlog exactly the way the focused conversation does. */
@OptIn(ExperimentalEncodingApi::class)
internal fun historyItem(h: HistoryMessage): ChatItem = when (h.role) {
    // images the prompt carried replay as real tiles (issue #254) — a turn composed at the computer
    // is no longer text-only here, and an image-ONLY turn is no longer a blank bubble. A base64 blob
    // the platform can't decode is dropped rather than rendered as a broken tile; the renderer's own
    // decode-failure card covers bytes that only fail later (on the image decoder).
    ChatRole.USER -> ChatItem.User(
        h.text,
        images = h.images.mapNotNull { runCatching { Base64.Default.decode(it.base64) }.getOrNull() },
        imagesTruncated = h.imagesTruncated,
        // rewind/fork anchor coordinates (issue #282) — carried verbatim, including their absence
        seq = if (h.compactSummary) null else h.seq,
        uuid = if (h.compactSummary) null else h.uuid,
        compactSummary = h.compactSummary,
    )
    // a synthetic API-failure placeholder replays as the error it was, not as a normal reply (issue #65).
    // Attribution follows the placeholder text so the replay reads the same as the daemon live prompt:
    // an upstream gateway/5xx signal stops blaming context (issue #208).
    ChatRole.ASSISTANT -> if (h.error) {
        ChatItem.Sys(
            "API request failed — the agent wrote a placeholder, not a real reply. " +
                dev.ccpocket.protocol.SyntheticAttribution.attribution(h.text) +
                "\n\nplaceholder reply: ${h.text}",
        )
    } else {
        ChatItem.Assistant(h.text)
    }
    // an answered AskUserQuestion replays as the same compact answered row the live path leaves, not a
    // raw-JSON tool card (issue #110); ok/output keep a sub-agent card's outcome + report (issue #77);
    // workflowRunId binds a Workflow card to its separately-pushed run (issue #106)
    ChatRole.TOOL -> h.answers?.let { a -> ChatItem.QuestionsAnswered(a.map { it.question to it.answer }) }
        ?: OpenCodeQuestionParse.parse(h.tool ?: "", h.text)?.let { ChatItem.OpenCodeQuestion(it) }
        // …and one with NO answers is a question that never got one (issue #321). Falling through to
        // the plain tool row below made it read as a live-looking card with nothing to tap; say what
        // it actually is. Every backend's replay names the tool the same way (see the daemon's
        // TranscriptReplay / DshTranscriptReplay ASK_TOOL), so this needs no per-agent branch.
        ?: h.text.takeIf { h.tool == ASK_QUESTION_TOOL }?.let { ChatItem.QuestionsUnanswered(it) }
        // …and a plain tool row, which since issue #332 can carry the pictures its RESULT returned
        // (a browser screenshot, a `Read` of a PNG) exactly the way a USER row carries its attachments.
        ?: ChatItem.Tool(
            h.tool ?: "tool", h.text, ok = h.ok, output = h.output, workflowRunId = h.workflowRunId,
            images = decodeImages(h.images), imagesTruncated = h.imagesTruncated,
        )
}
