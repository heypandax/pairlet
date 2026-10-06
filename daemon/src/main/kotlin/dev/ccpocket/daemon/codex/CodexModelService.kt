package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.codex.CodexCatalogRpc.CatalogOutcome
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AgentModePreset
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_CLI_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_FILE
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_LAST_GOOD
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelCatalogMeta
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * The Codex model catalog: what the picker offers for [AgentKind.CODEX], where it came from and how fresh
 * it is (design: `docs/design/CODEX-MODEL-CATALOG-CACHE.md`).
 *
 * Sources, in the order a request consults them:
 *  1. **dynamic** — the installed CLI's `model/list` over a short-lived app-server ([CodexCatalogRpc]), with
 *     `account/read` and `config/read` in the same process. A ChatGPT account with an identity →
 *     [MODEL_CATALOG_SOURCE_DYNAMIC]; an account the CLI cannot identify (API key, Bedrock, no email), an
 *     `account/read` error or a provider that needs no OpenAI login → [MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED];
 *     no account at all → [MODEL_CATALOG_SOURCE_CLI_BUILTIN] (the CLI silently serves its built-in list then —
 *     fact 3 of the RPC).
 *  2. **file** — `$CODEX_HOME/models_cache.json`, the CLI's own on-disk catalog. The INSTANT first answer on
 *     a cold daemon and the fallback when the RPC cannot run; its `fetched_at` is the only upstream time we
 *     ever know. Pairlet never writes this file, and re-reading it never makes it fresher.
 *  3. **last-good** — after a failed refresh the previous RPC success is kept, with the error alongside, but
 *     ONLY while the local context it was read under still holds (same `config.toml` and project
 *     `.codex/config.toml` layers, same CLI binary and build stamp, same identity in the CLI's cache file). A
 *     context that moved makes the old rows another environment's rows — including a context that moved
 *     WHILE the RPC was running, whose answer is discarded and re-read instead of being published as current.
 *  4. **builtin** — [CODEX_MODEL_IDS], only when nothing could be read; always labelled with an error.
 *
 * A successful dynamic or file catalog is served AS IS: the built-in ids are never appended to it, so a model
 * the vendor hid or removed stays gone (the pre-2026-10 behaviour merged all three sources and resurrected
 * retired ids). The configured default (`config.toml`'s top-level `model`) leads the list as a retained item,
 * not as proof it is in the catalog.
 *
 * Context: a request names the workdir it is for. Codex resolves project config layers from the cwd, so the
 * provider/profile — and with them the catalog — may differ per directory; the service keeps one slot per
 * (normalised) workdir, bounded by [MAX_SLOTS], and the RPC is run with that cwd. Requests without a workdir
 * share the home slot.
 *
 * Scheduling: one [fetch] answers at once from the slot's current answer (flagged `refreshing=true` when a
 * background check is running for it) and emits the check's result as a second frame — for clients that
 * sent a `requestId` and can tell a preview from a confirmed catalog. Clients that sent none get ONE frame:
 * the check's result (or the fresh-enough cached answer), as before the cache existed — and that frame
 * carries capability rows ONLY when they are confirmed for the current account (an already-shipped App
 * reconciles its saved effort/tier against whatever rows it sees). Checks run at most every
 * [checkIntervalMs] unless the local fingerprint moved (cache file, `config.toml`, project layers, CLI
 * binary), the newest check FAILED and its retry backoff ([RETRY_MIN_MS]..[RETRY_MAX_MS]) elapsed, the
 * caller forced one, or there is no answer yet. Concurrent callers of one slot share one in-flight
 * [Deferred]. Every seam (clock, binary, transport, scope) is injectable; nothing in the tests spawns a
 * process.
 */
class CodexModelService(
    private val cachePath: Path = CodexPaths.codexHome().resolve("models_cache.json"),
    private val configPath: Path = CodexPaths.codexHome().resolve("config.toml"),
    /** The owner's `--codex-bin` override — the catalog must come from the same codex [CodexBackend] runs. */
    private val codexBin: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val binary: () -> Path? = { runCatching { CodexLauncher.resolveExecutable(codexBin) }.getOrNull() },
    /** (executable, cwd-or-null) → outcome. The cwd is the slot's workdir; null = the home slot. */
    private val transport: suspend (Path, Path?) -> CatalogOutcome = { exe, cwd -> CodexCatalogRpc.read(exe, cwd) },
    /** Where background checks run. They outlive the requesting connection on purpose: a check the phone
     *  stopped waiting for still refreshes the cache every later caller reads. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
) {
    private val log = logger("CodexCatalog")
    private val mutex = Mutex()

    /**
     * The cheap (stat-only) local fingerprint a slot's answer was read under: the global `config.toml`, every
     * project `.codex/config.toml` layer from the workdir up, the CLI binary's path and build stamp, and the
     * CLI's cache file. Any of them moving means "possibly another environment" — re-check now, and stop
     * treating the slot's rows as this environment's until the check lands.
     */
    private data class Fingerprint(
        val configStamp: Pair<Long, Long>?,
        val projectConfigStamps: List<Pair<String, Pair<Long, Long>>>,
        val exe: String?,
        val exeStamp: Pair<Long, Long>?,
        val cacheStamp: Pair<Long, Long>?,
    )

    /** [Fingerprint] plus the identity the CLI wrote into its cache file (needs a parse, so taken per check). */
    private data class LocalContext(val fingerprint: Fingerprint, val fileIdentity: String?)

    /** One workdir's catalog state. All fields are guarded by [mutex] except where noted. */
    private class Slot(val workdir: Path?) {
        /** The answer every request starts from (null until the first check or quick read ran). Volatile:
         *  [capabilitiesFor] reads it without the mutex. */
        @Volatile var current: ModelsList? = null

        /** The newest RPC success — what a failed refresh falls back to while [lastGoodContext] still holds.
         *  Volatile: the legacy-frame path reads its source without the mutex. */
        @Volatile var lastGood: ModelsList? = null
        var lastGoodContext: LocalContext? = null
        var inFlight: Deferred<ModelsList>? = null
        var failures = 0
        var nextAutoCheckAt = 0L
        var lastFailure: String? = null

        /** The newest check failed: the answer is a fallback and must be re-checked once the backoff
         *  elapses — NOT after the full reuse interval, which only a success earns. */
        var lastCheckFailed = false

        /** The local context the newest check ENDED under. Volatile for the same reason as [current]. */
        @Volatile var checkedWith: LocalContext? = null
    }

    /** workdir key → slot, least-recently-used first; guarded by its own monitor (a sync path reads it). */
    private val slots = object : LinkedHashMap<String, Slot>(MAX_SLOTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>): Boolean =
            size > MAX_SLOTS && eldest.value.inFlight?.isActive != true
    }

    /**
     * Synchronous capability lookup for a session's settings path ([CodexBackend.normalizeEffort] & co): the
     * row for [model] as read FOR [workdir], or null = unknown. Unknown must never be mistaken for
     * known-unsupported, because the caller drops a saved effort/tier the row does not list. So the answer
     * comes only from the slot of that very workdir (another project may run another provider and list the
     * same id with other levels), only when that slot has been checked, and only while the local fingerprint
     * it was checked under still holds. No workdir (a session not yet attached), no slot (no catalog request
     * for that directory yet) or a moved fingerprint → null, and the caller keeps the user's setting.
     */
    internal fun capabilitiesFor(model: String?, workdir: String?): ModelCapabilities? {
        if (workdir.isNullOrBlank()) return null
        // A null model follows Codex's effective project/profile default, which the top-level config
        // cannot resolve. Preserve the caller's settings until the CLI names the actual model.
        val wanted = model ?: return null
        val (key, dir) = slotKey(workdir)
        if (dir == null) return null
        val slot = synchronized(slots) { slots[key] } ?: return null
        val checked = slot.checkedWith ?: return null
        if (checked.fingerprint != fingerprint(slot, checked.fingerprint.exe)) return null
        val current = slot.current ?: return null
        val confirmed = current.catalog?.source == MODEL_CATALOG_SOURCE_DYNAMIC ||
            (current.catalog?.source == MODEL_CATALOG_SOURCE_LAST_GOOD &&
                slot.lastGood?.catalog?.source == MODEL_CATALOG_SOURCE_DYNAMIC &&
                current.catalog?.scope == slot.lastGood?.catalog?.scope)
        if (!confirmed) return null
        return current.modelCapabilities.firstOrNull { it.model == wanted }
    }

    /** Convenience for tests and simple callers: the FINAL answer of one [fetch] (after any background check). */
    suspend fun fetch(forceRefresh: Boolean = false, workdir: String? = null): ModelsList {
        var last: ModelsList? = null
        fetch(forceRefresh, workdir, requestId = null) { last = it }
        return last ?: error("fetch emitted nothing")
    }

    /**
     * Answer one request for [workdir]. With a [requestId] the caller gets up to two frames — the cached
     * answer flagged `refreshing` (or alone, when fresh enough), then the check's result — each echoing the
     * id. Without one the caller gets exactly one frame: the fresh-enough cached answer, or the check's
     * result once it is in (an older client reconciles its preferences from the first frame it sees, so a
     * preview must never be that frame). Never throws.
     */
    suspend fun fetch(forceRefresh: Boolean = false, workdir: String? = null, requestId: String? = null, emit: suspend (ModelsList) -> Unit) {
        val twoFrames = requestId != null
        val (slot, immediate, job) = mutex.withLock {
            val t = now()
            val slot = slotFor(workdir)
            val cur = slot.current
            // the local fingerprint moved since the check (cache file, config.toml, a project layer, the CLI
            // binary): possibly another environment, so it re-checks now — and it is not throttled by the
            // backoff a FAILURE in the previous context earned
            val checked = slot.checkedWith
            val contextMoved = checked != null && checked.fingerprint != fingerprint(slot, checked.fingerprint.exe)
            val stale = cur == null || forceRefresh || slot.lastCheckFailed || contextMoved ||
                t - (cur.catalog?.checkedAt ?: 0L) >= checkIntervalMs
            val allowed = forceRefresh || contextMoved || t >= slot.nextAutoCheckAt
            val job = when {
                slot.inFlight?.isActive == true -> slot.inFlight // merge into the running check, whatever triggered it
                stale && allowed -> scope.async { runCheck(slot) }.also { slot.inFlight = it }
                else -> null
            }
            // the immediate answer is only materialised for callers that will see it
            val immediate = if (job == null || twoFrames) cur ?: withContext(Dispatchers.IO) { quickLocal(slot, t) } else null
            Triple(slot, immediate, job)
        }
        if (job == null) {
            emit(if (twoFrames) immediate!!.tagged(requestId) else immediate!!.forLegacy(slot))
            return
        }
        if (twoFrames) emit(immediate!!.withRefreshing(true).tagged(requestId))
        val final = runCatching { job.await() }.getOrElse {
            slot.current ?: immediate ?: withContext(Dispatchers.IO) { quickLocal(slot, now()) }
        }
        emit(if (twoFrames) final.tagged(requestId) else final.forLegacy(slot))
    }

    /**
     * The frame for a client that sent no `requestId` — an already-shipped App, which ignores `catalog` and
     * reconciles its saved effort / service tier against any capability row it sees. Rows reach it only when
     * they are CONFIRMED for the current account: a `dynamic` answer, or a `last-good` answer that is keeping
     * a `dynamic` one. File, CLI-builtin, unconfirmed and built-in rows are dropped (the ids, the presets and
     * the error stay), so an unconfirmed environment can never silently clear a preference on an old App.
     */
    private fun ModelsList.forLegacy(slot: Slot): ModelsList {
        val source = catalog?.source
        val confirmed = source == MODEL_CATALOG_SOURCE_DYNAMIC ||
            (source == MODEL_CATALOG_SOURCE_LAST_GOOD && slot.lastGood?.catalog?.source == MODEL_CATALOG_SOURCE_DYNAMIC)
        return if (confirmed || modelCapabilities.isEmpty()) this else copy(modelCapabilities = emptyList())
    }

    /** (slot key, directory): an existing directory keys its own slot; anything else is the home slot (""). */
    private fun slotKey(workdir: String?): Pair<String, Path?> {
        val dir = workdir?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(dev.ccpocket.daemon.disk.ProjectPaths.expandTilde(it)).toAbsolutePath().normalize() }.getOrNull() }
            ?.takeIf { runCatching { Files.isDirectory(it) }.getOrDefault(false) }
        return (dir?.toString() ?: "") to dir
    }

    private fun slotFor(workdir: String?): Slot {
        val (key, dir) = slotKey(workdir)
        return synchronized(slots) { slots.getOrPut(key) { Slot(dir) } }
    }

    // ── the check ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun runCheck(slot: Slot): ModelsList {
        val started = now()
        val exe = withContext(Dispatchers.IO) { binary() }
        // the fingerprint is sampled BEFORE and AFTER the read: an answer whose environment moved underneath
        // it (account switched, config edited, CLI upgraded, cache rewritten) belongs to neither side and is
        // discarded — it must not be published as the new context's catalog and reused for six hours
        val before = fingerprint(slot, exe?.toString())
        val read = if (exe == null) CatalogOutcome.Failure("the Codex CLI is not installed on this machine")
        else runCatching { transport(exe, slot.workdir) }.getOrElse { CatalogOutcome.Failure("the Codex catalog read failed (${it::class.simpleName})") }
        val file = withContext(Dispatchers.IO) { runCatching { readFile() } }
        val after = fingerprint(slot, exe?.toString())
        val context = LocalContext(after, file.getOrNull()?.identity)
        val moved = before != after
        val outcome = if (moved && read is CatalogOutcome.Success) {
            CatalogOutcome.Failure("the Codex environment changed while the catalog was being read; it will be read again")
        } else read
        val prev = slot.current
        val fresh: ModelsList = when (outcome) {
            is CatalogOutcome.Success -> fromRpc(outcome)
            is CatalogOutcome.RpcError -> afterFailure(slot, context, file, "Codex answered: ${sanitize(outcome.message)}")
            is CatalogOutcome.Failure -> afterFailure(slot, context, file, outcome.reason)
        }
        val t = now()
        val stamped = fresh.stamped(prev, t)
        mutex.withLock {
            slot.current = stamped
            if (outcome is CatalogOutcome.Success) {
                slot.lastGood = stamped; slot.lastGoodContext = context
                slot.failures = 0; slot.nextAutoCheckAt = 0L; slot.lastFailure = null; slot.lastCheckFailed = false
            } else {
                if (slot.lastGoodContext != context) { slot.lastGood = null; slot.lastGoodContext = null }
                // failures earned under another context do not compound into this one's backoff
                if (slot.checkedWith != context) slot.failures = 0
                slot.lastFailure = stamped.error
                slot.lastCheckFailed = true
                if (moved) {
                    // not the CLI's fault: the next request re-reads at once, with no backoff charged
                    slot.nextAutoCheckAt = t
                } else {
                    slot.failures++
                    slot.nextAutoCheckAt = t + backoffMs(slot.failures)
                }
            }
            slot.checkedWith = context
            slot.inFlight = null
        }
        log.info(
            "codex catalog check: source=${stamped.catalog?.source} ms=${t - started} models=${stamped.models.size} " +
                "changed=${stamped.catalog?.changedAt == t} error=${stamped.error?.let { sanitize(it).take(80) }}",
        )
        return stamped
    }

    /** A failed check keeps the last success while its context holds; otherwise the file; otherwise the labelled
     *  built-in list. The reason rides along in every case — the rows may be good, but the check that was asked
     *  for failed — and a file that could not be read says so next to the reason instead of vanishing. */
    private fun afterFailure(slot: Slot, context: LocalContext, file: Result<FileCatalog?>, reason: String): ModelsList {
        slot.lastGood?.takeIf { slot.lastGoodContext == context }?.let { good ->
            return good.copy(error = reason, catalog = good.catalog?.copy(source = MODEL_CATALOG_SOURCE_LAST_GOOD, refreshing = false))
        }
        file.getOrNull()?.let { return fromFile(it, error = reason) }
        return builtin(if (file.isFailure) "$reason; the local Codex model cache could not be read either" else reason)
    }

    /** The cold-daemon instant answer: the CLI's file if it parses, else the labelled built-in list. Also what a
     *  request gets while the check is still blocked by the retry backoff. */
    private fun quickLocal(slot: Slot, t: Long): ModelsList {
        val parsed = runCatching { readFile() }
        val list = parsed.getOrNull()?.let { fromFile(it, error = slot.lastFailure) }
            ?: builtin(
                slot.lastFailure ?: if (parsed.isFailure) "The local Codex model cache could not be read — showing built-in models only."
                else "No local Codex model cache is available — showing built-in models only. Open the Codex CLI on this computer to refresh it.",
            )
        return list.stamped(null, t)
    }

    // ── sources → ModelsList ─────────────────────────────────────────────────────────────────────────

    private fun fromRpc(out: CatalogOutcome.Success): ModelsList {
        val rows = out.pages.asSequence().flatMap { it.asSequence() }.mapNotNull { parseRpcModel(it) }.distinctBy { it.id }.take(MAX_CACHE_MODELS).toList()
        val cliVersion = cliVersionOf(out.userAgent)
        val cli = cliVersion ?: out.userAgent
        // provider/profile as Codex resolved them for this cwd; an unreadable config is its own marker, not "default"
        val cfg = if (out.configError == null) "cfg" else "cfg?"
        val account = out.account
        val type = account?.get("type").text()
        val email = account?.get("email").text()?.takeIf { it.isNotBlank() }
        val plan = account?.get("planType").text()
        // "confirmed" = a ChatGPT account with an identity, AND the effective config was read, AND it runs
        // against OpenAI (`model_provider` unset = the CLI default, or "openai"). A chatgpt login that is still
        // on disk while the session's provider is azure/ollama/… does not make that provider's catalog the
        // account's catalog, and an unreadable config cannot be assumed to be the default one.
        val openAiProvider = out.provider == null || out.provider == OPENAI_PROVIDER
        val identified = out.accountError == null && type == "chatgpt" && email != null
        val confirmed = identified && out.configError == null && openAiProvider
        val (source, scopeMark) = when {
            confirmed -> MODEL_CATALOG_SOURCE_DYNAMIC to hash("acct", type, email, plan, cli, cfg, out.provider, out.profile)
            // the rows answered, but WHOSE they are is open: an account/read error, an identified account
            // behind another provider or an unreadable config, an account type that exposes no identity
            // (apiKey / amazonBedrock), a ChatGPT account without an email, or a provider that needs no
            // OpenAI login — scoped by what is known, never by a guessed identity
            out.accountError != null -> MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED to hash("acct-error", cli, cfg, out.provider, out.profile)
            identified -> MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED to hash("acct-unconfirmed", type, email, plan, cli, cfg, out.provider, out.profile)
            account != null -> MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED to hash("acct-anon", type, cli, cfg, out.provider, out.profile)
            out.requiresOpenaiAuth == false -> MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED to hash("no-auth", cli, cfg, out.provider, out.profile)
            else -> MODEL_CATALOG_SOURCE_CLI_BUILTIN to hash("none", cli, cfg, out.provider, out.profile)
        }
        return assemble(rows, ModelCatalogMeta(source = source, scope = scopeMark, cliVersion = cliVersion), error = null)
    }

    private fun fromFile(file: FileCatalog, error: String?): ModelsList = assemble(
        file.rows,
        ModelCatalogMeta(
            source = MODEL_CATALOG_SOURCE_FILE,
            scope = hash("file", file.identity, file.clientVersion),
            upstreamAt = file.fetchedAt,
            cliVersion = file.clientVersion,
        ),
        error = error,
    )

    private fun builtin(error: String): ModelsList = ModelsList(
        agent = AgentKind.CODEX,
        models = (listOfNotNull(CodexDefaultModel.resolve(configPath)) + CODEX_MODEL_IDS).distinct(),
        error = error,
        catalog = ModelCatalogMeta(source = MODEL_CATALOG_SOURCE_BUILTIN),
    )

    private fun assemble(rows: List<CatalogModel>, meta: ModelCatalogMeta, error: String?): ModelsList {
        val configured = CodexDefaultModel.resolve(configPath)
        val visible = rows.filter { !it.capabilities.hidden }.map { it.id }
        return ModelsList(
            agent = AgentKind.CODEX,
            // the configured default leads as a RETAINED item (it is what a null choice runs), never as proof
            // the catalog lists it; hidden rows stay out of the picker but keep their capabilities below
            models = (listOfNotNull(configured) + visible).distinct(),
            modelCapabilities = rows.map { it.capabilities },
            modePresets = MODE_PRESETS,
            error = error,
            catalog = meta,
        )
    }

    /** Fill the version/time fields against the previous answer: same content → same `changedAt`. */
    private fun ModelsList.stamped(prev: ModelsList?, t: Long): ModelsList {
        val meta = catalog ?: ModelCatalogMeta(source = MODEL_CATALOG_SOURCE_BUILTIN)
        val version = contentVersion(this)
        val prevMeta = prev?.catalog
        val changedAt = if (prevMeta != null && prevMeta.contentVersion == version) prevMeta.changedAt ?: t else t
        return copy(catalog = meta.copy(contentVersion = version, checkedAt = t, changedAt = changedAt, refreshing = false))
    }

    private fun ModelsList.withRefreshing(on: Boolean) = copy(catalog = (catalog ?: ModelCatalogMeta()).copy(refreshing = on))

    private fun ModelsList.tagged(requestId: String?) = if (requestId == null) this else copy(requestId = requestId)

    // ── the local files ──────────────────────────────────────────────────────────────────────────────

    private class FileCatalog(val rows: List<CatalogModel>, val fetchedAt: Long?, val identity: String?, val clientVersion: String?)

    /** Null when the file is absent; throws when it is unreadable or not a catalog (no object root, no `models`
     *  array). A present `"models": []` is a GENUINELY empty catalog, which is a result, not a failure. */
    private fun readFile(): FileCatalog? {
        val file = cachePath.toFile()
        if (!file.isFile) return null
        val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: throw IllegalStateException("models_cache.json root is not an object")
        val models = root["models"] as? JsonArray ?: throw IllegalStateException("models_cache.json has no models array")
        val rows = models.asSequence()
            .mapNotNull { parseFileModel(it) }
            .sortedWith(compareBy<Pair<CatalogModel, Int>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .distinctBy { it.id }
            .take(MAX_CACHE_MODELS)
            .toList()
        return FileCatalog(
            rows = rows,
            fetchedAt = root["fetched_at"].text()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            identity = root["identity"].text(),
            clientVersion = root["client_version"].text(),
        )
    }

    private fun stampOf(path: Path): Pair<Long, Long>? = runCatching {
        if (!Files.isRegularFile(path)) null else Files.getLastModifiedTime(path).toMillis() to Files.size(path)
    }.getOrNull()

    private fun fileStamp(): Pair<Long, Long>? = stampOf(cachePath)
    private fun configStamp(): Pair<Long, Long>? = stampOf(configPath)

    /** The stat-only fingerprint of [slot]'s environment right now, for the CLI at [exe] (null = none resolved). */
    private fun fingerprint(slot: Slot, exe: String?): Fingerprint = Fingerprint(
        configStamp = configStamp(),
        projectConfigStamps = projectConfigStamps(slot.workdir),
        exe = exe,
        exeStamp = exe?.let { runCatching { stampOf(Path.of(it)) }.getOrNull() },
        cacheStamp = fileStamp(),
    )

    /** Every `.codex/config.toml` from the workdir up to the root (the project layers Codex merges for that
     *  cwd), present ones only, path + stamp — a layer appearing, vanishing or changing is a change. Bounded. */
    private fun projectConfigStamps(workdir: Path?): List<Pair<String, Pair<Long, Long>>> {
        var dir: Path = workdir ?: return emptyList()
        val out = ArrayList<Pair<String, Pair<Long, Long>>>()
        repeat(MAX_PROJECT_LAYER_DEPTH) {
            val layer = dir.resolve(".codex").resolve("config.toml")
            stampOf(layer)?.let { out += layer.toString() to it }
            dir = dir.parent ?: return out
        }
        return out
    }

    // ── parsing (both shapes, each field shape-tolerant: one odd entry costs that field, not the listing) ──

    private class CatalogModel(val id: String, val capabilities: ModelCapabilities)

    /** `model/list` row (camelCase schema). The EXECUTION id is `model`, falling back to `id`. */
    private fun parseRpcModel(element: JsonElement): CatalogModel? {
        val obj = element as? JsonObject ?: return null
        val id = (obj["model"].text() ?: obj["id"].text())?.trim()?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) } ?: return null
        val efforts = (obj["supportedReasoningEfforts"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("reasoningEffort").text()?.trim()?.takeIf { e -> usableWireString(e, MAX_EFFORT_LEN) } }
            .distinct().take(MAX_EFFORTS)
        val upgrade = obj["upgrade"].text()?.trim()?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) }
            ?: (obj["upgradeInfo"] as? JsonObject)?.get("model").text()?.trim()?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) }
        return CatalogModel(
            id,
            ModelCapabilities(
                model = id,
                reasoningEfforts = efforts,
                defaultReasoningEffort = obj["defaultReasoningEffort"].text()?.trim()?.takeIf { usableWireString(it, MAX_EFFORT_LEN) },
                serviceTiers = parseTiers(obj["serviceTiers"]),
                displayName = obj["displayName"].text()?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_DISPLAY_NAME_LEN),
                hidden = (obj["hidden"] as? JsonPrimitive)?.booleanOrNull ?: false,
                isDefault = (obj["isDefault"] as? JsonPrimitive)?.booleanOrNull ?: false,
                upgradeTo = upgrade,
                defaultServiceTier = obj["defaultServiceTier"].text()?.trim()?.takeIf { usableWireString(it, MAX_TIER_ID_LEN) },
            ),
        )
    }

    /** `models_cache.json` row (snake_case). Returns the row with its `priority` for ordering. */
    private fun parseFileModel(element: JsonElement): Pair<CatalogModel, Int>? {
        val obj = element as? JsonObject ?: return null
        val slug = obj["slug"].text()?.trim()?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) } ?: return null
        val efforts = (obj["supported_reasoning_levels"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("effort").text()?.trim()?.takeIf { e -> usableWireString(e, MAX_EFFORT_LEN) } }
            .distinct().take(MAX_EFFORTS)
        // `upgrade` has been seen as null, as a string and as an object `{model: …}`; it is a HINT, never a filter
        val upgradeEl = obj["upgrade"]?.takeUnless { it is JsonNull }
        val upgrade = (upgradeEl.text() ?: (upgradeEl as? JsonObject)?.get("model").text())?.trim()?.takeIf { usableWireString(it, MAX_MODEL_ID_LEN) }
        val row = CatalogModel(
            slug,
            ModelCapabilities(
                model = slug,
                reasoningEfforts = efforts,
                defaultReasoningEffort = obj["default_reasoning_level"].text()?.trim()?.takeIf { usableWireString(it, MAX_EFFORT_LEN) },
                serviceTiers = parseTiers(obj["service_tiers"]),
                displayName = obj["display_name"].text()?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_DISPLAY_NAME_LEN),
                hidden = obj["visibility"].text() != "list",
                upgradeTo = upgrade,
                defaultServiceTier = obj["default_service_tier"].text()?.trim()?.takeIf { usableWireString(it, MAX_TIER_ID_LEN) },
            ),
        )
        return row to (obj["priority"].int() ?: Int.MAX_VALUE)
    }

    private fun parseTiers(element: JsonElement?): List<ModelServiceTier> =
        (element as? JsonArray).orEmpty().mapNotNull { tier ->
            val t = tier as? JsonObject ?: return@mapNotNull null
            val id = t["id"].text()?.trim()?.takeIf { usableWireString(it, MAX_TIER_ID_LEN) } ?: return@mapNotNull null
            ModelServiceTier(
                id = id,
                name = t["name"].text()?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TIER_NAME_LEN) ?: id,
                description = t["description"].text()?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TIER_DESCRIPTION_LEN),
            )
        }.distinctBy { it.id }.take(MAX_SERVICE_TIERS)

    companion object {
        /**
         * The permission-mode vocabulary this daemon's [CodexBackend] really implements, advertised so the
         * App stops baking it in (PR #296 review). One table, and it must stay in step with
         * `CodexBackend.approvalPolicy`/`sandbox`: PLAN → untrusted + read-only, DEFAULT → on-request +
         * workspace, ACCEPT_EDITS → never + workspace, BYPASS_PERMISSIONS → never + full access.
         *
         * [AgentModePreset.label]/[AgentModePreset.detail] are the ENGLISH FALLBACK for an App that doesn't
         * know the id — the shipped App renders its own localized copy for these four ids and only reads
         * the set, the order and the danger/recommended emphasis from here. That is exactly what a future
         * codex release may change without an app-store round trip.
         */
        internal val MODE_PRESETS = listOf(
            AgentModePreset(PermissionMode.PLAN, "cautious", "Cautious", "Ask before every step; read-only file access"),
            AgentModePreset(PermissionMode.DEFAULT, "balanced", "Balanced", "Ask when needed; workspace writes", recommended = true),
            AgentModePreset(PermissionMode.ACCEPT_EDITS, "autonomous", "Autonomous", "Never ask; workspace writes"),
            AgentModePreset(PermissionMode.BYPASS_PERMISSIONS, "full", "Full access", "Never ask; full filesystem access", danger = true),
        )

        /** How long a successful check is reused before the CLI is asked again (a changed cache file or
         *  `config.toml`, a failed check's backoff, or a manual refresh asks sooner). Not a vendor-freshness
         *  guarantee — see the class KDoc. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L

        /** Automatic retry after a failed check: doubling from one minute, capped at fifteen. */
        const val RETRY_MIN_MS = 60_000L
        const val RETRY_MAX_MS = 15 * 60_000L

        /** Workdir slots kept at once; the least recently used one (not mid-check) is dropped past this. */
        const val MAX_SLOTS = 16

        /** How many parent directories are walked for project `.codex/config.toml` layers. */
        private const val MAX_PROJECT_LAYER_DEPTH = 24

        /** Codex's default `model_provider`; the only one whose catalog is the ChatGPT account's own. */
        private const val OPENAI_PROVIDER = "openai"

        internal fun backoffMs(failures: Int): Long =
            (RETRY_MIN_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(RETRY_MAX_MS)

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_CACHE_MODELS = 128
        private const val MAX_MODEL_ID_LEN = 128
        private const val MAX_DISPLAY_NAME_LEN = 64
        private const val MAX_EFFORTS = 16
        private const val MAX_EFFORT_LEN = 32
        private const val MAX_SERVICE_TIERS = 8
        private const val MAX_TIER_ID_LEN = 64
        private const val MAX_TIER_NAME_LEN = 64
        private const val MAX_TIER_DESCRIPTION_LEN = 160
        private const val MAX_ERROR_CHARS = 200

        private val CLI_VERSION = Regex("""codex[\w-]*/(\d[\w.\-]*)""", RegexOption.IGNORE_CASE)

        /** `codex_cli_rs/0.155.1 (…)` → `0.155.1`; null when the user agent carries no version. */
        internal fun cliVersionOf(userAgent: String?): String? = userAgent?.let { CLI_VERSION.find(it)?.groupValues?.get(1) }

        /**
         * The content hash behind [ModelCatalogMeta.contentVersion]: the ORDERED semantic rows only (ids,
         * names, visibility, default flag, efforts, tiers, upgrade hints, configured default). Check times,
         * error texts and the source label are deliberately outside it — a re-read of unchanged data must
         * produce the same version.
         */
        internal fun contentVersion(list: ModelsList): String {
            val rows = list.modelCapabilities.joinToString("\n") { c ->
                listOf(
                    c.model, c.displayName.orEmpty(), c.hidden, c.isDefault, c.defaultReasoningEffort.orEmpty(),
                    c.reasoningEfforts.joinToString(","), c.serviceTiers.joinToString(",") { "${it.id}:${it.name}" },
                    c.defaultServiceTier.orEmpty(), c.upgradeTo.orEmpty(),
                ).joinToString("|")
            }
            return sha16("v1\n" + list.models.joinToString(",") + "\n" + rows)
        }

        /** A non-reversible scope marker: the hash of the facts, never the facts. */
        internal fun hash(vararg parts: String?): String = sha16(parts.joinToString("\u0000") { it.orEmpty() })

        private fun sha16(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        internal fun sanitize(message: String): String =
            message.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_ERROR_CHARS)

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

        private fun usableWireString(value: String, maxLength: Int): Boolean =
            value.isNotEmpty() && value.length <= maxLength && value.none(Char::isISOControl)
    }
}
