package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Reads a `dsh` session transcript (issue #255).
 *
 * PHYSICAL FORMAT (probe-verified, rc.6): `session.jsonl.zstd` is a sequence of CONCATENATED zstd frames —
 * frame 1 holds the header line, then one frame per durable batch, each XXH64-checked. Two consequences
 * drive this file:
 *  1. the decoder must walk a frame SEQUENCE (a single-frame decode silently stops after the header), and
 *  2. **an incomplete final frame is NORMAL, not corruption** — it means dsh is writing right now. We take
 *     the complete prefix and move on; reporting damage here would make every live session look broken.
 *
 * LOGICAL FORMAT: line 1 is the session header `{"type":"session","version":0,"id":…,"cwd":…}`; every later
 * line is an event `{type, seq, time, data, ignorable?}`. `version != 0` means a format we have never seen
 * — the whole session is skipped rather than guessed at.
 *
 * Parsing is defensive throughout: an unparseable line yields no row instead of an exception, because the
 * tail of a live transcript is expected to be ragged.
 */
object DshTranscript {
    private val log = logger("DshTranscript")
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The only session-file format version this build understands. */
    const val SUPPORTED_VERSION = 0L

    /** Hard ceiling on decompressed bytes we will hold for one session — a runaway/hostile file must not
     *  be able to exhaust the daemon heap just by being listed. */
    private const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

    /** Budget for the session-list read: header + title + opening exchange, never the whole chat. */
    private const val SUMMARY_BYTES = 512L * 1024

    /** Budget for a header-only read (the first record of the first frame). */
    private const val HEADER_BYTES = 256L * 1024

    /** Per-line clip before JSON parse (same guard as the Claude/Kimi replays, issue #81). */
    private const val MAX_LINE_CHARS = 200_000

    /** The session header line. [cwd] is the ONLY trustworthy cwd for a session — the containing
     *  directory name is a lossy, colliding normalization (see [DshPaths.projectKey]). */
    data class Header(
        val id: String,
        val cwd: String?,
        val createdAt: Long,
        val version: Long,
        val origin: String?,
        val parentSession: String?,
        val delegationDepth: Long,
    ) {
        /**
         * A session some agent spawned rather than the user — internal machinery that must not reach the
         * session list (same rule as ZCode's scanner).
         *
         * THREE signals, not one (issue #288): dsh's own header validation makes `origin` OPTIONAL
         * (`delegationDepth` is the required field), and plugin-created child sessions
         * (dsh-background-agents / dsh-routed-subagent …) legally omit it — a user's list was polluted
         * exactly that way. `parentSession` and a positive `delegationDepth` each independently mark a
         * derived session, so any of the three counts.
         */
        val isSubagent: Boolean get() =
            origin == "subagent" || parentSession != null || delegationDepth > 0
        val isSupported: Boolean get() = version == SUPPORTED_VERSION
    }

    /**
     * Decompress + split a transcript into COMPLETE lines.
     *
     * A trailing partial line (no terminating newline) is dropped: it is a half-flushed record, and
     * handing it to a JSON parser would only produce noise.
     */
    fun lines(file: Path, maxBytes: Long = MAX_DECOMPRESSED_BYTES): List<String> {
        if (!file.isRegularFile()) return emptyList()
        val bytes = if (file.fileName.toString().endsWith(".zstd")) {
            decompressPrefix(file, maxBytes)
        } else {
            readPlain(file, maxBytes)
        }
        if (bytes.isEmpty()) return emptyList()
        val text = String(bytes, Charsets.UTF_8)
        val split = text.split('\n')
        // The last element is "" when the text ended on a newline (every line complete); otherwise it is
        // a half-flushed record — or, when we stopped on the byte budget, an arbitrary cut. Drop it either
        // way: a partial line only ever produces parser noise.
        val complete = split.subList(0, (split.size - 1).coerceAtLeast(0))
        return complete.filter { it.isNotBlank() }
    }

    /**
     * Cheap read for the LISTING path: enough bytes to cover the header and the opening exchange, never
     * the whole transcript. The directory list touches every session on the machine, so decompressing
     * each one in full would make opening the project picker cost seconds and hundreds of MB.
     */
    fun summaryLines(file: Path): List<String> = lines(file, maxBytes = SUMMARY_BYTES)

    /** Just the header line — the first record of the first frame. */
    fun headerLine(file: Path): String? = lines(file, maxBytes = HEADER_BYTES).firstOrNull()

    /**
     * Decode as much of a concatenated-zstd file as is intact.
     *
     * `setContinuous(true)` is the load-bearing call: it tells zstd-jni that hitting the end of the input
     * mid-frame is an expected end-of-stream rather than an error, which is exactly the state a session
     * being appended to is in. The surrounding runCatching is belt-and-braces for a genuinely damaged
     * middle frame — we still return the good prefix accumulated so far.
     */
    private fun decompressPrefix(file: Path, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        runCatching {
            ZstdInputStreamNoFinalizer(BufferedInputStream(Files.newInputStream(file))).use { zin ->
                zin.setContinuous(true)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = zin.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() >= maxBytes) break
                }
            }
        }.onFailure {
            // Not an error path worth surfacing: a live writer lands here routinely.
            log.debug("dsh transcript $file decode stopped early (${it.javaClass.simpleName}); using ${out.size()}B prefix")
        }
        return out.toByteArray()
    }

    private fun readPlain(file: Path, maxBytes: Long): ByteArray =
        runCatching {
            Files.newInputStream(file).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() >= maxBytes) break
                }
                out.toByteArray()
            }
        }.getOrDefault(ByteArray(0))

    /** Parse the header (first line). Null when the file is empty/unreadable or the first line is not a
     *  `type:"session"` record — both mean "not a transcript we can speak for". */
    fun header(file: Path): Header? = headerOf(headerLine(file))

    internal fun headerOf(firstLine: String?): Header? {
        val root = parseLine(firstLine ?: return null) ?: return null
        if (root.str("type") != "session") return null
        val id = root.str("id") ?: return null
        return Header(
            id = id,
            // read VERBATIM — never reconstructed from the directory name
            cwd = root.str("cwd")?.takeIf { it.isNotBlank() },
            createdAt = root.long("createdAt") ?: 0L,
            // a missing version is NOT assumed to be 0: an older/newer writer that omits it is unknown
            // territory, and -1 routes it to the same "skip" branch as an explicitly unsupported version.
            version = root.long("version") ?: -1L,
            origin = root.str("origin"),
            parentSession = root.str("parentSession"),
            // absent reads as 0 (a root session): the field is formally required upstream, but a header
            // that omits it must not make every session look derived — filtering is fail-open here
            delegationDepth = root.long("delegationDepth") ?: 0L,
        )
    }

    /**
     * The LAST `session/title` of the WHOLE transcript — the rename channel (issue #289).
     *
     * dsh renames a session by APPENDING a `session/title` event, so the summary-budget read (which
     * assumes the title lives near the top) misses every rename once the chat outgrows [SUMMARY_BYTES].
     * This streams the full file line-by-line WITHOUT materializing it: O(1) memory, bounded by
     * [MAX_DECOMPRESSED_BYTES]. Cheap in the common case — a line is JSON-parsed only after a plain
     * substring hit on the event name. Null when the transcript has no title event at all.
     */
    fun lastTitle(file: Path): String? {
        var title: String? = null
        val needle = "\"$EVENT_TITLE\""
        forEachLine(file, MAX_DECOMPRESSED_BYTES) { line ->
            if (needle !in line) return@forEachLine
            val root = parseLine(line) ?: return@forEachLine
            if (root.str("type") != EVENT_TITLE) return@forEachLine
            root.obj("data")?.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
        }
        return title
    }

    /**
     * Stream every line through [onLine] without holding the transcript in memory. Same decoding
     * tolerance as [lines] (concatenated zstd, ragged live tail survives as a truncated prefix), at most
     * [maxBytes] decompressed bytes visited. Unlike [lines] a half-flushed FINAL line IS offered to
     * [onLine] — callers parse defensively ([parseLine] rejects truncated JSON), which is equivalent to
     * dropping it, and it saves this reader from re-implementing newline bookkeeping around a charset
     * decoder (a raw byte-chunk split would corrupt multi-byte UTF-8 on the chunk boundary).
     */
    private fun forEachLine(file: Path, maxBytes: Long, onLine: (String) -> Unit) {
        if (!file.isRegularFile()) return
        var seen = 0L
        runCatching {
            val raw = Files.newInputStream(file)
            val decoded = if (file.fileName.toString().endsWith(".zstd")) {
                ZstdInputStreamNoFinalizer(BufferedInputStream(raw)).also { it.setContinuous(true) }
            } else {
                raw
            }
            // budget the DECOMPRESSED bytes: a hostile/runaway file must not stream forever
            val bounded = object : java.io.FilterInputStream(decoded) {
                override fun read(): Int =
                    if (seen >= maxBytes) -1 else super.read().also { if (it >= 0) seen += 1 }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (seen >= maxBytes) return -1
                    val n = super.read(b, off, len)
                    if (n > 0) seen += n
                    return n
                }
            }
            bounded.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            }
        }.onFailure {
            // live writers land here routinely (ragged final frame) — the complete prefix was streamed
            log.debug("dsh transcript $file stream stopped early (${it.javaClass.simpleName}) after ${seen}B")
        }
    }

    /** The session's title: `session/title` events are LAST-WINS (dsh re-titles as the chat develops).
     *  Falls back to the first user message, clipped. Null when the transcript carries neither. */
    fun title(lines: List<String>): String? {
        var title: String? = null
        var firstUser: String? = null
        for (line in lines) {
            val root = parseLine(line) ?: continue
            when (root.str("type")) {
                EVENT_TITLE -> root.obj("data")?.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                EVENT_USER -> if (firstUser == null) {
                    firstUser = messageText(root.obj("data"))?.takeIf { it.isNotBlank() }
                }
            }
        }
        return title ?: firstUser?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
    }

    /** Count of user turns — the session list's `messageCount`. */
    fun countUserMessages(lines: List<String>): Int =
        lines.count { parseLine(it)?.str("type") == EVENT_USER }

    // ---- shared line helpers ----

    internal fun parseLine(raw: String): JsonObject? {
        val line = raw.trim()
        if (line.isEmpty() || line[0] != '{') return null
        val clipped = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
        return runCatching { json.parseToJsonElement(clipped) }.getOrNull() as? JsonObject
    }

    /**
     * Pull display text out of a message event's `data`.
     *
     * ⚠️ THE TWO MESSAGE EVENTS ARE ASYMMETRIC (source-verified, rc.6) — the single most bug-prone detail
     * of this format:
     *  - `user/message`      → `data` IS the Message          → text at `data.content[].text`
     *  - `assistant/message` → `data` WRAPS it under `message` → text at `data.message.content[].text`
     *
     * Both are handled here so callers never have to remember which is which. `content` is a
     * `ContentBlock[]`; only `text` blocks are chat text — `reasoning` blocks are thinking and
     * `tool-call` / `tool-result` / `image` blocks are not text at all, so [blocksText] drops them.
     */
    internal fun messageText(data: JsonObject?): String? {
        data ?: return null
        // assistant/message: one level down
        (data["message"] as? JsonObject)?.let { inner -> blocksText(inner["content"])?.let { return it } }
        // user/message: the Message itself
        blocksText(data["content"])?.let { return it }
        // tolerated fallbacks — a plain string content, or a bare `text`. Cheap, and they cost nothing
        // if dsh never writes them.
        (data["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        return data.str("text")?.takeIf { it.isNotBlank() }
    }

    /** Join the `text` blocks of a `ContentBlock[]`. Non-text block kinds (`reasoning`, `tool-call`,
     *  `tool-result`, `image`) are deliberately skipped: none of them is chat prose, and folding
     *  `reasoning` in would leak the model's thinking into the transcript as if it were a reply. */
    private fun blocksText(el: JsonElement?): String? {
        val arr = el as? JsonArray ?: return null
        val sb = StringBuilder()
        for (item in arr) {
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.let { sb.append(it) }
                is JsonObject -> if (item.str("type") == "text") item.str("text")?.let { sb.append(it) }
                else -> {}
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * Concatenate the payload of one of dsh's BATCHED storage rows (`text-chunks` / `reasoning-chunks` /
     * `tool-call-chunks`), which compress ≥3 consecutive same-kind streaming chunks onto one line as
     * `{type, seq0, time0, data:{turn, step, index, dt[], texts[]|args[]}}` (source-verified, rc.6).
     *
     * Member *k* of the batch reconstructs to `seq = seq0 + k` and `time = time0 + Σdt[0..k-1]`. We only
     * need the text here, so the timing bases are ignored.
     *
     * NOTE these rows are STORAGE-ONLY: they are the streaming deltas, and the same content also lands as
     * a complete `assistant/message`. The replay therefore does NOT use this — see [DshTranscriptReplay].
     * It exists for diagnostics and for a future live-tail reader. An unrecognized shape yields null; the
     * line is skipped, never fatal.
     */
    internal fun chunkText(root: JsonObject): String? {
        val data = root.obj("data") ?: return null
        val arr = (data["texts"] as? JsonArray) ?: (data["args"] as? JsonArray) ?: return null
        val sb = StringBuilder()
        for (item in arr) {
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.let { sb.append(it) }
                is JsonObject -> (item.str("text") ?: item.str("delta"))?.let { sb.append(it) }
                else -> {}
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    // event `type` discriminants we act on
    const val EVENT_USER = "user/message"
    const val EVENT_ASSISTANT = "assistant/message"
    const val EVENT_TITLE = "session/title"
    const val EVENT_TEXT_CHUNKS = "text-chunks"
    const val EVENT_REASONING_CHUNKS = "reasoning-chunks"
    const val EVENT_TOOL_CALL_CHUNKS = "tool-call-chunks"
}
