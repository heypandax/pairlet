package dev.ccpocket.daemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AgentProcess.shutdown] must give the CLI a real EOF-driven flush window before killing it
 * (issue #101). These drive REAL short-lived children through the actual ladder:
 *
 *  - `cat` reads stdin and exits 0 on EOF — stands in for a well-behaved stream-json CLI that
 *    stops itself when its stdin closes. Its exit code (0) proves it left via EOF, not a signal.
 *  - a SIGTERM-trapping shell loop stands in for a wedged child: it ignores both the stdin EOF and
 *    `destroy()`'s SIGTERM, so ONLY the SIGKILL fallback can end it. Exit code 137 (128+9) proves
 *    the full EOF -> SIGTERM -> SIGKILL ladder ran and terminated in bounded time.
 *
 * Unix-gated: `cat` / `sh` and the signal-derived exit codes (137 = SIGKILL, 143 = SIGTERM) are
 * POSIX. The Windows-specific hazard #101 is really about — `Process.destroy()` mapping to
 * `TerminateProcess` (== `destroyForcibly()`, uncatchable, no flush), so the EOF wait is the ONLY
 * graceful window and `taskkill /T /F` must stay on the force path — cannot be exercised off a real
 * Windows host; it is covered by [AgentProcess.shutdown]'s platform branches + KDoc and must be
 * verified on Windows by hand (see the issue's manual-verification steps).
 */
class AgentProcessShutdownTest {

    private fun proc(vararg cmd: String, body: suspend (AgentProcess) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = AgentProcess.start(ProcessBuilder(*cmd), scope)
            runBlocking { withTimeout(15_000) { body(p) } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `EOF lets a well-behaved child self-exit and it is never signalled`() = proc("cat") { p ->
        val start = System.currentTimeMillis()
        // Generous EOF grace; `cat` exits on its stdin EOF in milliseconds, so shutdown must return
        // FAR under that budget (early `waitFor` return) — never reaching destroy()/destroyForcibly().
        p.shutdown(eofGraceMs = 5_000, termGraceMs = 2_000, forceGraceMs = 2_000)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(0, p.exitCode(), "cat should exit 0 via stdin EOF, not be killed by a signal")
        assertTrue(elapsed < 3_000, "clean EOF exit must return early, not burn the grace budget (was ${elapsed}ms)")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a wedged child that ignores EOF and SIGTERM is force-killed within the bounded budget`() =
        // Ignores stdin (never reads it, so EOF is a no-op) AND traps SIGTERM: only SIGKILL ends it.
        proc("sh", "-c", "trap '' TERM; while true; do sleep 0.2; done") { p ->
            val start = System.currentTimeMillis()
            p.shutdown(eofGraceMs = 400, termGraceMs = 400, forceGraceMs = 1_000)
            val elapsed = System.currentTimeMillis() - start

            val code = p.exitCode()
            assertNotNull(code, "wedged child must be dead after shutdown (force-kill fallback fired)")
            // 137 = 128 + SIGKILL(9): the EOF wait lapsed, SIGTERM(143) was trapped, only SIGKILL worked.
            assertEquals(137, code, "wedged child must die by SIGKILL, proving the full ladder ran")
            // Bounded: eof(400) + term(400) + force(1000) + slack — a stuck agent can't wedge shutdown.
            assertTrue(elapsed < 5_000, "shutdown of a wedged child must stay bounded (was ${elapsed}ms)")
        }
}
