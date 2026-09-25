package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentRepairProgress
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

/**
 * Runs the one-tap dsh repair: `npm i -g @deepseek-ai/dsh@latest`, streaming its output back to the phone
 * as [AgentRepairProgress] frames.
 *
 * WHY THIS EXISTS: a `npm i -g @deepseek-ai/dsh` can come out INCOMPLETE — npm drops a nested dependency
 * (confirmed on a real Windows box, 2026-09: `@earendil-works/pi-ai` was missing), and dsh then crashes at
 * boot with `ERR_MODULE_NOT_FOUND` / `failed to import loader entry llm-pi-ai`. The same dsh/Node/OS with a
 * COMPLETE install runs fine, so the fix is simply to reinstall — which is something the daemon can do FOR
 * the user instead of walking them through a terminal. [DshLauncher.looksLikeIncompleteInstall] detects the
 * crash and [dev.ccpocket.daemon.conversation.Conversation] offers this repair on the error the user sees.
 *
 * After a successful reinstall nothing here relaunches dsh: the crashed process already died, so the NEXT
 * prompt lazily respawns it — now against a complete install. The app auto-resends the failed prompt on
 * [AgentRepairProgress.ok].
 */
object DshRepairService {
    private val log = logger("DshRepairService")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** One repair at a time across the whole daemon: two concurrent `npm i -g` fight over the same global
     *  prefix and can leave it half-written — the exact failure we are fixing. */
    private val gate = Mutex()

    /** Cap each streamed line so a pathological npm line can't blow the frame budget. */
    private const val MAX_LINE = 500

    /**
     * Resolve `npm`, run the global reinstall, and stream every output line. Emits a terminal
     * [AgentRepairProgress] with `done=true` exactly once. [emit] is the phone sink of the client that
     * asked (never fanned out — a repair is the asking device's action).
     */
    suspend fun repair(convoId: String, emit: suspend (Frame) -> Unit) {
        if (!gate.tryLock()) {
            emit(done(convoId, ok = false, error = "a repair is already running — wait for it to finish"))
            return
        }
        try {
            runRepair(convoId, emit)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun runRepair(convoId: String, emit: suspend (Frame) -> Unit) {
        val npm = runCatching { resolveNpm() }.getOrElse {
            log.warn("dsh repair: npm not found — ${it.message}")
            emit(
                done(
                    convoId, ok = false,
                    error = "couldn't find npm on this computer to reinstall dsh. Install Node.js (which " +
                        "ships npm), then run `${DshLauncher.REPAIR_COMMAND}` in a terminal.",
                ),
            )
            return
        }
        emit(line(convoId, "\$ ${DshLauncher.REPAIR_COMMAND}"))
        log.info("dsh repair: running ${DshLauncher.REPAIR_COMMAND} via $npm")

        val argv = buildList {
            // A Windows `npm` is npm.cmd — a batch shim that only runs through cmd.exe, which re-parses the
            // line (same reason ClaudeLauncher wraps its shim). Unix npm is a node script run directly.
            if (isWindows) { add("cmd.exe"); add("/c") }
            add(npm.toString()); add("i"); add("-g"); add("@deepseek-ai/dsh@latest")
        }
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        // npm's shim invokes `node`; a launchd/service PATH may not contain node's dir. Put the resolved
        // npm's own directory (node lives beside it) on the child PATH so the shim can find its runtime.
        npm.parent?.let { dir ->
            val env = pb.environment()
            val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            env[key] = dir.toString() + File.pathSeparator + (env[key] ?: "")
        }
        val proc = withContext(Dispatchers.IO) { pb.start() }
        runCatching { proc.outputStream.close() } // no stdin; npm must never block waiting on a prompt
        // Read on the IO dispatcher (readLine blocks) but emit through the suspend sink line by line, so
        // progress reaches the phone live rather than in one dump at the end.
        val reader = proc.inputStream.bufferedReader()
        while (true) {
            val raw = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val text = raw.trim()
            if (text.isNotEmpty()) emit(line(convoId, text.take(MAX_LINE)))
        }
        val exit = withContext(Dispatchers.IO) { proc.waitFor() }
        log.info("dsh repair: npm exited $exit")
        emit(done(convoId, ok = exit == 0, error = if (exit == 0) null else "npm exited with code $exit"))
    }

    /**
     * Locate `npm` the same nvm/fnm-aware way agent CLIs are found: a background daemon inherits a
     * sanitized PATH, so `npm i -g` under nvm/fnm/homebrew would otherwise be invisible.
     */
    private fun resolveNpm(): Path {
        val exeNames = if (isWindows) listOf("npm.cmd", "npm.exe", "npm") else listOf("npm")
        val home = System.getProperty("user.home")
        val fallbackDirs = buildList {
            if (isWindows) {
                System.getenv("APPDATA")?.let { add(it + File.separator + "npm") }
                System.getenv("ProgramFiles")?.let { add(it + File.separator + "nodejs") }
            } else {
                add("/opt/homebrew/bin")
                add("/usr/local/bin")
                add("/usr/bin")
                add(home + File.separator + ".volta" + File.separator + "bin")
            }
        }
        return ExecutableResolver.resolve(
            explicit = null, envBin = System.getenv("CC_POCKET_NPM_BIN"),
            exeNames = exeNames, fallbackDirs = fallbackDirs,
            notFound = "npm not found",
        )
    }

    private fun line(convoId: String, text: String) =
        AgentRepairProgress(convoId, AgentKind.DSH, line = text)

    private fun done(convoId: String, ok: Boolean, error: String?) =
        AgentRepairProgress(convoId, AgentKind.DSH, done = true, ok = ok, error = error)
}
