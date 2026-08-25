package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.ToolMetadata
import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.daemon.disk.TranscriptNoise
import dev.ccpocket.daemon.opencode.ToolNameMapper
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
 *  - `tool/call` + its `tool/result` → a structured tool card (questions keep their answered card)
 *  - `approval/asked` + `approval/decided` → the decided approval card (issue #291)
 *  - everything else → dropped
 *
 * ⚠️ HOW A QUESTION AND AN APPROVAL LAND ON DISK IS NOT THE SAME (probe-verified, `--probe-ask`), and
 * getting it backwards is why this file reads them through two separate index maps:
 *  - a QUESTION is **not** a session event. No `question/…` record is ever written; it replays purely as
 *    a `tool/call` named `ask_user_question` plus the `tool/result` that carries the human's answer, and
 *    a question the user never answered (cancelled turn) has NO result at all.
 *  - an APPROVAL **is** a first-class pair of events, `approval/asked` and `approval/decided`.
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
        val out = ArrayList<MutableRow>()
        // issue #291: the two "a human decided something" cards are patched in place when their second
        // record scrolls past, exactly like the Claude replay's sub-agent/question cards. Keyed
        // separately because a callId and an approvalId are different id spaces.
        // callId -> (row index, the question texts read from the SAME tool/call). The texts are carried
        // rather than re-derived from the row's label so that a question containing a newline cannot
        // shift the answer pairing.
        val questionIdx = HashMap<String, Pair<Int, List<String>>>()
        val toolIdx = HashMap<String, Int>() // ordinary call id -> replay row to patch with its result
        val approvalIdx = HashMap<String, Int>() // approval id (disk spelling) -> its row's index
        var lineNo = 0L
        for (raw in lines) {
            lineNo += 1
            val root = DshTranscript.parseLine(raw) ?: continue
            // `ignorable` is dsh's own "this record carries no user-visible meaning" marker — respect it
            // rather than re-deriving the same judgement from the type.
            if (root["ignorable"]?.toString() == "true") continue
            val data = root.obj("data")
            when (root.str("type")) {
                DshTranscript.EVENT_USER ->
                    DshTranscript.messageText(data)
                        ?.takeIf { it.isNotBlank() }
                        // Same judgement every other replay path uses (issue #253): a user turn that is
                        // nothing but harness plumbing must not render under a "你" header as if it were
                        // typed. dsh's own `ignorable` flag (checked above) covers what IT marks; this
                        // covers injected wrapper blocks, which no flag distinguishes.
                        ?.takeUnless { TranscriptNoise.isNoiseUserText(it) }
                        ?.let { out += MutableRow(HistoryMessage(ChatRole.USER, it), lineNo) }
                DshTranscript.EVENT_ASSISTANT ->
                    DshTranscript.messageText(data)?.takeIf { it.isNotBlank() }?.let {
                        out += MutableRow(HistoryMessage(ChatRole.ASSISTANT, it), lineNo)
                    }
                // A question card. Named with the CLAUDE tool spelling deliberately: `tool` is what the
                // app switches its renderer on, and #110's answered-question card is the right one for
                // this row — the dsh-side name (`ask_user_question`) would fall through to a raw card.
                DshTranscript.EVENT_TOOL_CALL -> {
                    val rawName = data?.str("name") ?: "tool"
                    val args = DshAsk.toolCallArgs(data)
                    if (rawName == DshAsk.QUESTION_TOOL) {
                        val prompts = DshAsk.questionsOf(args).map { it.question }
                        val text = prompts.joinToString("\n").ifBlank { "Question" }
                        data?.str("callId")?.let { questionIdx[it] = out.size to prompts }
                        out += MutableRow(
                            HistoryMessage(ChatRole.TOOL, text.take(MAX_TOOL_TEXT), tool = ASK_TOOL),
                            lineNo,
                        )
                    } else {
                        val name = ToolNameMapper.map(rawName)
                        val preview = args?.let { ToolMetadata.of(name, it).preview }
                            ?: data?.str("arguments")?.takeIf { it.isNotBlank() }
                            ?: name
                        data?.str("callId")?.let { toolIdx[it] = out.size }
                        out += MutableRow(
                            HistoryMessage(ChatRole.TOOL, preview.take(MAX_TOOL_TEXT), tool = name),
                            lineNo,
                        )
                    }
                }
                // The answer half. A question with NO result (the turn was cancelled before anyone
                // answered) simply never reaches here — its row stays in the unanswered form, which the
                // #110 card already knows how to render.
                DshTranscript.EVENT_TOOL_RESULT -> {
                    for (result in DshAsk.toolResults(data)) {
                        val question = questionIdx.remove(result.callId)
                        if (question != null) {
                            val (idx, prompts) = question
                            val row = out.getOrNull(idx) ?: continue
                            val answers = DshAsk.replayAnswers(result.text, prompts) ?: continue
                            row.msg = row.msg.copy(answers = answers)
                            row.patchLine = lineNo
                            continue
                        }
                        val idx = toolIdx.remove(result.callId) ?: continue
                        val row = out.getOrNull(idx) ?: continue
                        row.msg = row.msg.copy(
                            ok = result.failed?.not() ?: true,
                            output = result.text.take(MAX_TOOL_OUTPUT).ifBlank { null },
                        )
                        row.patchLine = lineNo
                    }
                }
                // An approval card: the model's own escalation REASON is the text, because that is the
                // sentence the human actually read before deciding.
                DshTranscript.EVENT_APPROVAL_ASKED -> {
                    val tool = data?.str("toolName")?.takeIf { it.isNotBlank() } ?: "approval"
                    val reason = data?.str("reason")?.takeIf { it.isNotBlank() } ?: tool
                    // `id` on disk, `approvalId` on the wire — same value, two spellings.
                    data?.str("id")?.let { approvalIdx[it] = out.size }
                    out += MutableRow(
                        HistoryMessage(ChatRole.TOOL, reason.take(MAX_TOOL_TEXT), tool = tool),
                        lineNo,
                    )
                }
                DshTranscript.EVENT_APPROVAL_DECIDED -> {
                    val idx = data?.str("id")?.let(approvalIdx::remove) ?: continue
                    val row = out.getOrNull(idx) ?: continue
                    // Only `allowed-once` ran the tool. `rejected` / `cancelled` / `unavailable` all mean
                    // it did not — collapsing them to one boolean is exactly what the card shows.
                    row.msg = row.msg.copy(ok = DshAsk.outcomeOf(data) == DshAsk.OUTCOME_ALLOW)
                    row.patchLine = lineNo
                }
                // text-chunks / reasoning-chunks / tool-call-chunks are the streaming deltas of content
                // that also arrives whole as assistant/message — see the class comment. Dropping them is
                // what keeps each assistant turn from appearing twice.
                else -> {}
            }
        }
        return out.map { ReplaySlicer.Row(it.msg, it.line, it.patchLine) } to lineNo
    }

    /** A row while it can still be patched by a later record (the answer / the decision). */
    private class MutableRow(var msg: HistoryMessage, val line: Long, var patchLine: Long = 0L)

    /** The app's renderer key for the answered-question card (issue #110). */
    private const val ASK_TOOL = "AskUserQuestion"

    /** Same clip the Claude replay applies to a tool row's text. */
    private const val MAX_TOOL_TEXT = 4000

    /** Same output cap as the OpenCode/Kimi/ZCode structured replay cards. */
    private const val MAX_TOOL_OUTPUT = 4000
}
