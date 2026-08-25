package dev.ccpocket.daemon.dsh

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * On-disk layout of the DeepSeek Harness (`dsh`) session store (issue #255).
 *
 * ```
 * ~/.dsh/sessions/                       # or $DSH_HOME/sessions
 * └── --<normalized-cwd>--/              # LOSSY project key; `_no-cwd` when the session had no cwd
 *     └── <encoded-session-id>/
 *         ├── session.jsonl.zstd         # concatenated multi-frame zstd (or session.jsonl uncompressed)
 *         ├── *.tmp / .dsh-mkdir-*       # sidecars — skip
 *         └── *.lock                     # never touched
 * ```
 *
 * ⚠️ THE DIRECTORY NAME IS NOT THE CWD. [projectKey] is a lossy, COLLIDING normalization: `/a/b` and
 * `/a-b` both produce `--a-b--` (probe-verified). It is therefore usable ONLY as a candidate FILTER —
 * a cheap way to avoid walking every project dir. The authoritative cwd is always the `cwd` field of the
 * session file's header line, read verbatim (see [DshTranscript.header]); callers must re-filter on it.
 * This is the same class of bug as the Claude `dirKey` encoding trap, so it is fenced off here by
 * contract rather than by care.
 */
object DshPaths {
    /** Sidecar/lock names that are never session payloads. */
    private const val MKDIR_SENTINEL_PREFIX = ".dsh-mkdir-"

    /** The store root honours $DSH_HOME (the whole dsh home moves, sessions stay a subdir of it). */
    fun dshHome(): Path =
        System.getenv("DSH_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".dsh")

    fun sessionsRoot(): Path = dshHome().resolve("sessions")

    /**
     * dsh's lossy cwd → directory-name normalization. Ported 1:1 from `dsh-session-persistence-jsonl`'s
     * `projectKey()` (rc.6):
     *  1. `/`, `\` and `:` collapse to a SINGLE `-`, and consecutive runs fold together,
     *  2. `[A-Za-z0-9._-]` pass through verbatim — **except `~`**, which is escaped so it can never be
     *     confused with an escape marker,
     *  3. everything else (`~`, spaces, CJK, …) becomes `~XXXX` — the UPPERCASE 4-digit hex of the UTF-16
     *     code unit,
     *  4. LEADING `-` are stripped (trailing ones are kept), an empty result becomes `root`, the whole
     *     thing is truncated to 251 chars and wrapped in `--…--`.
     *
     * Step 1 is what makes it collide (`/a/b` and `/a-b` both give `--a-b--`) and step 4 truncates rather
     * than hashing, so long paths alias too. Both are intentional upstream. NEVER invert this to recover
     * a cwd — read the header's `cwd` field instead.
     */
    fun projectKey(cwd: String): String {
        val sb = StringBuilder(cwd.length + 8)
        var lastWasSeparator = false
        for (ch in cwd) {
            when {
                ch == '/' || ch == '\\' || ch == ':' -> {
                    if (!lastWasSeparator) sb.append('-')
                    lastWasSeparator = true
                }
                ch != '~' &&
                    (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') -> {
                    sb.append(ch)
                    lastWasSeparator = false
                }
                else -> {
                    // UTF-16 code unit, not code point: a non-BMP char yields TWO ~XXXX escapes, which is
                    // exactly what dsh writes (it iterates JS string units).
                    sb.append('~').append(ch.code.toString(16).uppercase().padStart(4, '0'))
                    lastWasSeparator = false
                }
            }
        }
        val stripped = sb.toString().trimStart('-').ifEmpty { "root" }
        return "--" + stripped.take(MAX_KEY_CHARS) + "--"
    }

    /**
     * dsh's session-id → directory-name encoding (`encodeSegment`, rc.6). A DIFFERENT function from
     * [projectKey] and, unlike it, INJECTIVE: nothing collapses, nothing truncates, and `/ \ :` are
     * escaped rather than turned into `-`. For the usual `session-<uuid>` ids it is the identity, which
     * is why the directory simply looks like the raw id.
     */
    fun encodeSessionId(sessionId: String): String {
        if (sessionId == ".") return "~002E"
        if (sessionId == "..") return "~002E~002E"
        val sb = StringBuilder(sessionId.length)
        for (ch in sessionId) {
            if (ch != '~' &&
                (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-')
            ) {
                sb.append(ch)
            } else {
                sb.append('~').append(ch.code.toString(16).uppercase().padStart(4, '0'))
            }
        }
        return sb.toString()
    }

    /** Reverse of the `~XXXX` escape, for turning a session-id DIRECTORY name back into the id.
     *  Session ids are `session-<uuid>` in practice, but the web profile lets a client choose its own,
     *  so the decode must be real rather than assumed to be the identity. Malformed escapes are left
     *  verbatim — a directory we cannot decode still has a usable name, and throwing here would take
     *  down the whole listing. */
    fun decodeName(name: String): String {
        if ('~' !in name) return name
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val ch = name[i]
            if (ch == '~' && i + 4 < name.length) {
                val hex = name.substring(i + 1, i + 5)
                val code = hex.toIntOrNull(16)
                if (code != null && hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                    sb.append(code.toChar())
                    i += 5
                    continue
                }
            }
            sb.append(ch)
            i += 1
        }
        return sb.toString()
    }

    /** Every project directory in the store (`--…--` plus the `_no-cwd` bucket). Empty when dsh
     *  is not installed / has never run — never throws. [root] is a test seam; production uses the
     *  real store. */
    fun projectDirs(root: Path = sessionsRoot()): List<Path> {
        if (!root.isDirectory()) return emptyList()
        return runCatching {
            root.listDirectoryEntries().filter { it.isDirectory() }
        }.getOrDefault(emptyList())
    }

    /** Session directories inside one project directory. */
    fun sessionDirs(projectDir: Path): List<Path> =
        runCatching { projectDir.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList())

    /**
     * The transcript file inside a session dir: the zstd form is authoritative, with the uncompressed
     * `session.jsonl` accepted as a fallback (dsh writes it when compression is disabled). Null when the
     * directory holds neither — e.g. a half-created dir that only has its `.dsh-mkdir-*` sentinel.
     */
    fun transcriptFile(sessionDir: Path): Path? {
        val zstd = sessionDir.resolve("session.jsonl.zstd")
        if (zstd.isRegularFile()) return zstd
        val plain = sessionDir.resolve("session.jsonl")
        if (plain.isRegularFile()) return plain
        return null
    }

    /** True for sidecars that must never be parsed as a transcript. */
    fun isSidecar(name: String): Boolean =
        name.endsWith(".tmp") || name.startsWith(MKDIR_SENTINEL_PREFIX) || name.endsWith(".lock")

    /** Locate a session directory by id. Uses [projectKey] only to try the LIKELY project dir first;
     *  falls back to a bounded scan because the key is lossy and a session may have moved. */
    fun findSessionDir(sessionId: String, cwdHint: String? = null, root: Path = sessionsRoot()): Path? {
        val ordered = buildList {
            val hinted = cwdHint?.takeIf { it.isNotBlank() }?.let { root.resolve(projectKey(it)) }
            if (hinted != null && hinted.isDirectory()) add(hinted)
            addAll(projectDirs(root).filter { it != hinted })
        }
        for (projectDir in ordered) {
            for (dir in sessionDirs(projectDir)) {
                if (decodeName(dir.fileName.toString()) == sessionId) return dir
            }
        }
        return null
    }

    /** dsh truncates the normalized key at 251 chars before wrapping it in `--…--` (255-char name limit). */
    private const val MAX_KEY_CHARS = 251
}
