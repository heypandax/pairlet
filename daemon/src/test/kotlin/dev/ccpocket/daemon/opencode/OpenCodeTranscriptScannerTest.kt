package dev.ccpocket.daemon.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenCodeTranscriptScannerTest {
    @Test
    fun parses_session_model_json_to_provider_slash_model() {
        assertEquals(
            "opencode/deepseek-v4-flash-free",
            OpenCodeTranscriptScanner.parseModel("""{"id":"deepseek-v4-flash-free","providerID":"opencode","variant":"max"}"""),
        )
    }

    @Test
    fun leaves_already_qualified_model_ids_alone() {
        assertEquals("zhipuai/glm-4.5", OpenCodeTranscriptScanner.parseModel("zhipuai/glm-4.5"))
        assertEquals(
            "openai/gpt-5.1",
            OpenCodeTranscriptScanner.parseModel("""{"id":"openai/gpt-5.1","providerID":"openai"}"""),
        )
    }

    @Test
    fun rejects_unqualified_or_garbled_model_values() {
        assertNull(OpenCodeTranscriptScanner.parseModel("deepseek-chat"))
        assertNull(OpenCodeTranscriptScanner.parseModel("""{"id":"deepseek-v4-flash-free"}"""))
        assertNull(OpenCodeTranscriptScanner.parseModel("{not json"))
    }
}
