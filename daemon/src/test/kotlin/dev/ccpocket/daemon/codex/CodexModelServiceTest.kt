package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexModelServiceTest {
    @Test
    fun fetch_reads_visible_cached_models_and_puts_configured_default_first() = runBlocking {
        val dir = Files.createTempDirectory("codex-models-test")
        val cache = dir.resolve("models_cache.json")
        val config = dir.resolve("config.toml")
        Files.writeString(config, "model = \"gpt-5.5\"\n")
        Files.writeString(
            cache,
            """
            {
              "models": [
                { "slug": "gpt-5.6-sol", "visibility": "list", "priority": 1, "upgrade": null },
                { "slug": "gpt-5.5", "visibility": "list", "priority": 7, "upgrade": null },
                { "slug": "codex-auto-review", "visibility": "hide", "priority": 43, "upgrade": null },
                { "slug": "gpt-locked", "visibility": "list", "priority": 2, "upgrade": "pro" }
              ]
            }
            """.trimIndent(),
        )

        val result = CodexModelService(cachePath = cache, configPath = config).fetch()

        assertEquals(AgentKind.CODEX, result.agent)
        assertEquals(null, result.error)
        assertEquals(listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5-codex"), result.models)
    }
}
