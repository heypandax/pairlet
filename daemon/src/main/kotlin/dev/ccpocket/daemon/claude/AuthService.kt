package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AuthBlockReason
import dev.ccpocket.protocol.AuthBlocker
import dev.ccpocket.protocol.AuthState
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.TimeUnit

/**
 * Drives the CLI's own auth commands (`claude auth status|logout|login`) so a paired client can
 * switch the daemon host's Claude account without a terminal. One account is active at a time — this
 * deliberately mirrors the official desktop app (logout → login), not a multi-profile scheme: all
 * session history stays in the one `~/.claude/projects`, and resume across a switch keeps working
 * (the transcript is still there; new turns just bill the now-active account).
 *
 * Login is the CLI's interactive flow driven headlessly (verified against claude 2.1.x without a
 * TTY): the child prints the OAuth URL (and opens the browser itself — it runs on the same machine
 * the user's browser does), then blocks reading the authorization code on stdin. We scrape the URL
 * out of its stdout for the client UI and pipe the pasted code back via [submitCode].
 *
 * Every entry point answers with an [AuthState]; a pending login's completion is pushed to the sink
 * that started it. Login/logout are refused only while a conversation is MID-TASK (turn in flight /
 * background jobs) — swapping credentials under an agent actively talking to the API breaks it.
 * Merely-open idle conversations are closed automatically instead: their transcripts persist on disk
 * and resume like any cold session (billing the new account) — without this the desktop's own open
 * chat would deadlock every switch attempt.
 */
class AuthService(
    private val scope: CoroutineScope,
    private val busyConversations: suspend () -> List<AuthBlocker>,
    private val closeIdleConversations: suspend () -> Int,
    private val closeBusyConversations: suspend () -> Int = { 0 },
    private val claudeExe: () -> String = { ClaudeLauncher.resolveExecutable().toString() },
    // credential isolation (issue #69): when set, every `claude auth …` child operates on the daemon's
    // OWN store — an app-driven switch/logout can no longer log out the terminal's claude
    private val claudeConfigDir: java.nio.file.Path? = null,
) {
    private val log = logger("Auth")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val mutex = Mutex()
    private var login: Process? = null
    private var loginUrl: String? = null

    /** Answer one [dev.ccpocket.protocol.FetchAuthStatus]. */
    suspend fun sendStatus(emit: suspend (Frame) -> Unit) = emit(currentState())

    /** Start a login (= account switch when already logged in). Replies via [emit]; completion is pushed later.
     *  [force] = the user saw the blocker list and chose "stop them & switch": close mid-task conversations
     *  too instead of refusing (then re-check — a turn that started in the gap still refuses). */
    suspend fun login(console: Boolean, emit: suspend (Frame) -> Unit, force: Boolean = false) {
        mutex.withLock {
            if (login?.isAlive == true) { // idempotent: re-announce the pending URL instead of spawning a twin
                emit(AuthState(loginPending = true, loginUrl = loginUrl))
                return
            }
        }
        if (force) closeBusyConversations().takeIf { it > 0 }?.let { log.info("force-closed $it busy conversation(s) for account switch") }
        guardBusy()?.let { emit(it); return }
        val exe = resolveOrNull() ?: run { emit(AuthState(error = "claude CLI not found")); return }
        // merely-open idle conversations would otherwise hold a stale token (and the desktop's own chat
        // would deadlock the switch forever) — close them; they resume from disk under the new account
        closeIdleConversations().takeIf { it > 0 }?.let { log.info("closed $it idle conversation(s) for account switch") }
        // switch semantics: drop the current credential first — `auth login` over a live one is not a verified path
        if (status(exe).loggedIn) runCatching { cli(exe, "auth", "logout") }
            .onFailure { log.warn("pre-login logout failed: ${it.message}") }
        val proc = runCatching {
            processBuilder(exe, buildList { add("auth"); add("login"); if (console) add("--console") }).start()
        }.getOrElse { emit(AuthState(error = "failed to start login: ${it.message}")); return }
        log.info("auth login started (pid ${proc.pid()}${if (console) ", console" else ""})")
        mutex.withLock { login = proc; loginUrl = null }
        watch(proc, emit)
    }

    /** Pipe the pasted OAuth authorization code into the waiting login child. */
    suspend fun submitCode(code: String, emit: suspend (Frame) -> Unit) {
        val proc = mutex.withLock { login }
        if (proc == null || !proc.isAlive) { emit(currentState().copy(error = "no login in progress")); return }
        withContext(Dispatchers.IO) {
            runCatching {
                proc.outputStream.write((code.trim() + System.lineSeparator()).toByteArray())
                proc.outputStream.flush()
            }.onFailure { log.warn("code write failed: ${it.message}") }
        }
        // no reply here — the watcher pushes the final AuthState when the child exits
    }

    /** Abandon a pending login; the watcher pushes the resulting (logged-out) state. */
    suspend fun cancelLogin(emit: suspend (Frame) -> Unit) {
        val proc = mutex.withLock { login }
        if (proc == null) { emit(currentState()); return }
        log.info("auth login cancelled")
        runCatching { proc.destroyForcibly() } // watcher sees the exit and emits the final state
    }

    suspend fun logout(emit: suspend (Frame) -> Unit) {
        mutex.withLock { login }?.let { runCatching { it.destroyForcibly() } } // a pending login can't outlive a logout
        guardBusy()?.let { emit(it); return }
        val exe = resolveOrNull() ?: run { emit(AuthState(error = "claude CLI not found")); return }
        closeIdleConversations().takeIf { it > 0 }?.let { log.info("closed $it idle conversation(s) for logout") }
        runCatching { cli(exe, "auth", "logout") }.onFailure { log.warn("logout failed: ${it.message}") }
        emit(status(exe))
    }

    // ── internals ─────────────────────────────────────────────────────────

    /** The refusal state when mid-work conversations forbid credential swapping, else null.
     *  Idle-but-open conversations don't refuse — the caller closes those (resumable from disk).
     *  Carries the blocker list (and names them in [AuthState.error] for clients that predate it)
     *  so the user sees WHAT is busy and can stop it, not just a dead-end count. */
    private suspend fun guardBusy(): AuthState? {
        val blockers = busyConversations()
        if (blockers.isEmpty()) return null
        val detail = blockers.joinToString("; ") { b ->
            val name = b.cwd.substringAfterLast('/').substringAfterLast('\\').ifBlank { b.cwd }
            when (b.reason) {
                AuthBlockReason.EXECUTING -> "$name is mid-turn"
                AuthBlockReason.BACKGROUND_JOBS ->
                    "$name has ${b.jobLabels.size.coerceAtLeast(1)} background task${if (b.jobLabels.size == 1) "" else "s"} running"
                AuthBlockReason.UNKNOWN -> "$name is still working" // decode fallback — this daemon never emits it
            }
        }
        return currentState().copy(
            error = "Sessions on this computer are still working: $detail — stop them (or wait) first",
            blockers = blockers,
        )
    }

    private suspend fun currentState(): AuthState {
        mutex.withLock { if (login?.isAlive == true) return AuthState(loginPending = true, loginUrl = loginUrl) }
        val exe = resolveOrNull() ?: return AuthState(error = "claude CLI not found")
        return status(exe)
    }

    private suspend fun status(exe: String): AuthState = withContext(Dispatchers.IO) {
        runCatching { parseStatus(cli(exe, "auth", "status", "--json")) }
            .getOrElse { AuthState(error = "`claude auth status` failed: ${it.message}") }
    }

    /** Reads the child's stdout: scrapes the OAuth URL for the client, then reports the final state on exit. */
    private fun watch(proc: Process, emit: suspend (Frame) -> Unit): Job = scope.launch(Dispatchers.IO) {
        val timeout = launch { // hard stop: an abandoned login must not hold the "one login at a time" slot forever
            delay(LOGIN_TIMEOUT_MS)
            if (proc.isAlive) { log.warn("auth login timed out — killing"); runCatching { proc.destroyForcibly() } }
        }
        var announced = false
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { line ->
                log.info("login: $line")
                if (!announced) {
                    line.split(WHITESPACE).firstOrNull { it.startsWith("https://") }?.let { url ->
                        announced = true
                        scope.launch {
                            mutex.withLock { loginUrl = url }
                            emit(AuthState(loginPending = true, loginUrl = url))
                        }
                    }
                }
            }
        }
        runCatching { proc.waitFor(10, TimeUnit.SECONDS) }
        log.info("auth login exited (code ${runCatching { proc.exitValue() }.getOrNull() ?: "?"})")
        mutex.withLock { login = null; loginUrl = null }
        timeout.cancel() // the child is gone — drop the abandonment guard
        emit(currentState()) // loggedIn flips true on success; back to logged-out on failure/cancel
    }

    private fun resolveOrNull(): String? = runCatching { claudeExe() }.getOrNull()

    /** Run a short-lived CLI command, returning its combined output (throws on timeout). */
    private fun cli(exe: String, vararg args: String): String {
        val proc = processBuilder(exe, args.toList()).start()
        proc.outputStream.close()
        // output is tiny (a JSON object / a confirmation line) — the pipe buffer holds it while we wait
        val done = proc.waitFor(CLI_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!done) { proc.destroyForcibly(); error("timed out") }
        return proc.inputStream.bufferedReader().readText()
    }

    private fun processBuilder(exe: String, args: List<String>): ProcessBuilder {
        // same Windows quirk as ClaudeLauncher: .cmd/.bat must run through cmd.exe
        val needsShell = isWindows && exe.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
        val argv = buildList {
            if (needsShell) { add(System.getenv("ComSpec") ?: "cmd.exe"); add("/c") }
            add(exe); addAll(args)
        }
        return ProcessBuilder(argv).apply {
            redirectErrorStream(true) // login prompts land on stderr or stdout depending on version — read both as one
            environment().remove("CLAUDECODE")
            // same store the session launcher uses — status/login/logout must see the daemon's login, not the terminal's
            claudeConfigDir?.let { environment()["CLAUDE_CONFIG_DIR"] = it.toString() }
        }
    }

    /**
     * Pull the auth fields out of `claude auth status --json` output. The JSON object is located by
     * brace-scanning rather than parsing the whole output: shell wrappers (observed locally: an
     * "ip-guard" preflight) may prepend banner lines the CLI itself never prints.
     */
    internal fun parseStatus(raw: String): AuthState {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val obj = if (start in 0 until end) {
            runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
        } else null
        if (obj == null) return AuthState(error = "unrecognized `claude auth status` output")
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        return AuthState(
            loggedIn = (obj["loggedIn"] as? JsonPrimitive)?.booleanOrNull ?: false,
            email = str("email"),
            orgName = str("orgName"),
            subscriptionType = str("subscriptionType"),
            authMethod = str("authMethod"),
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val CLI_TIMEOUT_SEC = 30L
        const val LOGIN_TIMEOUT_MS = 10 * 60_000L // browser dance abandoned — reclaim the login slot
    }
}
