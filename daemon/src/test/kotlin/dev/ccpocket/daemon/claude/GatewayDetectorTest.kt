package dev.ccpocket.daemon.claude

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayDetectorTest {

    private fun home(settingsJson: String? = null): File {
        val home = createTempDirectory("ccp-gw").toFile()
        if (settingsJson != null) {
            val dir = File(home, ".claude").apply { mkdirs() }
            File(dir, "settings.json").writeText(settingsJson)
        }
        return home
    }

    private val noEnv: (String) -> String? = { null }

    @Test
    fun `nothing configured resolves to null`() {
        assertNull(GatewayDetector.resolve(env = noEnv, home = home()))
    }

    @Test
    fun `process env base url is reported`() {
        val env: (String) -> String? = { if (it == "ANTHROPIC_BASE_URL") "https://gw.example.com/api" else null }
        assertEquals("https://gw.example.com/api", GatewayDetector.resolve(env = env, home = home()))
    }

    @Test
    fun `official endpoint is configured but NOT a gateway`() {
        val env: (String) -> String? = { if (it == "ANTHROPIC_BASE_URL") "https://api.anthropic.com" else null }
        assertNull(GatewayDetector.resolve(env = env, home = home()))
        // an explicit official value must not fall through to a lower-priority third-party source
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://gw.example.com"}}""")
        assertNull(GatewayDetector.resolve(env = env, home = h))
    }

    @Test
    fun `user settings env block is read`() {
        val h = home("""{"model":"opus","env":{"ANTHROPIC_BASE_URL":"https://open.bigmodel.cn/api/anthropic"}}""")
        assertEquals("https://open.bigmodel.cn/api/anthropic", GatewayDetector.resolve(env = noEnv, home = h))
    }

    @Test
    fun `active preset base url wins over env and settings`() {
        val env: (String) -> String? = { if (it == "ANTHROPIC_BASE_URL") "https://env.example.com" else null }
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://settings.example.com"}}""")
        assertEquals(
            "https://api.deepseek.com/anthropic",
            GatewayDetector.resolve(presetBaseUrl = "https://api.deepseek.com/anthropic", env = env, home = h),
        )
        // a preset pointing at the OFFICIAL endpoint means the effective launch is official → no gateway
        assertNull(GatewayDetector.resolve(presetBaseUrl = "https://api.anthropic.com", env = env, home = h))
    }

    @Test
    fun `blank values and malformed settings degrade to null without throwing`() {
        val env: (String) -> String? = { if (it == "ANTHROPIC_BASE_URL") "   " else null }
        assertNull(GatewayDetector.resolve(env = env, home = home("this is not json {")))
        assertNull(GatewayDetector.resolve(env = noEnv, home = home("""{"env":"not-an-object"}""")))
    }

    @Test
    fun `official host matching covers subdomains but not lookalikes`() {
        assertTrue(GatewayDetector.isOfficial("https://api.anthropic.com/v1"))
        assertTrue(GatewayDetector.isOfficial("https://foo.anthropic.com"))
        assertFalse(GatewayDetector.isOfficial("https://notanthropic.com"))
        assertFalse(GatewayDetector.isOfficial("https://api.anthropic.com.evil.example"))
        assertFalse(GatewayDetector.isOfficial("http://127.0.0.1:3456")) // cc-switch style local gateway
    }
}
