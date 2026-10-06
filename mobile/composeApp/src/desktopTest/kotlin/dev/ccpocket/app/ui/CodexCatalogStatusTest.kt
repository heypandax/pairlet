package dev.ccpocket.app.ui

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CODEX_MODEL_IDS
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_CLI_BUILTIN
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_DYNAMIC
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_FILE
import dev.ccpocket.protocol.MODEL_CATALOG_SOURCE_LAST_GOOD
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelCatalogMeta
import dev.ccpocket.protocol.ModelsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one rule for the Codex catalog state line, and the display-name mapping of the picker rows. */
class CodexCatalogStatusTest {
    private fun list(source: String?, models: List<String> = listOf("gpt-6-astra"), error: String? = null) =
        ModelsList(agent = AgentKind.CODEX, models = models, error = error, catalog = source?.let { ModelCatalogMeta(source = it) })

    @Test
    fun the_state_follows_the_source_and_the_request_state() {
        assertEquals(CodexCatalogStatus.REFRESHING, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC), refreshing = true))
        assertEquals(CodexCatalogStatus.REFRESHING, codexCatalogStatus(AgentKind.CODEX, null, refreshing = true), "no rows yet, request out")
        assertEquals(CodexCatalogStatus.PREVIEW, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC), preview = true))
        assertNull(codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC)), "a confirmed catalog has nothing to say")
        assertNull(codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_FILE)), "source alone cannot tell whether the repository is previewing these rows")
        assertEquals(CodexCatalogStatus.LAST_GOOD, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_FILE, error = "codex not installed")))
        assertEquals(CodexCatalogStatus.LAST_GOOD, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_LAST_GOOD, error = "timeout")))
        assertEquals(CodexCatalogStatus.CLI_BUILTIN, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_CLI_BUILTIN)))
        assertEquals(CodexCatalogStatus.BUILTIN, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_BUILTIN, CODEX_MODEL_IDS, error = "cache missing")))
        assertEquals(CodexCatalogStatus.EMPTY, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC, models = emptyList())), "an explicit empty catalog is empty")
        assertNull(codexCatalogStatus(AgentKind.CODEX, list(null, error = "old daemon sentence")), "an older daemon: only the raw sentence applies")
        assertNull(codexCatalogStatus(AgentKind.CODEX, list("quantum-sync")), "a source only a newer daemon knows makes no claim")
        for (agent in AgentKind.entries - AgentKind.CODEX) assertNull(codexCatalogStatus(agent, list(MODEL_CATALOG_SOURCE_BUILTIN, error = "x"), refreshing = true), "$agent")
    }

    @Test
    fun preview_does_not_hide_a_final_failure_or_builtin_source() {
        assertEquals(CodexCatalogStatus.LAST_GOOD, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_FILE, error = "timeout"), preview = true))
        assertEquals(CodexCatalogStatus.BUILTIN, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_BUILTIN, error = "missing"), preview = true))
        assertEquals(CodexCatalogStatus.CLI_BUILTIN, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_CLI_BUILTIN), preview = true))
        assertEquals(CodexCatalogStatus.PREVIEW, codexCatalogStatus(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC), preview = true))
    }

    @Test
    fun codex_rows_wear_the_upstream_display_name_but_pick_the_execution_id() {
        val l = ModelsList(
            agent = AgentKind.CODEX, models = listOf("gpt-6-astra", "gpt-6-sol", "custom-x"),
            modelCapabilities = listOf(ModelCapabilities("gpt-6-astra", displayName = "GPT-6-Astra"), ModelCapabilities("gpt-6-sol", displayName = "  ")),
        )
        val rows = modelChoicesFrom(AgentKind.CODEX, l)
        assertEquals(listOf("GPT-6-Astra", "gpt-6-sol", "custom-x"), rows.map { it.name })
        assertEquals(listOf("gpt-6-astra", "gpt-6-sol", "custom-x"), rows.map { it.pick }, "the id sent to --model is never the display name")
        assertEquals(CODEX_MODEL_IDS, modelChoicesFrom(AgentKind.CODEX, null).map { it.pick }, "no answer yet → the static trio")
        assertEquals(emptyList(), modelChoicesFrom(AgentKind.CODEX, list(MODEL_CATALOG_SOURCE_DYNAMIC, models = emptyList())), "an answered empty list stays empty")
    }
}
