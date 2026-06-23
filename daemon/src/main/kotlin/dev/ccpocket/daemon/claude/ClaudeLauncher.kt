package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** What to launch: working directory + optional resume/model/effort/mode. */
data class ClaudeSpec(
    val workdir: Path,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val appendSystemPrompt: String? = null,
    val effort: String? = null, // reasoning effort: low|medium|high|xhigh|max (claude --effort)
    // Fork the resumed session into a fresh id (claude `--fork-session`) instead of appending to the
    // original transcript. Set when the phone takes over / cold-resumes a session that another writer
    // (a desktop `claude --resume`, or a still-live terminal) may also hold: forking writes to a NEW
    // .jsonl so the two never share one file — eliminating the interleaved-write + branched-parentUuid
    // race by construction (claude does not lock transcripts). Mirrors claude's own `/branch`. No-op
    // unless resumeId != null.
    val forkSession: Boolean = false,
)

/** Resolves the real `claude` binary and builds the launch command — pure, no side effects. */
object ClaudeLauncher {

    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    private val envBin: String? = System.getenv("CC_POCKET_CLAUDE_BIN")

    // Executable basenames to probe in each PATH / fallback dir. On Windows a bare `claude` is not
    // directly runnable: the native installer drops `claude.exe` under ~\.local\bin (not on PATH by
    // default) and npm installs `claude.cmd`, so we must try the suffixed names — preferring .exe.
    private val exeNames: List<String> =
        if (isWindows) listOf("claude.exe", "claude.cmd", "claude.bat", "claude") else listOf("claude")

    // Well-known install dirs to search when PATH lacks claude (login services / GUI launchers often
    // start with a sanitized PATH). ~/.local/bin is the native installer's target on every OS.
    private val fallbackDirs: List<String> = buildList {
        add(System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin")
        if (!isWindows) {
            add("/opt/homebrew/bin"); add("/usr/local/bin"); add("/usr/bin")
        }
    }

    /**
     * Resolve the real `claude` executable. NEVER goes through a shell: the PATH `claude` may be a
     * zsh function / shim that prints to stdout and corrupts the stream-json. We prefer native
     * binaries over `#!`-script shims for the same reason.
     */
    fun resolveExecutable(explicit: String? = null): Path {
        explicit?.let { return Path.of(it).toRealPath() }
        val candidates = LinkedHashSet<Path>()
        envBin?.let { candidates.add(Path.of(it)) }
        val dirs = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { if (it.isNotBlank()) add(it) }
            addAll(fallbackDirs)
        }
        dirs.forEach { dir -> exeNames.forEach { name -> candidates.add(Path.of(dir, name)) } }

        val valid = candidates.filter {
            runCatching { it.isRegularFile() && it.isExecutable() }.getOrDefault(false)
        }
        // native binaries (sort key 0) before script shims (1)
        return valid.sortedBy { if (looksLikeScript(it)) 1 else 0 }
            .firstOrNull()
            ?.toRealPath()
            ?: error("claude executable not found. Set CC_POCKET_CLAUDE_BIN or pass --claude-bin.")
    }

    private fun looksLikeScript(p: Path): Boolean = runCatching {
        p.inputStream().use { it.readNBytes(2).contentEquals(byteArrayOf('#'.code.toByte(), '!'.code.toByte())) }
    }.getOrDefault(false)

    /** Build the argv. `-p` is mandatory for headless stream-json (else claude ignores stdin turns). */
    fun buildArgs(spec: ClaudeSpec): List<String> = buildList {
        add("-p")
        add("--output-format"); add("stream-json")
        add("--input-format"); add("stream-json")
        add("--permission-prompt-tool"); add("stdio")
        add("--replay-user-messages")
        add("--verbose")
        // ALWAYS pass the mode: omitting it lets claude fall back to the user's global
        // `permissions.defaultMode` (e.g. "auto"), silently breaking the phone's "Ask each step"
        add("--permission-mode"); add(spec.mode.wireName())
        spec.resumeId?.let {
            add("--resume"); add(it)
            // fork into a fresh id rather than appending to the resumed transcript — guarded by resumeId
            // so we never emit --fork-session with nothing to fork (see ClaudeSpec.forkSession)
            if (spec.forkSession) add("--fork-session")
        }
        spec.model?.let { add("--model"); add(it) }
        spec.effort?.let { add("--effort"); add(it) }
        spec.appendSystemPrompt?.let { add("--append-system-prompt"); add(it) }
    }

    fun processBuilder(exe: Path, spec: ClaudeSpec): ProcessBuilder {
        val exeStr = exe.toString()
        // Windows can't CreateProcess a .cmd/.bat directly — those must run through cmd.exe. A native
        // .exe (the installer's claude.exe) runs directly, same as the Unix binary.
        val needsShell = isWindows && exeStr.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
        val argv = buildList {
            if (needsShell) { add(System.getenv("ComSpec") ?: "cmd.exe"); add("/c") }
            add(exeStr)
            addAll(buildArgs(spec))
        }
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON stream
            environment().remove("CLAUDECODE") // avoid nested-session detection
        }
    }
}

/** The CLI flag value for a permission mode (single source of truth = the @SerialName). */
internal fun PermissionMode.wireName(): String = PocketJson.encodeToString(this).trim('"')
