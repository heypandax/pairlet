package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentModelCompatibilityTest {

    @Test
    fun codex_rejects_only_claude_aliases() {
        // minimal blocklist by design: the daemon's cache can carry "o3"-style ids no shape
        // heuristic places — over-classifying rejected daemon truth and locked the picker
        assertTrue(isModelCompatibleWithAgent(AgentKind.CODEX, "gpt-5.1-codex"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CODEX, "gpt-5.5"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CODEX, "o3"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CODEX, "sonnet"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.CODEX, "Opus"))
    }

    @Test
    fun opencode_requires_provider_prefix() {
        // the one HARD shape rule: a bare id makes `opencode run` hang silently
        assertTrue(isModelCompatibleWithAgent(AgentKind.OPENCODE, "opencode/deepseek-v4-flash-free"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.OPENCODE, "zhipuai/glm-5"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "deepseek-chat"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "sonnet"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "/x"))
        assertFalse(isModelCompatibleWithAgent(AgentKind.OPENCODE, "x/"))
    }

    @Test
    fun claude_stays_fully_permissive_for_gateway_ids() {
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "sonnet"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "deepseek-chat"))
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "gpt-5.5")) // LiteLLM-fronted ids are legitimate
        assertTrue(isModelCompatibleWithAgent(AgentKind.CLAUDE, "openrouter/anthropic/claude-4.5")) // slashed gateway ids too
    }

    @Test
    fun compatible_model_trims_or_drops_the_value() {
        assertEquals("gpt-5.1-codex", compatibleModelForAgent(AgentKind.CODEX, " gpt-5.1-codex "))
        assertEquals(null, compatibleModelForAgent(AgentKind.OPENCODE, "deepseek-chat"))
        assertEquals(null, compatibleModelForAgent(AgentKind.CODEX, "  "))
    }
}
