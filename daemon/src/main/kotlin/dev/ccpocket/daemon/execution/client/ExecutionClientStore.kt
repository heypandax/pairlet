package dev.ccpocket.daemon.execution.client

import dev.ccpocket.daemon.review.ReviewFiles
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Issue #367 G1 — the SOURCE's local MIRROR of runs it submitted (`~/.cc-pocket/execution-client-runs.json`,
 * 0600, atomic). Deliberately NOT a second ledger:
 *
 *  - the TARGET's [dev.ccpocket.daemon.execution.RunJournal] is the only authority on what a run did;
 *  - this file exists so a retry can reuse its requestId, a `result` can resume from its cursor, and a
 *    `status` can name a run the caller forgot the id of. Nothing here ever overrides a target's answer;
 *  - it holds NO prompt and NO output — only ids, the last state the target reported, and the cursor.
 *    A local mirror of someone else's private task text would be a second place for it to leak from.
 */
@Serializable
data class ClientRun(
    val runId: String,
    val grantId: String,
    val requestId: String,
    /** The last state string the TARGET reported. Opaque here on purpose: authority lives over there. */
    val state: String,
    /** Byte cursor of the output already fetched, so `result` resumes instead of re-reading. */
    val cursor: String? = null,
    val updatedAt: Long = 0,
)

class ExecutionClientStore private constructor(private val file: File?) {

    @Serializable
    private data class Stored(val v: Int = VERSION, val runs: List<ClientRun> = emptyList())

    private val lock = Any()
    private var runs: List<ClientRun> = emptyList()

    fun all(): List<ClientRun> = synchronized(lock) { runs }

    fun byId(runId: String): ClientRun? = synchronized(lock) { runs.firstOrNull { it.runId == runId } }

    fun byRequest(grantId: String, requestId: String): ClientRun? = synchronized(lock) {
        runs.firstOrNull { it.grantId == grantId && it.requestId == requestId }
    }

    /** Upsert by runId, newest first, bounded. A failed write only loses a convenience mirror. */
    fun put(run: ClientRun): ClientRun = synchronized(lock) {
        val next = (listOf(run) + runs.filterNot { it.runId == run.runId }).take(MAX_ROWS)
        if (persist(next)) runs = next
        run
    }

    private fun persist(next: List<ClientRun>): Boolean {
        val f = file ?: return true
        return ReviewFiles.write(f, PocketJson.encodeToString(Stored.serializer(), Stored(runs = next)))
    }

    companion object {
        const val VERSION = 1
        private const val MAX_ROWS = 500

        fun defaultPath(): File = ReviewFiles.path("execution-client-runs.json")

        fun load(file: File): ExecutionClientStore = ExecutionClientStore(file).apply {
            val stored = ReviewFiles.read(file) { PocketJson.decodeFromString(Stored.serializer(), it) }
            runs = stored?.takeIf { it.v == VERSION }?.runs ?: emptyList()
        }

        fun inMemory(): ExecutionClientStore = ExecutionClientStore(null)
    }
}
