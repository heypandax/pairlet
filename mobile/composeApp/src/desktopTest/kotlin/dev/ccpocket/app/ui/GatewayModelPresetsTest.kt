package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gateway model preset table (issue #139) — the single data source both shells' pickers render. */
class GatewayModelPresetsTest {

    @Test
    fun `table entries are non-blank and ids unique`() {
        assertTrue(GATEWAY_MODEL_PRESETS.isNotEmpty())
        GATEWAY_MODEL_PRESETS.forEach {
            assertTrue(it.vendor.isNotBlank() && it.id.isNotBlank(), "blank entry: $it")
            assertTrue(it.id.trim() == it.id, "id has stray whitespace: '${it.id}'")
            // 0714 design: the avatar is a two-letter uppercase lettermark, never a logo
            assertTrue(it.monogram.length == 2 && it.monogram == it.monogram.uppercase(), "bad monogram: '${it.monogram}'")
        }
        assertEquals(GATEWAY_MODEL_PRESETS.size, GATEWAY_MODEL_PRESETS.map { it.id.lowercase() }.toSet().size, "duplicate ids")
    }

    @Test
    fun `host label strips scheme and path, keeps port`() {
        assertEquals("api.deepseek.com", gatewayHostLabel("https://api.deepseek.com/anthropic"))
        assertEquals("127.0.0.1:3456", gatewayHostLabel("http://127.0.0.1:3456/api"))
        assertEquals("gw.example.com", gatewayHostLabel("gw.example.com")) // no scheme — as typed
        assertNull(gatewayHostLabel(null))
        assertNull(gatewayHostLabel("   "))
    }

    /**
     * Issue #168: ids in this table rot silently — a retired one only fails at the user's gateway, so
     * we get zero signal (`kimi-k2` sat dead here for two months). The audit comment above the table is
     * prose; this is the executable half. Every id here was confirmed retired against vendor docs on
     * 2026-07-19 — append, never remove, and never "fix" a failure by deleting the row from this list.
     */
    @Test
    fun `no known-retired vendor id is in the table`() {
        val retired = mapOf(
            "deepseek-chat" to "retired 2026-07-24 15:59 UTC → deepseek-v4-flash (non-thinking)",
            "deepseek-reasoner" to "retired 2026-07-24 15:59 UTC → deepseek-v4-flash (thinking)",
            "kimi-k2" to "discontinued 2026-05-25 → kimi-k3",
            "qwen3-coder" to "series name, never an API id → qwen3-coder-plus",
        )
        val ids = GATEWAY_MODEL_PRESETS.map { it.id.lowercase() }.toSet()
        retired.forEach { (dead, why) ->
            assertTrue(dead !in ids, "preset table still ships a retired id '$dead' — $why")
        }
    }

    @Test
    fun `vendor-matched rows rank first, table order otherwise`() {
        val deepseek = recommendedGatewayPresets("https://api.deepseek.com/anthropic")
        assertTrue(deepseek.take(2).all { it.vendor == "DeepSeek" }, "expected DeepSeek first: $deepseek")
        val glm = recommendedGatewayPresets("https://open.bigmodel.cn/api/anthropic")
        assertEquals("GLM", glm.first().vendor)
        // unmatched / null: the stable table order (an aggregator gateway routes any vendor)
        assertEquals(GATEWAY_MODEL_PRESETS, recommendedGatewayPresets("http://127.0.0.1:3456"))
        assertEquals(GATEWAY_MODEL_PRESETS, recommendedGatewayPresets(null))
    }
}
