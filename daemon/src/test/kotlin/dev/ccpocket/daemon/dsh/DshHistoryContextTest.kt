package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #320, phase B for dsh: what a session that merely SCROLLED BACK can prove about its own occupancy
 * and its own model.
 *
 * [DshResumeMetaTest] already pins the model / window / effort half. The hole this closes is the third
 * number the header needs — how full the window is — which until now resumed as null on every dsh session
 * and only appeared once the user happened to run another turn.
 *
 * ## Where the number comes from, and why it is this arithmetic and not another
 *
 * The live wire carries occupancy exactly once, on `usage_update`'s `used` ([DshBackend.handleUpdate]).
 * dsh computes that field in `@deepseek-ai/dsh-acp`:
 * ```js
 * used: meter.measure(session).totalTokens                       // dsh-acp/lib/index.js, usageUpdate()
 * // …whose baseline, when a real request has reported usage, is
 * usageTokens = (u) => u.inputTokens + (u.cacheReadTokens ?? 0) + (u.cacheWriteTokens ?? 0) + u.outputTokens
 * ```
 * (`@deepseek-ai/dsh-token-meter`, read off the installed 0.1.5 package.) That is term-for-term
 * [dev.ccpocket.protocol.TokenUsage.contextTokens], so the disk seed and the live value are the SAME
 * quantity and a resumed session does not visibly jump the moment it answers once.
 *
 * ⚠️ NOT `contextPressure.pressureTokens` from dsh's `session_projcache`, which is the tempting O(1) read:
 * that projection is documented "prompt-side only … it holds still while a turn streams", i.e. it excludes
 * the reply the model just wrote. Seeding from it would under-report every session by its last answer and
 * then step on the first live `usage_update`. The transcript's own `assistant/message` usage is both the
 * authoritative record and free — [DshTranscript.resumeMeta] already visits those lines.
 */
class DshHistoryContextTest {

    private fun store(): Path =
        Files.createTempDirectory("dsh-history-context").also { it.toFile().deleteOnExit() }

    /** Write one session the way dsh lays it out: header frame, then one frame of events. */
    private fun session(root: Path, cwd: String, id: String, events: String) {
        val dir = root.resolve(DshPaths.projectKey(cwd)).resolve(DshPaths.encodeSessionId(id))
        dir.createDirectories()
        val header =
            """{"type":"session","version":0,"id":"$id","cwd":"$cwd","createdAt":1700000000000,"delegationDepth":0}""" + "\n"
        val bytes = Zstd.compress(header.toByteArray()) +
            (if (events.isEmpty()) ByteArray(0) else Zstd.compress(events.toByteArray()))
        Files.write(dir.resolve("session.jsonl.zstd"), bytes)
    }

    private fun backend(root: Path) = DshBackend(null).apply { bindStoreRootForTest(root) }

    // ---- the real record shapes, copied off a local ~/.dsh/sessions/**/session.jsonl.zstd ----

    /**
     * ⚠️ `usage` is a SIBLING of `message` under `data`, not a member of it — the single detail most
     * likely to be "tidied" into a bug later (the same warning [DshUsageScanner] carries).
     */
    private fun answered(
        seq: Int,
        model: String = "deepseek-v4-flash",
        input: Long,
        output: Long,
        cacheRead: Long = 0,
        cacheWrite: Long? = null,
        reasoning: Long = 0,
    ): String {
        val write = cacheWrite?.let { ""","cacheWriteTokens":$it""" } ?: ""
        return """{"type":"assistant/message","seq":$seq,"time":$seq,"data":{"turn":1,"step":1,""" +
            """"message":{"role":"assistant","content":[{"type":"text","text":"hi"}],""" +
            """"source":{"kind":"model","provider":"deepseek-official","model":"$model"},"id":"m$seq"},""" +
            """"usage":{"inputTokens":$input,"outputTokens":$output,"cacheReadTokens":$cacheRead""" +
            """$write,"reasoningTokens":$reasoning}}}""" + "\n"
    }

    /** An assistant turn that reported NO usage block at all (dsh writes these for refusals). */
    private fun answeredWithoutUsage(seq: Int) =
        """{"type":"assistant/message","seq":$seq,"time":$seq,"data":{"turn":1,"step":1,""" +
            """"message":{"role":"assistant","content":[{"type":"text","text":"hi"}],""" +
            """"source":{"kind":"model","provider":"deepseek-official","model":"deepseek-v4-flash"},"id":"m$seq"}}}""" + "\n"

    private fun userMessage(text: String, seq: Int = 9) =
        """{"type":"user/message","seq":$seq,"time":$seq,"data":{"id":"u$seq","role":"user","content":[{"type":"text","text":"$text"}],"source":"user"}}""" + "\n"

    private fun requestContext(model: String, window: Long, seq: Int = 1) =
        """{"type":"request/context","seq":$seq,"time":$seq,"data":{"provider":"deepseek-official","model":"$model","contextWindow":$window}}""" + "\n"

    /** dsh asks a SEPARATE model to name the session. Its route is recorded, and it must never be mistaken
     *  for the conversation's own model or its own occupancy. */
    private fun titleRequest(model: String, seq: Int = 2) =
        """{"type":"session/title-llm-request","seq":$seq,"time":$seq,"data":{"titleProvider":"session-title-first-prompt-llm",""" +
            """"route":{"provider":"deepseek-official","model":"$model"},"maxTokens":64}}""" + "\n"

    // ---- 1. a brand-new session ----

    /** Nothing has been asked yet, so nothing is occupied. Null means "no readout"; a 0 would render as a
     *  confident 0% and is the one answer we must never invent. */
    @Test
    fun a_session_that_never_answered_seeds_no_occupancy() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello"))
        assertNull(backend(root).resumeContextTokens(CWD, SESSION))
    }

    /** An assistant turn dsh recorded WITHOUT a usage block is not evidence of an empty window either. */
    @Test
    fun an_answer_without_a_usage_block_stays_unknown() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello") + answeredWithoutUsage(3))
        assertNull(backend(root).resumeContextTokens(CWD, SESSION))
    }

    // ---- 2. history ----

    /** The whole point: a reopened session shows its occupancy before it runs anything. */
    @Test
    fun the_last_answered_turn_seeds_the_occupancy() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello") + answered(seq = 3, input = 11_173, output = 19))
        assertEquals(11_192L, backend(root).resumeContextTokens(CWD, SESSION))
    }

    /**
     * Term-for-term dsh's own `usageTokens`: input + cacheRead + cacheWrite + output. Getting this wrong is
     * invisible on a local store (every sample has cacheRead == 0) and then wrong by ~90% on a real one,
     * where the cached prefix is most of the prompt.
     */
    @Test
    fun the_seed_is_dshs_own_usage_arithmetic_including_both_cache_columns() {
        val root = store()
        session(root, CWD, SESSION, answered(seq = 3, input = 11_173, output = 19, cacheRead = 512, cacheWrite = 256))
        assertEquals(11_960L, backend(root).resumeContextTokens(CWD, SESSION))
    }

    /** DeepSeek counts reasoning INSIDE completion tokens, and dsh's own `usageTokens` does not add it.
     *  Adding it here would double-count every thinking turn. */
    @Test
    fun reasoning_tokens_are_already_inside_output_and_are_not_added_again() {
        val root = store()
        session(root, CWD, SESSION, answered(seq = 3, input = 1_000, output = 500, reasoning = 400))
        assertEquals(1_500L, backend(root).resumeContextTokens(CWD, SESSION))
    }

    /**
     * LAST wins, not the largest ever seen. After a `/compact` the window genuinely holds LESS, and a
     * high-water mark would leave the user staring at a full gauge on a freshly emptied session.
     */
    @Test
    fun a_compacted_session_seeds_the_smaller_new_occupancy_not_the_historic_peak() {
        val root = store()
        session(
            root, CWD, SESSION,
            answered(seq = 3, input = 180_000, output = 2_000) +
                userMessage("/compact", seq = 4) +
                answered(seq = 5, input = 12_000, output = 300),
        )
        assertEquals(12_300L, backend(root).resumeContextTokens(CWD, SESSION))
    }

    /** dsh's "this record carries no meaning" marker is honoured for occupancy exactly as it is for the
     *  model — a discarded request must not set the gauge. */
    @Test
    fun an_ignorable_answer_is_not_evidence_of_occupancy() {
        val root = store()
        session(
            root, CWD, SESSION,
            answered(seq = 3, input = 1_000, output = 100) +
                """{"type":"assistant/message","seq":4,"time":4,"ignorable":true,"data":{"usage":{"inputTokens":999999,"outputTokens":0,"cacheReadTokens":0}}}""" + "\n",
        )
        assertEquals(1_100L, backend(root).resumeContextTokens(CWD, SESSION))
    }

    /** The title is written by a separate, tiny request against a possibly different model. It is not the
     *  conversation, so it may set neither the occupancy nor the model. */
    @Test
    fun the_title_side_request_sets_neither_the_occupancy_nor_the_model() {
        val root = store()
        session(
            root, CWD, SESSION,
            requestContext("deepseek-v4-flash", 1_000_000) +
                answered(seq = 3, input = 1_000, output = 100) +
                titleRequest("deepseek-v4-titler", seq = 4),
        )
        val backend = backend(root)
        assertEquals(1_100L, backend.resumeContextTokens(CWD, SESSION))
        assertEquals("deepseek-v4-flash", backend.resumeModel(CWD, SESSION))
    }

    /** An id that resolves to nothing is not an error — it is a null, and the open proceeds. */
    @Test
    fun an_unknown_session_yields_nothing_rather_than_throwing() {
        val root = store()
        session(root, CWD, SESSION, answered(seq = 3, input = 1_000, output = 100))
        assertNull(backend(root).resumeContextTokens(CWD, "no-such-session"))
    }

    // ---- 3. the session LIST ----

    /**
     * Every other backend's list row names the model its history recorded ([SessionSummary.model]); dsh's
     * was the one that read blank. The scan already streams each transcript once for the rename channel
     * (issue #289), so the model rides that SAME pass — the listing must not grow a second full read per
     * session, let alone decompress the store twice.
     */
    @Test
    fun a_history_row_names_the_model_that_actually_answered() {
        val root = store()
        session(
            root, CWD, SESSION,
            requestContext("deepseek-v4", 128_000) +
                answered(seq = 3, model = "deepseek-v4", input = 10, output = 1) +
                userMessage("switch, please", seq = 4) +
                answered(seq = 5, model = "deepseek-v4-flash", input = 20, output = 2),
        )
        val row = DshTranscriptScanner.scan(CWD, root).single { it.sessionId == SESSION }
        assertEquals("deepseek-v4-flash", row.model, "last-wins: the row must say what the session IS")
    }

    /** …and a chat that never named a model stays blank rather than borrowing the local default. */
    @Test
    fun a_history_row_with_no_model_evidence_stays_blank() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello"))
        assertNull(DshTranscriptScanner.scan(CWD, root).single { it.sessionId == SESSION }.model)
    }

    private companion object {
        const val CWD = "/tmp/dsh-history-context"
        const val SESSION = "session-11111111-2222-3333-4444-555555555555"
    }
}
