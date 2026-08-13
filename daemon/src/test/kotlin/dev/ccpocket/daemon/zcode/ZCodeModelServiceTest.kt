package dev.ccpocket.daemon.zcode

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ZCodeModelServiceTest {
    @Test
    fun `reads documented provider model config`() {
        val file = createTempFile("zcode-config", ".json")
        file.writeText(
            """{"model":{"main":{"provider":"zai","model":"glm-5","thoughtLevel":"high"},"lite":{"provider":"zai","model":"glm-4.7"},"available":[{"provider":"anthropic","model":"claude-sonnet-4-5"}]},"provider":{"zai":{"models":{"glm-4.7":{},"glm-5":{}}},"anthropic":{"models":{"claude-sonnet-4-5":{}}}}}""",
        )
        val service = ZCodeModelService(file)
        assertEquals("zai/glm-5", service.defaultModel())
        assertEquals(listOf("zai/glm-5", "zai/glm-4.7", "anthropic/claude-sonnet-4-5"), service.configuredModels(file))
    }

    @Test
    fun `legacy string main remains a fallback`() {
        val file = createTempFile("zcode-legacy-config", ".json")
        file.writeText("""{"model":{"main":"zai/glm-legacy"}}""")
        assertEquals("zai/glm-legacy", ZCodeModelService(file).defaultModel())
    }

    @Test
    fun `built-in model targets survive without provider catalog`() {
        val file = createTempFile("zcode-builtin-config", ".json")
        file.writeText(
            """{"model":{"main":{"provider":"builtin:zai-start-plan","model":"glm-5"},"lite":{"provider":"builtin:zai-start-plan","model":"glm-4.7"},"available":[{"provider":"builtin:bigmodel-start-plan","model":"glm-5"}]}}""",
        )
        assertEquals(
            listOf("builtin:zai-start-plan/glm-5", "builtin:zai-start-plan/glm-4.7", "builtin:bigmodel-start-plan/glm-5"),
            ZCodeModelService(file).configuredModels(file),
        )
    }
}
