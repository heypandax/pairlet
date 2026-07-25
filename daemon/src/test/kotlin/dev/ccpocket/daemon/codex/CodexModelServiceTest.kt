package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                {
                  "slug": "gpt-5.6-sol",
                  "visibility": "list",
                  "priority": 1,
                  "upgrade": null,
                  "supported_reasoning_levels": [
                    { "effort": "max" },
                    { "effort": "ultra" }
                  ],
                  "default_reasoning_level": "max",
                  "service_tiers": [
                    { "id": "priority", "name": "Fast", "description": "Lower latency" }
                  ]
                },
                { "slug": "gpt-5.5", "visibility": "list", "priority": 7, "upgrade": null },
                { "slug": "codex-auto-review", "visibility": "hide", "priority": 43, "upgrade": null },
                { "slug": "gpt-locked", "visibility": "list", "priority": 2, "upgrade": { "model": "gpt-next" } }
              ]
            }
            """.trimIndent(),
        )

        val result = CodexModelService(cachePath = cache, configPath = config).fetch()

        assertEquals(AgentKind.CODEX, result.agent)
        assertEquals(null, result.error)
        assertEquals(listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5-codex"), result.models)
        val sol = result.modelCapabilities.single { it.model == "gpt-5.6-sol" }
        assertEquals(listOf("max", "ultra"), sol.reasoningEfforts)
        assertEquals("max", sol.defaultReasoningEffort)
        assertEquals("priority", sol.serviceTiers.single().id)
        assertEquals("Fast", sol.serviceTiers.single().name)
        assertNull(result.modelCapabilities.single { it.model == "gpt-5.5" }.defaultReasoningEffort)
    }

    @Test
    fun fetch_bounds_cache_capabilities_before_they_reach_the_wire() = runBlocking {
        val dir = Files.createTempDirectory("codex-model-bounds-test")
        val cache = dir.resolve("models_cache.json")
        val config = dir.resolve("config.toml")
        val efforts = (0 until 40).joinToString(",") { """{"effort":"effort-$it"}""" }
        val tiers = (0 until 40).joinToString(",") {
            """{"id":"tier-$it","name":"${"n".repeat(200)}","description":"${"d".repeat(400)}"}"""
        }
        val models = (0 until 250).joinToString(",") {
            """
            {
              "slug": "model-$it",
              "visibility": "list",
              "priority": $it,
              "upgrade": null,
              "supported_reasoning_levels": [$efforts],
              "service_tiers": [$tiers]
            }
            """.trimIndent()
        }
        Files.writeString(cache, """{"models":[$models]}""")

        val result = CodexModelService(cachePath = cache, configPath = config).fetch()

        assertEquals(128, result.modelCapabilities.size)
        assertTrue(result.modelCapabilities.all { it.reasoningEfforts.size == 16 })
        assertTrue(result.modelCapabilities.all { it.serviceTiers.size == 8 })
        assertTrue(result.modelCapabilities.flatMap { it.serviceTiers }.all {
            it.id.length <= 64 && it.name.length <= 64 && (it.description?.length ?: 0) <= 160
        })
        assertTrue(PocketJson.encodeToString(result).encodeToByteArray().size < 512 * 1024)
    }
}
