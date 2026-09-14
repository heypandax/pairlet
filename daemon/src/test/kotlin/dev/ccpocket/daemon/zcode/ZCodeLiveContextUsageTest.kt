package dev.ccpocket.daemon.zcode

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.TokenUsage
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #320, the LIVE half of the ZCode occupancy fix — and the reason the disk seed
 * ([ZCodeResumeContextTest]) is allowed to be the number it is.
 *
 * ZCode is OpenAI-lineage: `cacheReadTokens` is a SUBSET of `inputTokens`. [TokenUsage] is Claude-shaped
 * and treats its four columns as DISJOINT ([TokenUsage.contextTokens] adds all of them), so handing ZCode's
 * cache counters straight across double-counts the cached prefix — on a real local row, 17531 + 13 + 7680
 * reported 25224 tokens of occupancy for a turn ZCode itself totalled at 17544. A 44% overstatement, and
 * the resume seed and the live readout would have disagreed by exactly that much.
 *
 * The fix is the one [dev.ccpocket.daemon.dsh.DshBackend] already applies to `usage_update`: when the
 * backend's own number is already a total, it rides the SINGLE context-bearing column and the cache
 * columns stay null. Null here means "this backend does not report a disjoint cache split", which is the
 * truth — not "the cache was empty".
 *
 * ⚠️ This is deliberately NOT applied to [ZCodeTranscriptScanner.usageTurns], the billing feed: spend and
 * occupancy are different questions and the usage page's columns are its own contract.
 */
class ZCodeLiveContextUsageTest {

    private suspend fun ready(): ZCodeBackend {
        val backend = ZCodeBackend(null, executable = { Path.of("/fake/zcode") })
        backend.attach(AgentIo({}, {}), AgentSpec(Path.of("/repo")))
        backend.parse(
            """{"id":"ccp-1","result":{"session":{"sessionId":"sess_1","model":{"providerId":"zai","modelId":"glm-5"},"workspace":{"workspacePath":"/repo","workspaceKey":"/repo"}}}}""",
        )
        return backend
    }

    /** The exact envelope the 3.7.6 app-server sends after each provider call, with the real numbers from
     *  the local store's newest `model_usage` row. */
    private fun modelRequestCompleted(input: Long, output: Long, cacheRead: Long, cacheWrite: Long) =
        """{"method":"session/event","params":{"type":"session.updated","payload":{"type":"model_request_completed",""" +
            """"usage":{"inputTokens":$input,"outputTokens":$output,"cacheReadTokens":$cacheRead,"cacheWriteTokens":$cacheWrite}},"sessionId":"sess_1"}}"""

    @Test
    fun `a per-call usage reports the occupancy zcode itself totals, not the cached prefix twice`() = runBlocking {
        val backend = ready()
        val events = backend.parse(modelRequestCompleted(input = 17_531, output = 13, cacheRead = 7_680, cacheWrite = 0))
        val usage = events.filterIsInstance<AgentEvent.AssistantUsage>().single()

        assertEquals(17_531L, usage.inputTokens, "inputTokens is already the whole prompt, cached prefix included")
        assertNull(usage.cacheReadInputTokens, "a subset of inputTokens must not also ride a disjoint column")
        assertNull(usage.cacheCreationInputTokens)
        assertEquals(13L, usage.outputTokens)

        // the number the phone finally renders — and the same one [ZCodeResumeContextTest] seeds off disk
        val rendered = TokenUsage(
            usage.inputTokens, usage.outputTokens ?: 0,
            usage.cacheCreationInputTokens, usage.cacheReadInputTokens,
        ).contextTokens
        assertEquals(17_544L, rendered, "zcode's own computed_total_tokens for this row")
    }

    /** The seed and the first live turn of the SAME measurement must land on one number: a resumed session
     *  that visibly jumps the moment it answers is the bug report, not the fix. */
    @Test
    fun `the live readout equals the disk seed for the same measurement`() = runBlocking {
        val backend = ready()
        val usage = backend.parse(modelRequestCompleted(input = 51_062, output = 583, cacheRead = 1_024, cacheWrite = 0))
            .filterIsInstance<AgentEvent.AssistantUsage>().single()
        val live = TokenUsage(
            usage.inputTokens, usage.outputTokens ?: 0,
            usage.cacheCreationInputTokens, usage.cacheReadInputTokens,
        ).contextTokens

        val fromDisk = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE model_usage(id TEXT, session_id TEXT, query_source TEXT, provider_id TEXT," +
                        "model_id TEXT, status TEXT, started_at INTEGER, completed_at INTEGER," +
                        "input_tokens INTEGER, output_tokens INTEGER," +
                        "cache_creation_input_tokens INTEGER, cache_read_input_tokens INTEGER)",
                )
                st.execute(
                    "INSERT INTO model_usage VALUES('u1','sess_1','main_turn','p','kimi-k3','completed',1000,1200,51062,583,0,1024)",
                )
            }
            ZCodeTranscriptScanner.resumeContextTokensFrom(db, "sess_1")
        }
        assertEquals(live, fromDisk)
    }
}
