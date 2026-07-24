package dev.ccpocket.daemon.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeTranscriptScannerTest {
    // issue #172: OpenCode's task tool spawns sub-agent runs as CHILD sessions (parent_id → the
    // enclosing session). Those are internal sub-runs, not resumable top-level conversations, and
    // must be filtered from the phone's session list. Detection is by OpenCode's own parent/child
    // column ONLY — never by title text.

    @Test
    fun sub_agent_child_session_is_filtered_by_parent_id() {
        assertTrue(OpenCodeTranscriptScanner.isSubAgentSession("ses_7f3aParentEnclosing"))
    }

    @Test
    fun top_level_session_without_parent_is_kept() {
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession(null))
        // a blank column (defensive: OpenCode writes NULL, but treat empty as "no parent" too)
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession(""))
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession("   "))
    }

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
