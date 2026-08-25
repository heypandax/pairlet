package dev.ccpocket.daemon.kimi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads the top-level `default_model` from config.toml, defensively (issue #206, #96 contract). */
class KimiDefaultModelTest {

    private fun cfg(text: String) = Files.createTempFile("kimi_cfg", ".toml").also { Files.writeString(it, text) }

    @Test
    fun `top-level default_model is read`() {
        assertEquals("kimi-k2", KimiDefaultModel.resolve(cfg("default_model = \"kimi-k2\"\n")))
    }

    @Test
    fun `stops at first table header`() {
        val c = cfg("[providers.foo]\ndefault_model = \"inside-table\"\n")
        assertNull(KimiDefaultModel.resolve(c))
    }

    @Test
    fun `comment is stripped and blank degrades to null`() {
        assertEquals("m1", KimiDefaultModel.resolve(cfg("default_model = 'm1'  # the default\n")))
        assertNull(KimiDefaultModel.resolve(cfg("# just a comment\n")))
    }

    @Test
    fun `missing file is null, never throws`() {
        assertNull(KimiDefaultModel.resolve(Files.createTempDirectory("x").resolve("none.toml")))
    }
}
