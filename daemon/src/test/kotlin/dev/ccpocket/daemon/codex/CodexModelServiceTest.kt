package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.codex.CodexCatalogRpc.CatalogOutcome
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_CLI_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_FILE
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_LAST_GOOD
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Codex catalog service against injected sources — no test here spawns a process or reads the
 * developer's `~/.codex`. The RPC fixtures mirror the `model/list` shape probed on codex-cli 0.155.1
 * (`_local/codex-model-catalog-probe`); the file fixtures mirror `models_cache.json`.
 */
class CodexModelServiceTest {

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────

    private class Env(val dir: Path) {
        val cache: Path = dir.resolve("models_cache.json")
        val config: Path = dir.resolve("config.toml")
        var clock = 1_000_000L
        val transportCalls = AtomicInteger()
    }

    private fun env(config: String? = "model = \"gpt-6-astra\"\n", cache: String? = FILE_CATALOG): Env {
        val e = Env(Files.createTempDirectory("codex-catalog-test"))
        config?.let { Files.writeString(e.config, it) }
        cache?.let { Files.writeString(e.cache, it) }
        return e
    }

    private fun service(
        e: Env,
        binary: Path? = e.dir.resolve("codex"),
        checkIntervalMs: Long = CodexModelService.CHECK_INTERVAL_MS,
        transport: suspend (Path) -> CatalogOutcome,
    ) = serviceWithCwd(e, binary, checkIntervalMs) { p, _ -> transport(p) }

    private fun serviceWithCwd(
        e: Env,
        binary: Path? = e.dir.resolve("codex"),
        checkIntervalMs: Long = CodexModelService.CHECK_INTERVAL_MS,
        transport: suspend (Path, Path?) -> CatalogOutcome,
    ) = CodexModelService(
        cachePath = e.cache, configPath = e.config, now = { e.clock }, binary = { binary },
        transport = { p, cwd -> e.transportCalls.incrementAndGet(); transport(p, cwd) },
        scope = CoroutineScope(Dispatchers.Default), checkIntervalMs = checkIntervalMs,
    )

    private fun page(vararg models: String): JsonArray = Json.parseToJsonElement("[${models.joinToString(",")}]") as JsonArray

    private fun rpcModel(
        id: String, name: String = id.uppercase(), hidden: Boolean = false, isDefault: Boolean = false,
        efforts: List<String> = listOf("low", "medium", "high"), upgrade: String? = null, upgradeInfoModel: String? = null,
    ) = """
        {"id":"$id","model":"$id","displayName":"$name","description":"d","hidden":$hidden,"isDefault":$isDefault,
         "defaultReasoningEffort":"medium",
         "supportedReasoningEfforts":[${efforts.joinToString(",") { """{"reasoningEffort":"$it","description":"x"}""" }}],
         "serviceTiers":[{"id":"priority","name":"Fast","description":"2x speed"}],
         "defaultServiceTier":null,"inputModalities":["text","image"],"supportsPersonality":true,
         "upgrade":${upgrade?.let { "\"$it\"" } ?: "null"},
         "upgradeInfo":${upgradeInfoModel?.let { """{"model":"$it","upgradeCopy":"Try it"}""" } ?: "null"}}
    """.trimIndent()

    private val signedIn: JsonObject = Json.parseToJsonElement("""{"type":"chatgpt","email":"alex@example.com","planType":"pro"}""").jsonObject
    private val userAgent = "codex_cli_rs/0.155.1 (Mac OS 26.5.0; arm64) cc-pocket/0.0.1"

    private fun success(vararg pages: JsonArray, account: JsonObject? = signedIn, accountError: String? = null) =
        CatalogOutcome.Success(pages.toList(), account, accountError, userAgent)

    private val requestIds = AtomicInteger()

    /** Drive one [CodexModelService.fetch] AS A NEW CLIENT (a requestId opts into the two-frame delivery)
     *  and collect every emitted frame. */
    private suspend fun emits(svc: CodexModelService, force: Boolean = false, workdir: String? = null, requestId: String? = "req-${requestIds.incrementAndGet()}"): List<ModelsList> {
        val out = ArrayList<ModelsList>()
        withTimeout(10_000) { svc.fetch(force, workdir, requestId) { out += it } }
        return out
    }

    // ── the local file as a source ───────────────────────────────────────────────────────────────────

    @Test
    fun the_file_catalog_is_served_as_is_with_the_configured_default_first_and_no_builtin_ids_appended() = runBlocking {
        val e = env()
        val svc = service(e, binary = null) { fail("no binary, no transport") }

        val result = emits(svc).last() // a new client: the rows come with their provenance

        assertEquals(AgentKind.CODEX, result.agent)
        // configured leads; visible rows follow in priority order; `gpt-locked` (upgrade hint) STAYS; hidden rows and
        // the three built-in ids are NOT appended to a catalog that answered
        assertEquals(listOf("gpt-6-astra", "gpt-locked", "gpt-5.6-sol", "gpt-5.5"), result.models)
        assertFalse(result.models.containsAll(CODEX_MODEL_IDS), "a real catalog must not resurrect the built-in trio")
        val sol = result.modelCapabilities.single { it.model == "gpt-5.6-sol" }
        assertEquals(listOf("max", "ultra"), sol.reasoningEfforts)
        assertEquals("max", sol.defaultReasoningEffort)
        assertEquals("Fast", sol.serviceTiers.single().name)
        assertEquals("GPT-5.6-Sol", sol.displayName)
        assertEquals("gpt-next", result.modelCapabilities.single { it.model == "gpt-locked" }.upgradeTo, "upgrade is a hint, not a filter")
        val hidden = result.modelCapabilities.single { it.model == "codex-auto-review" }
        assertTrue(hidden.hidden, "hidden rows keep their capabilities so a saved id can still be explained")
        assertNull(result.modelCapabilities.single { it.model == "gpt-5.5" }.defaultReasoningEffort)

        val meta = assertNotNull(result.catalog)
        assertEquals(MODEL_CATALOG_SOURCE_FILE, meta.source)
        assertEquals(1_791_256_374_259L, meta.upstreamAt, "the file's own fetched_at (2026-10-06T03:12:54.259Z) is the only upstream time we know")
        assertEquals("0.160.0", meta.cliVersion)
        assertNotNull(meta.scope); assertNotNull(meta.contentVersion)
        assertEquals(e.clock, meta.checkedAt)
        assertFalse(meta.refreshing)
        // the check itself could not run (no CLI) — the rows are good but the answer says the check failed
        assertTrue(result.error.orEmpty().contains("not installed"), result.error.toString())
    }

    /** One entry in a shape this daemon has not seen must cost that entry's field, not the whole list —
     *  a throw here used to replace the real catalog with the three built-in fallbacks. */
    @Test
    fun the_file_catalog_survives_one_entry_in_an_unexpected_shape() = runBlocking {
        val e = env(
            config = null,
            cache = """
            {
              "models": [
                { "slug": "gpt-6-sol", "visibility": "list", "priority": { "rank": 1 },
                  "default_reasoning_level": { "effort": "max" },
                  "supported_reasoning_levels": [ { "effort": ["max"] }, { "effort": "ultra" } ],
                  "service_tiers": [ { "id": "priority", "name": { "en": "Fast" }, "description": [] } ] },
                { "slug": { "id": "broken" }, "visibility": "list", "priority": 2 },
                { "slug": "gpt-6-luna", "visibility": ["list"], "priority": 3 },
                { "slug": "gpt-6-astra", "visibility": "list", "priority": 4, "upgrade": null }
              ]
            }
            """.trimIndent(),
        )
        val result = emits(service(e, binary = null) { fail("no transport") }).last()

        assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), result.models, "luna's odd visibility reads as hidden; the broken slug is dropped")
        val sol = result.modelCapabilities.single { it.model == "gpt-6-sol" }
        assertEquals(listOf("ultra"), sol.reasoningEfforts)
        assertNull(sol.defaultReasoningEffort)
        assertEquals("priority", sol.serviceTiers.single().name)
    }

    /** Without the cache AND without a CLI the list is only the built-in fallback; the reply must say so. */
    @Test
    fun missing_cache_and_no_cli_is_the_labelled_builtin_fallback() = runBlocking {
        val e = env(config = "model = \"gpt-6-sol\"\n", cache = null)
        val result = service(e, binary = null) { fail("no transport") }.fetch()

        assertEquals(listOf("gpt-6-sol") + CODEX_MODEL_IDS, result.models)
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, result.catalog?.source)
        assertNull(result.catalog?.scope, "a built-in list belongs to no environment")
        assertTrue(result.error.orEmpty().contains("not installed"), result.error.toString())
        assertTrue(result.modePresets.isEmpty(), "the error branch advertises no vocabulary")
    }

    @Test
    fun a_corrupt_cache_is_an_error_not_a_catalog() = runBlocking {
        val e = env(config = null, cache = "{ not json")
        val result = service(e, binary = null) { fail("no transport") }.fetch()

        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, result.catalog?.source)
        assertEquals(CODEX_MODEL_IDS, result.models)
        assertNotNull(result.error)
        assertTrue(result.modePresets.isEmpty())
    }

    @Test
    fun a_cache_without_a_models_array_is_corrupt_but_an_empty_models_array_is_an_empty_catalog() = runBlocking {
        val noKey = service(env(config = null, cache = """{"fetched_at":"2026-10-06T03:12:54Z","etag":"x"}"""), binary = null) { fail("no transport") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, noKey.catalog?.source, "a catalog file without `models` is not a catalog")
        assertTrue(noKey.error.orEmpty().contains("could not be read"), noKey.error.toString())

        val notObject = service(env(config = null, cache = "[1,2,3]"), binary = null) { fail("no transport") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, notObject.catalog?.source)

        val empty = service(env(config = null, cache = """{"models":[],"identity":"abc"}"""), binary = null) { fail("no transport") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_FILE, empty.catalog?.source, "`models: []` is a real, empty catalog")
        assertTrue(empty.models.isEmpty() && empty.modelCapabilities.isEmpty())
    }

    @Test
    fun file_capabilities_are_bounded_before_they_reach_the_wire() = runBlocking {
        val efforts = (0 until 40).joinToString(",") { """{"effort":"effort-$it"}""" }
        val tiers = (0 until 40).joinToString(",") { """{"id":"tier-$it","name":"${"n".repeat(200)}","description":"${"d".repeat(400)}"}""" }
        val models = (0 until 250).joinToString(",") {
            """{"slug":"model-$it","visibility":"list","priority":$it,"upgrade":null,"supported_reasoning_levels":[$efforts],"service_tiers":[$tiers]}"""
        }
        val e = env(config = null, cache = """{"models":[$models]}""")
        val result = emits(service(e, binary = null) { fail("no transport") }).last()

        assertEquals(128, result.modelCapabilities.size)
        assertTrue(result.modelCapabilities.all { it.reasoningEfforts.size == 16 })
        assertTrue(result.modelCapabilities.all { it.serviceTiers.size == 8 })
        assertTrue(result.modelCapabilities.flatMap { it.serviceTiers }.all { it.id.length <= 64 && it.name.length <= 64 && (it.description?.length ?: 0) <= 160 })
        assertTrue(PocketJson.encodeToString(result).encodeToByteArray().size < 512 * 1024)
    }

    // ── the dynamic (RPC) source ─────────────────────────────────────────────────────────────────────

    @Test
    fun a_cold_fetch_answers_from_the_file_at_once_then_replaces_it_with_the_dynamic_catalog() = runBlocking {
        val e = env()
        val svc = service(e) {
            success(page(rpcModel("gpt-6-astra", "GPT-6-Astra", isDefault = true), rpcModel("gpt-6-sol"), rpcModel("gpt-5.5", hidden = true),
                rpcModel("gpt-5.4", hidden = true, upgrade = "gpt-5.5"), rpcModel("gpt-old", upgradeInfoModel = "gpt-6-sol")))
        }

        val frames = emits(svc)

        assertEquals(2, frames.size, "cached-at-once, then the check's result")
        val first = frames[0]
        assertEquals(MODEL_CATALOG_SOURCE_FILE, first.catalog?.source)
        assertTrue(first.catalog?.refreshing == true, "the instant answer says a check is running")
        assertNull(first.error, "no check has failed yet")

        val fresh = frames[1]
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, fresh.catalog?.source)
        assertFalse(fresh.catalog?.refreshing == true)
        assertEquals(listOf("gpt-6-astra", "gpt-6-sol", "gpt-old"), fresh.models, "hidden rows out, upgrade-hinted rows in, nothing appended")
        assertNull(fresh.error)
        val astra = fresh.modelCapabilities.single { it.model == "gpt-6-astra" }
        assertEquals("GPT-6-Astra", astra.displayName); assertTrue(astra.isDefault)
        assertEquals("gpt-5.5", fresh.modelCapabilities.single { it.model == "gpt-5.4" }.upgradeTo)
        assertEquals("gpt-6-sol", fresh.modelCapabilities.single { it.model == "gpt-old" }.upgradeTo, "upgradeInfo.model is the fallback hint")
        assertTrue(fresh.modelCapabilities.single { it.model == "gpt-5.5" }.hidden)
        assertEquals("0.155.1", fresh.catalog?.cliVersion)
        assertNotNull(fresh.catalog?.scope)
        assertNull(fresh.catalog?.upstreamAt, "the RPC does not say when the vendor was asked — so neither do we")
        assertEquals(CodexModelService.MODE_PRESETS, fresh.modePresets)
        assertEquals(1, e.transportCalls.get())
    }

    @Test
    fun pages_are_assembled_in_order_into_one_catalog() = runBlocking {
        val e = env(cache = null)
        val svc = service(e) { success(page(rpcModel("a"), rpcModel("b")), page(rpcModel("c")), page(rpcModel("d", hidden = true))) }

        val result = svc.fetch()

        assertEquals(listOf("gpt-6-astra", "a", "b", "c"), result.models)
        assertEquals(listOf("a", "b", "c", "d"), result.modelCapabilities.map { it.model })
    }

    @Test
    fun a_signed_out_cli_is_labelled_cli_builtin_with_a_different_scope() = runBlocking {
        val e = env(cache = null)
        val signedInScope = service(e) { success(page(rpcModel("a"))) }.fetch().catalog?.scope
        val out = service(env(cache = null)) { success(page(rpcModel("a")), account = null) }.fetch()

        assertEquals(MODEL_CATALOG_SOURCE_CLI_BUILTIN, out.catalog?.source)
        assertEquals(listOf("gpt-6-astra", "a"), out.models, "the CLI's rows are still real rows")
        assertNull(out.error)
        assertNotNull(out.catalog?.scope)
        assertNotEquals(signedInScope, out.catalog?.scope, "signed-in and signed-out are different environments")
        // an account/read error leaves the account UNCONFIRMED — not "no account", not "this account"
        val unconfirmed = service(env(cache = null)) { success(page(rpcModel("a")), accountError = "method not found") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, unconfirmed.catalog?.source)
        assertNotEquals(signedInScope, unconfirmed.catalog?.scope)
        assertNotEquals(out.catalog?.scope, unconfirmed.catalog?.scope)
    }

    @Test
    fun accounts_without_an_identity_are_unconfirmed_and_provider_profile_and_cli_shape_the_scope() = runBlocking {
        suspend fun scopeOf(account: JsonObject?, provider: String? = null, profile: String? = null, ua: String = userAgent, requiresAuth: Boolean? = true, configError: String? = null): Pair<String?, String?> {
            val r = service(env(cache = null)) {
                CatalogOutcome.Success(listOf(page(rpcModel("a"))), account, null, ua, requiresOpenaiAuth = requiresAuth, provider = provider, profile = profile, configError = configError)
            }.fetch()
            return r.catalog?.source to r.catalog?.scope
        }
        val apiKey = Json.parseToJsonElement("""{"type":"apiKey"}""").jsonObject
        val bedrock = Json.parseToJsonElement("""{"type":"amazonBedrock","usesCodexManagedCredentials":false}""").jsonObject
        val noEmail = Json.parseToJsonElement("""{"type":"chatgpt","email":null,"planType":"pro"}""").jsonObject

        val (s1, k1) = scopeOf(apiKey)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, s1, "an API-key account exposes only its type: not confirmable")
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(bedrock).first)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(noEmail).first, "a ChatGPT account without an email is not an identity")
        assertNotEquals(k1, scopeOf(bedrock).second, "different account kinds are different environments")
        assertEquals(k1, scopeOf(apiKey).second, "same facts → same scope (the App keys its cache on it)")
        // a provider that needs no OpenAI login answers with no account — that is not "signed out"
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(null, provider = "ollama", requiresAuth = false).first)
        assertEquals(MODEL_CATALOG_SOURCE_CLI_BUILTIN, scopeOf(null, requiresAuth = true).first)

        val (_, confirmed) = scopeOf(signedIn)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, scopeOf(signedIn).first)
        assertNotEquals(confirmed, scopeOf(signedIn, provider = "azure").second, "provider is part of the environment")
        assertNotEquals(confirmed, scopeOf(signedIn, profile = "work").second, "profile is part of the environment")
        assertNotEquals(confirmed, scopeOf(signedIn, ua = userAgent.replace("0.155.1", "0.156.0")).second, "a CLI upgrade is a new environment")
        assertNotEquals(confirmed, scopeOf(signedIn, configError = "boom").second, "an unreadable config is not the default config")
        assertEquals(confirmed, scopeOf(signedIn).second)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, scopeOf(signedIn, provider = "openai").first, "the explicit default provider is still OpenAI")

        // a ChatGPT login that is still on disk does not make a custom provider's catalog the account's catalog,
        // and a config that could not be read cannot be assumed to be the default one
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(signedIn, provider = "azure").first)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(signedIn, provider = "ollama", requiresAuth = false).first)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED, scopeOf(signedIn, configError = "config/read answered without a config object").first)
        assertNotEquals(scopeOf(signedIn, provider = "azure").second, scopeOf(signedIn, provider = "ollama").second)
    }

    @Test
    fun the_scope_never_carries_the_account_in_clear() = runBlocking {
        val e = env(cache = null)
        val result = service(e) { success(page(rpcModel("a"))) }.fetch()
        val wire = PocketJson.encodeToString(result)
        assertFalse("alex@example.com" in wire); assertFalse("chatgpt" in wire); assertFalse("pro" in result.catalog!!.scope!!)
        assertEquals(16, result.catalog!!.scope!!.length)
    }

    @Test
    fun an_explicitly_empty_dynamic_catalog_is_empty_not_the_builtin_list() = runBlocking {
        val e = env(cache = null)
        val result = service(e) { success(page()) }.fetch()

        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, result.catalog?.source)
        assertEquals(listOf("gpt-6-astra"), result.models, "only the configured default is retained")
        assertNull(result.error, "empty is a result, not a failure")
        assertTrue(result.modelCapabilities.isEmpty())
    }

    // ── failure handling ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_failed_check_keeps_the_last_good_catalog_and_reports_the_failure() = runBlocking {
        val e = env(cache = null)
        var fail = false
        val svc = service(e, checkIntervalMs = 1_000) { if (fail) CatalogOutcome.Failure("the Codex app-server did not answer in time") else success(page(rpcModel("a"), rpcModel("b"))) }

        val good = svc.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, good.catalog?.source)
        fail = true
        e.clock += 2_000
        val frames = emits(svc)

        assertEquals(2, frames.size)
        assertTrue(frames[0].catalog?.refreshing == true)
        val kept = frames[1]
        assertEquals(MODEL_CATALOG_SOURCE_LAST_GOOD, kept.catalog?.source)
        assertEquals(good.models, kept.models)
        assertEquals(good.modelCapabilities, kept.modelCapabilities)
        assertEquals(good.catalog?.contentVersion, kept.catalog?.contentVersion)
        assertEquals(good.catalog?.changedAt, kept.catalog?.changedAt, "nothing changed, so the change time stands")
        assertEquals(e.clock, kept.catalog?.checkedAt, "…but the check time moves")
        assertTrue(kept.error.orEmpty().contains("did not answer in time"))
        assertFalse(kept.catalog?.refreshing == true, "a failed refresh still ENDS the refreshing state")
    }

    @Test
    fun an_rpc_error_and_a_throwing_transport_both_fall_back_the_same_way() = runBlocking {
        val e1 = env()
        val rpc = service(e1, checkIntervalMs = 1) { CatalogOutcome.RpcError("not logged in") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_FILE, rpc.catalog?.source, "no last-good yet → the file")
        assertTrue(rpc.error.orEmpty().contains("not logged in"))

        val e2 = env(cache = null)
        val thrown = service(e2) { throw IllegalStateException("boom") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, thrown.catalog?.source)
        assertTrue(thrown.error.orEmpty().contains("failed"), thrown.error.toString())

        // a failed check whose file fallback is ALSO unreadable says both, instead of hiding the second failure
        val e3 = env(cache = "{ not json")
        val both = service(e3) { CatalogOutcome.Failure("down") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, both.catalog?.source)
        assertTrue(both.error.orEmpty().let { "down" in it && "could not be read" in it }, both.error.toString())
    }

    /** The bug this guards: a failed answer was stamped `checkedAt=now`, so with the default 6 h reuse window the
     *  1-minute retry never fired — the service only re-asked six hours later. */
    @Test
    fun a_failed_check_is_retried_after_its_backoff_under_the_default_reuse_window() = runBlocking {
        val e = env(cache = null)
        var fail = true
        val svc = service(e) { if (fail) CatalogOutcome.Failure("down") else success(page(rpcModel("a"))) }

        svc.fetch()
        assertEquals(1, e.transportCalls.get())
        e.clock += CodexModelService.RETRY_MIN_MS - 1
        emits(svc)
        assertEquals(1, e.transportCalls.get(), "inside the first minute: no retry")
        e.clock += 1
        fail = false
        val frames = emits(svc)
        assertEquals(2, e.transportCalls.get(), "one minute after a failure the check runs again — not six hours later")
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, frames.last().catalog?.source)
        e.clock += CodexModelService.RETRY_MIN_MS * 2
        emits(svc)
        assertEquals(2, e.transportCalls.get(), "a success is reused for the full window again")
    }

    @Test
    fun failures_back_off_and_a_manual_refresh_still_goes_out() = runBlocking {
        val e = env(cache = null)
        val svc = service(e) { CatalogOutcome.Failure("down") } // the DEFAULT 6 h window: the backoff must act on its own

        svc.fetch()
        assertEquals(1, e.transportCalls.get())
        e.clock += 10_000 // well inside the first minute
        val quiet = emits(svc)
        assertEquals(1, e.transportCalls.get(), "inside the backoff no check runs")
        assertEquals(1, quiet.size); assertFalse(quiet[0].catalog?.refreshing == true)
        assertTrue(quiet[0].error.orEmpty().contains("down"))

        emits(svc, force = true)
        assertEquals(2, e.transportCalls.get(), "the user asking again is not throttled")

        e.clock += CodexModelService.RETRY_MIN_MS * 2 - 1
        emits(svc)
        assertEquals(2, e.transportCalls.get(), "second failure doubled the wait")
        e.clock += 2
        emits(svc)
        assertEquals(3, e.transportCalls.get())
        assertEquals(CodexModelService.RETRY_MAX_MS, CodexModelService.backoffMs(20), "capped")
    }

    // ── scheduling ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun fetches_inside_the_reuse_window_answer_from_cache_and_run_no_check() = runBlocking {
        val e = env(cache = null)
        val svc = service(e, checkIntervalMs = 60_000) { success(page(rpcModel("a"))) }

        svc.fetch()
        e.clock += 59_999
        val cached = emits(svc)
        assertEquals(1, cached.size)
        assertFalse(cached[0].catalog?.refreshing == true)
        assertEquals(1, e.transportCalls.get())

        e.clock += 1
        val again = emits(svc)
        assertEquals(2, again.size, "past the window: cached first, then the check")
        assertEquals(2, e.transportCalls.get())
    }

    @Test
    fun a_changed_cache_file_triggers_an_early_check() = runBlocking {
        val e = env()
        val svc = service(e, checkIntervalMs = Long.MAX_VALUE / 2) { success(page(rpcModel("a"))) }
        svc.fetch()
        assertEquals(1, e.transportCalls.get())
        e.clock += 1
        emits(svc)
        assertEquals(1, e.transportCalls.get(), "nothing changed, nothing to do")

        // the CLI rewrote its own cache (an app-server started and refreshed) — that is the cue
        Files.writeString(e.cache, FILE_CATALOG.replace("\"priority\": 7", "\"priority\": 70"))
        Files.setLastModifiedTime(e.cache, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        emits(svc)
        assertEquals(2, e.transportCalls.get())
    }

    @Test
    fun concurrent_fetches_share_one_check() = runBlocking {
        val e = env(cache = null)
        val gate = CompletableDeferred<Unit>()
        val svc = service(e) { gate.await(); success(page(rpcModel("a"))) }

        val first = async { emits(svc) }
        val second = async { emits(svc, force = true) }
        delay(200)
        assertEquals(1, e.transportCalls.get(), "the forced request joined the running check")
        gate.complete(Unit)
        val a = first.await(); val b = second.await()
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, a.last().catalog?.source)
        assertEquals(a.last().copy(requestId = null), b.last().copy(requestId = null), "both callers got the same final answer")
        assertEquals(1, e.transportCalls.get())
    }

    // ── context isolation: workdir slots, last-good validity, legacy clients ─────────────────────────

    private fun touch(path: Path, plusMs: Long = 5_000) =
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + plusMs))

    @Test
    fun a_moved_config_or_cache_identity_retires_the_last_good_catalog_instead_of_serving_it_after_a_failure() = runBlocking {
        val e = env()
        var fail = false
        val svc = service(e, checkIntervalMs = 1_000) { if (fail) CatalogOutcome.Failure("down") else success(page(rpcModel("rpc-a"))) }
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, svc.fetch().catalog?.source)

        // same context, failure → the last success is kept
        fail = true; e.clock += 2_000
        assertEquals(MODEL_CATALOG_SOURCE_LAST_GOOD, svc.fetch().catalog?.source)

        // config.toml rewritten (provider/profile may have changed) → the old success is another environment's
        Files.writeString(e.config, "model = \"gpt-6-astra\"\nmodel_provider = \"azure\"\n"); touch(e.config)
        e.clock += 2_000
        val afterConfig = svc.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_FILE, afterConfig.catalog?.source, "the CLI's own file, not the retired last-good")
        assertFalse("rpc-a" in afterConfig.models, "rows read under the old config must not leak into the new one")
        assertTrue(afterConfig.error.orEmpty().contains("down"))

        // a success under the new context (once the failure's backoff elapsed), then the cache file's identity
        // changes (another account wrote it)
        fail = false; e.clock += CodexModelService.RETRY_MIN_MS
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, svc.fetch().catalog?.source)
        fail = true; e.clock += 2_000
        Files.writeString(e.cache, FILE_CATALOG.replace("0123456789abcdef", "fedcba9876543210")); touch(e.cache, 10_000)
        val afterIdentity = svc.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_FILE, afterIdentity.catalog?.source, "a new identity in the CLI's cache retires the last-good too")
        assertFalse("rpc-a" in afterIdentity.models)

        // and with no file at all, the labelled built-in list — never a stale success
        fail = false; e.clock += CodexModelService.RETRY_MIN_MS
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, svc.fetch().catalog?.source)
        fail = true; e.clock += 2_000
        Files.delete(e.cache)
        assertEquals(MODEL_CATALOG_SOURCE_BUILTIN, svc.fetch().catalog?.source)
    }

    @Test
    fun a_changed_config_triggers_an_early_check() = runBlocking {
        val e = env()
        val svc = service(e, checkIntervalMs = Long.MAX_VALUE / 2) { success(page(rpcModel("a"))) }
        svc.fetch()
        e.clock += 1
        emits(svc)
        assertEquals(1, e.transportCalls.get())

        Files.writeString(e.config, "model = \"gpt-6-sol\"\nprofile = \"work\"\n"); touch(e.config)
        emits(svc)
        assertEquals(2, e.transportCalls.get(), "provider/profile may have moved: ask again now, not in six hours")
    }

    @Test
    fun each_existing_workdir_gets_its_own_slot_and_cwd_and_a_missing_one_shares_the_home_slot() = runBlocking {
        val e = env(cache = null)
        val a = Files.createDirectory(e.dir.resolve("a"))
        val b = Files.createDirectory(e.dir.resolve("b"))
        val cwds = ArrayList<Path?>()
        val svc = serviceWithCwd(e) { _, cwd -> cwds += cwd; success(page(rpcModel("m-${cwd?.fileName ?: "home"}"))) }

        val inA = svc.fetch(workdir = a.toString())
        val inB = svc.fetch(workdir = "$b/./")
        assertEquals(listOf(a, b), cwds, "the read runs in the requested directory (normalised)")
        assertTrue("m-a" in inA.models && "m-b" in inB.models)

        e.clock += 1
        assertTrue("m-a" in svc.fetch(workdir = a.toString()).models, "switching back serves A's own cached rows")
        assertEquals(2, e.transportCalls.get(), "…without another read")

        val home = svc.fetch(workdir = e.dir.resolve("does-not-exist").toString())
        assertNull(cwds.last(), "a workdir that does not exist is read from the home directory")
        assertTrue("m-home" in home.models)
        assertEquals(3, e.transportCalls.get())
        e.clock += 1
        svc.fetch(workdir = null)
        assertEquals(3, e.transportCalls.get(), "no workdir and a missing workdir are the same (home) slot")
    }

    @Test
    fun a_client_without_a_request_id_gets_one_frame_the_checks_result_never_a_preview() = runBlocking {
        val e = env() // a file catalog exists: a two-frame client would be shown it first
        val gate = CompletableDeferred<Unit>()
        val svc = service(e) { gate.await(); success(page(rpcModel("rpc-only"))) }

        val legacy = async { emits(svc, requestId = null) }
        delay(200)
        assertFalse(legacy.isCompleted, "nothing is emitted before the check answers")
        gate.complete(Unit)
        val frames = legacy.await()
        assertEquals(1, frames.size, "one frame, as before the cache existed")
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, frames[0].catalog?.source)
        assertTrue("rpc-only" in frames[0].models)
        assertNull(frames[0].requestId)

        // the same client inside the reuse window still gets one frame, from the cache
        e.clock += 1
        val cached = emits(svc, requestId = null)
        assertEquals(1, cached.size); assertFalse(cached[0].catalog?.refreshing == true)
    }

    @Test
    fun a_request_id_is_echoed_on_both_frames_and_distinguishes_concurrent_callers() = runBlocking {
        val e = env()
        val gate = CompletableDeferred<Unit>()
        val svc = service(e) { gate.await(); success(page(rpcModel("a"))) }

        val one = async { emits(svc, requestId = "one") }
        val two = async { emits(svc, requestId = "two") }
        delay(200)
        gate.complete(Unit)
        val f1 = one.await(); val f2 = two.await()
        assertEquals(listOf("one", "one"), f1.map { it.requestId }, "preview and final both carry the caller's id")
        assertEquals(listOf("two", "two"), f2.map { it.requestId })
        assertTrue(f1[0].catalog?.refreshing == true && f1[1].catalog?.refreshing == false)
        assertEquals(f1[1].copy(requestId = null), f2[1].copy(requestId = null), "one shared check, two correlated answers")
        assertEquals(1, e.transportCalls.get())
    }

    @Test
    fun capabilities_are_answered_per_workdir_and_never_from_another_project_or_no_slot() = runBlocking {
        val e = env(cache = null)
        val a = Files.createDirectory(e.dir.resolve("a"))
        val b = Files.createDirectory(e.dir.resolve("b"))
        val c = Files.createDirectory(e.dir.resolve("c"))
        // The SAME id lists different levels in two confirmed project/profile catalogs.
        val svc = serviceWithCwd(e) { _, cwd ->
            if (cwd == b) success(page(rpcModel("gpt-6-astra", efforts = listOf("ultra"))))
            else success(page(rpcModel("gpt-6-astra", efforts = listOf("low", "high"))))
        }
        svc.fetch(workdir = a.toString())
        svc.fetch(workdir = b.toString())

        assertEquals(listOf("low", "high"), svc.capabilitiesFor("gpt-6-astra", a.toString())?.reasoningEfforts)
        assertEquals(listOf("ultra"), svc.capabilitiesFor("gpt-6-astra", b.toString())?.reasoningEfforts, "B's own rows, not A's")
        assertNull(svc.capabilitiesFor("gpt-6-astra", c.toString()), "no catalog was read for C: unknown, never A's or B's")
        assertNull(svc.capabilitiesFor("gpt-6-astra", null), "a session not yet attached has no workdir: unknown")
        assertNull(svc.capabilitiesFor("gpt-6-astra", ""), "…and an empty one is the same")
        assertNull(svc.capabilitiesFor("gpt-6-astra", e.dir.resolve("gone").toString()), "a missing directory maps to the home slot, which was never read")
        assertNull(svc.capabilitiesFor("gpt-6-astra", a.toString().let { "$it/nested-not-a-dir" }))
    }

    @Test
    fun capabilities_from_a_slot_whose_fingerprint_moved_are_unknown_until_it_is_re_read() = runBlocking {
        val e = env(cache = null)
        val a = Files.createDirectory(e.dir.resolve("a"))
        val svc = service(e) { success(page(rpcModel("gpt-6-astra", efforts = listOf("low", "high")))) }
        svc.fetch(workdir = a.toString())
        assertEquals(listOf("low", "high"), svc.capabilitiesFor("gpt-6-astra", a.toString())?.reasoningEfforts)

        // the global config was rewritten: the rows may be another provider's now → unknown, never "unsupported"
        Files.writeString(e.config, "model = \"gpt-6-astra\"\nmodel_provider = \"other\"\n"); touch(e.config)
        assertNull(svc.capabilitiesFor("gpt-6-astra", a.toString()))
        e.clock += 1
        svc.fetch(workdir = a.toString())
        assertNotNull(svc.capabilitiesFor("gpt-6-astra", a.toString()), "re-read under the new config: known again")

        // a project layer appeared under the workdir
        Files.createDirectories(a.resolve(".codex")); Files.writeString(a.resolve(".codex/config.toml"), "profile = \"work\"\n")
        assertNull(svc.capabilitiesFor("gpt-6-astra", a.toString()))
        e.clock += 1
        svc.fetch(workdir = a.toString())
        assertNotNull(svc.capabilitiesFor("gpt-6-astra", a.toString()))

        // the CLI rewrote its cache (identity may differ) → unknown until re-read
        Files.writeString(e.cache, FILE_CATALOG); touch(e.cache)
        assertNull(svc.capabilitiesFor("gpt-6-astra", a.toString()))
        assertNull(svc.capabilitiesFor("gpt-5.6-sol", a.toString()), "no silent fallback to the global file either")
    }

    @Test
    fun a_project_layer_or_a_cli_binary_change_triggers_an_early_check() = runBlocking {
        val e = env()
        val a = Files.createDirectory(e.dir.resolve("a"))
        val exe = Files.writeString(e.dir.resolve("codex"), "#!/bin/sh\n")
        val svc = service(e, binary = exe, checkIntervalMs = Long.MAX_VALUE / 2) { success(page(rpcModel("m"))) }
        svc.fetch(workdir = a.toString())
        e.clock += 1
        emits(svc, workdir = a.toString())
        assertEquals(1, e.transportCalls.get())

        // a parent-level project layer appears (Codex merges every `.codex/config.toml` up to the root)
        Files.createDirectories(e.dir.resolve(".codex")); Files.writeString(e.dir.resolve(".codex/config.toml"), "model_provider = \"azure\"\n")
        emits(svc, workdir = a.toString())
        assertEquals(2, e.transportCalls.get(), "a new project layer is a possible provider change")
        emits(svc, workdir = a.toString())
        assertEquals(2, e.transportCalls.get(), "…and once re-read it is the known context")

        // the layer is edited in place
        Files.writeString(e.dir.resolve(".codex/config.toml"), "model_provider = \"azure\"\nprofile = \"x\"\n"); touch(e.dir.resolve(".codex/config.toml"))
        emits(svc, workdir = a.toString())
        assertEquals(3, e.transportCalls.get())

        // the CLI binary at the same path was upgraded
        Files.writeString(exe, "#!/bin/sh\n# v2\n"); touch(exe)
        emits(svc, workdir = a.toString())
        assertEquals(4, e.transportCalls.get(), "a rebuilt binary at the same path is a new CLI")
        emits(svc, workdir = a.toString())
        assertEquals(4, e.transportCalls.get())
    }

    @Test
    fun an_environment_that_moves_during_the_read_is_not_published_and_is_re_read_at_once() = runBlocking {
        val e = env()
        var flip = true
        val svc = service(e, checkIntervalMs = 1_000) {
            if (flip) {
                // the account/config changed while the app-server was answering: the CLI rewrote its cache with
                // another identity and the user edited config.toml
                Files.writeString(e.cache, FILE_CATALOG.replace("0123456789abcdef", "fedcba9876543210")); touch(e.cache)
                Files.writeString(e.config, "model = \"gpt-6-sol\"\n"); touch(e.config)
                flip = false
            }
            success(page(rpcModel("mid-read")))
        }

        val first = svc.fetch()
        assertNotEquals(MODEL_CATALOG_SOURCE_DYNAMIC, first.catalog?.source, "the answer belongs to neither environment")
        assertFalse("mid-read" in first.models, "its rows are not published")
        assertEquals(MODEL_CATALOG_SOURCE_FILE, first.catalog?.source, "the CLI's own (new) file stands in")
        assertTrue(first.error.orEmpty().contains("changed while"), first.error.toString())
        assertNull(svc.capabilitiesFor("mid-read", null))

        // no backoff is charged: the very next request re-reads (clock unchanged), and now it is current
        val second = emits(svc)
        assertEquals(2, e.transportCalls.get())
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, second.last().catalog?.source)
        assertTrue("mid-read" in second.last().models)
    }

    @Test
    fun a_client_without_a_request_id_never_receives_unconfirmed_capability_rows() = runBlocking {
        // an already-shipped App reconciles its saved effort/tier against ANY capability row it sees; only rows
        // confirmed for the current account may reach it — ids, presets and the error always do
        val fileOnly = service(env(), binary = null) { fail("no transport") }.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_FILE, fileOnly.catalog?.source)
        assertTrue(fileOnly.modelCapabilities.isEmpty(), "file rows are not account-confirmed")
        assertEquals(listOf("gpt-6-astra", "gpt-locked", "gpt-5.6-sol", "gpt-5.5"), fileOnly.models)
        assertEquals(CodexModelService.MODE_PRESETS, fileOnly.modePresets)
        assertNotNull(fileOnly.error)

        assertTrue(service(env(cache = null)) { success(page(rpcModel("a")), account = null) }.fetch().modelCapabilities.isEmpty(), "cli-builtin")
        assertTrue(service(env(cache = null)) { success(page(rpcModel("a")), accountError = "x") }.fetch().modelCapabilities.isEmpty(), "unconfirmed")
        assertTrue(service(env(cache = null)) {
            CatalogOutcome.Success(listOf(page(rpcModel("a"))), signedIn, null, userAgent, requiresOpenaiAuth = true, provider = "azure")
        }.fetch().modelCapabilities.isEmpty(), "chatgpt login behind another provider")

        // confirmed rows DO reach it, and keep reaching it while a failure keeps the confirmed answer
        val e = env(cache = null)
        var fail = false
        val svc = service(e, checkIntervalMs = 1_000) { if (fail) CatalogOutcome.Failure("down") else success(page(rpcModel("a"))) }
        val confirmed = svc.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, confirmed.catalog?.source)
        assertEquals(listOf("a"), confirmed.modelCapabilities.map { it.model })
        fail = true; e.clock += 2_000
        val kept = svc.fetch()
        assertEquals(MODEL_CATALOG_SOURCE_LAST_GOOD, kept.catalog?.source)
        assertEquals(listOf("a"), kept.modelCapabilities.map { it.model }, "last-good over a dynamic answer is still confirmed")

        // a new client (requestId) always gets the rows, with the provenance to judge them by
        val tagged = emits(service(env(), binary = null) { fail("no transport") })
        assertTrue(tagged.last().modelCapabilities.isNotEmpty())
        assertEquals(MODEL_CATALOG_SOURCE_FILE, tagged.last().catalog?.source)
    }

    @Test
    fun fallback_and_unconfirmed_rows_never_normalize_session_settings_and_default_model_stays_unknown() = runBlocking<Unit> {
        val e = env()
        val wd = e.dir.toString()
        val fromFile = service(e, binary = null) { fail("no transport") }
        fromFile.fetch(workdir = wd)
        assertNull(fromFile.capabilitiesFor("gpt-5.6-sol", wd), "a parsed fallback file is still unconfirmed")

        val unconfirmed = service(e) { success(page(rpcModel("gpt-6-astra")), accountError = "account unavailable") }
        unconfirmed.fetch(workdir = wd)
        assertNull(unconfirmed.capabilitiesFor("gpt-6-astra", wd))

        var failed = false
        val confirmed = service(e) {
            if (failed) CatalogOutcome.Failure("offline") else success(page(rpcModel("gpt-6-astra")))
        }
        confirmed.fetch(workdir = wd)
        assertNotNull(confirmed.capabilitiesFor("gpt-6-astra", wd))
        assertNull(confirmed.capabilitiesFor(null, wd), "global config does not resolve project/profile defaults")
        failed = true
        confirmed.fetch(forceRefresh = true, workdir = wd)
        assertNotNull(confirmed.capabilitiesFor("gpt-6-astra", wd), "a same-context confirmed last-good remains usable")
    }

    @Test
    fun tilde_directory_uses_the_same_catalog_slot_as_the_daemon_home() = runBlocking {
        val e = env()
        val cwds = mutableListOf<Path?>()
        val svc = serviceWithCwd(e) { _, cwd -> cwds += cwd; success(page(rpcModel("m"))) }
        svc.fetch(workdir = "~/")
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        assertEquals(home, cwds.single(), "expand the daemon's home just as session launch does")
        svc.fetch(workdir = home.toString())
        assertEquals(1, e.transportCalls.get(), "tilde and absolute home must share the reuse window")
        assertNotNull(svc.capabilitiesFor("m", "~/"))
        assertNull(svc.capabilitiesFor("m", e.dir.resolve("missing").toString()))
    }

    // ── content versions ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_content_version_tracks_semantics_not_timestamps_or_errors() {
        val base = ModelsList(agent = AgentKind.CODEX, models = listOf("a", "b"), modelCapabilities = listOf(
            dev.ccpocket.protocol.ModelCapabilities("a", listOf("low"), displayName = "A"),
            dev.ccpocket.protocol.ModelCapabilities("b", listOf("low")),
        ))
        val v = CodexModelService.contentVersion(base)
        assertEquals(v, CodexModelService.contentVersion(base.copy(error = "x", catalog = dev.ccpocket.protocol.ModelCatalogMeta(checkedAt = 5, source = "file"))))
        assertNotEquals(v, CodexModelService.contentVersion(base.copy(models = listOf("b", "a"))), "order is semantic")
        assertNotEquals(v, CodexModelService.contentVersion(base.copy(modelCapabilities = base.modelCapabilities.map { it.copy(hidden = true) })), "visibility is semantic")
        assertNotEquals(v, CodexModelService.contentVersion(base.copy(modelCapabilities = base.modelCapabilities.map { it.copy(displayName = "Z") })), "names are semantic")
        assertNotEquals(v, CodexModelService.contentVersion(base.copy(modelCapabilities = base.modelCapabilities.map { it.copy(upgradeTo = "c") })), "upgrade hints are semantic")
    }

    @Test
    fun an_unchanged_catalog_keeps_its_change_time_across_checks() = runBlocking {
        val e = env(cache = null)
        var rows = listOf(rpcModel("a"), rpcModel("b"))
        val svc = service(e, checkIntervalMs = 1) { success(page(*rows.toTypedArray())) }
        val first = svc.fetch()
        e.clock += 10
        val second = svc.fetch()
        assertEquals(first.catalog?.changedAt, second.catalog?.changedAt)
        assertEquals(e.clock, second.catalog?.checkedAt)
        rows = listOf(rpcModel("a"), rpcModel("b"), rpcModel("c"))
        e.clock += 10
        val third = svc.fetch()
        assertEquals(e.clock, third.catalog?.changedAt, "a new model is a change")
        assertNotEquals(first.catalog?.contentVersion, third.catalog?.contentVersion)
    }

    @Test
    fun cli_version_is_read_from_the_user_agent() {
        assertEquals("0.155.1", CodexModelService.cliVersionOf("codex_cli_rs/0.155.1 (Mac OS 26.5.0; arm64) cc-pocket/0.0.1"))
        assertNull(CodexModelService.cliVersionOf("something else"))
        assertNull(CodexModelService.cliVersionOf(null))
    }

    // ── mode presets (unchanged contract) ────────────────────────────────────────────────────────────

    /** The advertised vocabulary IS the App's picker now, so the four rows and their emphasis are a
     *  contract: drop a row or move `recommended`/`danger` and every client's Codex mode sheet changes. */
    @Test
    fun fetch_advertises_the_four_codex_mode_presets_with_their_emphasis() = runBlocking {
        val e = env(config = null, cache = """{"models":[{"slug":"gpt-5.5","visibility":"list","priority":1,"upgrade":null}]}""")
        val result = service(e, binary = null) { fail("no transport") }.fetch()

        assertEquals(listOf("cautious", "balanced", "autonomous", "full"), result.modePresets.map { it.id }, "the ladder order the App renders top-down")
        assertEquals(
            listOf(PermissionMode.PLAN, PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS, PermissionMode.BYPASS_PERMISSIONS),
            result.modePresets.map { it.mode },
            "each preset must carry the mode CodexBackend translates into its approval + sandbox pair",
        )
        assertEquals("balanced", result.modePresets.single { it.recommended }.id)
        assertEquals("full", result.modePresets.single { it.danger }.id)
        assertTrue(result.modePresets.all { it.label.isNotBlank() && !it.detail.isNullOrBlank() })
    }

    /**
     * The drift gate between the copy the daemon BROADCASTS and the translation a session actually RUNS.
     * `CodexModelService.MODE_PRESETS` is a promise made with the daemon's authority ("this row means
     * read-only"), while `CodexBackend.approvalPolicyFor`/`sandboxFor` is what a turn is really launched
     * with. This test makes either side moving alone turn red. Copy is asserted with case-insensitive
     * `contains` on purpose — the point is the SEMANTIC pairing, not the exact wording.
     */
    @Test
    fun advertised_mode_presets_stay_paired_with_the_backend_translation() {
        val expectedDetailPhrases = mapOf(
            ("untrusted" to "read-only") to listOf("Ask before every", "read-only"),
            ("on-request" to "workspace-write") to listOf("Ask when needed", "workspace"),
            ("never" to "workspace-write") to listOf("Never ask", "workspace"),
            ("never" to "danger-full-access") to listOf("Never ask", "full filesystem"),
        )
        val presets = CodexModelService.MODE_PRESETS
        assertEquals(PermissionMode.entries.toSet(), presets.map { it.mode }.toSet(), "every PermissionMode CodexBackend translates needs exactly one advertised row")
        assertEquals(presets.size, presets.map { it.mode }.toSet().size, "no mode may be advertised twice")
        for (preset in presets) {
            val approvalPolicy = CodexBackend.approvalPolicyFor(preset.mode)
            val sandbox = CodexBackend.sandboxFor(preset.mode).flat
            assertEquals(sandbox == "danger-full-access", preset.danger, "preset '${preset.id}' runs as sandbox=$sandbox — the danger badge must mark exactly the full-access row")
            assertEquals(approvalPolicy == "on-request", preset.recommended, "preset '${preset.id}' runs as approvalPolicy=$approvalPolicy — recommended must mark exactly the on-request row")
            val phrases = expectedDetailPhrases[approvalPolicy to sandbox]
                ?: fail("preset '${preset.id}' translates to ($approvalPolicy, $sandbox) which this test has no expected copy for — extend the table")
            val detail = preset.detail.orEmpty()
            for (phrase in phrases) assertTrue(detail.contains(phrase, ignoreCase = true), "preset '${preset.id}' detail \"$detail\" no longer says \"$phrase\"")
        }
        val livePairs = presets.map { CodexBackend.approvalPolicyFor(it.mode) to CodexBackend.sandboxFor(it.mode).flat }.toSet()
        assertEquals(expectedDetailPhrases.keys, livePairs, "the expected-copy table must describe exactly the (approvalPolicy, sandbox) pairs the backend produces")
    }

    companion object {
        /** A `models_cache.json` in the shape codex-cli writes (snake_case; `upgrade` null or an object). */
        val FILE_CATALOG = """
            {
              "fetched_at": "2026-10-06T03:12:54.259570Z",
              "etag": "W/\"abc\"",
              "client_version": "0.160.0",
              "identity": "0123456789abcdef",
              "models": [
                {
                  "slug": "gpt-5.6-sol", "display_name": "GPT-5.6-Sol", "visibility": "list", "priority": 5, "upgrade": null,
                  "supported_reasoning_levels": [ { "effort": "max" }, { "effort": "ultra" } ],
                  "default_reasoning_level": "max",
                  "service_tiers": [ { "id": "priority", "name": "Fast", "description": "Lower latency" } ]
                },
                { "slug": "gpt-5.5", "visibility": "list", "priority": 7, "upgrade": null },
                { "slug": "codex-auto-review", "visibility": "hide", "priority": 43, "upgrade": null },
                { "slug": "gpt-locked", "visibility": "list", "priority": 2, "upgrade": { "model": "gpt-next" } }
              ]
            }
        """.trimIndent()
    }
}
