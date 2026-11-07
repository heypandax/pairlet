package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.disk.ReplayRead
import dev.ccpocket.observability.*

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.disk.ReplaySlicer
import dev.ccpocket.daemon.disk.sizeOrNull
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

/**
 * Flattens a Codex rollout `.jsonl` into [HistoryMessage]s for replaying a resumed chat. Mirrors the Claude
 * [dev.ccpocket.daemon.disk.TranscriptReplay]: user + assistant text + tool calls, skipping the Codex-injected
 * context blocks (env/permission wrappers, AGENTS.md dump, @-file expansion — see [isSyntheticUserText]). Schema: `response_item` payloads —
 * `message` (role user `input_text` / assistant `output_text`), `function_call`, `web_search_call`, `custom_tool_call`.
 */
object CodexTranscriptReplay {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(file: Path, maxMessages: Int = 100, maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES): List<HistoryMessage> =
        slice(file, sinceSeq = null, maxMessages = maxMessages, maxFrameTextBytes = maxFrameTextBytes).messages

    /** A late tool completion patches its original row. The shared slicer falls back to a full window
     *  when that row was already delivered, so a reconnect cannot lose the outcome in an empty delta. */
    fun slice(
        file: Path,
        sinceSeq: Long?,
        maxMessages: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val parsed = parse(file)
        return ReplaySlicer.slice(parsed.first, parsed.second, sinceSeq, maxMessages, maxFrameTextBytes)
            .copy(quality = parsed.quality, sourceRows = parsed.second, failedRows = parsed.failedRows, sourceBytes = file.sizeOrNull())
    }

    /** One page of history OLDER than [beforeSeq] — the scroll-to-top lazy load (issue #147). */
    fun page(
        file: Path,
        beforeSeq: Long,
        limit: Int = 100,
        maxFrameTextBytes: Long = ReplayBudget.MAX_FRAME_TEXT_BYTES,
    ): ReplaySlice {
        val parsed = parse(file)
        return ReplaySlicer.page(parsed.first, beforeSeq, limit, maxFrameTextBytes)
            .copy(quality = parsed.quality, sourceRows = parsed.second, failedRows = parsed.failedRows)
    }

    /** Parse the rollout into rows tagged with their source line (the #147 seq) + the total line count
     *  (the cursor). Every raw line advances the cursor — stable under append-only growth. */
    private fun parse(file: Path): ReplayRead<ReplaySlicer.Row> {
        if (!file.exists()) return ReplayRead(emptyList(), 0L, "unavailable")
        val state = State()
        var readFailed = false
        runCatching { file.bufferedReader().useLines { lines -> lines.forEach(state::consume) } }
        .onFailure { readFailed = true; Diagnostics.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, it,
            SafeMetrics(totalCount = state.lineNo, returnedCount = state.out.size.toLong(), resultQuality = ResultQuality.PARTIAL)) }
        if (state.malformed > 0) {
            val corrupt = state.malformed > 1 || state.lastMalformed != state.lineNo
            Diagnostics.report(ErrorPath.HISTORY_READ, Stage.PARSE,
                if (corrupt) ErrorCode.DECODE_FAILED else ErrorCode.INCOMPLETE,
                if (corrupt) state.firstError else null,
                SafeMetrics(totalCount = state.lineNo, failedCount = state.malformed, returnedCount = state.out.size.toLong(), resultQuality = ResultQuality.PARTIAL))
        }
        return ReplayRead(state.out, state.lineNo, if (readFailed || state.malformed > 0) "partial" else "complete", state.malformed)
    }

    /** One observer owns one cursor. Paging remains an independent full-file read. */
    internal class Tail(
        private val openFile: (Path) -> java.io.RandomAccessFile = { java.io.RandomAccessFile(it.toFile(), "r") },
    ) {
        var retryRead = false
            private set
        private var initialized = false
        private var path: Path? = null
        private var key: Any? = null
        private var size = -1L
        private var modified: java.nio.file.attribute.FileTime? = null
        private var offset = 0L
        private var boundary = byteArrayOf()
        private var state = State()
        val runtime get() = state.runtime
        val turns get() = state.turns
        val title get() = state.title
        internal var bytesRead = 0L
            private set

        fun slice(file: Path, sinceSeq: Long?): ReplaySlice = try {
            readSlice(file, sinceSeq).also { retryRead = false }
        } catch (error: java.io.IOException) {
            // A concurrent rotation/short read must not terminate the observer or erase its UI.
            // Discard the cursor, since the reducer may already contain part of this failed read.
            path = null
            retryRead = true
            Diagnostics.report(ErrorPath.HISTORY_READ, Stage.READ, ErrorCode.READ_FAILED, error,
                SafeMetrics(resultQuality = ResultQuality.PARTIAL))
            ReplaySlice(emptyList(), delta = true, quality = "unavailable")
        }

        private fun readSlice(file: Path, sinceSeq: Long?): ReplaySlice {
            val attrs = try { java.nio.file.Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes::class.java) }
                catch (_: java.nio.file.NoSuchFileException) { null }
            if (attrs == null) {
                path = null
                state = State()
                return ReplaySlice(emptyList(), quality = "unavailable")
            }
            var reset = path != file || key != attrs.fileKey() || attrs.size() < size ||
                (attrs.size() == size && modified != attrs.lastModifiedTime())
            openFile(file).use { input ->
                if (!reset && boundary.isNotEmpty()) {
                    input.seek(offset - boundary.size)
                    val actual = ByteArray(boundary.size)
                    reset = input.read(actual) != actual.size || !actual.contentEquals(boundary)
                }
                if (reset) { state = State(); offset = 0L }
                input.seek(offset)
                val buffer = ByteArray(64 * 1024)
                val line = java.io.ByteArrayOutputStream()
                var remaining = attrs.size() - offset
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    bytesRead += count
                    remaining -= count
                    var start = 0
                    for (index in 0 until count) if (buffer[index] == 10.toByte()) {
                        line.write(buffer, start, index - start)
                        state.consume(line.toString(Charsets.UTF_8.name()))
                        offset += line.size() + 1L
                        line.reset()
                        state.trim()
                        start = index + 1
                    }
                    line.write(buffer, start, count - start)
                }
                // Unfinished JSON / UTF-8 is retried next tick; it never advances the cursor.
                boundary = ByteArray(minOf(offset, 128L).toInt())
                input.seek(offset - boundary.size)
                input.readFully(boundary)
            }
            path = file; key = attrs.fileKey(); size = attrs.size(); modified = attrs.lastModifiedTime()
            val cursor = sinceSeq?.takeUnless { (reset && initialized) || it < state.discardedThrough }
            initialized = true
            val result = ReplaySlicer.slice(state.out, state.lineNo, cursor, 100, ReplayBudget.MAX_FRAME_TEXT_BYTES)
            return result.copy(
                hasMore = result.hasMore || (!result.delta && state.discardedThrough > 0),
                quality = if (state.malformed > 0 || offset < attrs.size()) "partial" else "complete",
                sourceRows = state.lineNo, failedRows = state.malformed, sourceBytes = attrs.size(),
            )
        }
    }

    private class State {
        var runtime = CodexTranscriptScanner.RuntimeState()
        var turns: dev.ccpocket.daemon.conversation.TurnEvidence? = null
        var title: String? = null
        val out = ArrayList<ReplaySlicer.Row>()
        val toolIndices = HashMap<String, Int>()
        val completions = HashMap<String, Pair<Boolean, Long>>()
        fun patch(id: String, ok: Boolean, line: Long) {
            val index = toolIndices[id] ?: return
            val row = out[index]
            if (row.msg.ok != ok) out[index] = row.copy(msg = row.msg.copy(ok = ok), patchLine = line)
        }
        var lineNo = 0L
        var malformed = 0L
        var lastMalformed = -1L
        var firstError: Throwable? = null

        var discardedThrough = 0L
        fun trim() {
            if (out.size > 1024) {
                val removed = out.size - 512
                discardedThrough = out[removed - 1].line
                out.subList(0, removed).clear()
                toolIndices.entries.removeIf { it.value < removed }
                toolIndices.replaceAll { _, index -> index - removed }
            }
            if (completions.size > 2048) {
                completions.entries.removeIf { it.key !in toolIndices && it.value.second < lineNo - 2048 }
            }
        }

        fun consume(raw: String) {
            lineNo += 1
            val line = raw.trim()
            if (line.isEmpty()) return
            val obj = runCatching { json.parseToJsonElement(line) }.onFailure {
                malformed++; lastMalformed = lineNo
                if (firstError == null) firstError = it
            }.getOrNull() as? JsonObject ?: return
            runtime = CodexTranscriptScanner.mergeRuntimeState(runtime, obj)
            turns = CodexTranscriptScanner.mergeTurnEvidence(turns, obj)
            val p = obj.obj("payload") ?: return
            if (obj.str("type") == "compacted") {
                out += ReplaySlicer.Row(
                    HistoryMessage(ChatRole.USER, CODEX_COMPACTION_NOTICE, compactSummary = true), lineNo,
                )
                return
            }
            if (obj.str("type") == "event_msg" && p.str("type") == "item_completed") {
                val item = p.obj("item") ?: return
                val id = item.str("id") ?: return
                val ok = codexCompletedToolOutcome(item) ?: return
                completions[id] = ok to lineNo
                patch(id, ok, lineNo)
                return
            }
            if (obj.str("type") != "response_item") return
            when (p.str("type")) {
                "message" -> {
                    val text = codexMessageText(p)?.takeIf { it.isNotBlank() } ?: return
                    when (p.str("role")) {
                        "user" -> if (!isSyntheticUserText(text)) {
                            if (title == null) title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                            out += ReplaySlicer.Row(HistoryMessage(ChatRole.USER, text), lineNo)
                        }
                        "assistant" -> out += ReplaySlicer.Row(HistoryMessage(ChatRole.ASSISTANT, text), lineNo)
                        else -> {}
                    }
                }
                "function_call", "custom_tool_call" -> {
                    val id = p.str("call_id")
                    if (id != null) toolIndices[id] = out.size
                    val completion = completions[id]
                    val input = if (p.str("type") == "function_call") p.str("arguments") else p.str("input")
                    out += ReplaySlicer.Row(
                        HistoryMessage(ChatRole.TOOL, input.orEmpty().take(1000), tool = p.str("name") ?: "tool", ok = completion?.first),
                        lineNo, completion?.second ?: 0L,
                    )
                }
                "web_search_call" -> out += ReplaySlicer.Row(
                    HistoryMessage(ChatRole.TOOL, "", tool = "WebSearch", ok = codexToolStatus(p.str("status"))), lineNo,
                )
                "function_call_output", "custom_tool_call_output" -> {
                    val id = p.str("call_id") ?: return
                    if (id in completions) return // typed completion is authoritative
                    val index = toolIndices[id] ?: return
                    val ok = codexRolloutToolOutcome(out[index].msg.tool.orEmpty(), p) ?: return
                    patch(id, ok, lineNo)
                }
            }
        }
    }
}
