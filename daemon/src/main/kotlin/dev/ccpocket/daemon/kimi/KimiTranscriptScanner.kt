package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Lists resumable Kimi sessions for the phone (issue #206) by reading the GLOBAL `session_index.jsonl`
 * (never by re-deriving the on-disk `workDirKey` slug — that undocumented encoding is the dirKey bug trap).
 * Each index entry carries the session's `workDir`; we match it to the requested workdir with
 * [ProjectPaths.canonicalKey] (the same realpath-normalizing key the cross-backend project merge uses), then
 * read each session's `state.json` for title / lastPrompt / timestamps.
 * The index spelling is probe-verified on 0.34.0 (2026-08-08): entries carry `sessionId` / `sessionDir` /
 * `workDir` verbatim. state.json title/lastPrompt spellings remain assumption-based — everything degrades
 * to an empty list on mismatch (never throws).
 */
object KimiTranscriptScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val LIVE_WINDOW_MS = 20_000L

    /** All Kimi sessions whose recorded workDir matches [workdir], newest-first. */
    fun scan(workdir: String): List<SessionSummary> {
        val target = workdir.takeIf { it.isNotBlank() }?.let(ProjectPaths::canonicalKey) ?: return emptyList()
        return KimiSessionIndex.entries()
            .filter { it.workDir != null && ProjectPaths.canonicalKey(it.workDir) == target }
            .mapNotNull { runCatching { summarize(it) }.getOrNull() }
            .sortedByDescending { it.lastModified }
    }

    /** Every workDir with Kimi history → its newest session mtime (for the directory list). */
    fun cwdsByNewest(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (e in KimiSessionIndex.entries()) {
            val wd = e.workDir ?: continue
            val mtime = sessionMtime(e)
            out.merge(wd, mtime, ::maxOf)
        }
        return out
    }

    private fun sessionDirOf(entry: KimiSessionIndex.Entry): Path? =
        entry.sessionDir?.let { Path.of(it) } ?: KimiPaths.sessionDir(entry.sessionId)

    private fun sessionMtime(entry: KimiSessionIndex.Entry): Long {
        val dir = sessionDirOf(entry) ?: return 0L
        val state = dir.resolve("state.json")
        val src = if (state.exists()) state else dir
        return runCatching { src.getLastModifiedTime().toMillis() }.getOrDefault(0L)
    }

    private fun summarize(entry: KimiSessionIndex.Entry): SessionSummary? {
        val dir = sessionDirOf(entry) ?: return null
        val state = dir.resolve("state.json")
        val stateObj = if (state.isRegularFile()) {
            runCatching { json.parseToJsonElement(state.readText()) }.getOrNull() as? JsonObject
        } else null
        val title = stateObj?.firstStr("title", "name")
        val lastPrompt = stateObj?.firstStr("lastPrompt", "last_prompt", "firstPrompt", "first_prompt")
        val mtime = sessionMtime(entry)
        val wire = KimiPaths.mainWireLog(dir)
        val msgCount = runCatching { if (wire.isRegularFile()) countChatRows(wire) else 0 }.getOrDefault(0)
        return SessionSummary(
            sessionId = entry.sessionId,
            title = title?.takeIf { it.isNotBlank() }
                ?: lastPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                ?: entry.sessionId,
            firstPrompt = lastPrompt ?: "",
            messageCount = msgCount,
            cwd = entry.workDir ?: "",
            lastModified = mtime,
            version = stateObj?.firstStr("version", "cliVersion", "cli_version"),
            live = System.currentTimeMillis() - mtime < LIVE_WINDOW_MS,
            agent = AgentKind.KIMI,
        )
    }

    /** Bounded count of user turns in the transcript (approximate messageCount; App tolerates 0).
     *  wire.jsonl is the internal wire format (probe 0.34.0): real prompts are `turn.prompt` lines. */
    private fun countChatRows(wire: Path): Int {
        var n = 0
        wire.bufferedReader().useLines { lines ->
            for (raw in lines) {
                if (n > 5000) break
                if ("\"turn.prompt\"" in raw) n++
            }
        }
        return n
    }

    private fun JsonObject.firstStr(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it)?.takeIf { s -> s.isNotBlank() } }
}
