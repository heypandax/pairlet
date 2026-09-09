package dev.ccpocket.daemon.zcode

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * ZCode 3.11's store: a `provider` map and nothing else. Redacted copy of a real 3.11.2 v2 config —
     * four built-in entries the Electron host authenticates privately (blank apiKey, one of them disabled
     * with `systemDisabledReason`, one with no models at all) plus one custom API-key provider.
     */
    private val v2Store = """
        {"provider":{
          "builtin:zai":{"name":"Z.ai - API Key","kind":"anthropic","enabled":false,"source":"custom",
            "options":{"apiKey":"","baseURL":"https://api.z.ai/api/anthropic","apiKeyRequired":true},
            "models":{"GLM-5.3":{"limit":{"context":1000000,"output":128000}}},
            "systemDisabledReason":"oauth_provider_inactive"},
          "builtin:bigmodel":{"name":"Bigmodel - API Key","kind":"anthropic","source":"custom",
            "options":{"apiKey":"","baseURL":"https://open.bigmodel.cn/api/anthropic"},
            "models":{"GLM-5.3":{"limit":{"context":1000000,"output":128000},
              "modalities":{"input":["text"],"output":["text"]}}}},
          "builtin:zai-start-plan":{"name":"Z.ai - Coding Plan","kind":"anthropic","source":"custom",
            "options":{"apiKey":"","baseURL":"https://zcode.z.ai/api/v1/zcode-plan/anthropic"},"models":{}},
          "695d6dc1":{"name":"go","kind":"anthropic","source":"custom",
            "options":{"apiKey":"sk-redacted","baseURL":"https://llm.example.test/v1","apiKeyRequired":true},
            "models":{"glm-5.3":{"name":"GLM-5.3",
              "reasoning":{"enabled":true,"variants":["low","high","max"],"defaultVariant":"max"},
              "limit":{"context":1000000,"output":128000},
              "modalities":{"input":["text","image","video","pdf"],"output":["text"]},
              "zcode":{"modalitiesConfigured":true,"modified":true}}}}
        }}
    """.trimIndent()

    private fun store(name: String, body: String) = createTempFile(name, ".json").also { it.writeText(body) }

    @Test
    fun `3_11 store without a model object still yields a default and a catalog`() {
        val file = store("zcode-v2", v2Store)
        val service = ZCodeModelService(file)
        // Only the provider that can authenticate headlessly is offered: the built-ins carry a blank key
        // because the Electron host injects their Coding Plan credentials privately, which cc-pocket cannot.
        assertEquals(listOf("695d6dc1/glm-5.3"), service.configuredModels(file))
        assertEquals("695d6dc1/glm-5.3", service.defaultModel())
    }

    @Test
    fun `runtime model carries the provider inline in the runtime's strict shape`() {
        val file = store("zcode-v2-runtime", v2Store)
        val runtime = assertNotNull(ZCodeModelService(file).runtimeModel())
        assertEquals(
            """{"providerId":"695d6dc1","modelId":"glm-5.3"}""",
            runtime["model"].toString(),
        )
        // Locked byte-for-byte: every one of these schemas is .strict() in the runtime, so an extra or
        // renamed key rejects the whole session/create. apiKey is emitted last (deepest in the frame).
        assertEquals(
            """{"providerId":"695d6dc1","kind":"anthropic","label":"go","source":"custom",""" +
                """"baseURL":"https://llm.example.test/v1","apiKeyRequired":true,""" +
                """"models":[{"modelId":"glm-5.3","label":"GLM-5.3","contextWindow":1000000,""" +
                """"maxOutputTokens":128000,"supportsImages":true,"supportsPdf":true,"supportsVideo":true,""" +
                """"reasoning":{"enabled":true,"levels":[{"value":"low","label":"low"},""" +
                """{"value":"high","label":"high"},{"value":"max","label":"max"}],"defaultLevel":"max"}}],""" +
                """"apiKey":{"source":"inline","value":"sk-redacted"}}""",
            runtime["provider"].toString(),
        )
        val revision = (runtime["revision"] as JsonPrimitive).content
        assertTrue(revision.isNotBlank(), "revision must be a non-empty string")
        // Content-derived: a reopen without an edit must not look like a new registry generation.
        assertEquals(revision, (assertNotNull(ZCodeModelService(file).runtimeModel())["revision"] as JsonPrimitive).content)
    }

    @Test
    fun `an unknown selection never produces a half-built provider`() {
        val file = store("zcode-v2-unknown", v2Store)
        val service = ZCodeModelService(file)
        assertNull(service.runtimeModel("695d6dc1/no-such-model"))
        assertNull(service.runtimeModel("no-such-provider/glm-5.3"))
        assertNull(service.runtimeModel("bare-model-id"))
    }

    @Test
    fun `catalog falls back to unauthenticated providers rather than going empty`() {
        val file = store(
            "zcode-v2-nokeys",
            """{"provider":{"builtin:bigmodel":{"kind":"anthropic","options":{"apiKey":""},
               "models":{"GLM-5.3":{}}},
               "builtin:zai":{"kind":"anthropic","enabled":false,"models":{"GLM-5.3":{}}}}}""",
        )
        val service = ZCodeModelService(file)
        // The disabled provider stays out even in the fallback; the enabled-but-keyless one is still shown
        // so a picker that used to have entries never silently empties.
        assertEquals(listOf("builtin:bigmodel/GLM-5.3"), service.configuredModels(file))
        assertNull(service.defaultModel())
    }
}
