package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * "What did this session touch, and show me one of those files" — backs ListSessionFiles/ReadFile/
 * ReadFileDiff.
 *
 * The changed-file set is re-derived from the session's own transcript on every call (never cached,
 * never phone-supplied): for Claude, `tool_use` inputs of the file-writing tools; for Codex, the
 * `*** Update/Add/Delete File:` envelopes inside apply_patch tool-call arguments. [readFile] serves
 * ONLY paths in that set — the phone already sees these files through the transcript it can replay,
 * so this adds no read surface beyond it (an arbitrary-path read would bypass the approval firewall).
 *
 * Line-level data ([ChangedFile.adds]/[dels] and [fileDiff]) rides the same scan: Claude transcripts
 * carry ready-made `structuredPatch` hunks on each Edit/Write `toolUseResult` (a full-file Write
 * additionally carries `content`, synthesized here into an all-added hunk); Codex patch envelopes
 * ARE hunks, minus line numbers. Both normalize to unified-diff text — nothing is read from disk.
 */
object SessionFilesService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keep one FileContent/FileDiff frame well under the 4 MiB relay cap (base64 + JSON + E2E headroom). */
    const val TEXT_CAP_BYTES = 256_000
    const val BINARY_CAP_BYTES = 1_800_000
    const val DIFF_CAP_BYTES = 256_000

    private val imageTypes = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    )

    /** Documents the clients hand to a native viewer (QuickLook / system app) instead of rendering
     *  (issues #67/#79). Served as base64 like images; NEVER truncated — a capped docx is corrupt,
     *  so oversized ones fail with an explicit size message instead. */
    private val documentTypes = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
    )

    /** Newest-touched first. Empty when the transcript is missing/unreadable. */
    fun changedFiles(agent: AgentKind, workdir: String, sessionId: String): List<ChangedFile> {
        val file = transcriptFor(agent, workdir, sessionId) ?: return emptyList()
        return changedFilesIn(agent, file, workdir)
    }

    /** [changedFiles] against an explicit transcript [file] — testable without touching `$HOME`. */
    internal fun changedFilesIn(agent: AgentKind, file: Path, workdir: String): List<ChangedFile> {
        return scan(agent, file, workdir, diffFor = null).map { (path, acc) ->
            ChangedFile(path, op = acc.op, edits = acc.edits, adds = acc.adds, dels = acc.dels)
        }.asReversed()
    }

    /** One capped read of a file [changedFiles] listed. Never throws; failures ride FileContent.error. */
    fun readFile(agent: AgentKind, workdir: String, sessionId: String, path: String): FileContent {
        val transcript = transcriptFor(agent, workdir, sessionId)
            ?: return FileContent(workdir, sessionId, path, ok = false, error = "session transcript not found")
        return readFileIn(agent, transcript, workdir, sessionId, path)
    }

    /** [readFile] against an explicit transcript [file] — testable without touching `$HOME`. */
    internal fun readFileIn(agent: AgentKind, transcript: Path, workdir: String, sessionId: String, path: String): FileContent {
        fun fail(error: String) = FileContent(workdir, sessionId, path, ok = false, error = error)
        val allowed = scan(agent, transcript, workdir, diffFor = null).keys
        val abs = resolve(path, workdir) ?: return fail("bad path")
        if (abs !in allowed) return fail("not a file this session changed")
        val file = Path.of(abs)
        if (!file.isRegularFile()) return fail("file no longer exists")
        val total = runCatching { file.fileSize() }.getOrDefault(0L)

        // whole-or-nothing base64 for anything the client hands off to a native viewer
        fun binary(mediaType: String): FileContent {
            if (total > BINARY_CAP_BYTES) {
                return fail("file too large to send (${total / 1024} KB — the link caps transfers at ${BINARY_CAP_BYTES / 1024} KB)")
            }
            val bytes = runCatching { java.nio.file.Files.readAllBytes(file) }.getOrElse { return fail("unreadable: ${it.message}") }
            return FileContent(
                workdir, sessionId, path,
                base64 = Base64.getEncoder().encodeToString(bytes), mediaType = mediaType, totalBytes = total,
            )
        }

        val ext = abs.substringAfterLast('.', "").lowercase()
        imageTypes[ext]?.let { return binary(it) }
        documentTypes[ext]?.let { return binary(it) }

        val bytes = runCatching {
            java.nio.file.Files.newInputStream(file).use { it.readNBytes(TEXT_CAP_BYTES) }
        }.getOrElse { return fail("unreadable: ${it.message}") }
        // unknown-extension binary (NUL sniff): still exportable — share/save beats "can't preview"
        if (bytes.take(8192).any { it == 0.toByte() }) return binary("application/octet-stream")
        return FileContent(
            workdir, sessionId, path,
            text = String(bytes, Charsets.UTF_8), truncated = total > bytes.size, totalBytes = total,
        )
    }

    /** The unified diff of one file [changedFiles] listed. Never throws; failures ride FileDiff.error. */
    fun fileDiff(agent: AgentKind, workdir: String, sessionId: String, path: String): FileDiff {
        val transcript = transcriptFor(agent, workdir, sessionId)
            ?: return FileDiff(workdir, sessionId, path, ok = false, error = "session transcript not found")
        return fileDiffIn(agent, transcript, workdir, sessionId, path)
    }

    /** [fileDiff] against an explicit transcript [file] — testable without touching `$HOME`. */
    internal fun fileDiffIn(agent: AgentKind, transcript: Path, workdir: String, sessionId: String, path: String): FileDiff {
        fun fail(error: String) = FileDiff(workdir, sessionId, path, ok = false, error = error)
        val abs = resolve(path, workdir) ?: return fail("bad path")
        val acc = scan(agent, transcript, workdir, diffFor = abs)[abs] ?: return fail("not a file this session changed")
        val diff = acc.diff.toString()
        if (diff.isEmpty()) return fail("no line-level diff in this session's transcript")
        return FileDiff(
            workdir, sessionId, path,
            diff = diff, adds = acc.adds ?: 0, dels = acc.dels ?: 0, truncated = acc.diffTruncated,
        )
    }

    // --- the one scan both surfaces share ---

    /**
     * Per-file accumulator. [adds]/[dels] stay null until a hunk-bearing record shows up, so files
     * whose transcript has no line-level data (notebooks, pre-structuredPatch CLIs) read "no stats"
     * rather than "+0 −0". [diff] is only filled for the single path a [fileDiff] call asks about.
     */
    private class Acc(var op: String, var edits: Int) {
        var adds: Int? = null
        var dels: Int? = null
        val diff = StringBuilder()
        var diffTruncated = false

        fun stat(a: Int, d: Int) { adds = (adds ?: 0) + a; dels = (dels ?: 0) + d }

        /** Appends one tool call's hunk group whole, or drops it whole — never mid-hunk garbage. */
        fun appendHunks(text: String) {
            when {
                diff.length + text.length <= DIFF_CAP_BYTES -> diff.append(text)
                diff.isEmpty() -> { // a single oversized group: keep whole lines up to the cap
                    val cut = text.take(DIFF_CAP_BYTES)
                    diff.append(cut.substring(0, cut.lastIndexOf('\n') + 1))
                    diffTruncated = true
                }
                else -> diffTruncated = true
            }
        }
    }

    /** Insertion order = oldest-touched first, re-anchored on re-touch (callers reverse for the wire).
     *  [record]'s hunk text is a thunk: list/read calls (diffFor = null) never materialize it — only
     *  the one path a [fileDiff] call asks about pays for diff-text assembly. */
    private fun scan(agent: AgentKind, file: Path, workdir: String, diffFor: String?): LinkedHashMap<String, Acc> {
        val seen = LinkedHashMap<String, Acc>()
        fun touch(rawPath: String, op: String) {
            val abs = resolve(rawPath, workdir) ?: return
            val acc = seen.remove(abs) ?: Acc(op, 0)
            acc.op = op; acc.edits += 1
            seen[abs] = acc
        }
        fun record(rawPath: String, adds: Int, dels: Int, hunks: () -> String) {
            val abs = resolve(rawPath, workdir) ?: return
            val acc = seen.getOrPut(abs) { Acc("edit", 0) }
            acc.stat(adds, dels)
            if (abs == diffFor) acc.appendHunks(hunks())
        }
        when (agent) {
            AgentKind.CLAUDE -> claudeScan(file, ::touch, ::record)
            AgentKind.CODEX -> codexScan(file, ::touch, ::record)
        }
        return seen
    }

    // --- transcript location (same per-backend sources the session list uses) ---

    private fun transcriptFor(agent: AgentKind, workdir: String, sessionId: String): Path? {
        // sessionId is interpolated into a filename; forbid separators/dot-dot so it can't traverse
        if (sessionId.contains('/') || sessionId.contains('\\') || sessionId.contains("..")) return null
        val file = when (agent) {
            AgentKind.CLAUDE -> ProjectPaths.dirFor(workdir).resolve("$sessionId.jsonl")
            AgentKind.CODEX -> CodexPaths.findSession(sessionId)
        }
        return file?.takeIf { it.exists() }
    }

    // --- Claude: tool_use blocks on assistant lines; structuredPatch on user-line toolUseResults ---

    private val claudeOps = mapOf(
        "Write" to "write", "Edit" to "edit", "MultiEdit" to "edit", "NotebookEdit" to "notebook",
    )

    private fun claudeScan(file: Path, touch: (String, String) -> Unit, record: (String, Int, Int, () -> String) -> Unit) {
        forEachJsonLine(file) { obj ->
            when (obj.str("type")) {
                "assistant" -> {
                    val content = (obj["message"] as? JsonObject)?.get("content") as? JsonArray ?: return@forEachJsonLine
                    for (el in content) {
                        val block = el as? JsonObject ?: continue
                        if (block.str("type") != "tool_use") continue
                        val op = claudeOps[block.str("name")] ?: continue
                        val input = block["input"] as? JsonObject ?: continue
                        val p = input.str("file_path") ?: input.str("notebook_path") ?: continue
                        touch(p, op)
                    }
                }
                "user" -> {
                    // Edit/Write results carry filePath + structuredPatch (hunks the CLI computed at
                    // edit time). Write-create ships an empty patch + the full content instead.
                    val tur = obj["toolUseResult"] as? JsonObject ?: return@forEachJsonLine
                    val p = tur.str("filePath") ?: return@forEachJsonLine
                    val patch = tur["structuredPatch"] as? JsonArray ?: return@forEachJsonLine
                    if (patch.isEmpty() && tur.str("type") == "create") {
                        val body = tur.str("content") ?: return@forEachJsonLine
                        val lines = body.lineSequence().toList()
                        record(p, lines.size, 0) {
                            buildString {
                                append("@@ -0,0 +1,${lines.size} @@\n")
                                lines.forEach { append('+').append(it).append('\n') }
                            }
                        }
                        return@forEachJsonLine
                    }
                    var adds = 0; var dels = 0; var any = false
                    for (h in patch) {
                        val hunk = h as? JsonObject ?: continue
                        val lines = (hunk["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                        any = true
                        for (l in lines) { if (l.startsWith("+")) adds++ else if (l.startsWith("-")) dels++ }
                    }
                    if (any) record(p, adds, dels) {
                        buildString {
                            for (h in patch) {
                                val hunk = h as? JsonObject ?: continue
                                val lines = (hunk["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                                append("@@ -${hunk.int("oldStart")},${hunk.int("oldLines")} +${hunk.int("newStart")},${hunk.int("newLines")} @@\n")
                                for (l in lines) append(l).append('\n')
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Codex: apply_patch envelopes inside tool-call arguments ---
    // The patch body reaches the rollout as a nested JSON string, so newlines may appear either raw
    // or as literal `\n` — [codexPatchText] recovers the raw text (JSON-decoding the arguments
    // document when needed); the path regex stops at raw newlines, quotes and backslashes so it
    // still works on the escaped form as a fallback.

    private val codexPatch = Regex("""\*\*\* (Update|Add|Delete) File: ([^\n"\\]+)""")

    private fun codexScan(file: Path, touch: (String, String) -> Unit, record: (String, Int, Int, () -> String) -> Unit) {
        forEachJsonLine(file) { obj ->
            if (obj.str("type") != "response_item") return@forEachJsonLine
            val p = obj["payload"] as? JsonObject ?: return@forEachJsonLine
            val body = when (p.str("type")) {
                "function_call" -> p.str("arguments")
                "custom_tool_call" -> p.str("input")
                else -> null
            } ?: return@forEachJsonLine
            val patch = codexPatchText(body)
            if (patch != null) {
                for ((verb, path, section) in codexSections(patch)) {
                    val op = when (verb) { "Add" -> "write"; "Delete" -> "delete"; else -> "edit" }
                    touch(path, op)
                    val adds = section.count { it.startsWith("+") }
                    val dels = section.count { it.startsWith("-") }
                    if (adds > 0 || dels > 0) record(path, adds, dels) { codexHunkText(section) }
                }
            } else {
                // unparseable arguments — keep the pre-diff behavior: paths only, no stats
                for (m in codexPatch.findAll(body)) {
                    val op = when (m.groupValues[1]) { "Add" -> "write"; "Delete" -> "delete"; else -> "edit" }
                    touch(m.groupValues[2].trim(), op)
                }
            }
        }
    }

    /**
     * The raw patch text out of a tool-call body: as-is, or fished from the arguments JSON document.
     * Real newlines are the tell — a raw patch must contain them, while a JSON-string-escaped body
     * can't (JSON forbids raw newlines in strings). Checking for literal `\n` instead would misread
     * patches whose CODE contains the two-character sequence.
     */
    private fun codexPatchText(body: String): String? {
        if ("*** Begin Patch" in body && '\n' in body) return body
        fun JsonElement.findPatch(): String? = when (this) {
            is JsonPrimitive -> contentOrNull?.takeIf { "*** Begin Patch" in it }
            is JsonObject -> values.firstNotNullOfOrNull { it.findPatch() }
            is JsonArray -> firstNotNullOfOrNull { it.findPatch() }
        }
        return runCatching { json.parseToJsonElement(body) }.getOrNull()?.findPatch()
    }

    /** Splits a patch into (verb, path, body-lines) per `*** <Verb> File:` header, markers dropped. */
    private fun codexSections(patch: String): List<Triple<String, String, List<String>>> {
        val heads = codexPatch.findAll(patch).toList()
        return heads.mapIndexed { i, m ->
            val end = if (i + 1 < heads.size) heads[i + 1].range.first else patch.length
            // trim marker lines and the blank edges only — a " " context line mid-hunk is real content
            val body = patch.substring(m.range.last + 1, end)
                .lines().filterNot { it.startsWith("*** ") }
                .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            Triple(m.groupValues[1], m.groupValues[2].trim(), body)
        }
    }

    /**
     * One section's lines as a unified hunk group. apply_patch context lines already carry the
     * leading space and +/− prefixes; its `@@` locators have no line numbers, so headers become the
     * `@@ -0,0 +0,0 @@` sentinel the protocol documents (a section with no locator gets one).
     */
    private fun codexHunkText(section: List<String>): String = buildString {
        var open = false
        for (line in section) {
            if (line.startsWith("@@")) {
                append("@@ -0,0 +0,0 @@\n"); open = true
            } else {
                if (!open) { append("@@ -0,0 +0,0 @@\n"); open = true }
                append(line).append('\n')
            }
        }
    }

    // --- shared plumbing ---

    private inline fun forEachJsonLine(file: Path, block: (JsonObject) -> Unit) {
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                    block(obj)
                }
            }
        }
    }

    /** Absolute normalized form; Codex patch paths are workdir-relative, Claude's are absolute. */
    private fun resolve(raw: String, workdir: String): String? = runCatching {
        val p = Path.of(raw)
        (if (p.isAbsolute) p else Path.of(workdir).resolve(p)).normalize().toString()
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
}
