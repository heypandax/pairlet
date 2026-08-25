package dev.ccpocket.daemon.kimi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parses `kimi provider list --json` (`{providers,models}`) into the model alias list (issue #206). */
class KimiModelServiceTest {
    private val svc = KimiModelService()

    @Test
    fun `models object keys become the sorted alias list`() {
        val json = """{"providers":{"moonshot":{}},"models":{"kimi-k2":{"provider":"moonshot"},"kimi-latest":{}}}"""
        assertEquals(listOf("kimi-k2", "kimi-latest"), svc.parseModels(json))
    }

    @Test
    fun `empty config yields empty list`() {
        assertTrue(svc.parseModels("""{"providers":{},"models":{}}""").isEmpty())
    }

    @Test
    fun `garbage yields empty list, never throws`() {
        assertTrue(svc.parseModels("not json").isEmpty())
        assertTrue(svc.parseModels("").isEmpty())
    }
}
