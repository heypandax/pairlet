package dev.ccpocket.daemon.disk

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import com.github.luben.zstd.Zstd
import dev.ccpocket.daemon.dsh.DshPaths
import dev.ccpocket.daemon.dsh.DshTranscript
import dev.ccpocket.daemon.zcode.ZCodePaths
import dev.ccpocket.protocol.AgentKind
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Verified backend-owned session evidence. No synthetic transcript paths or client-owned cwd grants. */
internal class BackendSessionFiles(
    private val dshRoot: () -> Path = DshPaths::sessionsRoot,
    private val zcodeConnection: () -> Connection? = ZCodePaths::connectReadOnly,
    private val maxEvidenceBytes: Long = 64L * 1024 * 1024,
    private val maxEvidenceRows: Int = 100_000,
) {
    data class Change(val path: String, val op: String, val adds: Int? = null, val dels: Int? = null, val diff: String? = null)
    data class Evidence(val changes: List<Change>)

    fun load(agent: AgentKind, workdir: String, sessionId: String, requireChanges: Boolean = true): Result<Evidence> = runCatching {
        require(sessionId.isNotBlank()) { "session id is empty" }
        when (agent) {
            AgentKind.DSH -> dsh(workdir, sessionId, requireChanges)
            AgentKind.ZCODE -> zcode(workdir, sessionId, requireChanges)
            else -> error("unsupported session file source")
        }
    }

    private fun sameDirectory(actual: String?, requested: String): Boolean {
        if (actual.isNullOrBlank() || requested.isBlank()) return false
        val a = Path.of(actual)
        val b = Path.of(requested)
        // Both are daemon-side absolute directories, never a client relative root.
        return a.isAbsolute && b.isAbsolute && a.toRealPath() == b.toRealPath()
    }

    private fun dsh(workdir: String, sid: String, requireChanges: Boolean): Evidence {
        val root = dshRoot().toRealPath()
        val dir = DshPaths.findSessionDir(sid, workdir, root) ?: error("session transcript not found")
        val file = DshPaths.transcriptFile(dir) ?: error("session transcript not found")
        require(file.toRealPath().startsWith(root)) { "session transcript is outside its store" }
        val identity = DshTranscript.header(file) ?: error("session transcript header is unreadable")
        require(identity.isSupported) { "unsupported session transcript format" }
        require(identity.id == sid && sameDirectory(identity.cwd, workdir)) { "session does not belong to this project" }
        if (!requireChanges) return Evidence(emptyList()) // live ragged tails do not block in-tree reads
        // Decode strictly here: the history reader's useful partial-prefix tolerance must not turn
        // a corrupt/truncated transcript into evidence authorizing an outside-project file read.
        val raw = Files.newInputStream(file)
        val bytes = (if (file.fileName.toString().endsWith(".zstd")) ZstdInputStreamNoFinalizer(raw) else raw).use {
            it.readNBytes(MAX_TRANSCRIPT_BYTES + 1)
        }
        require(bytes.size <= MAX_TRANSCRIPT_BYTES) { "session transcript exceeds file-evidence limit" }
        if (file.fileName.toString().endsWith(".zstd")) {
            // Validate the whole physical input, including any frame that yielded no complete output.
            // Stream decoders can stop at a prior complete frame when a following header is partial.
            val compressed = Files.newInputStream(file).use { it.readNBytes(MAX_TRANSCRIPT_BYTES + 1) }
            require(compressed.size <= MAX_TRANSCRIPT_BYTES) { "compressed transcript exceeds file-evidence limit" }
            val validated = ByteArray(bytes.size)
            val count = Zstd.decompress(validated, compressed)
            require(!Zstd.isError(count) && count == bytes.size.toLong() && validated.contentEquals(bytes)) {
                "session transcript compression is incomplete or changed during read"
            }
        }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.endsWith('\n')) { "session transcript is incomplete" }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val header = DshTranscript.headerOf(lines.firstOrNull(), DshPaths.transcriptVersion(file.fileName.toString()))
            ?: error("session transcript header is unreadable")
        require(header.isSupported) { "unsupported session transcript format" }
        require(header.id == sid && sameDirectory(header.cwd, workdir)) { "session does not belong to this project" }
        val pending = HashMap<String, Pair<String, JsonObject>>()
        val changes = mutableListOf<Change>()
        for (line in lines.drop(1)) {
            val event = DshTranscript.parseRecord(line, header.version) ?: error("session transcript record is unreadable")
            if (event["ignorable"]?.jsonPrimitive?.booleanOrNull == true) continue
            val data = event.obj("data") ?: continue
            when (event.str("type")) {
                "tool/call" -> {
                    val call = data.str("callId") ?: continue
                    pending.remove(call) // a reused id must never inherit a previous tool's authority
                    val name = data.str("name")?.takeIf { it in setOf("str_replace_editor", "write", "edit") } ?: continue
                    val args = data.obj("arguments") ?: data.str("arguments")?.let(::parse) ?: continue
                    pending[call] = name to args
                }
                "tool/result" -> {
                    if (DshTranscript.messagePlacement(event, header.version) != DshTranscript.MessagePlacement.APPEND) continue
                    for (part in data.obj("message")?.array("content").orEmpty()) {
                        val block = part as? JsonObject ?: continue
                        if (block.str("type") != "tool-result") continue
                        val (name, args) = pending.remove(block.str("toolCallId")) ?: continue
                        // Released DSH omits isError on successful results; failed/aborted calls set true.
                        // A malformed marker is unknown, not success. The result's content must exist.
                        if (block["isError"] != null && block["isError"]?.jsonPrimitive?.booleanOrNull != false) continue
                        if (data["error"] != null || block.array("content") == null) continue
                        val output = block.array("content").orEmpty().mapNotNull {
                            (it as? JsonObject)?.takeIf { part -> part.str("type") == "text" }?.str("text")
                        }.joinToString("")
                        (if (name == "str_replace_editor") dshChange(args) else dshFsChange(name, args, output))?.let(changes::add)
                    }
                }
            }
        }
        return Evidence(changes)
    }

    /** Official str_replace_editor (DSH 0.1.5) commands; shell and view never authorize reads. */
    private fun dshChange(args: JsonObject): Change? {
        val path = args.str("path")?.takeIf { runCatching { Path.of(it).isAbsolute }.getOrDefault(false) } ?: return null
        return when (args.str("command")) {
            "create" -> {
                val body = args.str("file_text") ?: return null
                replacement(path, "write", "", body, 0, 1)
            }
            "str_replace" -> {
                val old = args.str("old_str")?.takeIf { it.isNotEmpty() } ?: return null
                val fresh = if (args["new_str"] == null) "" else args.str("new_str") ?: return null
                // The exact fragment is known, but its file offset is not. The protocol's zero-locator
                // sentinel (also used for Codex patches) keeps this honest: not a full-file snapshot.
                replacement(path, "edit", old, fresh, 0, 0)
            }
            "insert" -> {
                val fresh = args.str("new_str") ?: return null
                val line = args["insert_line"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return null
                replacement(path, "edit", "", fresh, line, line + 1)
            }
            else -> null
        }
    }

    /** dsh-tool-fs renders only confirmation text; full before/after remains runtime-only. */
    private fun dshFsChange(name: String, args: JsonObject, output: String): Change? {
        if (name == "write") {
            val match = WRITE_RESULT.matchEntire(output) ?: return null
            val path = match.groupValues[1]
            if (!Path.of(path).isAbsolute) return null
            return if (match.groupValues[2] == "Created") {
                replacement(path, "write", "", args.str("content") ?: return null, 0, 1)
            } else Change(path, "write") // overwritten file's prior content is not persisted in this result
        }
        val match = EDIT_RESULT.matchEntire(output) ?: return null
        val path = match.groupValues[1]
        if (!Path.of(path).isAbsolute) return null
        if (match.groupValues[2].isNotEmpty() || args["replace_all"]?.jsonPrimitive?.booleanOrNull == true) {
            return Change(path, "edit") // neither replacement count nor full old content is on disk
        }
        return replacement(path, "edit", args.str("old_string")?.takeIf { it.isNotEmpty() } ?: return null,
            args.str("new_string") ?: return null, 0, 0)
    }

    private fun zcode(workdir: String, sid: String, requireChanges: Boolean): Evidence {
        val conn = zcodeConnection() ?: error("session database not found")
        return conn.use { c ->
            // The same read transaction establishes both ownership and tool evidence.
            c.autoCommit = false
            val cwd = c.prepareStatement("SELECT directory FROM session WHERE id=?").use { st ->
                st.setString(1, sid)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            require(sameDirectory(cwd, workdir)) { "session does not belong to this project" }
            if (!requireChanges) return@use Evidence(emptyList())
            val changes = mutableListOf<Change>()
            val completedCalls = HashSet<String>()
            var byteCount = 0L
            var rowCount = 0
            val partIds = mutableListOf<String>()
            // Preflight only IDs and byte counts: selecting payloads alongside ORDER BY would load
            // every payload into SQLite's sorter before any JVM-side size check could run.
            c.prepareStatement("SELECT p.id,octet_length(p.data) AS part_bytes,octet_length(m.data) AS message_bytes " +
                "FROM part p JOIN message m ON m.id=p.message_id AND m.session_id=p.session_id " +
                "WHERE p.session_id=? ORDER BY m.sequence,m.time_created,m.id,p.sequence,p.time_created,p.id LIMIT ?").use { st ->
                st.setString(1, sid)
                st.setInt(2, maxEvidenceRows + 1)
                st.executeQuery().use { rs -> while (rs.next()) {
                    val partBytes = rs.getLong("part_bytes")
                    val messageBytes = rs.getLong("message_bytes")
                    require(partBytes in 0..MAX_ROW_BYTES && messageBytes in 0..MAX_ROW_BYTES &&
                        ++rowCount <= maxEvidenceRows) { "session file evidence exceeds record limit" }
                    byteCount += partBytes + messageBytes
                    require(byteCount <= maxEvidenceBytes) { "session file evidence exceeds byte limit" }
                    partIds += rs.getString("id")
                } }
            }
            c.prepareStatement("SELECT p.data,m.data AS message_data FROM part p " +
                "JOIN message m ON m.id=p.message_id AND m.session_id=p.session_id WHERE p.id=? AND p.session_id=?").use { st ->
                for (partId in partIds) {
                    st.setString(1, partId)
                    st.setString(2, sid)
                    st.executeQuery().use { rs -> while (rs.next()) {
                        val message = parse(rs.getString("message_data")) ?: continue
                        if (message.str("role") != "assistant" || message.str("visibility") == "model-only" ||
                            message.obj("semantics")?.str("kind") == "compact_summary") continue
                        val p = parse(rs.getString("data")) ?: continue
                        if (p.str("type") != "tool") continue
                        val name = p.str("tool") ?: p.str("toolName")
                        if (name != "Write" && name != "Edit") continue
                        val state = p.obj("state") ?: continue
                        if (state.str("status") != "completed" || state["error"] != null) continue
                        val callId = p.str("callID") ?: p.str("callId")
                        if (callId != null && !completedCalls.add(callId)) continue
                        // ZCode supports user-modified approvals: input is only the proposal. The persisted
                        // completed display patch contains the actual edit; never fabricate diff from input.
                        val display = state.obj("metadata")?.obj("display")
                        if (display == null && name == "Write") {
                            // Official Write-create has empty structuredPatch and therefore no display.
                            // Its exact success text carries the final path after approval, but no actual
                            // file content: record the change without inventing a diff from input.content.
                            zcodeCreatedPath(state.str("output"))?.let { changes += Change(it, "write") }
                            continue
                        }
                        val actualPath = display?.takeIf { it.str("kind") == "file_diff" }?.str("filePath") ?: continue
                        val op = if (name == "Write") "write" else "edit"
                        val actual = structured(actualPath, op, display)
                        // Input is only a proposal. Without an actual-result path there is no changed
                        // file fact to show; a known path without hunks can still be listed honestly.
                        changes += actual ?: Change(actualPath, op)
                    } }
                }
            }
            Evidence(changes)
        }
    }

    /** Narrow ZCode 3.11.2 formatWriteModelContent create-result contract, not free-form prose. */
    private fun zcodeCreatedPath(output: String?): String? {
        if (output == null || !output.startsWith(ZCODE_CREATE_PREFIX)) return null
        val suffix = listOf(ZCODE_CREATE_SUFFIX, ZCODE_CREATE_MODIFIED_SUFFIX).singleOrNull { output.endsWith(it) } ?: return null
        val path = output.removePrefix(ZCODE_CREATE_PREFIX).removeSuffix(suffix)
        // Refuse concatenated confirmations and control characters rather than interpreting them
        // as a filename. Ambiguous/unrecognized output must never grant outside-project reads.
        if (path.isBlank() || path.any { it.isISOControl() } ||
            ZCODE_CREATE_PREFIX in path || ZCODE_CREATE_SUFFIX in path || ZCODE_CREATE_MODIFIED_SUFFIX in path) return null
        return path.takeIf { runCatching { Path.of(it).isAbsolute }.getOrDefault(false) }
    }

    private fun structured(path: String, op: String, display: JsonObject): Change? {
        if (display["truncated"]?.jsonPrimitive?.booleanOrNull == true) return null
        val patch = display.array("structuredPatch")?.takeIf { it.isNotEmpty() } ?: return null
        var adds = 0
        var dels = 0
        val diff = buildString {
            for (entry in patch) {
                val h = entry as? JsonObject ?: return null
                val oldStart = h["oldStart"]?.jsonPrimitive?.intOrNull ?: return null
                val oldLines = h["oldLines"]?.jsonPrimitive?.intOrNull ?: return null
                val newStart = h["newStart"]?.jsonPrimitive?.intOrNull ?: return null
                val newLines = h["newLines"]?.jsonPrimitive?.intOrNull ?: return null
                if (minOf(oldStart, oldLines, newStart, newLines) < 0) return null
                append("@@ -$oldStart,$oldLines +$newStart,$newLines @@\n")
                for (raw in h.array("lines") ?: return null) {
                    val line = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    if ('\n' in line || '\r' in line) return null
                    when (line.firstOrNull()) { '+' -> adds++; '-' -> dels++; ' ', '\\' -> {}; else -> return null }
                    append(line).append('\n')
                }
            }
        }
        return Change(path, op, adds, dels, diff)
    }

    private fun replacement(path: String, op: String, old: String, fresh: String, oldAt: Int, newAt: Int): Change {
        fun lines(s: String) = if (s.isEmpty()) emptyList() else s.removeSuffix("\n").split('\n')
        val before = lines(old)
        val after = lines(fresh)
        val diff = buildString {
            append("@@ -$oldAt,${before.size} +$newAt,${after.size} @@\n")
            before.forEach { append('-').append(it).append('\n') }
            after.forEach { append('+').append(it).append('\n') }
        }
        return Change(path, op, after.size, before.size, diff)
    }

    companion object {
        private const val ZCODE_CREATE_PREFIX = "File created successfully at: "
        private const val ZCODE_CREATE_MODIFIED_SUFFIX = " The user modified your proposed content before accepting it."
        private const val ZCODE_CREATE_SUFFIX = " (file state is current in your context — no need to Read it back)"
        private const val MAX_TRANSCRIPT_BYTES = 64 * 1024 * 1024
        private const val MAX_ROW_BYTES = 8L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val WRITE_RESULT = Regex("<path>([^\\r\\n]+)</path>\n<type>file</type>\n<content>\n(Created|Updated) file\n</content>")
        private val EDIT_RESULT = Regex("The file ([^\\r\\n]+) has been updated(?: successfully|\\. (All occurrences were successfully replaced))\\.")
        fun supports(agent: AgentKind) = agent == AgentKind.DSH || agent == AgentKind.ZCODE
        private fun parse(s: String) = runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull()
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
        private fun JsonObject.array(k: String) = this[k] as? JsonArray
    }
}
