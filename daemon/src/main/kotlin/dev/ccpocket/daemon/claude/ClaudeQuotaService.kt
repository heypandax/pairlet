package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.CLAUDE_QUOTA_HTTP
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_WEEKLY_ALL
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_NORMAL
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Reads the Claude subscription allowance behind the CLI's own `/usage` panel — the rolling 5-hour
 * window, the 7-day window, and the per-model 7-day windows — and answers one [ClaudeQuota] per request.
 *
 * Why this is not derivable from what the daemon already has: [dev.ccpocket.daemon.disk.UsageService]
 * counts TOKENS out of local transcripts. The subscription allowance is a server-side number keyed to the
 * account, with its own reset clock; nothing on disk can be made to reveal it. So this is the one place
 * the daemon talks to Anthropic on the user's behalf:
 *
 * ```
 *   GET https://api.anthropic.com/api/oauth/usage
 *   Authorization: Bearer <claudeAiOauth.accessToken>
 *   anthropic-beta: oauth-2025-04-20
 * ```
 *
 * Four facts shape the implementation, each of which fails confusingly if ignored:
 *  1. **The credential lives in the OS keychain on macOS**, not in a file: `security find-generic-password
 *     -s "Claude Code-credentials" -w` prints the same JSON that Linux/Windows keep at
 *     `~/.claude/.credentials.json`. Looking only at the file on a Mac reads as "never signed in".
 *  2. **An expired token is indistinguishable from a valid one by shape.** The stored JSON carries
 *     `expiresAt` (epoch ms); past it, the endpoint answers 401 and the honest state is "signed out",
 *     not "the network is broken" — so the expiry is checked BEFORE the request goes out.
 *  3. **An API-key account has no OAuth credential at all**, and no subscription allowance to report.
 *     That is [CLAUDE_QUOTA_NO_TOKEN] — a state the client HIDES, never an error it shows.
 *  4. **The payload is a moving target.** Beside the documented rows it carries experiment fields that
 *     are null today and may be objects tomorrow, so the parse is `ignoreUnknownKeys` throughout and the
 *     row vocabulary stays a String on the wire (see ClaudeQuota.kt's red lines).
 *
 * The access token never leaves this object: it is not logged, not echoed into [ClaudeQuota.error], and
 * not carried in any exception message this class constructs. Failures are logged by CLASS, never by body.
 *
 * Results are cached briefly ([OK_TTL_MS] / [FAIL_TTL_MS]) so a UI that re-requests on every navigation
 * cannot turn into one outbound call per render; [ClaudeQuotaGet.forceRefresh] bypasses it.
 */
class ClaudeQuotaService(
    /** Credential seam — replaced in tests so expiry/absence branches need no keychain and no file. */
    private val credentials: () -> QuotaCredential = { readCredential() },
    /** Wall clock seam (expiry + cache age). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Transport seam. Tests inject a canned [HttpOutcome]; nothing in this class's tests hits the network. */
    private val transport: suspend (token: String) -> HttpOutcome = { token -> httpGet(token) },
) {
    private val log = logger("ClaudeQuota")
    private val mutex = Mutex()

    private var cached: ClaudeQuota? = null
    private var cachedAt: Long = 0

    /** What the credential store says. [Present] carries a token; the other two are both "signed out"
     *  on the wire but kept apart here so the log line says which one it was. */
    sealed interface QuotaCredential {
        data class Present(val accessToken: String) : QuotaCredential
        /** No OAuth credential at all: never signed in, signed out, or an API-key account. */
        data object Missing : QuotaCredential
        /** A credential exists but is past its `expiresAt`. */
        data object Expired : QuotaCredential
    }

    /** The transport's verdict, deliberately three-valued: a 200 body, a non-2xx code, or no answer. */
    sealed interface HttpOutcome {
        data class Body(val text: String) : HttpOutcome
        data class Status(val code: Int) : HttpOutcome
        data class Failure(val reason: String) : HttpOutcome
    }

    /**
     * Answer one request, riding the cache unless [forceRefresh]. Never throws: every failure becomes a
     * [ClaudeQuota] with a non-OK status, because a thrown exception here would take down the router
     * coroutine that is only trying to draw a progress bar.
     */
    suspend fun get(forceRefresh: Boolean = false): ClaudeQuota = mutex.withLock {
        val hit = cached
        if (!forceRefresh && hit != null && now() - cachedAt < ttlFor(hit.status)) return hit
        val fresh = runCatching { fetch() }.getOrElse {
            log.info("claude quota read failed: ${it::class.simpleName}")
            ClaudeQuota(status = CLAUDE_QUOTA_NETWORK, error = "could not read the subscription allowance")
        }
        cached = fresh
        cachedAt = now()
        fresh
    }

    private fun ttlFor(status: String) = if (status == CLAUDE_QUOTA_OK) OK_TTL_MS else FAIL_TTL_MS

    private suspend fun fetch(): ClaudeQuota {
        // the default credential read spawns `security` and touches the disk — never on the caller's thread
        val token = when (val c = withContext(Dispatchers.IO) { credentials() }) {
            is QuotaCredential.Present -> c.accessToken
            QuotaCredential.Missing -> return ClaudeQuota(status = CLAUDE_QUOTA_NO_TOKEN, error = "not signed in to a Claude subscription")
            // TRANSIENT, not signed-out: the OAuth token expires ~8h after the last claude run and renews
            // itself the next time ANY claude runs. Mapping this to NO_TOKEN made the strip vanish every
            // night and reappear mid-morning (observed daily in the QuotaRoute log, 08-24→08-27) — the
            // client clears its snapshot on NO_TOKEN but keeps an aging one on a transient status, which
            // is the honest render for "the numbers are stale until claude next runs".
            QuotaCredential.Expired -> return ClaudeQuota(status = CLAUDE_QUOTA_NETWORK, error = "the stored Claude login has expired — it renews the next time Claude runs")
        }
        return when (val out = transport(token)) {
            is HttpOutcome.Body -> parse(out.text, now())
            // the code is safe to surface; the BODY is not (it can echo request context) and never is
            is HttpOutcome.Status -> ClaudeQuota(status = CLAUDE_QUOTA_HTTP, error = "Anthropic answered HTTP ${out.code}")
            is HttpOutcome.Failure -> ClaudeQuota(status = CLAUDE_QUOTA_NETWORK, error = out.reason)
        }
    }

    // ── parsing ────────────────────────────────────────────────────────────────────────────────────

    @Serializable
    private data class Payload(
        @SerialName("five_hour") val fiveHour: Window? = null,
        @SerialName("seven_day") val sevenDay: Window? = null,
        val limits: List<Row> = emptyList(),
    )

    @Serializable
    private data class Window(
        val utilization: Double? = null,
        @SerialName("resets_at") val resetsAt: String? = null,
    )

    @Serializable
    private data class Row(
        val kind: String? = null,
        val group: String? = null,
        val percent: Double? = null,
        val severity: String? = null,
        @SerialName("resets_at") val resetsAt: String? = null,
        val scope: Scope? = null,
        @SerialName("is_active") val isActive: Boolean = false,
    )

    @Serializable
    private data class Scope(val model: ScopeModel? = null)

    @Serializable
    private data class ScopeModel(val id: String? = null, @SerialName("display_name") val displayName: String? = null)

    companion object {
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        const val OAUTH_BETA = "oauth-2025-04-20"
        const val REQUEST_TIMEOUT_MS = 10_000L

        /** A successful read is good for a minute — long enough that page navigation is free, short
         *  enough that a window crossing 80% is visible on the next look. */
        const val OK_TTL_MS = 60_000L

        /** Failures cache shorter: the common cause (offline, a 5xx) clears on its own, and a user who
         *  reconnects should not have to wait out a success-length TTL to see real numbers. */
        const val FAIL_TTL_MS = 15_000L

        /** The macOS keychain item Claude Code stores its OAuth JSON under. */
        const val KEYCHAIN_SERVICE = "Claude Code-credentials"

        private val log = logger("ClaudeQuota")

        private val json = Json {
            ignoreUnknownKeys = true   // the payload carries experiment fields that appear and change shape
            isLenient = true
            coerceInputValues = true   // an explicit null on a non-null field (is_active) falls to the default
        }

        /** Shared, lazily built: one client for the process, not one per request. */
        private val http: HttpClient by lazy {
            HttpClient(CIO) {
                expectSuccess = false // a 401/5xx is a value here, not an exception
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        }

        /** The real transport. Kept in the companion so the instance seam can replace it wholesale. */
        internal suspend fun httpGet(token: String): HttpOutcome = try {
            val res = http.get(USAGE_URL) {
                header("Authorization", "Bearer $token")
                header("anthropic-beta", OAUTH_BETA)
            }
            if (res.status.value in 200..299) HttpOutcome.Body(res.bodyAsText())
            else HttpOutcome.Status(res.status.value)
        } catch (t: Throwable) {
            // never surface `t.message`: a ktor/JDK exception can quote the request line, and the request
            // line is where the bearer token lives
            HttpOutcome.Failure("could not reach Anthropic (${t::class.simpleName})")
        }

        /**
         * Turn one 200 body into the wire shape. `limits[]` is the source of truth (it alone carries the
         * per-model `weekly_scoped` rows, the severities and the active flag); the flat `five_hour` /
         * `seven_day` pair is only a FALLBACK for a payload whose `limits` we could not read — they are
         * the first two rows' flattening, so synthesizing them twice would double-count.
         *
         * [at] is stamped as [ClaudeQuota.fetchedAt] so a cached reply keeps reporting the moment the
         * numbers were actually true.
         */
        internal fun parse(body: String, at: Long): ClaudeQuota {
            val payload = runCatching { json.decodeFromString(Payload.serializer(), body) }.getOrElse {
                log.info("claude quota payload did not parse: ${it::class.simpleName}")
                return ClaudeQuota(status = CLAUDE_QUOTA_HTTP, error = "Anthropic returned an unrecognized usage payload")
            }
            val rows = payload.limits.map { r ->
                ClaudeQuotaLimit(
                    kind = r.kind.orEmpty(),
                    group = r.group.orEmpty(),
                    percent = pct(r.percent),
                    severity = r.severity?.takeIf { it.isNotBlank() } ?: CLAUDE_QUOTA_SEVERITY_NORMAL,
                    resetsAt = epochMs(r.resetsAt),
                    isActive = r.isActive,
                    // a scoped row without a display name is left null rather than labelled with the raw
                    // id: the id is null upstream today, and "null" is not a model name
                    modelDisplayName = r.scope?.model?.displayName?.takeIf { it.isNotBlank() },
                )
            }
            val limits = rows.ifEmpty { flatten(payload) }
            return ClaudeQuota(limits = limits, fetchedAt = at, status = CLAUDE_QUOTA_OK)
        }

        /** The `limits`-less fallback: rebuild the two headline windows from the flat fields. */
        private fun flatten(p: Payload): List<ClaudeQuotaLimit> = buildList {
            p.fiveHour?.let {
                add(ClaudeQuotaLimit(kind = CLAUDE_QUOTA_KIND_SESSION, group = "session", percent = pct(it.utilization), resetsAt = epochMs(it.resetsAt)))
            }
            p.sevenDay?.let {
                add(ClaudeQuotaLimit(kind = CLAUDE_QUOTA_KIND_WEEKLY_ALL, group = "weekly", percent = pct(it.utilization), resetsAt = epochMs(it.resetsAt)))
            }
        }

        /** Percent CONSUMED, clamped: upstream sends 18 or 18.0 and there is no rendering for -3 or 140. */
        private fun pct(v: Double?): Int = ((v ?: 0.0).roundToInt()).coerceIn(0, 100)

        /** `2026-08-24T06:50:00.177220+00:00` → epoch ms; null (or anything unparseable) → null, so the
         *  client shows no countdown instead of a wrong one. */
        internal fun epochMs(iso: String?): Long? {
            if (iso.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }

        // ── credential store ───────────────────────────────────────────────────────────────────────

        /**
         * Read the OAuth credential the way the CLI stores it: the macOS keychain first, then the
         * file-based store used on Linux/Windows (and left behind by older macOS CLIs).
         *
         * Candidates are tried in order and the FIRST unexpired one wins; if every candidate is present
         * but expired the answer is [QuotaCredential.Expired] (a distinct log line from "never signed
         * in"), and if none parses at all it is [QuotaCredential.Missing].
         */
        internal fun readCredential(
            isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac"),
            files: List<File> = credentialFiles(),
            keychain: () -> String? = { keychainJson() },
            now: Long = System.currentTimeMillis(),
        ): QuotaCredential {
            val blobs = buildList {
                if (isMac) keychain()?.let { add(it) }
                for (f in files) {
                    val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
                    if (!text.isNullOrBlank()) add(text)
                }
            }
            var sawExpired = false
            for (blob in blobs) {
                val parsed = parseCredential(blob, now)
                when (parsed) {
                    is QuotaCredential.Present -> return parsed
                    QuotaCredential.Expired -> sawExpired = true
                    QuotaCredential.Missing -> Unit
                }
            }
            return if (sawExpired) QuotaCredential.Expired else QuotaCredential.Missing
        }

        /** Where the file-based store can live. The daemon's ISOLATED config dir (issue #69) is consulted
         *  too — when isolation is on, that is the login the daemon's own sessions actually bill. */
        private fun credentialFiles(): List<File> = listOf(
            File(System.getProperty("user.home"), ".claude/.credentials.json"),
            File(ClaudeHome.defaultHome(), ".credentials.json"),
        )

        /**
         * `security find-generic-password -s "Claude Code-credentials" -w` → the stored JSON, or null when
         * the item is absent (exit 44), the tool is missing, or the call hangs. stdout is NEVER logged.
         */
        internal fun keychainJson(): String? = runCatching {
            val p = ProcessBuilder("security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w")
                .redirectErrorStream(false)
                .start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            runCatching { p.errorStream.close() }
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() != 0) null else out.trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

        /**
         * Pull `claudeAiOauth.accessToken` / `.expiresAt` out of a credential blob. A token whose
         * `expiresAt` is in the past is [QuotaCredential.Expired] — the endpoint would 401 and the honest
         * client state is "signed out", not "the network is broken". A MISSING `expiresAt` is treated as
         * usable: absence is not evidence of expiry, and a 401 still degrades correctly.
         */
        internal fun parseCredential(blob: String, now: Long): QuotaCredential {
            val obj = runCatching { json.parseToJsonElement(blob) }.getOrNull() as? kotlinx.serialization.json.JsonObject
                ?: return QuotaCredential.Missing
            val oauth = obj["claudeAiOauth"] as? kotlinx.serialization.json.JsonObject ?: return QuotaCredential.Missing
            val token = (oauth["accessToken"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return QuotaCredential.Missing
            val expiresAt = (oauth["expiresAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            if (expiresAt != null && expiresAt <= now) return QuotaCredential.Expired
            return QuotaCredential.Present(token)
        }
    }
}
