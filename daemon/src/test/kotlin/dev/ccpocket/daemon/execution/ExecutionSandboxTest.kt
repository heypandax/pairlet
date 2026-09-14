package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.codex.CodexBackend
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #367 G1 SECURITY — the extra walls a REMOTE-driven session gets. Every case here is deliberately set up
 * INSIDE the granted workspace, so the existing pathScope containment would happily allow it: what is
 * under test is the execution-only deny-list, not the guest wall it sits on top of.
 *
 * The negative control matters as much as the positives: an ordinary (non-execution) session must be
 * completely unaffected, or this change is a behaviour regression for every guest and bridge.
 */
class ExecutionSandboxTest {

    private val ws = createTempDirectory("ccp-exec-wall").toFile().canonicalFile
    /** The daemon's state dir, planted INSIDE the workspace so only the new wall can catch it. */
    private val stateDir = File(ws, "home/.cc-pocket").apply { mkdirs() }.canonicalFile

    @AfterTest
    fun cleanup() { ws.deleteRecursively() }

    private data class Resp(val allow: Boolean, val deny: String?)

    private fun bridge(
        scope: CoroutineScope,
        emitted: MutableList<Frame>,
        responses: MutableList<Resp>,
        execution: Boolean = true,
        mode: PermissionMode = PermissionMode.DEFAULT,
    ) = PermissionBridge(
        "c1", mode, ApprovalCoordinator(scope), { emitted += it }, mutableSetOf(),
        respond = { _, allow, _, _, _, deny -> responses += Resp(allow, deny) },
        pathScope = listOf(ws.path),
        workdir = ws.path,
        executionSession = execution,
        daemonStateDir = stateDir.path,
    )

    private fun req(tool: String, key: String, value: String) =
        AgentEvent.ControlRequest("r", tool, buildJsonObject { put(key, value) })

    /** Run one tool call and report what the wall did. */
    private fun verdict(ev: AgentEvent.ControlRequest, execution: Boolean = true, mode: PermissionMode = PermissionMode.DEFAULT): Pair<List<Frame>, List<Resp>> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        runBlocking { bridge(scope, emitted, responses, execution, mode).onControlRequest(ev) }
        scope.cancel()
        return emitted to responses
    }

    private fun assertHardDenied(ev: AgentEvent.ControlRequest, code: String) {
        val (emitted, responses) = verdict(ev)
        assertTrue(emitted.isEmpty(), "${ev.toolName} must not even SURFACE an ask — no owner is watching a remote run")
        val r = responses.single()
        assertFalse(r.allow)
        assertTrue(r.deny!!.contains(code), "expected the deny to name `$code`, got: ${r.deny}")
    }

    private fun assertReachesTheOwner(ev: AgentEvent.ControlRequest) {
        val (emitted, responses) = verdict(ev)
        assertTrue(emitted.single() is PermissionAsk, "${ev.toolName} should have gone to the ordinary card")
        assertTrue(responses.isEmpty())
    }

    // ---------------------------------------------------------------- the daemon's own state

    @Test
    fun `reading the daemon state directory is refused for every tool, even from inside the workspace`() {
        val token = File(stateDir, "local-control-token").path
        assertHardDenied(req("Read", "file_path", token), ExecutionSandbox.DENY_DAEMON_STATE)
        assertHardDenied(req("Grep", "path", stateDir.path), ExecutionSandbox.DENY_DAEMON_STATE)
        assertHardDenied(req("Write", "file_path", File(stateDir, "identity.json").path), ExecutionSandbox.DENY_DAEMON_STATE)
    }

    @Test
    fun `a Bash command that names the daemon state directory is refused before the card`() {
        assertHardDenied(
            AgentEvent.ControlRequest("r", "Bash", buildJsonObject { put("command", "cat ~/.cc-pocket/local-control-token") }),
            ExecutionSandbox.DENY_DAEMON_STATE,
        )
        // …and an ordinary command still reaches the owner's card rather than being swallowed
        assertReachesTheOwner(AgentEvent.ControlRequest("r", "Bash", buildJsonObject { put("command", "make test") }))
    }

    // ---------------------------------------------------------------- agent configuration

    @Test
    fun `agent config directories and settings files are refused in BOTH directions`() {
        for (path in listOf(".claude/settings.json", ".codex/config.toml", ".opencode/x", "sub/settings.local.json", "sub/.mcp.json")) {
            assertHardDenied(req("Read", "file_path", File(ws, path).path), ExecutionSandbox.DENY_AGENT_CONFIG)
            assertHardDenied(req("Write", "file_path", File(ws, path).path), ExecutionSandbox.DENY_AGENT_CONFIG)
        }
    }

    @Test
    fun `a git hook is refused, and the case of the segment does not matter`() {
        assertHardDenied(req("Write", "file_path", File(ws, ".git/hooks/pre-commit").path), ExecutionSandbox.DENY_AGENT_CONFIG)
        assertHardDenied(req("Read", "file_path", File(ws, ".GIT/HOOKS/pre-commit").path), ExecutionSandbox.DENY_AGENT_CONFIG)
    }

    // ---------------------------------------------------------------- owner-executed prose

    @Test
    fun `writing an instruction file the owner's next session auto-loads is refused, reading it is not`() {
        // a remote caller that can write CLAUDE.md owns a standing prompt-injection seat in the owner's
        // own later sessions — but READING project instructions is ordinary work
        assertHardDenied(req("Write", "file_path", File(ws, "CLAUDE.md").path), ExecutionSandbox.DENY_OWNER_EXECUTION)
        assertHardDenied(req("Edit", "file_path", File(ws, "docs/AGENTS.md").path), ExecutionSandbox.DENY_OWNER_EXECUTION)
        assertHardDenied(req("Write", "file_path", File(ws, ".envrc").path), ExecutionSandbox.DENY_OWNER_EXECUTION)
        assertReachesTheOwner(req("Read", "file_path", File(ws, "CLAUDE.md").path))
    }

    // ---------------------------------------------------------------- no regression, no escape

    @Test
    fun `an ordinary file inside the workspace still reaches the ordinary card`() {
        assertReachesTheOwner(req("Read", "file_path", File(ws, "src/Main.kt").path))
        assertReachesTheOwner(req("Write", "file_path", File(ws, "src/Main.kt").path))
    }

    @Test
    fun `a tilde target cannot slip past by resolving inside the workspace`() {
        // PathScope does not expand `~`, so `~/.cc-pocket/x` used to canonicalise to <ws>/~/.cc-pocket/x —
        // inside the scope. The wall expands it itself before deciding.
        assertHardDenied(req("Read", "file_path", "~/.cc-pocket/identity.json"), ExecutionSandbox.DENY_DAEMON_STATE)
    }

    @Test
    fun `a NON-execution session is completely unaffected by the new wall`() {
        for (path in listOf(".claude/settings.json", ".git/hooks/pre-commit", "CLAUDE.md")) {
            val (emitted, responses) = verdict(req("Write", "file_path", File(ws, path).path), execution = false)
            assertTrue(responses.isEmpty(), "a guest/bridge session must keep its previous behaviour for $path")
            assertTrue(emitted.single() is PermissionAsk)
        }
    }

    @Test
    fun `the wall holds under every ceiling a grant may carry, including acceptEdits`() {
        for (mode in ExecutionPolicy.ALLOWED_CEILINGS) {
            val (emitted, responses) = verdict(req("Write", "file_path", File(stateDir, "identity.json").path), mode = mode)
            assertTrue(emitted.isEmpty(), "mode $mode must not surface an ask for the daemon state dir")
            assertFalse(responses.single().allow, "mode $mode must not allow a write into the daemon state dir")
        }
    }

    // ---------------------------------------------------------------- one hop

    @Test
    fun `the child environment loses every handle on a Pairlet daemon and keeps everything else`() {
        val env = mutableMapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/Users/someone",
            "CC_POCKET_IDENTITY" to "/Users/someone/.cc-pocket/identity.json",
            "CC_POCKET_CLAUDE_BIN" to "/tmp/evil-claude",
            "cc_pocket_codex_bin" to "/tmp/evil-codex",
            "PAIRLET_SOMETHING" to "x",
            "ANTHROPIC_BASE_URL" to "https://example.test",
        )
        ExecutionSandbox.stripChildEnv(env)
        assertEquals(setOf("PATH", "HOME", "ANTHROPIC_BASE_URL"), env.keys)
    }

    // ---------------------------------------------------------------- codex

    @Test
    fun `no ceiling a grant may carry can put Codex in danger-full-access`() {
        for (mode in ExecutionPolicy.ALLOWED_CEILINGS) {
            assertNotEquals("danger-full-access", CodexBackend.sandboxFor(mode).flat, "ceiling $mode")
        }
        // and the one mode that would is structurally unreachable for a grant
        assertFalse(PermissionMode.BYPASS_PERMISSIONS in ExecutionPolicy.ALLOWED_CEILINGS)
        assertEquals("read-only", CodexBackend.sandboxFor(PermissionMode.PLAN).flat)
    }

    // ---------------------------------------------------------------- the predicate itself

    @Test
    fun `the origin predicate recognises exactly the execution prefix`() {
        assertTrue(RunService.isExecutionOrigin(RunService.originOf("xg_abcdefgh")))
        assertFalse(RunService.isExecutionOrigin("feishu-bot"))
        assertFalse(RunService.isExecutionOrigin(null))
        assertNull(ExecutionSandbox.forbidden("Read", File(ws, "a.txt").path, stateDir.path))
    }
}
