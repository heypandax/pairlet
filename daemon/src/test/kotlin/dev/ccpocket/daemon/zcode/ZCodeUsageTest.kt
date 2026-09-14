package dev.ccpocket.daemon.zcode

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZCode usage extraction (issue #258). The fixture mirrors the REAL `model_usage` columns probed on the
 * local 3.7.6 store — including the `session_title` side request that `turn_usage` leaves out and the
 * all-zero `error` row a failed request writes.
 */
class ZCodeUsageTest {
    private fun database(rows: String): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { st ->
            st.execute(
                "CREATE TABLE model_usage(id TEXT, query_source TEXT, provider_id TEXT, model_id TEXT, status TEXT," +
                    "started_at INTEGER, completed_at INTEGER, input_tokens INTEGER, output_tokens INTEGER," +
                    "cache_creation_input_tokens INTEGER, cache_read_input_tokens INTEGER)",
            )
            rows.trimIndent().lines().filter { it.isNotBlank() }.forEach(st::execute)
        }
    }

    @Test
    fun `model requests carry qualified model and split tokens`() {
        val db = database(
            """
            INSERT INTO model_usage VALUES('u1','main_turn','anthropic','glm-5','completed',1000,1200,100,40,10,50)
            INSERT INTO model_usage VALUES('u2','session_title','anthropic','glm-5-air','completed',1300,1400,5,1,0,0)
            """,
        )
        val turns = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 0)

        assertEquals(2, turns.size, "the session_title side request is real spend and must count too")
        val main = turns.single { it.id == "u1" }
        assertEquals("anthropic/glm-5", main.model)
        assertEquals(1200L, main.whenEpochMs, "completed_at wins over started_at")
        // stored input 100 CONTAINS the 50 cache-read and 10 cache-write; the disjoint fresh-prompt
        // figure is therefore 40. See `the cached prefix lives inside input and is never billed twice`.
        assertEquals(40L, main.input)
        assertEquals(40L, main.output)
        assertEquals(10L, main.cacheCreation)
        assertEquals(50L, main.cacheRead)
    }

    @Test
    fun `zero-token error rows and out-of-window rows drop out`() {
        val db = database(
            """
            INSERT INTO model_usage VALUES('err','main_turn','anthropic','glm-5','error',1000,1100,0,0,0,0)
            INSERT INTO model_usage VALUES('old','main_turn','anthropic','glm-5','completed',10,20,999,999,0,0)
            INSERT INTO model_usage VALUES('keep','main_turn','anthropic','glm-5','completed',5000,5100,7,3,0,0)
            """,
        )
        val turns = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 500)
        assertEquals(listOf("keep"), turns.map { it.id })
    }

    @Test
    fun `a still-running request falls back to started_at and an unqualified model stays as-is`() {
        val db = database(
            """
            INSERT INTO model_usage VALUES('run','main_turn','zai','vendor/glm-5','running',7000,NULL,20,0,0,0)
            INSERT INTO model_usage VALUES('bare','main_turn',NULL,NULL,'completed',7000,7100,1,1,0,0)
            """,
        )
        val turns = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 0)
        val running = turns.single { it.id == "run" }
        assertEquals(7000L, running.whenEpochMs)
        assertEquals("vendor/glm-5", running.model, "an already-qualified model id is not re-prefixed")
        assertEquals("zcode", turns.single { it.id == "bare" }.model)
    }

    /**
     * ZCode is OpenAI-lineage: `cache_read_input_tokens` is a SUBSET of `input_tokens`, not a sibling of
     * it. [ZCodeTranscriptScanner.UsageTurn]'s four columns are DISJOINT by contract (UsageService adds
     * them for the day/model total and divides them for the cache-hit rate), so passing `input_tokens`
     * through raw counted the cached prefix twice and inflated ZCode spend.
     *
     * The rows below are verbatim from the local 3.7.6 store. Their own `computed_total_tokens` column is
     * the oracle, and it equalled `input + output` on all 29 rows present — never `input + output + cache`.
     */
    @Test
    fun `the cached prefix lives inside input and is never billed twice`() {
        // real row: input 17531, output 13, cacheWrite 0, cacheRead 7680 — ZCode wrote computed_total 17544
        val db = database(
            "INSERT INTO model_usage VALUES('u1','main_turn','zai','kimi-k3','completed',1000,1200,17531,13,0,7680)",
        )
        val turn = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 0).single()

        assertEquals(9_851L, turn.input, "input less the cached prefix it already contains (17531 - 7680)")
        assertEquals(13L, turn.output)
        assertEquals(7_680L, turn.cacheRead, "the split is PRESERVED — the usage page shows a cache-hit rate")
        assertEquals(0L, turn.cacheCreation)
        assertEquals(
            17_544L,
            turn.input + turn.output + turn.cacheCreation + turn.cacheRead,
            "the four disjoint columns must sum to ZCode's own computed_total_tokens for this row",
        )
    }

    /** The same containment on the worst row in the local store: a 97% overstatement, because at 43008
     *  cached tokens out of 44125 almost the whole prompt was being billed a second time. */
    @Test
    fun `a heavily cached turn is not billed at double its real size`() {
        // real row: input 44125, output 293, cacheRead 43008 — ZCode wrote computed_total 44418
        val db = database(
            "INSERT INTO model_usage VALUES('u1','main_turn','zai','kimi-k3','completed',1000,1200,44125,293,0,43008)",
        )
        val turn = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 0).single()
        assertEquals(
            44_418L,
            turn.input + turn.output + turn.cacheCreation + turn.cacheRead,
            "summing the raw columns would have reported 87426",
        )
        assertEquals(1_117L, turn.input, "44125 - 43008 fresh prompt tokens")
    }

    /**
     * A row whose cache counters exceed its own prompt is corrupt or from a store generation we do not
     * understand. Clamping at zero keeps the day's total finite and slightly high; a negative column would
     * silently CANCEL other real spend out of the same bucket.
     */
    @Test
    fun `an impossible cache split clamps at zero rather than going negative`() {
        val db = database(
            "INSERT INTO model_usage VALUES('u1','main_turn','zai','glm-5','completed',1000,1200,100,5,0,999)",
        )
        val turn = ZCodeTranscriptScanner.usageTurnsFrom(db, sinceEpochMs = 0).single()
        assertEquals(0L, turn.input)
        assertTrue(turn.input >= 0L && turn.output >= 0L)
    }

    @Test
    fun `a missing table degrades to an empty list, never throws`() {
        val empty = DriverManager.getConnection("jdbc:sqlite::memory:")
        assertTrue(ZCodeTranscriptScanner.usageTurns(sinceEpochMs = 0, conn = empty).isEmpty())
    }
}
