package dev.ccpocket.daemon.zcode

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #320, phase B for ZCode: the occupancy a reopened session shows before its first new turn.
 *
 * ## Source, and why `model_usage` rather than the message rows
 *
 * Both stores carry the number and they agree exactly (probed on the local 3.7.6 db: the newest assistant
 * `message.data.tokens` matches its `model_usage` row term for term). `model_usage` wins because it is the
 * only one that can say WHAT KIND of request it was: `query_source` separates the conversation's own
 * `main_turn` from the `session_title` side request that ZCode fires against a smaller model. Reading the
 * message rows means inferring that distinction; reading this column means being told it.
 *
 * ## The arithmetic — deliberately NOT the four columns summed
 *
 * ZCode is OpenAI-lineage: `cache_read_input_tokens` is a SUBSET of `input_tokens`, not a sibling of it.
 * Its own `computed_total_tokens` column is the proof, on real local rows:
 * ```
 * input 51062, output 583, cacheRead 1024 → computed_total 51645   ( = input + output )
 * input 17531, output  13, cacheRead 7680 → computed_total 17544   ( = input + output )
 * ```
 * Summing all four would have reported 52669 and 25224 — the second inflated by 44%. So the occupancy is
 * `input_tokens + output_tokens`, which is exactly what ZCode itself calls the total.
 *
 * (Contrast OpenCode, whose `tokens.total` DOES equal input+output+cache — `OpenCodeTranscriptScanner`
 * adds all four for that reason. The two backends genuinely differ; neither convention is portable.)
 */
class ZCodeResumeContextTest {

    /** The real `model_usage` columns, in the order the local 3.7.6 store declares them. */
    private fun database(rows: String): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { st ->
            st.execute(
                "CREATE TABLE model_usage(id TEXT, session_id TEXT, query_source TEXT, provider_id TEXT," +
                    "model_id TEXT, status TEXT, started_at INTEGER, completed_at INTEGER," +
                    "input_tokens INTEGER, output_tokens INTEGER," +
                    "cache_creation_input_tokens INTEGER, cache_read_input_tokens INTEGER)",
            )
            rows.trimIndent().lines().filter { it.isNotBlank() }.forEach(st::execute)
        }
    }

    private fun seed(rows: String, sid: String = SID): Long? =
        database(rows).use { ZCodeTranscriptScanner.resumeContextTokensFrom(it, sid) }

    // ---- 1. a brand-new session ----

    /** No request has completed, so nothing is occupied. Null = no readout; 0 would render as a confident
     *  0% on a session we simply have not measured. */
    @Test
    fun a_session_with_no_completed_request_seeds_nothing() {
        assertNull(seed(""))
    }

    /** A failed request writes an all-zero row. That is an absence of measurement, not an empty window. */
    @Test
    fun an_all_zero_error_row_is_not_an_empty_window() {
        assertNull(
            seed("INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5','error',1000,1100,0,0,0,0)"),
        )
    }

    // ---- 2. history ----

    /** The whole point: a reopened session shows its occupancy before it runs anything. */
    @Test
    fun the_newest_completed_main_turn_seeds_the_occupancy() {
        assertEquals(
            51_645L,
            seed("INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','kimi-k3','completed',1000,1200,51062,583,0,1024)"),
        )
    }

    /**
     * The cache column is inside `input_tokens`; adding it back is the 44% inflation this test exists to
     * catch. 17531 + 13 = 17544, which is the `computed_total_tokens` ZCode wrote for this very row.
     */
    @Test
    fun the_cached_prefix_is_already_inside_input_and_is_not_added_again() {
        assertEquals(
            17_544L,
            seed("INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5.3','completed',1000,1200,17531,13,0,7680)"),
        )
    }

    /** LAST wins, not the largest ever seen: after a compaction the window really does hold less. */
    @Test
    fun a_compacted_session_seeds_the_smaller_new_occupancy_not_the_historic_peak() {
        assertEquals(
            12_300L,
            seed(
                """
                INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5','completed',1000,1100,180000,2000,0,0)
                INSERT INTO model_usage VALUES('u2','$SID','main_turn','p','glm-5','completed',2000,2100,12000,300,0,0)
                """,
            ),
        )
    }

    /** ZCode names the session with a separate, tiny request against a smaller model. It is not the
     *  conversation, so it may never become the conversation's occupancy — even when it is the newest row. */
    @Test
    fun the_session_title_side_request_never_becomes_the_occupancy() {
        assertEquals(
            1_100L,
            seed(
                """
                INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5','completed',1000,1100,1000,100,0,0)
                INSERT INTO model_usage VALUES('u2','$SID','session_title','p','glm-5-air','completed',2000,2100,42,7,0,0)
                """,
            ),
        )
    }

    /** A sub-agent runs as its OWN ZCode session row, so its spend must not leak into the parent's gauge. */
    @Test
    fun another_sessions_usage_is_not_borrowed() {
        assertNull(
            seed("INSERT INTO model_usage VALUES('u1','sess_someone_else','main_turn','p','glm-5','completed',1000,1100,9999,1,0,0)"),
        )
    }

    /** A request still in flight has not measured anything yet; the previous completed one is still the
     *  truth about the window. */
    @Test
    fun a_running_request_does_not_displace_the_last_completed_measurement() {
        assertEquals(
            1_100L,
            seed(
                """
                INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5','completed',1000,1100,1000,100,0,0)
                INSERT INTO model_usage VALUES('u2','$SID','main_turn','p','glm-5','running',2000,NULL,0,0,0,0)
                """,
            ),
        )
    }

    /** A cancelled turn that already burned tokens really did fill the window — the user's next prompt
     *  carries all of it. Status is not the gate; a measurement of zero is. */
    @Test
    fun a_cancelled_turn_that_already_burned_tokens_still_counts() {
        assertEquals(
            5_050L,
            seed("INSERT INTO model_usage VALUES('u1','$SID','main_turn','p','glm-5','cancelled',1000,1100,5000,50,0,0)"),
        )
    }

    private companion object {
        const val SID = "sess_fb06e430-da40-4b1c-82cd-41cd092b84b8"
    }
}
