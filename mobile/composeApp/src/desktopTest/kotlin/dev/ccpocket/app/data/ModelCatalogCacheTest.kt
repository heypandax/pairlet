package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
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
import dev.ccpocket.protocol.AgentModePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The on-device catalog record and the reuse rule, against in-memory seams. */
class ModelCatalogCacheTest {

    private val backing = HashMap<String, String>()
    private var writes = 0
    private val store = ModelCatalogStore(backing::get, { k, v -> backing[k] = v; writes++ }, { backing.remove(it) })
    private val identity = "wss://r|acct|pub|dev"

    private fun dynamic(
        models: List<String> = listOf("gpt-6-astra", "gpt-6-sol"), source: String = MODEL_CATALOG_SOURCE_DYNAMIC,
        refreshing: Boolean = false, scope: String? = "scope-1", version: String? = "v1", checkedAt: Long = 10, error: String? = null,
    ) = ModelsList(
        agent = AgentKind.CODEX, models = models, error = error,
        modelCapabilities = models.map { ModelCapabilities(it, listOf("low", "high"), "high", listOf(ModelServiceTier("priority", "Fast", "2x")), displayName = it.uppercase()) },
        modePresets = listOf(AgentModePreset(PermissionMode.DEFAULT, "balanced", "Balanced")),
        supportedEfforts = listOf("x"), permissionModes = listOf("auto"), supportsThinkingToggle = true,
        catalog = ModelCatalogMeta(source = source, scope = scope, contentVersion = version, checkedAt = checkedAt, changedAt = 5, cliVersion = "0.155.1", refreshing = refreshing),
    )

    @Test
    fun a_confirmed_catalog_round_trips_as_rows_and_provenance_only() {
        assertTrue(store.save(identity, AgentKind.CODEX, dynamic()))
        val restored = store.load(identity, AgentKind.CODEX)!!

        assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), restored.models)
        assertEquals("GPT-6-ASTRA", restored.modelCapabilities.first().displayName)
        assertEquals("scope-1", restored.catalog?.scope)
        assertEquals(MODEL_CATALOG_SOURCE_DYNAMIC, restored.catalog?.source)
        assertEquals(10L, restored.catalog?.checkedAt)
        // the per-daemon capabilities of the ORIGINAL frame are deliberately NOT part of the record: a restored
        // list must not carry a previous daemon's presets / efforts / toggles into a new connection
        assertTrue(restored.modePresets.isEmpty()); assertTrue(restored.supportedEfforts.isEmpty())
        assertTrue(restored.permissionModes.isEmpty()); assertFalse(restored.supportsThinkingToggle)
        assertNull(restored.error)
    }

    @Test
    fun only_a_confirmed_scoped_final_catalog_is_persisted() {
        for (rejected in listOf(
            dynamic(source = MODEL_CATALOG_SOURCE_BUILTIN), dynamic(source = MODEL_CATALOG_SOURCE_LAST_GOOD),
            dynamic(source = MODEL_CATALOG_SOURCE_CLI_BUILTIN), dynamic(source = MODEL_CATALOG_SOURCE_FILE),
            dynamic(source = MODEL_CATALOG_SOURCE_DYNAMIC_UNCONFIRMED), // real rows, but not a confirmed account's
            dynamic(refreshing = true), dynamic(error = "the check failed"),
            dynamic(scope = null), dynamic(version = null),
            dynamic().copy(catalog = null), // an old daemon's unscoped list is compatibility mode, not a cache
        )) {
            assertFalse(store.save(identity, AgentKind.CODEX, rejected), rejected.catalog.toString())
            assertFalse(store.persistable(rejected))
        }
        assertTrue(backing.isEmpty())
        assertTrue(store.save(identity, AgentKind.CODEX, dynamic()))
        // a later weaker answer must not overwrite the good record
        store.save(identity, AgentKind.CODEX, dynamic(models = emptyList(), source = MODEL_CATALOG_SOURCE_BUILTIN))
        assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), store.load(identity, AgentKind.CODEX)?.models)
    }

    @Test
    fun unchanged_content_is_not_rewritten() {
        assertTrue(store.save(identity, AgentKind.CODEX, dynamic(checkedAt = 10)))
        assertFalse(store.save(identity, AgentKind.CODEX, dynamic(checkedAt = 99)), "a newer check time alone is not new content")
        assertEquals(1, writes)
        assertTrue(store.save(identity, AgentKind.CODEX, dynamic(version = "v2", checkedAt = 99)), "a new content version is")
        assertTrue(store.save(identity, AgentKind.CODEX, dynamic(scope = "scope-2", version = "v2")), "and so is another account's identical content")
        assertEquals(3, writes)
    }

    @Test
    fun records_are_keyed_by_binding_and_agent_and_cleared_on_demand() {
        store.save(identity, AgentKind.CODEX, dynamic())
        assertNull(store.load("wss://r|other|pub|dev", AgentKind.CODEX), "another computer's rows are not this one's")
        assertNull(store.load(identity, AgentKind.CLAUDE))
        store.clear(identity, AgentKind.CODEX)
        assertNull(store.load(identity, AgentKind.CODEX))
        assertTrue(backing.isEmpty())
    }

    @Test
    fun an_unreadable_or_oversized_record_is_dropped_not_served() {
        val k = ModelCatalogStore.key(identity, AgentKind.CODEX)
        backing[k] = "{ not json"
        assertNull(store.load(identity, AgentKind.CODEX))
        assertTrue(backing.isEmpty(), "the corrupt record is removed so the fetch path runs clean")

        // rows are capped at MAX_MODELS on the way in; a record over the byte bound after that is not written
        val many = dynamic(models = (0 until 2000).map { "model-$it" })
        assertTrue(store.save(identity, AgentKind.CODEX, many))
        assertEquals(ModelCatalogStore.MAX_MODELS, store.load(identity, AgentKind.CODEX)?.models?.size)
        store.clear(identity, AgentKind.CODEX)
        val fat = dynamic(models = (0 until 128).map { "model-$it" }).let { l ->
            l.copy(modelCapabilities = l.modelCapabilities.map { it.copy(displayName = "n".repeat(2_000)) })
        }
        assertFalse(store.save(identity, AgentKind.CODEX, fat))
        assertTrue(backing.isEmpty())

        // …and the same bounds on the way OUT: a record someone else wrote too large is dropped unread
        backing[k] = "x".repeat(ModelCatalogStore.MAX_CHARS + 1)
        assertNull(store.load(identity, AgentKind.CODEX)); assertTrue(backing.isEmpty())
        val tooManyCaps = """{"schema":1,"agent":"codex","models":["a"],"capabilities":[${(0..200).joinToString(",") { """{"model":"m$it"}""" }}],"catalog":{"source":"dynamic"}}"""
        backing[k] = tooManyCaps
        assertNull(store.load(identity, AgentKind.CODEX)); assertTrue(backing.isEmpty())
    }

    // ── reuse ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun an_answer_is_reused_only_for_its_own_context_and_only_while_young() {
        var clock = 1_000_000L
        val policy = ModelCatalogRefreshPolicy { clock }
        assertFalse(policy.reusable(AgentKind.CODEX, "id|/w"), "nothing answered yet")
        policy.replied(AgentKind.CODEX, "id|/w")
        clock += ModelCatalogRefreshPolicy.REUSE_MS - 1
        assertTrue(policy.reusable(AgentKind.CODEX, "id|/w"))
        assertFalse(policy.reusable(AgentKind.CODEX, "id|/other"), "another working directory is another catalog (the daemon scopes per workdir)")
        assertFalse(policy.reusable(AgentKind.CODEX, "other|/w"), "another computer, likewise")
        assertFalse(policy.reusable(AgentKind.CLAUDE, "id|/w"), "per agent")
        clock += 1
        assertFalse(policy.reusable(AgentKind.CODEX, "id|/w"), "past the window")
        policy.replied(AgentKind.CODEX, "id|/w")
        policy.invalidate(AgentKind.CODEX)
        assertFalse(policy.reusable(AgentKind.CODEX, "id|/w"), "a model-unavailable error forgets the answer's age")
        policy.replied(AgentKind.CODEX, "id|/w")
        policy.reset()
        assertFalse(policy.reusable(AgentKind.CODEX, "id|/w"), "another computer: nothing of the previous one's age")
    }

    @Test
    fun model_unavailable_errors_are_recognised_narrowly() {
        assertTrue(looksLikeModelUnavailable("The model `gpt-5.4` does not exist or you do not have access to it."))
        assertTrue(looksLikeModelUnavailable("Unsupported model: gpt-old"))
        assertTrue(looksLikeModelUnavailable("model gpt-5.5 is no longer available"))
        assertFalse(looksLikeModelUnavailable("usage limit reached|1720000000"))
        assertFalse(looksLikeModelUnavailable("network error while contacting the model provider: timeout"))
        assertFalse(looksLikeModelUnavailable("file not found: build.gradle"))
    }
}
