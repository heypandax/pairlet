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
