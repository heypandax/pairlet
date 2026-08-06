package dev.ccpocket.daemon.kimi

import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Kimi Code CLI (the TypeScript single-binary `kimi`, NOT the legacy Python kimi-cli) stores its data
 * under `$KIMI_CODE_HOME` (default `~/.kimi-code`), per the official data-locations doc:
 *
 * ```
 * ~/.kimi-code
 * ├── config.toml                    # default_model = "…"
 * ├── session_index.jsonl            # one {sessionId, sessionDir, workDir} line per session (global index)
 * └── sessions/<workDirKey>/<sessionId>/
 *     ├── state.json                 # title, lastPrompt, timestamps, forkedFrom
 *     └── agents/main/wire.jsonl     # the main agent's full wire event stream (replay source)
 * ```
 *
 * We NEVER compute `workDirKey` ourselves (its slug rule is undocumented and cwds with `_`/`.` are the
 * dirKey encoding-bug trap): sessions are located through `session_index.jsonl`'s recorded `sessionDir`.
 */
object KimiPaths {
    fun kimiHome(): Path =
        System.getenv("KIMI_CODE_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".kimi-code")

    fun sessionsRoot(): Path = kimiHome().resolve("sessions")

    /** The global session index: one JSON line per session carrying sessionId + its on-disk sessionDir + workDir. */
    fun sessionIndex(): Path = kimiHome().resolve("session_index.jsonl")

    fun configToml(): Path = kimiHome().resolve("config.toml")

    /** The main agent's wire log inside a session dir (`agents/main/wire.jsonl`) — the replay/resume source. */
    fun mainWireLog(sessionDir: Path): Path = sessionDir.resolve("agents").resolve("main").resolve("wire.jsonl")

    /** Resolve a sessionId's on-disk directory via the index's recorded `sessionDir`. Falls back to a bounded
     *  walk of `sessions/` only when the index has no hit (older/renamed layouts). Never throws. */
    fun sessionDir(sessionId: String): Path? {
        KimiSessionIndex.entries().firstOrNull { it.sessionId == sessionId }?.sessionDir
            ?.let { return Path.of(it) }
        // fallback: sessions/<workDirKey>/<sessionId>/
        val root = sessionsRoot()
        if (!root.isDirectory()) return null
        return runCatching {
            java.nio.file.Files.walk(root, 2).use { stream ->
                stream.filter { p -> p.isDirectory() && p.fileName?.toString() == sessionId }.findFirst().orElse(null)
            }
        }.getOrNull()
    }
}
