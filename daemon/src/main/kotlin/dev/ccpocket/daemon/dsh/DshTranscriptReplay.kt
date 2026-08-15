package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import java.nio.file.Path

/**
 * Flattens a `dsh` session transcript into [HistoryMessage]s for replaying a resumed chat (issue #255).
 * Mirrors the Claude/Codex/Kimi replays and shares their windowing through [ReplaySlicer].
 *
 * The rows we surface are deliberately few — v1 scope is "the conversation reads back correctly":
 *  - `user/message` → a user row
 *  - `assistant/message` → an assistant row
 *  - everything else → dropped
 *
 * ⚠️ WHY THE `*-chunks` ROWS ARE DROPPED RATHER THAN UNPACKED. `text-chunks` / `reasoning-chunks` /
 * `tool-call-chunks` are the batched STREAMING DELTAS of a reply, and dsh ALSO writes the finished reply
 * as a complete `assistant/message`. Replaying both would print every assistant turn twice — once
 * assembled from deltas and once whole. `assistant/message` is the durable record, so it wins and the
 * deltas are skipped. (`reasoning-chunks` would be wrong to show regardless: thinking is not a chat row.)
 *
 * KNOWN v1 LIMITATION: dsh's surface events can carry `surfaceOp = {op:'replace', start, end}`, meaning a
 * later event REWRITES a range of earlier surface content. We treat every event as an append. In practice
 * that only shows up where dsh rewrites a streamed message in place; a replaced range would render as
 * both versions rather than just the final one. Honouring `surfaceOp` belongs with the tool-card work.
 *
 * DEFENSIVE BY CONSTRUCTION: an unrecognized shape yields NO row rather than an exception. A missing row
 * costs a gap in the replay; a throw would cost the whole session.
 */
object DshTranscriptReplay {

    fun read(
        file: Path,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): List<HistoryMessage> = slice(file, sinceSeq = null, maxMessages, maxFrameTextBytes).messages

    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, cursor) = parse(file)
        return ReplaySlicer.slice(rows, cursor, sinceSeq, maxMessages, maxFrameTextBytes)
    }

    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val (rows, _) = parse(file)
        return ReplaySlicer.page(rows, beforeSeq, limit, maxFrameTextBytes)
    }

    /**
     * Parse a transcript into replay rows tagged with their source LINE NUMBER (the #147 cursor `seq`).
     *
     * NOTE the deliberate choice of cursor: dsh events carry their own `seq` field, but we index by line
     * number like every other backend. The packed `*-chunks` lines make the event `seq` non-contiguous
     * (one line covers a whole range via `seq0`/`dt`), so using it would make "everything past cursor N"
     * ambiguous at exactly the lines where a live tail is most likely to land.
     */
    private fun parse(file: Path): Pair<List<ReplaySlicer.Row>, Long> {
        val lines = runCatching { DshTranscript.lines(file) }.getOrDefault(emptyList())
        val out = ArrayList<ReplaySlicer.Row>()
        var lineNo = 0L
        for (raw in lines) {
            lineNo += 1
            val root = DshTranscript.parseLine(raw) ?: continue
            // `ignorable` is dsh's own "this record carries no user-visible meaning" marker — respect it
            // rather than re-deriving the same judgement from the type.
            if (root["ignorable"]?.toString() == "true") continue
            when (root.str("type")) {
                DshTranscript.EVENT_USER ->
                    DshTranscript.messageText(root.obj("data"))?.takeIf { it.isNotBlank() }?.let {
                        out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, it), lineNo)
                    }
                DshTranscript.EVENT_ASSISTANT ->
                    DshTranscript.messageText(root.obj("data"))?.takeIf { it.isNotBlank() }?.let {
                        out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, it), lineNo)
                    }
                // text-chunks / reasoning-chunks / tool-call-chunks are the streaming deltas of content
                // that also arrives whole as assistant/message — see the class comment. Dropping them is
                // what keeps each assistant turn from appearing twice.
                else -> {}
            }
        }
        return out to lineNo
    }
}
