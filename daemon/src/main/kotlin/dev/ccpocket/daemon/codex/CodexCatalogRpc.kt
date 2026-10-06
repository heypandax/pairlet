package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.util.logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The read-only `model/list` transport behind [CodexModelService]: one SHORT-LIVED `codex app-server`,
 * the two-step handshake, `account/read`, `config/read`, then every page of `model/list`, and the process
 * is destroyed.
 *
 * ```
 *   -> {"id":1,"method":"initialize","params":{clientInfo, capabilities:{experimentalApi:false}}}
 *   <- {"id":1,"result":{userAgent, codexHome, …}}
 *   -> {"method":"initialized"}
 *   -> {"id":2,"method":"account/read","params":{}}
 *   <- {"id":2,"result":{"account":{"type":"chatgpt","email":…,"planType":…} | {"type":"apiKey"} | null,"requiresOpenaiAuth":…}}
 *   -> {"id":3,"method":"config/read","params":{"cwd":…}}
 *   <- {"id":3,"result":{"config":{"model_provider":…,"profile":…,…},"origins":{…}}}
 *   -> {"id":4,"method":"model/list","params":{"includeHidden":true}}
 *   <- {"id":4,"result":{"data":[Model…],"nextCursor":"…"|null}}
 *   -> {"id":5,"method":"model/list","params":{"includeHidden":true,"cursor":"…"}}   (until nextCursor is null)
 * ```
 *
 * Facts this rests on, each verified against codex-cli 0.155.1 with the CLI's own
 * `app-server generate-json-schema` and a read-only probe on 2026-10-05 (`_local/codex-model-catalog-probe`):
 *  1. `ModelListParams` is `{cursor?, includeHidden?, limit?}` and the response is `{data: Model[], nextCursor}`;
 *     the cursor is an opaque string. A catalog is complete ONLY when the last page's `nextCursor` is null —
 *     a page that fails or times out makes the whole read a [CatalogOutcome.Failure], never a shorter catalog.
 *  2. The execution field is `Model.model`; `Model.id` was equal to it on every probed row but is not assumed to
 *     stay so. `displayName` is display copy only.
 *  3. **A signed-out CLI answers `model/list` with its BUILT-IN catalog and no error.** Only `account/read`
 *     tells the two apart, which is why the account is read in the same process and travels with the pages.
 *     `Account` is a tagged union: `chatgpt` carries `email`/`planType`; `apiKey` and `amazonBedrock` carry
 *     ONLY their `type` — such an account cannot be told from another of the same kind.
 *  4. `config/read` takes an optional `cwd` and answers the EFFECTIVE config seen from there (project layers
 *     included); `model_provider` and `profile` are what decide which catalog a session in that directory
 *     runs against, so they travel with the pages too. Only those two keys are kept — never base URLs,
 *     env-key names or anything else from the config object.
 *  5. The daemon never reads `~/.codex/auth.json`; the app-server authenticates itself (same rule as
 *     [CodexQuotaService]). The account object is reduced to a hash before it leaves [CodexModelService].
 *  6. `model/list` answers from the app-server's in-process catalog (~1 ms). In every probe this short-lived
 *     process did NOT rewrite `$CODEX_HOME/models_cache.json`; only a session app-server was seen to (about
 *     2 s after its start). Whether and when the CLI refreshes its catalog from the vendor is the CLI's own
 *     decision; this read cannot force it, and nothing here claims the rows are vendor-fresh.
 */
object CodexCatalogRpc {
    private val log = logger("CodexCatalog")

    /** The transport's verdict. [Success.account] is the raw `account` object (or null = signed out / unknown). */
    sealed interface CatalogOutcome {
        data class Success(
            val pages: List<JsonArray>,
            val account: JsonObject?,
            /** Set when `account/read` itself answered an error: the rows exist but the account could not be confirmed. */
            val accountError: String?,
            val userAgent: String?,
            /** `requiresOpenaiAuth` from `account/read`; null when the call failed or the field was absent. */
            val requiresOpenaiAuth: Boolean? = null,
            /** Effective `model_provider` for the request's cwd (null = unset/default or `config/read` failed). */
            val provider: String? = null,
            /** Effective `profile` for the request's cwd (null = none or `config/read` failed). */
            val profile: String? = null,
            /** Set when `config/read` answered an error — provider/profile are then unknown, not "default". */
            val configError: String? = null,
        ) : CatalogOutcome

        data class RpcError(val message: String) : CatalogOutcome
        data class Failure(val reason: String) : CatalogOutcome
    }

    /** Whole-read budget: spawn + handshake + account + config + every page. */
    const val REQUEST_TIMEOUT_MS = 15_000L

    /** Hard cap on pages — a cursor that never ends must not pin the process until the watchdog. */
    const val MAX_PAGES = 50

    /** How long the destroyed child is given to actually exit before the read returns (bounded, never a hang). */
    private const val EXIT_GRACE_MS = 2_000L

    private const val ID_INITIALIZE = 1L
    private const val ID_ACCOUNT = 2L
    private const val ID_CONFIG = 3L
    private const val ID_FIRST_PAGE = 4L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The blocking stdio conversations run here, OUTSIDE the caller's job tree: a thread blocked in
     * `readLine` observes neither cancellation nor a timeout, so the caller must stay free to run its
     * `finally` (destroy the child) while that thread is still stuck — the kill is what unblocks it.
     */
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("codex-catalog-rpc"))

    /**
     * Spawn, read, destroy. [cwd] is the directory the catalog is asked FOR (a session's workdir — Codex
     * resolves project config layers from it); null or a non-directory falls back to the user's home.
     *
     * The process is destroyed in `finally` on EVERY path — success, parse error, the [timeoutMs] deadline
     * and cancellation of the calling coroutine alike — and the read does not return until the child has
     * exited (bounded by [EXIT_GRACE_MS]). The deadline and the cancellation both act on the AWAIT, not on
     * the blocked reader thread, which is released by the kill itself.
     */
    suspend fun read(exe: Path, cwd: Path? = null, timeoutMs: Long = REQUEST_TIMEOUT_MS): CatalogOutcome = withContext(Dispatchers.IO) {
        val requested = cwd?.takeIf { runCatching { Files.isDirectory(it) }.getOrDefault(false) }
        val dir = requested?.toFile() ?: File(System.getProperty("user.home"))
        val proc = runCatching {
            CodexLauncher.processBuilder(exe, dir)
                // stderr must never reach the JSON-RPC stream, and an undrained pipe would wedge the child
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse {
            return@withContext CatalogOutcome.Failure("could not start the Codex app-server (${it::class.simpleName})")
        }
        val conversation = workers.async {
            runCatching { converse(proc, requested?.toString()) }
                .getOrElse { CatalogOutcome.Failure("the Codex app-server read failed (${it::class.simpleName})") }
        }
        try {
            withTimeoutOrNull(timeoutMs) { conversation.await() }
                ?: CatalogOutcome.Failure("the Codex app-server did not answer in time")
        } finally {
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS) }
            conversation.cancel()
        }
    }

    /** The blocking half of [read]. */
    private fun converse(proc: Process, cwd: String?): CatalogOutcome {
        val w = proc.outputStream.bufferedWriter()
        val r = proc.inputStream.bufferedReader()
        fun send(line: String) { w.write(line); w.write("\n"); w.flush() }
        send(
            """{"id":$ID_INITIALIZE,"method":"initialize","params":{"clientInfo":{"name":"cc-pocket","version":""" +
                "\"${dev.ccpocket.daemon.util.DaemonVersion.CURRENT}\"" +
                """},"capabilities":{"experimentalApi":false}}}""",
        )
        var userAgent: String? = null
        var account: JsonObject? = null
        var accountError: String? = null
        var requiresOpenaiAuth: Boolean? = null
        var provider: String? = null
        var profile: String? = null
        var configError: String? = null
        val pages = ArrayList<JsonArray>()
        var pageId = ID_FIRST_PAGE
        fun requestPage(cursor: String?) {
            val params = buildJsonObject {
                put("includeHidden", true)
                if (cursor != null) put("cursor", cursor)
            }
            send("""{"id":$pageId,"method":"model/list","params":$params}""")
        }
        while (true) {
            val line = r.readLine() ?: return CatalogOutcome.Failure("the Codex app-server closed the connection")
            val obj = runCatching { json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue // notifications stream by
            val error = (obj["error"] as? JsonObject)?.let { e -> (e["message"] as? JsonPrimitive)?.content ?: e.toString() }
            when (id) {
                ID_INITIALIZE -> {
                    if (error != null) return CatalogOutcome.RpcError(error)
                    userAgent = (obj["result"] as? JsonObject)?.get("userAgent").text()
                    send("""{"method":"initialized"}""")
                    send("""{"id":$ID_ACCOUNT,"method":"account/read","params":{}}""")
                }
                ID_ACCOUNT -> {
                    // an error here is NOT fatal for the rows: the catalog is still the CLI's answer, only the
                    // "whose catalog" question stays open — recorded, so the service labels the rows honestly
                    if (error != null) accountError = error
                    else {
                        val result = obj["result"] as? JsonObject
                        account = result?.get("account")?.takeUnless { it is JsonNull } as? JsonObject
                        requiresOpenaiAuth = (result?.get("requiresOpenaiAuth") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                    }
                    val params = buildJsonObject { if (cwd != null) put("cwd", cwd) }
                    send("""{"id":$ID_CONFIG,"method":"config/read","params":$params}""")
                }
                ID_CONFIG -> {
                    // same rule: provider/profile are scope facts, not rows — a failure leaves them UNKNOWN
                    val config = (obj["result"] as? JsonObject)?.get("config") as? JsonObject
                    when {
                        error != null -> configError = error
                        // no config object = nothing was resolved; that is NOT "the default config"
                        config == null -> configError = "config/read answered without a config object"
                        else -> {
                            provider = config["model_provider"].text()
                            profile = config["profile"].text()
                        }
                    }
                    requestPage(null)
                }
                pageId -> {
                    if (error != null) return CatalogOutcome.RpcError(error)
                    val result = obj["result"] as? JsonObject ?: return CatalogOutcome.Failure("Codex answered without a result")
                    val data = result["data"] as? JsonArray ?: return CatalogOutcome.Failure("Codex answered without a model list")
                    pages += data
                    val next = result["nextCursor"].text()
                    if (next == null) {
                        return CatalogOutcome.Success(
                            pages, account, accountError, userAgent,
                            requiresOpenaiAuth = requiresOpenaiAuth, provider = provider, profile = profile, configError = configError,
                        )
                    }
                    if (pages.size >= MAX_PAGES) return CatalogOutcome.Failure("the Codex model list did not end after $MAX_PAGES pages")
                    pageId++
                    requestPage(next)
                }
                else -> Unit // a server→client request of its own; this read answers none of them
            }
        }
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** Log helper shared with the service so the two never disagree on what is loggable: no payloads. */
    internal fun note(message: String) = log.info(message)
}
