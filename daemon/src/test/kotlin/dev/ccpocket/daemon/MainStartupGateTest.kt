package dev.ccpocket.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #130: the `run` startup gate — each agent CLI is optional on its own; only BOTH missing refuses. */
class MainStartupGateTest {
    private val claude = Path.of("/some/claude")
    private val codex = Path.of("/some/codex")
    private val zcode = Path.of("/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs")
    private val dsh = Path.of("/some/dsh")

    @Test
    fun both_missing_refuses_startup_with_actionable_message() {
        val msg = missingAgentsMessage(null, null, null)
        assertNotNull(msg)
        assertTrue("claude" in msg, msg)
        assertTrue("codex" in msg, msg)
        assertTrue("--claude-bin" in msg, msg)
        assertTrue("--codex-bin" in msg, msg)
        assertTrue("opencode" in msg, msg)
        assertTrue("zcode" in msg, msg)
        assertTrue("--zcode-bin" in msg, msg)
        assertTrue("dsh" in msg, msg)
        assertTrue("--dsh-bin" in msg, msg)
    }

    @Test
    fun codex_only_machine_starts() = assertNull(missingAgentsMessage(null, codex, null))

    @Test
    fun claude_only_machine_starts() = assertNull(missingAgentsMessage(claude, null, null))

    @Test
    fun zcode_only_machine_starts() = assertNull(missingAgentsMessage(null, null, null, null, zcode))

    /** A machine with ONLY DeepSeek Harness installed must boot (issue #255) — the same "each agent is
     *  optional on its own" rule the four before it get. */
    @Test
    fun dsh_only_machine_starts() = assertNull(missingAgentsMessage(null, null, null, null, null, dsh))

    @Test
    fun both_present_starts() = assertNull(missingAgentsMessage(claude, codex, null))
}
