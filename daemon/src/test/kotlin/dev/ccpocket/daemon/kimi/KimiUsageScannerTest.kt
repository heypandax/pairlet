package dev.ccpocket.daemon.kimi

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kimi usage extraction (issue #258).
 *
 * Two classes of fixture, deliberately kept apart:
 *  - the CONFIRMED shape (`{"type":"usage.record","model":"kimi-code/k3","usage":{"output":76}}`, the same
 *    line KimiTranscriptReplayTest asserts is skipped as a chat row) — asserted exactly;
 *  - HYPOTHETICAL richer shapes, which only pin the defensive contract "a present token key is counted,
 *    an absent one is 0, an unknown one is ignored". They must NOT be read as claims about the real wire.
 */
class KimiUsageScannerTest {
    private fun wire(vararg lines: String): Path {
        val dir = Files.createTempDirectory("ccp-kimi-usage")
        val f = dir.resolve("wire.jsonl")
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    // ── confirmed shape ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `the probe-confirmed usage record contributes its output tokens`() {
        val f = wire("""{"type":"usage.record","model":"kimi-code/k3","usage":{"output":76}}""")
        val r = KimiUsageScanner.readWire(f, sinceEpochMs = 0).single()
        assertEquals("kimi-code/k3", r.model)
        assertEquals(76L, r.output)
        assertEquals(0L, r.input)
        assertEquals(0L, r.cacheCreation)
        assertEquals(0L, r.cacheRead)
        // no clock on the record and none earlier in the file → the file's own mtime dates it
        assertTrue(r.whenEpochMs > 0L, "a record with no timestamp still lands on the session's write day")
    }

    @Test
    fun `chat lines are ignored and their clock dates a later usage record`() {
        val ts = 1_780_000_000_000L
        val f = wire(
            """{"type":"turn.prompt","input":[{"type":"text","text":"hi"}],"time":$ts}""",
            """{"type":"usage.record","model":"kimi-code/k3","usage":{"output":10}}""",
        )
        val r = KimiUsageScanner.readWire(f, sinceEpochMs = 0).single()
        assertEquals(ts, r.whenEpochMs)
        assertEquals(10L, r.output)
    }

    // ── hypothetical shapes: the defensive contract only ─────────────────────────────────────────
    @Test
    fun `hypothetical input and cache keys are counted when present`() {
        val ts = 1_780_000_000_000L
        val f = wire(
            """{"type":"usage.record","model":"kimi-code/k3","time":$ts,"usage":{"input":100,"output":20,"cache_read":30,"cache_creation":5,"unknown_future_key":9}}""",
        )
        val r = KimiUsageScanner.readWire(f, sinceEpochMs = 0).single()
        assertEquals(100L, r.input)
        assertEquals(20L, r.output)
        assertEquals(30L, r.cacheRead)
        assertEquals(5L, r.cacheCreation)
        assertEquals(ts, r.whenEpochMs)
    }

    @Test
    fun `a nested cache object and camelCase spellings are tolerated`() {
        val f = wire(
            """{"type":"usage.record","modelId":"kimi-code/k3","usage":{"inputTokens":8,"outputTokens":2,"cache":{"read":4,"write":1}}}""",
        )
        val r = KimiUsageScanner.readWire(f, sinceEpochMs = 0).single()
        assertEquals(8L, r.input)
        assertEquals(2L, r.output)
        assertEquals(4L, r.cacheRead)
        assertEquals(1L, r.cacheCreation)
        assertEquals("kimi-code/k3", r.model)
    }

    @Test
    fun `reasoning stands in for a missing output but never adds to a present one`() {
        val standIn = KimiUsageScanner.readWire(
            wire("""{"type":"usage.record","usage":{"reasoning":40}}"""), 0,
        ).single()
        assertEquals(40L, standIn.output, "no output key → reasoning is the only output signal we have")

        val subset = KimiUsageScanner.readWire(
            wire("""{"type":"usage.record","usage":{"output":40,"reasoning":30}}"""), 0,
        ).single()
        assertEquals(40L, subset.output, "reasoning is a SUBSET of output everywhere — adding both would inflate")
    }

    @Test
    fun `a usage record nested under a loop event is picked up too`() {
        val f = wire(
            """{"type":"context.append_loop_event","event":{"type":"usage.record","model":"kimi-code/k3","usage":{"output":7}}}""",
        )
        assertEquals(7L, KimiUsageScanner.readWire(f, sinceEpochMs = 0).single().output)
    }

    // ── degradation ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `unknown shapes and unparseable lines yield nothing instead of invented numbers`() {
        val f = wire(
            "not json",
            """{"type":"usage.record","model":"kimi-code/k3","usage":{"totally_unknown":"nope"}}""",
            """{"type":"usage.record","model":"kimi-code/k3","usage":{}}""",
            """{"type":"turn.ended","time":1}""",
        )
        assertTrue(KimiUsageScanner.readWire(f, sinceEpochMs = 0).isEmpty())
    }

    @Test
    fun `a session whose wire log predates the window is skipped whole`() {
        val f = wire("""{"type":"usage.record","model":"kimi-code/k3","usage":{"output":76}}""")
        assertTrue(KimiUsageScanner.readWire(f, sinceEpochMs = System.currentTimeMillis() + 60_000).isEmpty())
    }

    @Test
    fun `a missing wire log degrades to empty, never throws`() {
        assertTrue(KimiUsageScanner.readWire(Files.createTempDirectory("x").resolve("nope.jsonl"), 0).isEmpty())
        assertTrue(KimiUsageScanner.usageRecords(0, entries = emptyList()).isEmpty())
    }
}
