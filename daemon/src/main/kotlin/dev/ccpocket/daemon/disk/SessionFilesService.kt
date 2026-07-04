package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.codex.CodexPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.FileContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * "What did this session touch, and show me one of those files" — backs ListSessionFiles/ReadFile.
 *
 * The changed-file set is re-derived from the session's own transcript on every call (never cached,
 * never phone-supplied): for Claude, `tool_use` inputs of the file-writing tools; for Codex, the
 * `*** Update/Add/Delete File:` envelopes inside apply_patch tool-call arguments. [readFile] serves
 * ONLY paths in that set — the phone already sees these files through the transcript it can replay,
 * so this adds no read surface beyond it (an arbitrary-path read would bypass the approval firewall).
 */
object SessionFilesService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keep one FileContent frame well under the 4 MiB relay cap (base64 + JSON + E2E headroom). */
    const val TEXT_CAP_BYTES = 256_000
    const val IMAGE_CAP_BYTES = 1_800_000

    private val imageTypes = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    )

    /** Newest-touched first. Empty when the transcript is missing/unreadable. */
    fun changedFiles(agent: AgentKind, workdir: String, sessionId: String): List<ChangedFile> {
        val file = transcriptFor(agent, workdir, sessionId) ?: return emptyList()
        return changedFilesIn(agent, file, workdir)
    }

    /** [changedFiles] against an explicit transcript [file] — testable without touching `$HOME`. */
    internal fun changedFilesIn(agent: AgentKind, file: Path, workdir: String): List<ChangedFile> {
        // LinkedHashMap keyed by normalized path: last op wins, insertion order re-anchored on re-touch
        val seen = LinkedHashMap<String, ChangedFile>()
        fun touch(rawPath: String, op: String) {
            val abs = resolve(rawPath, workdir) ?: return
            val prev = seen.remove(abs)
            seen[abs] = ChangedFile(abs, op = op, edits = (prev?.edits ?: 0) + 1)
        }
        when (agent) {
            AgentKind.CLAUDE -> claudeTouches(file, ::touch)
            AgentKind.CODEX -> codexTouches(file, ::touch)
        }
        return seen.values.toList().asReversed()
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
        val allowed = changedFilesIn(agent, transcript, workdir).map { it.path }.toSet()
        val abs = resolve(path, workdir) ?: return fail("bad path")
        if (abs !in allowed) return fail("not a file this session changed")
        val file = Path.of(abs)
        if (!file.isRegularFile()) return fail("file no longer exists")
        val total = runCatching { file.fileSize() }.getOrDefault(0L)

        val mediaType = imageTypes[abs.substringAfterLast('.', "").lowercase()]
        if (mediaType != null) {
            if (total > IMAGE_CAP_BYTES) return fail("image too large to send (${total / 1024} KB)")
            val bytes = runCatching { java.nio.file.Files.readAllBytes(file) }.getOrElse { return fail("unreadable: ${it.message}") }
            return FileContent(
                workdir, sessionId, path,
                base64 = Base64.getEncoder().encodeToString(bytes), mediaType = mediaType, totalBytes = total,
            )
        }

        val bytes = runCatching {
            java.nio.file.Files.newInputStream(file).use { it.readNBytes(TEXT_CAP_BYTES) }
        }.getOrElse { return fail("unreadable: ${it.message}") }
        if (bytes.take(8192).any { it == 0.toByte() }) return fail("binary file — can't preview")
        return FileContent(
            workdir, sessionId, path,
            text = String(bytes, Charsets.UTF_8), truncated = total > bytes.size, totalBytes = total,
        )
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

    // --- Claude: tool_use blocks on assistant lines ---

    private val claudeOps = mapOf(
        "Write" to "write", "Edit" to "edit", "MultiEdit" to "edit", "NotebookEdit" to "notebook",
    )

    private fun claudeTouches(file: Path, touch: (String, String) -> Unit) {
        forEachJsonLine(file) { obj ->
            if (obj.str("type") != "assistant") return@forEachJsonLine
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
    }

    // --- Codex: apply_patch envelopes inside tool-call arguments ---
    // The patch body reaches the rollout as a nested JSON string, so newlines may appear either raw
    // or as literal `\n` — the path match stops at both, and at a closing quote.

    private val codexPatch = Regex("""\*\*\* (Update|Add|Delete) File: ([^\n"\\]+)""")

    private fun codexTouches(file: Path, touch: (String, String) -> Unit) {
        forEachJsonLine(file) { obj ->
            if (obj.str("type") != "response_item") return@forEachJsonLine
            val p = obj["payload"] as? JsonObject ?: return@forEachJsonLine
            val body = when (p.str("type")) {
                "function_call" -> p.str("arguments")
                "custom_tool_call" -> p.str("input")
                else -> null
            } ?: return@forEachJsonLine
            for (m in codexPatch.findAll(body)) {
                val op = when (m.groupValues[1]) { "Add" -> "write"; "Delete" -> "delete"; else -> "edit" }
                touch(m.groupValues[2].trim(), op)
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
}
