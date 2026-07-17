package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The composer model chip's label pipeline (issue #157, design model-chip.jsx):
 *  [midTruncateModel] keeps long gateway ids inside the chip, [modelChipLabel] decides
 *  alias-vs-id exactly once for both shells. */
class ModelChipLabelTest {

    @Test
    fun `long id middle-truncates to head + ellipsis + tail`() {
        assertEquals("deepseek…v3.2", midTruncateModel("deepseek-chat-v3.2"))
        assertEquals("kimi-k2-…view", midTruncateModel("kimi-k2-0905-preview"))
        // one char over the boundary already truncates (14 → 13)
        assertEquals("deepseek…hat2", midTruncateModel("deepseek-chat2"))
    }

    @Test
    fun `boundary-length id passes through unchanged`() {
        // 13 chars == head+tail+1: swapping a char for '…' would save nothing
        assertEquals("deepseek-chat", midTruncateModel("deepseek-chat"))
    }

    @Test
    fun `short id passes through unchanged`() {
        assertEquals("glm-4.6", midTruncateModel("glm-4.6"))
        assertEquals("fable", midTruncateModel("fable"))
    }

    @Test
    fun `chip shows the bare alias for claude-family models`() {
        assertEquals("fable", modelChipLabel("fable"))                    // bare alias — the default state
        assertEquals("opus", modelChipLabel("claude-opus-4-8"))           // full id collapses like the header
        assertEquals("opus", modelChipLabel("claude-opus-4-8[1m]"))       // 1M variant keeps the alias
    }

    @Test
    fun `chip keeps the real id for gateway and codex models`() {
        // modelAlias would misname these ("deepseek-chat" → "deepseek") — the chip must not
        assertEquals("deepseek-chat", modelChipLabel("deepseek-chat"))    // boundary: fits, untouched
        assertEquals("deepseek…v3.2", modelChipLabel("deepseek-chat-v3.2"))
        assertEquals("gpt-5.1-codex", modelChipLabel("gpt-5.1-codex"))
        assertEquals("gpt-5.1-…mini", modelChipLabel("gpt-5.1-codex-mini"))
    }

    @Test
    fun `blank in, blank out — callers fall back to the account-default placeholder`() {
        assertEquals("", modelChipLabel(null))
        assertEquals("", modelChipLabel("   "))
    }
}
