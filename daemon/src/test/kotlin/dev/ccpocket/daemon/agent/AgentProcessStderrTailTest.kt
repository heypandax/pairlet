package dev.ccpocket.daemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stderr tail + its compression (issue #328). A single-slot "last line" was overwritten by every
 * later line, so a MULTI-line runtime death surfaced to the phone as only its final line — for a Node
 * crash that is the bare version footer, which says nothing at all. These drive REAL children whose
 * stderr is a captured-shape crash dump, so the buffer, its bounds and the compression are exercised
 * through the actual pump rather than a hand-fed list.
 *
 * Unix-gated for the same reason as [AgentProcessShutdownTest]: the fixtures ride `sh -c`.
 */
class AgentProcessStderrTailTest {

    /** Runs a child whose stderr is exactly [stderr] (written to a file, cat'd to fd 2 — no shell
     *  quoting or `echo` escape-expansion differences between /bin/sh flavors). */
    private fun withStderr(stderr: String, body: suspend (AgentProcess) -> Unit) {
        val f = Files.createTempDirectory("ccp-stderr-tail").resolve("err.txt").apply { writeText(stderr) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = AgentProcess.start(ProcessBuilder("sh", "-c", "cat '${f.absolutePathString()}' 1>&2; exit 1"), scope)
            runBlocking {
                withTimeout(15_000) {
                    p.awaitExit() // also waits for the stderr pump to reach EOF
                    body(p)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    // Shape of a real Node fatal dump: frame header, the throw, the actual cause, stack, the error
    // object's fields, then the version footer that used to be ALL the phone got.
    private val nodeCrash = """
        node:internal/fs/utils:355
            throw err;
            ^
        Error: ENOENT: no such file or directory, open '/tmp/missing-session.json'
            at Object.openSync (node:fs:596:3)
            at readFileSync (node:fs:464:35)
          errno: -2,
          syscall: 'open',
          path: '/tmp/missing-session.json'
        }

        Node.js v24.16.0
    """.trimIndent() + "\n"

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a multi-line crash surfaces the real cause, not the version footer`() = withStderr(nodeCrash) { p ->
        // the pre-#328 behavior, kept as the documented meaning of lastStderr: the final line only
        assertEquals("Node.js v24.16.0", p.lastStderr)

        val diag = assertNotNull(p.stderrDiagnostic())
        assertTrue("ENOENT: no such file or directory" in diag, diag)
        assertTrue("missing-session.json" in diag, diag)
        // the footer is stripped, so what a human reads leads with the failure
        assertFalse("Node.js v24.16.0" in diag, diag)
        // and it starts at the first line that STATES a failure ("throw err;"), not at the frame header
        assertFalse("node:internal/fs/utils" in diag.lineSequence().first(), diag)
        assertTrue(diag.length <= 700, "default budget must bound the diagnostic (was ${diag.length})")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the tail is bounded to 50 lines of 400 chars`() {
        val long = "x".repeat(500)
        val stderr = (1..200).joinToString("\n") { "line$it-$long" } + "\n"
        withStderr(stderr) { p ->
            val tail = p.stderrTail()
            assertEquals(50, tail.size, "tail must keep only the newest 50 lines")
            assertTrue(tail.all { it.length <= 400 }, "every retained line must be truncated to 400 chars")
            // newest kept, oldest dropped
            assertTrue(tail.last().startsWith("line200-"), tail.last().take(20))
            assertTrue(tail.first().startsWith("line151-"), tail.first().take(20))
            // no error signal anywhere → the retained head leads, still inside the char budget
            val diag = assertNotNull(p.stderrDiagnostic())
            assertTrue(diag.startsWith("line151-"), diag.take(20))
            assertTrue(diag.length <= 700, "was ${diag.length}")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a footer-only tail falls back to the last line rather than nothing`() =
        // stripping must never leave the caller with LESS than the single line it used to show
        withStderr("Node.js v24.16.0\n") { p ->
            assertEquals("Node.js v24.16.0", p.stderrDiagnostic())
        }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a silent process has no diagnostic`() = withStderr("") { p ->
        assertTrue(p.stderrTail().isEmpty())
        assertNull(p.stderrDiagnostic())
        assertNull(p.lastStderr)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a marker buried above later output is still visible in the tail`() {
        // what the session-lock heal and the "Session not found" reset scan for — both used to read
        // only the last line and lost the marker to whatever the runtime printed after it
        val stderr = "Error: Session held-1 is currently running as a background agent (bg).\n" +
            "Use claude agents to find and attach to it.\n" +
            "Node.js v24.16.0\n"
        withStderr(stderr) { p ->
            assertTrue(p.stderrTail().any { "is currently running as a background agent" in it })
            assertEquals("Node.js v24.16.0", p.lastStderr) // last-line-only would have missed it
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `an explicit budget truncates the diagnostic`() = withStderr(nodeCrash) { p ->
        val diag = assertNotNull(p.stderrDiagnostic(maxChars = 40))
        assertEquals(40, diag.length)
    }
}
