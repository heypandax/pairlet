package dev.ccpocket.app.data

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

    /** Mid-turn right now. Kept here because [appendChunk] is what flips it on. */
    val streaming = mutableStateOf(false)

    /** One-shot dedupe armed by a history replay (issue #107) — see [appendChunk] / [onToolEvent]. */
    var replayEcho = false

    /** Start of the thinking block still being streamed, for the "Thought for 5s" stamp. */
    private var thinkStartMs: Long? = null

    /** Drop everything — a conversation boundary (open/close/clear). */
    fun reset() {
        messages.clear()
        replayEcho = false
        thinkStartMs = null
        streaming.value = false
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
                if (last is ChatItem.Assistant) messages[messages.lastIndex] = last.copy(text = last.text + p.text)
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
                val i = cardIndex(f.toolUseId)
                // no card on screen (opened mid-run): the reattach history replay carries the outcome instead
                if (i >= 0) messages[i] = (messages[i] as ChatItem.Tool).copy(ok = f.ok, output = f.output)
            }
            parent != null -> {
                val i = cardIndex(parent)
                if (i >= 0) {
                    val card = messages[i] as ChatItem.Tool
                    messages[i] = card.copy(childCount = card.childCount + 1, lastChild = f.tool)
                } else messages.add(ChatItem.Tool(f.tool, f.inputPreview ?: ""))
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
     */
    fun mergeHistory(
        f: ConvoHistory,
        onMerged: (before: List<ChatItem>, after: List<ChatItem>) -> Unit = { _, _ -> },
    ): Long? {
        val local = messages.toList()
        val merged = if (f.delta) {
            if (f.messages.isEmpty()) return f.lastSeq
            TranscriptMerge.mergeDelta(local, f.messages.map(::historyItem))
        } else {
            TranscriptMerge.merge(local, f.messages.map(::historyItem))
        }
        if (merged != local) {
            messages.clear()
            messages.addAll(merged)
        }
        onMerged(local, merged)
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
        seq = h.seq,
        uuid = h.uuid,
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
        ?: ChatItem.Tool(h.tool ?: "tool", h.text, ok = h.ok, output = h.output, workflowRunId = h.workflowRunId)
}
