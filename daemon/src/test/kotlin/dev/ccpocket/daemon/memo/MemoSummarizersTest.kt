package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoSummarizersTest {

    private val claude = FakeSummarizer(agent = VOICE_MEMO_AGENT_CLAUDE)
    private val codex = FakeSummarizer(agent = VOICE_MEMO_AGENT_CODEX)

    @Test
    fun available_keeps_preference_order_and_skips_unlaunchable_adapters() {
        assertEquals(listOf("claude", "codex"), MemoSummarizers(listOf(claude, codex)).available())
        assertEquals(listOf("codex", "claude"), MemoSummarizers(listOf(codex, claude)).available())
        claude.available = false
        assertEquals(listOf("codex"), MemoSummarizers(listOf(claude, codex)).available())
        codex.available = false
        assertTrue(MemoSummarizers(listOf(claude, codex)).available().isEmpty())
    }

    @Test
    fun an_adapter_whose_check_throws_is_unavailable() {
        claude.availability = { error("stat failed") }
        assertEquals(listOf("codex"), MemoSummarizers(listOf(claude, codex)).available())
    }

    @Test
    fun forAgent_finds_the_adapter_whether_or_not_it_can_launch() {
        val all = MemoSummarizers(listOf(claude, codex))
        assertSame(codex, all.forAgent("codex"))
        claude.available = false
        assertSame(claude, all.forAgent("claude"))
        assertNull(all.forAgent(VOICE_MEMO_AGENT_NONE))
        assertNull(all.forAgent("kimi"))
    }

    @Test
    fun no_adapters_is_a_legal_state() {
        assertTrue(MemoSummarizers.EMPTY.available().isEmpty())
        assertNull(MemoSummarizers.EMPTY.forAgent("claude"))
    }

    @Test
    fun wiring_mistakes_fail_fast() {
        assertFailsWith<IllegalArgumentException> { MemoSummarizers(listOf(claude, FakeSummarizer(agent = VOICE_MEMO_AGENT_CLAUDE))) }
        assertFailsWith<IllegalArgumentException> { MemoSummarizers(listOf(FakeSummarizer(agent = VOICE_MEMO_AGENT_NONE))) }
        assertFailsWith<IllegalArgumentException> { MemoSummarizers(listOf(FakeSummarizer(agent = " "))) }
    }

    @Test
    fun production_adapters_serve_their_wire_names() {
        val runtime = dev.ccpocket.daemon.claude.ClaudeRuntime(binOverride = null, configDir = null, presetEnv = { null })
        val real = MemoSummarizers(
            listOf(
                ClaudeMemoSummarizer(runtime, runner = { error("never") }, resolveBin = { null }),
                CodexMemoSummarizer(codexBin = null, runner = { error("never") }, resolveBin = { null }),
            ),
        )
        assertEquals(listOf("claude", "codex"), real.all.map { it.agent })
        assertTrue(real.available().isEmpty())
    }
}
