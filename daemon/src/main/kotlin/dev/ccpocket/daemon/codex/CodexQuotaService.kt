package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_QUOTA_HTTP
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_ALL
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_SCOPED
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Reads the CODEX subscription allowance (issue #348) — the same numbers the Codex CLI shows for the
 * ChatGPT plan behind it — and answers one [ClaudeQuota] per request, tagged [AgentKind.CODEX].
 *
 * Deliberately shaped as the twin of [dev.ccpocket.daemon.claude.ClaudeQuotaService]: injected clock,
 * injected transport, one single-slot cache behind a mutex, never throws, and every failure degrades into
 * a non-OK status rather than an exception on the router's coroutine. What differs is only WHERE the
 * numbers come from:
 *
 * ```
 *   codex app-server                      (newline-delimited JSON-RPC over stdio, NO `jsonrpc` field)
 *     -> {"id":1,"method":"initialize","params":{clientInfo, capabilities:{experimentalApi:false}}}
 *     <- {"id":1,"result":{…}}
 *     -> {"method":"initialized"}
 *     -> {"id":2,"method":"account/rateLimits/read"}
 *     <- {"id":2,"result":{"rateLimits":{…},"rateLimitsByLimitId":{…}}}
 * ```
 *
 * Four facts shape this, each verified against codex-cli 0.153.4 on 2026-09-07 and each of which fails
 * confusingly if assumed away:
 *  1. **The daemon never reads `~/.codex/auth.json`.** The app-server authenticates itself; a credential
 *     the daemon parsed by hand would be a second, drifting copy of Codex's own login state. "Signed out"
 *     therefore arrives as a JSON-RPC *error*, not as a missing file — see [looksLikeAuthFailure].
 *  2. **Not every account has both windows.** The probed account reports `secondary: null` and a single
 *     10080-minute (7-day) `primary`. Assuming a 5h+7d pair — the shape Claude always has — would render
 *     a phantom session row at 0%. Windows are classified by their own [SESSION_WINDOW_MAX_MINS], never
 *     by their position.
 *  3. **`resetsAt` is epoch SECONDS here**, where Claude's endpoint sends an ISO-8601 string and the wire
 *     carries millis. A missed ×1000 puts every reset in January 1970 and the countdown reads "resets
 *     now" forever.
 *  4. **`rateLimitsByLimitId` carries per-model caps** (`codex_bengalfox` → "GPT-5.3-Codex-Spark",
 *     `base_model_inference` → "gpt-reserve") whose ids are OpenAI's and demonstrably change between
 *     releases. They are mapped as scoped rows named by `limitName`, exactly as Claude's `weekly_scoped`
 *     rows are named by their model's display name — the shared UI already knows how to draw those.
 *
 * The app-server is spawned SHORT-LIVED, once per uncached read, and force-destroyed on the way out: this
 * is a control-plane read, and holding a live Codex process open for a progress bar would collide with
 * [CodexBackend]'s own one-shot-per-turn process discipline.
 */
class CodexQuotaService(
    /** The owner's `--codex-bin` override, threaded from Main so this and [CodexBackend] resolve the same
     *  binary. Null = the launcher's normal env/PATH/well-known-dirs search. */
    private val codexBin: String? = null,
    /** Wall clock seam (cache age). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Binary-resolution seam. Null = no Codex CLI on this machine, which is [CLAUDE_QUOTA_NO_TOKEN]:
     *  there is no allowance to report, and the client HIDES that rather than showing an error. */
    private val binary: () -> Path? = { runCatching { CodexLauncher.resolveExecutable(codexBin) }.getOrNull() },
    /** Transport seam. Tests inject a canned [AppServerOutcome]; nothing in this class's tests spawns a
     *  process, and nothing in them touches the developer's real Codex login. */
    private val transport: suspend (Path) -> AppServerOutcome = { exe -> readRateLimits(exe) },
) {
    private val log = logger("CodexQuota")
    private val mutex = Mutex()

    private var cached: ClaudeQuota? = null
    private var cachedAt: Long = 0

    /** Availability memo for the [DaemonInfo][dev.ccpocket.protocol.DaemonInfo] advertisement. Short TTL
     *  rather than a one-shot lazy: installing the Codex CLI must not require a daemon restart to be
     *  advertised, and re-walking a handful of directories once a minute is free. */
    @Volatile private var availableAt: Long = 0
    @Volatile private var availableWas: Boolean = false

    /** The transport's verdict, three-valued exactly like the Claude service's: a `result` object, a
     *  JSON-RPC `error` (the app-server ran and refused), or no answer at all (spawn/timeout/EOF). */
    sealed interface AppServerOutcome {
        /** The raw `result` object of `account/rateLimits/read`, as JSON text. */
        data class Result(val json: String) : AppServerOutcome
        data class RpcError(val message: String) : AppServerOutcome
        data class Failure(val reason: String) : AppServerOutcome
    }

    /**
     * True when a Codex binary is resolvable right now — the gate on advertising `"codex"` in
     * [dev.ccpocket.protocol.DaemonInfo.quotaAgents]. Deliberately does NOT spawn anything: the
     * advertisement rides the handshake, and a handshake must not wait on a process launch.
     */
    fun available(): Boolean {
        val t = now()
        if (t - availableAt < AVAILABILITY_TTL_MS) return availableWas
        val hit = runCatching { binary() != null }.getOrDefault(false)
        availableWas = hit
        availableAt = t
        return hit
    }

    /**
     * Answer one request, riding the cache unless [forceRefresh]. Never throws — same contract as the
     * Claude service, and for the same reason: this runs on the router's coroutine, which is only trying
     * to draw a progress bar.
     */
    suspend fun get(forceRefresh: Boolean = false): ClaudeQuota = mutex.withLock {
        val hit = cached
        if (!forceRefresh && hit != null && now() - cachedAt < ttlFor(hit.status)) return hit
        val fresh = runCatching { fetch() }.getOrElse {
            log.info("codex quota read failed: ${it::class.simpleName}")
            fail(CLAUDE_QUOTA_NETWORK, "could not read the Codex allowance")
        }
        cached = fresh
        cachedAt = now()
        fresh
    }

    private fun ttlFor(status: String) = if (status == CLAUDE_QUOTA_OK) OK_TTL_MS else FAIL_TTL_MS

    private suspend fun fetch(): ClaudeQuota {
        // resolving the binary stats a handful of directories — never on the caller's thread
        val exe = withContext(Dispatchers.IO) { binary() }
            ?: return fail(CLAUDE_QUOTA_NO_TOKEN, "the Codex CLI is not installed on this machine")
        return when (val out = transport(exe)) {
            is AppServerOutcome.Result -> parse(out.json, now())
            // "signed out" is an ERROR here, not an absence (fact 1) — and it is the one error the client
            // must treat as authoritative-nothing-to-show rather than as a transient blip
            is AppServerOutcome.RpcError ->
                if (looksLikeAuthFailure(out.message)) fail(CLAUDE_QUOTA_NO_TOKEN, "not signed in to a Codex plan")
                else fail(CLAUDE_QUOTA_HTTP, "Codex answered: ${sanitize(out.message)}")
            is AppServerOutcome.Failure -> fail(CLAUDE_QUOTA_NETWORK, out.reason)
        }
    }

    // ── parsing ────────────────────────────────────────────────────────────────────────────────────

    @Serializable
    private data class Payload(
        val rateLimits: Snapshot? = null,
        val rateLimitsByLimitId: Map<String, Snapshot> = emptyMap(),
    )

    @Serializable
    private data class Snapshot(
        val limitId: String? = null,
        val limitName: String? = null,
        val primary: Window? = null,
        val secondary: Window? = null,
        val planType: String? = null,
    )

    @Serializable
    private data class Window(
        val usedPercent: Double? = null,
        val windowDurationMins: Long? = null,
        /** epoch SECONDS upstream (fact 3) — converted on the way onto the wire, never before. */
        val resetsAt: Long? = null,
    )

    companion object {
        private val log = logger("CodexQuota")

        /** JSON-RPC ids for the two requests the read needs. Fixed, because the process is single-use. */
        private const val ID_INITIALIZE = 1L
        private const val ID_RATE_LIMITS = 2L

        const val RATE_LIMITS_METHOD = "account/rateLimits/read"

        /** Whole-read budget: spawn + handshake + one request. Matches the Claude service's HTTP budget. */
        const val REQUEST_TIMEOUT_MS = 10_000L

        /** Same cache terms as the Claude service — success is good for a minute, failures clear faster. */
        const val OK_TTL_MS = 60_000L
        const val FAIL_TTL_MS = 15_000L

        /** How long the "is codex installed" answer is reused for the DaemonInfo advertisement. */
        const val AVAILABILITY_TTL_MS = 60_000L

        /** A window this short or shorter is the rolling SESSION window; anything longer is a weekly one.
         *  300 = the 5 hours Codex reports for its short caps, and the boundary is inclusive so a future
         *  4-hour window still classifies as a session rather than silently becoming a "weekly". */
        const val SESSION_WINDOW_MAX_MINS = 300L

        /** The account-wide entry in `rateLimitsByLimitId`; it duplicates the top-level `rateLimits`, so
         *  emitting it again would draw every headline window twice. */
        const val ACCOUNT_LIMIT_ID = "codex"

        /** Cap on an error string forwarded to the client — the app-server's messages are short, and a
         *  runaway one must not inflate the frame. */
        private const val MAX_ERROR_CHARS = 200

        private val json = Json {
            ignoreUnknownKeys = true // credits / individualLimit / spendControlReached / rateLimitResetCredits …
            isLenient = true
            coerceInputValues = true // an explicit null on a non-null field falls to the default
        }

        private fun fail(status: String, error: String) =
            ClaudeQuota(status = status, error = error, agent = AgentKind.CODEX)

        /**
         * Turn one `account/rateLimits/read` result into the wire shape.
         *
         * Row order is deliberate and matches what the shared UI expects to find first: the ACCOUNT-wide
         * windows (from the top-level `rateLimits`) lead, then the per-limit scoped rows in upstream
         * order. [dev.ccpocket.app.ui.worstWeekly] picks the unscoped weekly as its headline and only
         * promotes a scoped row when that row is both warning and worse — which is only meaningful if the
         * unscoped row is actually present and unscoped, i.e. carries a null `modelDisplayName`.
         */
        internal fun parse(body: String, at: Long): ClaudeQuota {
            val payload = runCatching { json.decodeFromString(Payload.serializer(), body) }.getOrElse {
                log.info("codex quota payload did not parse: ${it::class.simpleName}")
                return fail(CLAUDE_QUOTA_HTTP, "Codex returned an unrecognized rate-limit payload")
            }
            val top = payload.rateLimits
            // account-wide windows: unscoped (modelDisplayName == null) so the shared weekly picker can
            // recognise one of them as the headline "all models" row
            val account = listOfNotNull(top?.primary, top?.secondary).map { w -> row(w, scopedName = null) }
            // the BINDING window is the one closest to running out; upstream has no `is_active` flag here,
            // and "primary" is a slot name, not a verdict about which cap will stop you first
            val bindingPct = account.maxOfOrNull { it.percent }
            var bindingTaken = false
            val accountRows = account.map {
                if (!bindingTaken && bindingPct != null && it.percent == bindingPct) {
                    bindingTaken = true; it.copy(isActive = true)
                } else it
            }
            val scopedRows = payload.rateLimitsByLimitId.entries.flatMap { (id, snap) ->
                if (id == ACCOUNT_LIMIT_ID || (top?.limitId != null && id == top.limitId)) return@flatMap emptyList()
                // a cap without a human name is labelled by its raw id: unlike Claude's null model name,
                // the id here IS meaningful ("base_model_inference") and hiding the row would hide a cap
                val name = snap.limitName?.takeIf { it.isNotBlank() } ?: id
                listOfNotNull(snap.primary, snap.secondary).map { w -> row(w, scopedName = name) }
            }
            val planType = top?.planType?.takeIf { it.isNotBlank() }
                ?: payload.rateLimitsByLimitId.values.firstNotNullOfOrNull { it.planType?.takeIf(String::isNotBlank) }
            return ClaudeQuota(
                limits = accountRows + scopedRows,
                fetchedAt = at,
                status = CLAUDE_QUOTA_OK,
                agent = AgentKind.CODEX,
                planType = planType,
            )
        }

        /** One window → one wire row. [scopedName] null = an account-wide window. */
        private fun row(w: Window, scopedName: String?): ClaudeQuotaLimit {
            val session = (w.windowDurationMins ?: Long.MAX_VALUE) <= SESSION_WINDOW_MAX_MINS
            return ClaudeQuotaLimit(
                // a scoped 5h cap is still a SESSION window: the group is what the UI buckets on, and a
                // per-model short cap belongs beside the account's short cap, not among the weeklies
                kind = when {
                    session -> CLAUDE_QUOTA_KIND_SESSION
                    scopedName != null -> CLAUDE_QUOTA_KIND_WEEKLY_SCOPED
                    else -> CLAUDE_QUOTA_KIND_WEEKLY_ALL
                },
                group = if (session) "session" else "weekly",
                percent = pct(w.usedPercent),
                // Codex reports no severity of its own; the shared UI's local >= 80% threshold is the only
                // escalation, which is exactly the "payload without severity" case that guard exists for
                resetsAt = epochMs(w.resetsAt),
                isActive = false, // set by the caller for the binding account window only
                modelDisplayName = scopedName,
            )
        }

        /** Percent CONSUMED, clamped: there is no rendering for -3 or 140. */
        private fun pct(v: Double?): Int = ((v ?: 0.0).roundToInt()).coerceIn(0, 100)

        /** epoch SECONDS → epoch MILLIS (fact 3). Absent / non-positive → null, so the client shows no
         *  countdown rather than a wrong one. */
        internal fun epochMs(seconds: Long?): Long? = seconds?.takeIf { it > 0 }?.times(1000)

        /**
         * Does this app-server error mean "not signed in"? Substring matching on an English message is
         * crude, and deliberately so: the alternative is pinning an error CODE vocabulary that is
         * OpenAI's to change, and guessing wrong in that direction would show an alarming red error where
         * the honest answer is the quiet "no allowance to report" the client hides.
         */
        internal fun looksLikeAuthFailure(message: String): Boolean {
            val m = message.lowercase()
            return AUTH_HINTS.any { it in m }
        }

        private val AUTH_HINTS = listOf(
            "not logged in", "not signed in", "log in", "login", "sign in", "signed out",
            "unauthorized", "unauthenticated", "authentication", "auth.json", "no credentials", "api key",
        )

        /** Bound and de-control-char an upstream message before it becomes [ClaudeQuota.error]. */
        internal fun sanitize(message: String): String =
            message.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_ERROR_CHARS)

        // ── the real transport ─────────────────────────────────────────────────────────────────────

        /**
         * Spawn a short-lived `codex app-server`, run the two-step handshake, ask for the rate limits and
         * kill it. Blocking IO by construction (the app-server speaks over stdio), so it runs on
         * [Dispatchers.IO] with a watchdog that force-destroys the process rather than a `withTimeout`
         * around a blocking `readLine` — a blocked stdio read does not observe coroutine cancellation,
         * and destroying the process is what actually unblocks it.
         */
        internal suspend fun readRateLimits(exe: Path, timeoutMs: Long = REQUEST_TIMEOUT_MS): AppServerOutcome =
            withContext(Dispatchers.IO) {
                val proc = runCatching {
                    CodexLauncher.processBuilder(exe, File(System.getProperty("user.home")))
                        // stderr must never reach the JSON-RPC stream, and an undrained pipe would wedge
                        // the child once it filled — discard it outright, this read logs nothing from it
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }.getOrElse {
                    return@withContext AppServerOutcome.Failure("could not start the Codex app-server (${it::class.simpleName})")
                }
                val timedOut = AtomicBoolean(false)
                val watchdog = launch {
                    delay(timeoutMs)
                    timedOut.set(true)
                    runCatching { proc.destroyForcibly() }
                }
                try {
                    val outcome = runCatching { handshakeAndRead(proc) }
                        .getOrElse { AppServerOutcome.Failure("the Codex app-server read failed (${it::class.simpleName})") }
                    // the watchdog's kill surfaces as a closed stream; report it as what it was
                    if (timedOut.get()) AppServerOutcome.Failure("the Codex app-server did not answer in time")
                    else outcome
                } finally {
                    watchdog.cancel()
                    runCatching { proc.destroyForcibly() }
                }
            }

        /** The blocking half of [readRateLimits]: write two frames, read until the answer's id lands. */
        private fun handshakeAndRead(proc: Process): AppServerOutcome {
            val w = proc.outputStream.bufferedWriter()
            val r = proc.inputStream.bufferedReader()
            fun send(line: String) { w.write(line); w.write("\n"); w.flush() }
            send(
                """{"id":$ID_INITIALIZE,"method":"initialize","params":{"clientInfo":{"name":"cc-pocket","version":""" +
                    "\"${dev.ccpocket.daemon.util.DaemonVersion.CURRENT}\"" +
                    """},"capabilities":{"experimentalApi":false}}}""",
            )
            while (true) {
                val line = r.readLine() ?: return AppServerOutcome.Failure("the Codex app-server closed the connection")
                val obj = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull()
                    as? kotlinx.serialization.json.JsonObject ?: continue
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                // notifications (remoteControl/status/changed, …) stream in throughout — ignore them all
                if (id == null) continue
                (obj["error"] as? kotlinx.serialization.json.JsonObject)?.let { e ->
                    val msg = (e["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: e.toString()
                    return AppServerOutcome.RpcError(msg)
                }
                when (id) {
                    ID_INITIALIZE -> {
                        // `initialized` is a NOTIFICATION (no id); the app-server refuses real work before it
                        send("""{"method":"initialized"}""")
                        send("""{"id":$ID_RATE_LIMITS,"method":"$RATE_LIMITS_METHOD"}""")
                    }
                    ID_RATE_LIMITS -> {
                        val result = obj["result"] ?: return AppServerOutcome.Failure("Codex answered without a result")
                        return AppServerOutcome.Result(result.toString())
                    }
                    else -> Unit // a server→client request of its own; this read answers none of them
                }
            }
        }
    }
}
