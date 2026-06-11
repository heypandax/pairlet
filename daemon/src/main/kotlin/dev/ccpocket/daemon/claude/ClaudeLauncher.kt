package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** What to launch: working directory + optional resume/model/mode. */
data class ClaudeSpec(
    val workdir: Path,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val appendSystemPrompt: String? = null,
)

/** Resolves the real `claude` binary and builds the launch command — pure, no side effects. */
object ClaudeLauncher {

    private val envBin: String? = System.getenv("CC_POCKET_CLAUDE_BIN")
    private val fallbacks: List<String> = listOf(
        System.getProperty("user.home") + "/.local/bin/claude",
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
        "/usr/bin/claude",
    )

    /**
     * Resolve the real `claude` executable. NEVER goes through a shell: the PATH `claude` may be a
     * zsh function / shim that prints to stdout and corrupts the stream-json. We prefer native
     * binaries over `#!`-script shims for the same reason.
     */
    fun resolveExecutable(explicit: String? = null): Path {
        explicit?.let { return Path.of(it).toRealPath() }
        val candidates = LinkedHashSet<Path>()
        envBin?.let { candidates.add(Path.of(it)) }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach {
            if (it.isNotBlank()) candidates.add(Path.of(it, "claude"))
        }
        fallbacks.forEach { candidates.add(Path.of(it)) }

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
        spec.resumeId?.let { add("--resume"); add(it) }
        spec.model?.let { add("--model"); add(it) }
        spec.appendSystemPrompt?.let { add("--append-system-prompt"); add(it) }
    }

    fun processBuilder(exe: Path, spec: ClaudeSpec): ProcessBuilder =
        ProcessBuilder(buildList { add(exe.toString()); addAll(buildArgs(spec)) }).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep stderr off the stdout JSON stream
            environment().remove("CLAUDECODE") // avoid nested-session detection
        }
}

/** The CLI flag value for a permission mode (single source of truth = the @SerialName). */
internal fun PermissionMode.wireName(): String = PocketJson.encodeToString(this).trim('"')
