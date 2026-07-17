package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentModelCompatibilityTest {

    @Test
    fun codex_accepts_only_codex_shaped_models() {
        assertTrue(isModelCompatibleWithAgent(AgentKind.CODEX, "gpt-5.1-codex"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CODEX, "gpt-5.2-codex"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CODEX, "gpt-5.5"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CODEX, "opencode/deepseek-v4-flash-free"))
    }

    @Test
    fun opencode_requires_provider_prefix() {
        assertTrue(isModelCompatibleWithAgent(AgentKind.OPENCODE, "opencode/deepseek-v4-flash-free"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.OPENCODE, "zhipuai/glm-5"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "deepseek-chat"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "sonnet"))
    }

    @Test
    fun claude_allows_gateway_ids_but_not_other_agent_ids() {
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "sonnet"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "deepseek-chat"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "gpt-5.5"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CLAUDE, "gpt-5.1-codex"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CLAUDE, "opencode/deepseek-v4-flash-free"))
    }

    @Test
    fun compatible_model_trims_or_drops_the_value() {
        assertEquals("gpt-5.1-codex", compatibleModelForAgent(AgentKind.CODEX, " gpt-5.1-codex "))
        assertEquals(null, compatibleModelForAgent(AgentKind.CODEX, " gpt-5.5 "))
    }
}
