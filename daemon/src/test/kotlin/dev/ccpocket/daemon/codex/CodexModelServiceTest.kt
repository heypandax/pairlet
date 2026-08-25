package dev.ccpocket.daemon.codex

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
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
        assertEquals(listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"), result.models)
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

    /** The advertised vocabulary IS the App's picker now, so the four rows and their emphasis are a
     *  contract: drop a row or move `recommended`/`danger` and every client's Codex mode sheet changes. */
    @Test
    fun fetch_advertises_the_four_codex_mode_presets_with_their_emphasis() = runBlocking {
        val dir = Files.createTempDirectory("codex-mode-presets-test")
        val cache = dir.resolve("models_cache.json")
        val config = dir.resolve("config.toml")
        Files.writeString(cache, """{"models":[{"slug":"gpt-5.5","visibility":"list","priority":1,"upgrade":null}]}""")

        val result = CodexModelService(cachePath = cache, configPath = config).fetch()

        assertNull(result.error)
        assertEquals(
            listOf("cautious", "balanced", "autonomous", "full"),
            result.modePresets.map { it.id },
            "the ladder order the App renders top-down",
        )
        assertEquals(
            listOf(
                PermissionMode.PLAN,
                PermissionMode.DEFAULT,
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.BYPASS_PERMISSIONS,
            ),
            result.modePresets.map { it.mode },
            "each preset must carry the mode CodexBackend translates into its approval + sandbox pair",
        )
        assertEquals("balanced", result.modePresets.single { it.recommended }.id)
        assertEquals("full", result.modePresets.single { it.danger }.id)
        // English fallback copy is what an App that doesn't know the id renders verbatim — never blank
        assertTrue(result.modePresets.all { it.label.isNotBlank() && !it.detail.isNullOrBlank() })
    }

    /** The error branch says "I could not inspect this backend" — advertising a vocabulary there would
     *  hand the picker rows the daemon just failed to confirm. */
    @Test
    fun failed_fetch_advertises_no_mode_presets() = runBlocking {
        val dir = Files.createTempDirectory("codex-mode-presets-error-test")
        val cache = dir.resolve("models_cache.json")
        Files.writeString(cache, "{ not json")

        val result = CodexModelService(cachePath = cache, configPath = dir.resolve("config.toml")).fetch()

        assertTrue(result.error != null, "a corrupt cache must land on the error branch")
        assertTrue(result.modePresets.isEmpty())
    }
}
