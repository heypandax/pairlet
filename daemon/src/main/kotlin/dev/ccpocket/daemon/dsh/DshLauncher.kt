package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Path

/**
 * Resolves the `dsh` (DeepSeek Harness) executable and builds its `--profile web` launch command
 * (issue #255).
 *
 * WHY THE WEB PROFILE: dsh rc.6 ships no ACP server, and its SDK JSON-RPC mode has no cancel, no resume
 * and no approval callback — so the only channel that can carry a real interactive session is the local
 * HTTP/WebSocket API the `web` profile serves. The daemon runs it on loopback and speaks to it directly;
 * the browser UI it also serves is never opened. See [DshApiClient] for the protocol side.
 */
object DshLauncher {
    private val log = logger("DshLauncher")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val envBin: String? = System.getenv("CC_POCKET_DSH_BIN")

    private val exeNames: List<String> =
        if (isWindows) listOf("dsh.exe", "dsh.cmd", "dsh.bat", "dsh") else listOf("dsh")

    /** npm/pnpm/bun/volta global bins plus the usual system locations — a launchd-started daemon
     *  inherits a sanitized PATH and would otherwise never see a user-global npm install. */
    private val fallbackDirs: List<String> = buildList {
        val home = System.getProperty("user.home")
        add(home + File.separator + ".local" + File.separator + "bin")
        add(home + File.separator + ".npm-global" + File.separator + "bin")
        add(home + File.separator + ".volta" + File.separator + "bin")
        add(home + File.separator + ".bun" + File.separator + "bin")
        if (isWindows) {
            System.getenv("APPDATA")?.let { add(it + File.separator + "npm") }
        } else {
            add("/opt/homebrew/bin")
            add("/usr/local/bin")
            add("/usr/bin")
        }
    }

    fun resolveExecutable(explicit: String? = null): Path =
        ExecutableResolver.resolve(
            explicit, envBin, exeNames, fallbackDirs,
            "dsh executable not found. Install DeepSeek Harness (npm i -g @deepseek-ai/dsh), " +
                "or set CC_POCKET_DSH_BIN / pass --dsh-bin.",
        )

    /**
     * `dsh --profile web --port 0`, bound to loopback.
     *
     * PORT 0 IS DELIBERATE: dsh's web profile accepts `--port 0` to mean "let the OS pick a free one",
     * then prints the bound URL on stdout (see [parseBootPort]). Pre-reserving a port ourselves with a
     * throwaway `ServerSocket(0)` would introduce a TOCTOU window in which anything else on the machine
     * could take it between our close and dsh's bind; reading back the port dsh ACTUALLY bound has no
     * such race. `--host` is left at its default (127.0.0.1) — dsh rejects `0.0.0.0` outright, and
     * loopback is what the browser-trust fence wants anyway.
     *
     * PERMISSION MODE is seeded through `DSH_PERMISSION_MODE` (default `workspace-write`). This is the
     * BOOT-TIME default only: dsh records mode changes as durable log events (`sandbox/mode`,
     * `approval/policy`, `permission/preset`) and a `/permission <preset>` inside the chat will move it
     * for the rest of the session. The daemon does not drive that in v1 — but nothing here should be
     * read as "the mode is pinned for the session's life", because it is not.
     *
     * NO CREDENTIALS ARE PASSED. dsh reads `DEEPSEEK_API_KEY` from the environment or
     * `~/.dsh/.credentials.yaml` itself; the daemon deliberately does not manage, forward or store the
     * user's key.
     */
    fun processBuilder(exe: Path, spec: AgentSpec, permissionMode: String): ProcessBuilder {
        val argv = listOf(
            exe.toString(),
            "--profile", "web",
            "--port", "0",
        )
        log.info("launch argv: ${argv.joinToString(" ")} (cwd=${spec.workdir})")
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false)
            redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
            val env = environment()
            env["DSH_PERMISSION_MODE"] = permissionMode
            env.putIfAbsent("LANG", "C.UTF-8")
        }
    }

    /**
     * Recover the bound port from dsh's boot line, which reads
     * `dsh web: http://127.0.0.1:53124` (optionally followed by ` (LAN: …)`).
     *
     * Returns null for every other stdout line, so the caller can simply offer it each line until it
     * answers. Matching is anchored on the loopback authority rather than on the `dsh web:` prefix so a
     * banner reword upstream doesn't silently strand us with no port.
     */
    fun parseBootPort(line: String): Int? =
        BOOT_PORT_RE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    private val BOOT_PORT_RE = Regex("""https?://(?:127\.0\.0\.1|localhost|\[::1]):(\d{1,5})""")

    /** dsh requires Node ≥ 22.12. When it is launched through a too-old Node the failure surfaces as an
     *  opaque syntax/engine error on stderr, so translate the well-known spellings into something a user
     *  can act on. Returns null when [stderr] is not a Node-version complaint. */
    fun nodeVersionHint(stderr: String?): String? {
        val s = stderr?.lowercase() ?: return null
        val looksLikeEngineFailure = "unsupported engine" in s ||
            ("node" in s && ("requires" in s || "engine" in s)) ||
            "unexpected token" in s || "syntaxerror" in s
        return if (looksLikeEngineFailure) {
            "DeepSeek Harness requires Node.js 22.12 or newer — upgrade Node, or point --dsh-bin at a " +
                "dsh installed under a newer runtime."
        } else {
            null
        }
    }

    /** Default permission ceiling for a dsh session. `workspace-write` matches dsh's own default. */
    const val DEFAULT_PERMISSION_MODE = "workspace-write"
}
