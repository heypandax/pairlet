package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.protocol.TokenUsage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #320 — a dsh session's header read "default" with no context readout for its whole life, because
 * the only [AgentEvent.SessionInit] the backend ever emitted was our OWN synthetic one (model = null) and
 * `assistant/message` was dropped whole, usage and all.
 *
 * Every frame below is a REAL shape, copied off a local `~/.dsh/sessions/**/session.jsonl.zstd`, and every
 * test goes through the public `parse(line)` seam (mux envelope included) so a translation that regresses
 * shows up here rather than as a quietly empty header on the phone.
 */
class DshRuntimeMetaTest {

    private fun backend() = DshBackend(null).apply { bindSessionForTest(SESSION) }

    /** Wrap one SessionEvent in the mux envelope the WS client re-injects. */
    private fun event(body: String): String =
        """{"method":"session/event","payload":{"sessionId":"$SESSION","event":${body.trimIndent().replace("\n", " ")}}}"""

    private val requestContext = event(
        """
        {"type":"request/context",
         "data":{"provider":"deepseek-official","model":"deepseek-v4-flash","contextWindow":1000000}}
        """,
    )

    private val requestHeader = event(
        """
        {"type":"request/header",
         "data":{"header":{"config":{"provider":"deepseek-official","model":"deepseek-v4-flash",
                                     "maxTokens":256000,"reasoningEffort":"high"}}}}
        """,
    )

    /** [usage] is spliced in verbatim so the negative cases can drop or zero it without touching the rest. */
    private fun assistantMessage(usage: String) = event(
        """
        {"type":"assistant/message","seq":36,"time":1786805272848,
         "data":{"turn":1,"step":1,
                 "message":{"role":"assistant","content":[{"type":"text","text":"hello"}],
                            "source":{"kind":"model","provider":"deepseek-official","model":"deepseek-v4-flash"},
                            "id":"580798a2-1f2c-4a3b-9c11-6d5e4f3a2b10"}$usage}}
        """,
    )

    @Test
    fun request_context_carries_the_model_and_the_real_context_window() = runBlocking<Unit> {
        val meta = assertIs<AgentEvent.RuntimeMeta>(backend().parse(requestContext).single())
        assertEquals("deepseek-v4-flash", meta.model)
        assertEquals(1_000_000L, meta.contextWindow)
        assertNull(meta.effort) // this frame says nothing about effort — and null means exactly that
    }

    /**
     * ⚠️ The trap this test exists for: `config.maxTokens` (256000) is the OUTPUT cap of the very model whose
     * context window is 1,000,000. Reading it as the window would understate occupancy four-fold, so the
     * header frame must contribute model + effort and NOTHING resembling a window.
     */
    @Test
    fun request_header_carries_effort_but_never_mistakes_maxTokens_for_the_window() = runBlocking<Unit> {
        val meta = assertIs<AgentEvent.RuntimeMeta>(backend().parse(requestHeader).single())
        assertEquals("deepseek-v4-flash", meta.model)
        assertEquals("high", meta.effort)
        assertNull(meta.contextWindow)
        assertTrue(meta.contextWindow != 256_000L, "maxTokens is the output cap, never the context window")
    }

    @Test
    fun assistant_message_yields_the_answering_model_and_its_usage_but_still_renders_no_text() = runBlocking<Unit> {
        val events = backend().parse(assistantMessage(USAGE_UNCACHED))
        // still no AssistantText: the reply already streamed as `assistant/chunk` deltas, and surfacing
        // both would print every dsh answer twice (the live-vs-disk rule in DshBackend's class comment)
        assertTrue(events.none { it is AgentEvent.AssistantText }, "the assembled message must not re-render")
        assertEquals("deepseek-v4-flash", events.filterIsInstance<AgentEvent.RuntimeMeta>().single().model)
        val usage = events.filterIsInstance<AgentEvent.AssistantUsage>().single()
        assertEquals(11_149L, usage.inputTokens)
        assertNull(usage.cacheCreationInputTokens) // dsh has no cache-write counter at all — never reported
        assertEquals(0L, usage.cacheReadInputTokens) // …whereas this one IS reported, and measured zero
    }

    /**
     * DeepSeek is OpenAI-lineage — `cacheReadTokens` is a SUBSET of `inputTokens` — while
     * [TokenUsage.contextTokens] sums its columns as DISJOINT sets. Handing the pair over raw would count the
     * cached prefix twice; this pins the same subtraction [DshUsageScanner] applies on the disk path.
     */
    @Test
    fun a_cached_prefix_is_subtracted_so_the_window_readout_cannot_double_count() = runBlocking<Unit> {
        val usage = backend().parse(assistantMessage(USAGE_CACHED))
            .filterIsInstance<AgentEvent.AssistantUsage>().single()
        assertEquals(7_149L, usage.inputTokens) // 11149 prompt − 4000 of it served from cache
        assertEquals(4_000L, usage.cacheReadInputTokens)
        // what the phone's statusline actually divides by the window: the prompt, counted once
        val occupancy = TokenUsage(usage.inputTokens, 0, usage.cacheCreationInputTokens, usage.cacheReadInputTokens)
        assertEquals(11_149L, occupancy.contextTokens)
    }

    /**
     * The negative half. A zero [AgentEvent.AssistantUsage] would become a zero TurnDone usage and snap the
     * phone's "Context NN%" back to 0 mid-session — the same reason TurnResult carries a null usage instead of
     * placeholder zeros. Absent numbers must therefore produce NO usage event at all.
     */
    @Test
    fun a_missing_or_all_zero_usage_reports_nothing_rather_than_zeros() = runBlocking<Unit> {
        for (shape in listOf(USAGE_ABSENT, USAGE_ZERO)) {
            val events = backend().parse(assistantMessage(shape))
            assertTrue(
                events.none { it is AgentEvent.AssistantUsage },
                "usage shape `$shape` carries no tokens — it must not be reported as zero",
            )
            // the model is still learned: knowing WHO answered doesn't depend on knowing what it cost
            assertEquals("deepseek-v4-flash", events.filterIsInstance<AgentEvent.RuntimeMeta>().single().model)
        }
    }

    /** A frame whose fields are all missing says nothing — and an all-null RuntimeMeta travelling to the
     *  Conversation to say nothing is worse than no event: it would re-announce on every arrival. */
    @Test
    fun a_metadata_frame_with_nothing_in_it_is_ignored() = runBlocking<Unit> {
        assertIs<AgentEvent.Ignored>(backend().parse(event("""{"type":"request/context","data":{}}""")).single())
        assertIs<AgentEvent.Ignored>(backend().parse(event("""{"type":"request/header","data":{}}""")).single())
    }

    private companion object {
        const val SESSION = "session-1"

        /** The probed shape: `usage` is a SIBLING of `message` under `data`, never a field of it. */
        const val USAGE_UNCACHED =
            ""","usage":{"inputTokens":11149,"outputTokens":123,"cacheReadTokens":0,"reasoningTokens":41}"""
        const val USAGE_CACHED =
            ""","usage":{"inputTokens":11149,"outputTokens":123,"cacheReadTokens":4000,"reasoningTokens":41}"""
        const val USAGE_ZERO =
            ""","usage":{"inputTokens":0,"outputTokens":0,"cacheReadTokens":0,"reasoningTokens":0}"""
        const val USAGE_ABSENT = ""
    }
}
