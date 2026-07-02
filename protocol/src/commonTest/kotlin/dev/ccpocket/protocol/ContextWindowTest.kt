package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextWindowTest {

    @Test
    fun explicit_1m_markers_win() {
        assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor("claude-sonnet-4-5 [1m]"))
        assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor("claude-sonnet-4-5-1m"))
    }

    @Test
    fun natively_1m_ids_map_to_the_large_window() {
        listOf(
            "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
            "claude-sonnet-5", "claude-sonnet-4-6",
            "claude-fable-5", "claude-mythos-5",
        ).forEach { assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor(it), it) }
    }

    @Test
    fun canonical_200k_ids_stay_default() {
        listOf("claude-haiku-4-5", "claude-opus-4-5", "claude-opus-4-1").forEach {
            assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor(it), it)
        }
    }

    @Test
    fun beta_gated_ids_default_to_200k_without_the_marker() {
        // capability ≠ enablement: canonical sonnet-4-5 / sonnet-4-20250514 default to 200k; the
        // observed-usage upgrade in the daemon handles beta-enabled sessions (see Conversation.live)
        assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor("claude-sonnet-4-5"))
        assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor("claude-sonnet-4-20250514"))
    }

    @Test
    fun bare_aliases_use_the_exact_match_table() {
        // a substring "opus" would wrongly match 200k opus-4-5/4-1 — aliases are exact-matched
        assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor("opus"))
        assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor("sonnet"))
        assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor("haiku"))
        assertEquals(LARGE_CONTEXT_WINDOW, contextWindowFor(" Opus ")) // trimmed + case-folded
    }

    @Test
    fun null_and_unknown_default_to_200k() {
        assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor(null))
        assertEquals(DEFAULT_CONTEXT_WINDOW, contextWindowFor("gpt-5.1-codex"))
    }
}
