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
        assertEquals(100L, main.input)
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

    @Test
    fun `a missing table degrades to an empty list, never throws`() {
        val empty = DriverManager.getConnection("jdbc:sqlite::memory:")
        assertTrue(ZCodeTranscriptScanner.usageTurns(sinceEpochMs = 0, conn = empty).isEmpty())
    }
}
