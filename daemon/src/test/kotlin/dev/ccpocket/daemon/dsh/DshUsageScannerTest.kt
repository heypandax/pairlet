package dev.ccpocket.daemon.dsh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * dsh usage extraction (issue #279).
 *
 * Unlike the Kimi scanner's fixtures, the shape asserted here is PROBE-CONFIRMED end to end against a real
 * local transcript — top-level `time`, `data.usage` as a SIBLING of `data.message`, the model on
 * `data.message.source.model`, the dedup id on `data.message.id`. The camel/snake alternatives the parser
 * also accepts are defensive spare tyres and are not claimed to exist on the wire.
 *
 * Sessions are written UNCOMPRESSED: the concatenated-zstd frame walk (and its ragged live tail) belongs to
 * [DshTranscript] and is DshTranscriptTest's subject, so repeating it here would only test zstd twice.
 */
class DshUsageScannerTest {

    /** The clock off the probe sample. */
    private val TS = 1_786_875_275_791L

    private fun store(): Path =
        Files.createTempDirectory("dsh-usage-test").also { it.toFile().deleteOnExit() }

    /** Write one session into the store the way dsh lays it out, returning its transcript file. */
    private fun session(
        root: Path,
        id: String = "session-a",
        cwd: String = "/work/alpha",
        version: Int = 0,
        origin: String? = null,
        dirName: String? = null,
        events: List<String> = emptyList(),
    ): Path {
        val dir = root.resolve(DshPaths.projectKey(cwd)).resolve(dirName ?: DshPaths.encodeSessionId(id))
        dir.createDirectories()
        val header = buildString {
            append("""{"type":"session","version":$version,"id":"$id","cwd":"$cwd","createdAt":1700000000000""")
            if (origin != null) append(""","origin":"$origin"""")
            append("}")
        }
        val file = dir.resolve("session.jsonl")
        file.writeText((listOf(header) + events).joinToString("\n") + "\n")
        return file
    }

    /** The probe-confirmed `assistant/message` event, with the `usage` object left to the caller. */
    private fun assistantMsg(
        seq: Int,
        usage: String,
        time: Long = TS,
        id: String = "msg-$seq",
        model: String = "deepseek-v4-flash",
    ) = """{"type":"assistant/message","seq":$seq,"time":$time,"data":{"turn":1,"step":1,""" +
        """"message":{"role":"assistant","content":[{"type":"text","text":"hi"}],""" +
        """"source":{"kind":"model","provider":"deepseek-official","model":"$model"},"id":"$id"},""" +
        """"usage":$usage},"sourceEventSeqs":[$seq],"surfaceOp":null}"""

    /** One session, one assistant turn carrying [usage] — the short path to "what did the parse make of it". */
    private fun record(usage: String): DshUsageScanner.UsageRecord {
        val root = store()
        session(root, events = listOf(assistantMsg(1, usage)))
        return DshUsageScanner.usageRecords(0, root).single()
    }

    // ── the confirmed shape ──────────────────────────────────────────────────────────────────────
    @Test
    fun `the probe-confirmed assistant message yields id, model, clock and token columns`() {
        val root = store()
        session(
            root,
            events = listOf(
                assistantMsg(
                    142,
                    """{"inputTokens":11149,"outputTokens":123,"cacheReadTokens":0,"reasoningTokens":41}""",
                    id = "c9caa5fb-0000-4000-8000-000000000000",
                ),
            ),
        )
        val r = DshUsageScanner.usageRecords(0, root).single()
        assertEquals("c9caa5fb-0000-4000-8000-000000000000", r.id, "the message id is the dedup key")
        assertEquals("deepseek-v4-flash", r.model)
        assertEquals(TS, r.whenEpochMs, "the clock is the event's TOP-LEVEL time")
        assertEquals(11149L, r.input)
        assertEquals(123L, r.output, "the 41 reasoning tokens are already inside output")
        assertEquals(0L, r.cacheCreation, "dsh records no cache-write counter")
        assertEquals(0L, r.cacheRead)
    }

    // ── the two accounting decisions ─────────────────────────────────────────────────────────────
    @Test
    fun `reasoning stands in for a missing output but never adds to a present one`() {
        assertEquals(
            40L, record("""{"outputTokens":40,"reasoningTokens":30}""").output,
            "DeepSeek counts reasoning INSIDE completion tokens — adding both would inflate output",
        )
        assertEquals(
            30L, record("""{"outputTokens":0,"reasoningTokens":30}""").output,
            "no output signal but a reasoning one → count it rather than report a free turn",
        )
    }

    @Test
    fun `cache reads are subtracted out of input to reach disjoint columns`() {
        val r = record("""{"inputTokens":1000,"outputTokens":10,"cacheReadTokens":400}""")
        assertEquals(600L, r.input, "DeepSeek is OpenAI-lineage: cached tokens are a SUBSET of the prompt count")
        assertEquals(400L, r.cacheRead)
        assertEquals(1010L, r.input + r.output + r.cacheRead, "the split moves tokens between columns, never loses them")

        // a cache count larger than the prompt count would be nonsense; clamp instead of going negative
        val odd = record("""{"inputTokens":100,"outputTokens":5,"cacheReadTokens":300}""")
        assertEquals(0L, odd.input)
        assertEquals(300L, odd.cacheRead)
    }

    // ── which sessions count ─────────────────────────────────────────────────────────────────────
    @Test
    fun `an unknown format version is skipped whole but a subagent session still counts`() {
        val future = store()
        session(future, version = 1, events = listOf(assistantMsg(1, """{"inputTokens":10,"outputTokens":10}""")))
        assertTrue(DshUsageScanner.usageRecords(0, future).isEmpty(), "version != 0 is never guessed at")

        val sub = store()
        session(sub, origin = "subagent", events = listOf(assistantMsg(1, """{"inputTokens":10,"outputTokens":10}""")))
        val r = DshUsageScanner.usageRecords(0, sub).single()
        assertEquals(
            20L, r.input + r.output,
            "the session LIST hides sub-agents, but their tokens are the user's tokens — usage must count them",
        )
    }

    @Test
    fun `a session whose transcript predates the window is skipped whole`() {
        val root = store()
        session(root, events = listOf(assistantMsg(1, """{"inputTokens":10,"outputTokens":10}""")))
        assertEquals(1, DshUsageScanner.usageRecords(0, root).size, "precondition: the record parses")
        assertTrue(DshUsageScanner.usageRecords(System.currentTimeMillis() + 60_000, root).isEmpty())
    }

    @Test
    fun `every project directory is walked, sidecars are not, and an absent store yields nothing`() {
        val root = store()
        session(root, id = "session-a", cwd = "/work/alpha", events = listOf(assistantMsg(1, """{"outputTokens":5}""")))
        session(root, id = "session-b", cwd = "/work/beta", events = listOf(assistantMsg(2, """{"outputTokens":7}""")))
        // a half-written session dir carries a parseable transcript but must never be read as one
        session(
            root, id = "session-tmp", cwd = "/work/alpha", dirName = "session-tmp.tmp",
            events = listOf(assistantMsg(3, """{"outputTokens":900}""")),
        )

        assertEquals(12L, DshUsageScanner.usageRecords(0, root).sumOf { it.output }, "both projects, no sidecar")
        assertTrue(DshUsageScanner.usageRecords(0, root.resolve("never-created")).isEmpty())
    }

    // ── degradation ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `zero-token, non-assistant and unparseable lines contribute nothing`() {
        val root = store()
        session(
            root,
            events = listOf(
                "not json at all",
                """{"type":"user/message","seq":1,"time":$TS,"data":{"id":"u1","role":"user","content":[{"type":"text","text":"hi"}],"source":"user"}}""",
                // a truncated assistant line: survives the substring prefilter, dies in the JSON parse
                """{"type":"assistant/message","seq":2,"time":$TS,"data":{"usage":{"inputTokens":5""",
                // an all-zero turn is real but free — a bar for it would be noise
                assistantMsg(3, """{"inputTokens":0,"outputTokens":0,"cacheReadTokens":0,"reasoningTokens":0}"""),
                // usage-shaped numbers on some OTHER event type are not a model call
                """{"type":"tool/call","seq":4,"time":$TS,"data":{"usage":{"inputTokens":99}}}""",
            ),
        )
        assertTrue(DshUsageScanner.usageRecords(0, root).isEmpty())
    }

    @Test
    fun `an implausible clock falls back to the transcript's write day`() {
        val root = store()
        session(root, events = listOf(assistantMsg(1, """{"outputTokens":5}""", time = 1)))
        val r = DshUsageScanner.usageRecords(0, root).single()
        assertTrue(
            r.whenEpochMs >= 1_000_000_000_000L,
            "a seconds-scale or fixture clock would file the spend in 1970 instead of on the session's day",
        )
    }

    @Test
    fun `a turn with no message id or model still lands on usable fallbacks`() {
        val root = store()
        session(
            root,
            events = listOf(
                """{"type":"assistant/message","seq":1,"time":$TS,"data":{"message":{"role":"assistant","source":{"kind":"model","provider":"deepseek-official"}},"usage":{"outputTokens":7}}}""",
                """{"type":"assistant/message","seq":2,"time":$TS,"data":{"usage":{"outputTokens":9}}}""",
            ),
        )
        val rs = DshUsageScanner.usageRecords(0, root)
        assertEquals(2, rs.size)
        assertEquals("deepseek-official", rs[0].model, "no model name → the provider labels the row")
        assertEquals("deepseek", rs[1].model, "neither → a placeholder, never a dropped turn")
        assertNotEquals(rs[0].id, rs[1].id, "with no message id the file+line keeps two turns distinct")
    }
}
