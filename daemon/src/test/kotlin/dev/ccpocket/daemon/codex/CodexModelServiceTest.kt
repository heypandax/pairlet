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
import kotlin.test.fail

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

    /**
     * The drift gate between the copy the daemon BROADCASTS and the translation a session actually RUNS.
     *
     * `CodexModelService.MODE_PRESETS` is a promise made with the daemon's authority ("this row means
     * read-only"), while `CodexBackend.approvalPolicyFor`/`sandboxFor` is what a turn is really launched
     * with. Until now the two were only wired together by KDoc pointing at each other, so editing one
     * alone shipped a daemon-authority LIE: the phone would render an emphasis/description the local
     * session does not honour. This test makes either side moving alone turn red.
     *
     * Copy is asserted with case-insensitive `contains` on purpose — the point is the SEMANTIC pairing
     * (this row must still describe untrusted + read-only), not the exact wording, which stays free to be
     * polished or reworded around those anchors.
     */
    @Test
    fun advertised_mode_presets_stay_paired_with_the_backend_translation() {
        // (approvalPolicy, sandbox.flat) → phrases the English fallback copy MUST still carry.
        // Change the translation without the copy (or the copy without the translation) and this table
        // stops matching, which is exactly the drift we want to be loud.
        val expectedDetailPhrases = mapOf(
            ("untrusted" to "read-only") to listOf("Ask before every", "read-only"),
            ("on-request" to "workspace-write") to listOf("Ask when needed", "workspace"),
            ("never" to "workspace-write") to listOf("Never ask", "workspace"),
            ("never" to "danger-full-access") to listOf("Never ask", "full filesystem"),
        )

        val presets = CodexModelService.MODE_PRESETS

        // 1) the advertised table covers every mode the backend can translate — and only those, once each
        assertEquals(
            PermissionMode.entries.toSet(),
            presets.map { it.mode }.toSet(),
            "every PermissionMode CodexBackend translates needs exactly one advertised row",
        )
        assertEquals(presets.size, presets.map { it.mode }.toSet().size, "no mode may be advertised twice")

        for (preset in presets) {
            val approvalPolicy = CodexBackend.approvalPolicyFor(preset.mode)
            val sandbox = CodexBackend.sandboxFor(preset.mode).flat

            // 2) the danger badge is the full-access sandbox, and nothing else
            assertEquals(
                sandbox == "danger-full-access",
                preset.danger,
                "preset '${preset.id}' runs as sandbox=$sandbox — the danger badge must mark exactly the full-access row",
            )

            // 3) the recommended badge is the ask-when-needed policy, and nothing else
            assertEquals(
                approvalPolicy == "on-request",
                preset.recommended,
                "preset '${preset.id}' runs as approvalPolicy=$approvalPolicy — recommended must mark exactly the on-request row",
            )

            // 4) the fallback copy must describe BOTH axes this mode really runs on
            val phrases = expectedDetailPhrases[approvalPolicy to sandbox]
                ?: fail(
                    "preset '${preset.id}' translates to ($approvalPolicy, $sandbox) which this test has no expected " +
                        "copy for — the translation changed, so review MODE_PRESETS' wording and extend this table",
                )
            val detail = preset.detail.orEmpty()
            for (phrase in phrases) {
                assertTrue(
                    detail.contains(phrase, ignoreCase = true),
                    "preset '${preset.id}' runs as ($approvalPolicy, $sandbox) but its detail \"$detail\" " +
                        "no longer says \"$phrase\" — the advertised copy and the real translation drifted apart",
                )
            }
        }

        // the table itself must not rot: an entry nobody reaches is a stale expectation, not a guard
        val livePairs = presets.map { CodexBackend.approvalPolicyFor(it.mode) to CodexBackend.sandboxFor(it.mode).flat }.toSet()
        assertEquals(
            expectedDetailPhrases.keys,
            livePairs,
            "the expected-copy table must describe exactly the (approvalPolicy, sandbox) pairs the backend produces",
        )
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
