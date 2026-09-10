package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Path

/**
 * Resolves the `dsh` (DeepSeek Harness) executable and builds its `--profile acp` launch command
 * (issue #255, re-transported for dsh 0.1.2).
 *
 * WHY THE ACP PROFILE: this backend originally drove the `web` profile's local HTTP/WebSocket API,
 * because dsh rc.6 shipped no ACP server and its SDK JSON-RPC mode had no cancel, no resume and no
 * approval callback. dsh 0.1.2-rc.1 ended both facts at once: it replaced that local API wholesale
 * (`dsh-host-apiproxy` → the Typert gateway: `/api/<ns>/<method>`, a bidirectional `/api/remote.mux`
 * socket, and a signed browser cookie minted from a launch token on EVERY /api request — the old client
 * cannot even connect) and it added `--profile acp`, a standard Agent Client Protocol v1 server on
 * stdio with session create/resume/cancel, model selection and permission requests. Speaking the
 * documented protocol is both less code and far less exposed to dsh's internals; see [DshBackend].
 * Behaviors are pinned by `scripts/probe-dsh-acp.py`, which must be re-run after every dsh upgrade.
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
            // The old text said "set CC_POCKET_DSH_BIN" with no qualifier — which walks a launchd user
            // straight into a shell `export` the service can never see (issue #287). Split the advice by
            // the actual situation instead.
            "dsh executable not found. Not installed yet? Run: npm i -g @deepseek-ai/dsh (needs Node >= 22.12). " +
                "Already installed? The daemon runs as a background service and can't see your shell's PATH — " +
                "re-run `cc-pocket-daemon service-install --apply` from the terminal where `which dsh` works, " +
                "or pass --dsh-bin / set CC_POCKET_DSH_BIN where the service can see it " +
                "(a plain `export` in your shell won't reach it). " +
                "Desktop-app builds of dsh bundle a private copy the daemon can't drive — keep the npm install. " +
                // issue #365: `npx @deepseek-ai/dsh` installs no `dsh` file at all, so there is nothing for
                // any search to find — the fix is to create the missing file, or to pin a path that persists.
                "Only ever run dsh through npx? Then no `dsh` file exists to find: create one at " +
                "~/.local/bin/dsh containing `#!/bin/sh` and `exec /absolute/path/to/npx --yes " +
                "@deepseek-ai/dsh@latest \"$@\"`, chmod +x it (the npx path must be the absolute one from " +
                "`which npx` — a service cannot see your shell's PATH), or pin any path permanently with " +
                "`cc-pocket-daemon config --dsh-bin <path>`.",
        )

    /**
     * `dsh --profile acp` — the ACP v1 stdio server, spoken over the child's own stdin/stdout.
     *
     * STDIN IS THE UPLINK, so it is deliberately NOT redirected (the web profile took none and this
     * used to point at /dev/null — under ACP that reads as an immediate client disconnect and dsh shuts
     * down cleanly, i.e. the session would die at launch with no error anywhere). Stdout carries ONLY
     * protocol frames; dsh keeps its logs on stderr, which [dev.ccpocket.daemon.agent.AgentProcess]
     * drains separately.
     *
     * PERMISSION MODE is seeded through `DSH_PERMISSION_MODE` (default `workspace-write`) — the base
     * profile's `sandbox-policy` and `approval/policy` rows both read that variable at boot
     * (source-verified, dsh 0.1.2-rc.1). It is the BOOT-TIME default: ACP exposes no mode-switch method,
     * so [DshBackend] relaunches to change it.
     *
     * NO CREDENTIALS ARE PASSED. dsh reads `DEEPSEEK_API_KEY` from the environment or
     * `~/.dsh/.credentials.yaml` itself; the daemon deliberately does not manage, forward or store the
     * user's key.
     */
    fun processBuilder(exe: Path, spec: AgentSpec, permissionMode: String): ProcessBuilder {
        val argv = listOf(exe.toString(), "--profile", PROFILE)
        log.info("launch argv: ${argv.joinToString(" ")} (cwd=${spec.workdir})")
        return ProcessBuilder(argv).apply {
            directory(spec.workdir.toFile())
            redirectErrorStream(false) // keep dsh's own logs off the stdout ACP stream
            val env = environment()
            env["DSH_PERMISSION_MODE"] = permissionMode
            env.putIfAbsent("LANG", "C.UTF-8")
        }
    }

    /** The profile whose app IS the ACP server. Shipped since dsh 0.1.2-rc.1 ([MIN_VERSION]). */
    const val PROFILE = "acp"

    /** First dsh release carrying `--profile acp` (and the release that broke the old `web`-profile
     *  local API this backend used to drive — see [DshBackend]). Quoted in the user-facing hints. */
    const val MIN_VERSION = "0.1.2-rc.1"

    /**
     * Translate a launch failure's stderr into something a user can act on, or null when it says nothing
     * we recognize.
     *
     *  - **Too-old dsh.** `--profile acp` on a pre-0.1.2 install boots a profile whose bundle list has no
     *    app in it: nothing ever claims stdio and the handshake simply never answers. That is also the
     *    exact symptom of the release that broke the old web transport, so it is worth naming the version.
     *  - **Too-old Node.** dsh requires Node ≥ 22.12; under an older one the failure surfaces as an opaque
     *    syntax/engine error.
     */
    fun launchHint(stderr: String?): String? {
        val s = stderr?.lowercase() ?: return null
        val looksLikeEngineFailure = "unsupported engine" in s ||
            ("node" in s && ("requires" in s || "engine" in s)) ||
            "unexpected token" in s || "syntaxerror" in s
        if (looksLikeEngineFailure) {
            return "DeepSeek Harness requires Node.js 22.12 or newer — upgrade Node, or point --dsh-bin at a " +
                "dsh installed under a newer runtime."
        }
        val looksLikeMissingProfile = "profile" in s && ("acp" in s || "unknown" in s || "no app" in s)
        return if (looksLikeMissingProfile) outdatedHint() else null
    }

    /** What to tell a user whose dsh cannot serve ACP at all. */
    fun outdatedHint(): String =
        "this DeepSeek Harness has no `acp` profile — cc-pocket needs dsh $MIN_VERSION or newer " +
            "(npm i -g @deepseek-ai/dsh@latest, Node >= 22.12)."

    /** Default permission ceiling for a dsh session. `workspace-write` matches dsh's own default. */
    const val DEFAULT_PERMISSION_MODE = "workspace-write"
}
