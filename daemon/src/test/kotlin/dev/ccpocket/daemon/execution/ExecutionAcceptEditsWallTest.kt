package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.agent.PermissionBridge
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.claude.ClaudeLauncher
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #367 security review HIGH-1 — the acceptEdits hole, closed and pinned.
 *
 * THE BUG: under an `acceptEdits` ceiling the daemon used to launch the CLI in its NATIVE acceptEdits mode
 * (Claude `--permission-mode acceptEdits`, Codex `approvalPolicy=never`). Both apply in-workspace Edit/Write
 * THEMSELVES and emit no ControlRequest — and every wall a remote run has ([ExecutionSandbox], pathScope)
 * hangs off that request. So a remote caller could write `.claude/settings.json`, a git hook or `CLAUDE.md`
 * with no check whatsoever: persistent code execution on the owner's machine.
 *
 * It survived review because `ExecutionSandboxTest` called the wall function DIRECTLY. The wall was correct;
 * it was simply never reached. So this file deliberately tests the two halves the real path is made of:
 *
 *  1. the LAUNCH ARGUMENTS a remote-execution session is actually started with — via [ClaudeLauncher.buildArgs]
 *     and [CodexBackend.approvalPolicyFor], the same functions the process spawn uses;
 *  2. the BRIDGE decision for those same tool calls once they DO come back as requests.
 *
 * Neither half alone is the fix, and neither half alone would have caught the bug.
 */
class ExecutionAcceptEditsWallTest {

    private val ws = createTempDirectory("ccp-exec-ae").toFile().canonicalFile
    private val stateDir = createTempDirectory("ccp-exec-state").toFile().canonicalFile

    private data class Resp(val askId: String, val allow: Boolean, val deny: String?)

    /** A bridge configured exactly as a remote-execution conversation configures it. */
    private fun bridge(
        ceiling: PermissionMode,
        emitted: MutableList<Frame>,
        responses: MutableList<Resp>,
        scope: CoroutineScope,
    ) = PermissionBridge(
        "c1",
        // the LAUNCH mode: DEFAULT, because that is what Conversation.launchMode() forces for a remote run
        PermissionMode.DEFAULT,
        ApprovalCoordinator(scope), { emitted += it }, mutableSetOf(),
        respond = { id, allow, _, _, _, deny -> responses += Resp(id, allow, deny) },
        pathScope = listOf(ws.path),
        workdir = ws.path,
        executionSession = true,
        executionCeiling = ceiling,
        daemonStateDir = stateDir.path,
    )

    private fun write(path: String) = AgentEvent.ControlRequest("r", "Write", buildJsonObject { put("file_path", path) })

    // ------------------------------------------------------------------ 1. launch arguments

    @Test
    fun a_remote_run_is_never_launched_in_the_cli_s_native_accept_edits_mode() {
        // THE regression. Both backends map their "don't ask about edits" state off AgentSpec.mode, so the
        // fix is that a remote-execution conversation never puts ACCEPT_EDITS there — asserted on the very
        // functions that build the process invocation.
        // the rule Conversation.launchMode() applies, fed into the very functions that build the process
        // invocation — so a regression in the rule turns THIS red, not just a unit test of the rule
        val launched = ExecutionSandbox.launchMode(PermissionMode.ACCEPT_EDITS, remoteExecution = true)
        val remote = AgentSpec(ws.toPath(), mode = launched, cleanRoom = true)
        val args = ClaudeLauncher.buildArgs(remote)
        val modeArg = args[args.indexOf("--permission-mode") + 1]
        assertEquals("default", modeArg, "a remote run must be launched in DEFAULT so every edit is a request")
        assertNotEquals("acceptEdits", modeArg)
        // Codex's twin of the same setting, through the same rule
        assertNotEquals(
            "never", CodexBackend.approvalPolicyFor(launched),
            "approvalPolicy=never is Codex's acceptEdits — it must not be how a remote run starts",
        )
        // a future widening of the ceiling set must not sneak a native full-auto launch in either
        assertEquals(PermissionMode.DEFAULT, ExecutionSandbox.launchMode(PermissionMode.BYPASS_PERMISSIONS, true))
        // …while a LOCAL session is untouched: the rule only ever bends a REMOTE run's launch
        assertEquals(PermissionMode.ACCEPT_EDITS, ExecutionSandbox.launchMode(PermissionMode.ACCEPT_EDITS, false))
        assertEquals(PermissionMode.PLAN, ExecutionSandbox.launchMode(PermissionMode.PLAN, true))
        // …and the control that shows these assertions can fail: the NATIVE mapping really is the unsafe one
        assertEquals("acceptEdits", ClaudeLauncher.buildArgs(AgentSpec(ws.toPath(), mode = PermissionMode.ACCEPT_EDITS))
            .let { it[it.indexOf("--permission-mode") + 1] })
        assertEquals("never", CodexBackend.approvalPolicyFor(PermissionMode.ACCEPT_EDITS))
    }

    // ------------------------------------------------------------------ 2. the bridge decision

    @Test
    fun under_an_accept_edits_ceiling_a_workspace_file_is_auto_approved(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        bridge(PermissionMode.ACCEPT_EDITS, emitted, responses, scope)
            .onControlRequest(write(File(ws, "src/main.kt").path))
        // the ceiling's semantics survive: no card for an ordinary in-workspace edit
        assertTrue(emitted.none { it is PermissionAsk }, "acceptEdits must not pester the owner: $emitted")
        assertEquals(1, responses.size)
        assertTrue(responses.single().allow, "an ordinary workspace write is allowed under acceptEdits")
        scope.cancel()
    }

    @Test
    fun under_an_accept_edits_ceiling_agent_config_and_hooks_are_still_denied(): Unit = runBlocking {
        // exactly the paths the native mode would have written unchecked
        val forbidden = listOf(
            File(ws, ".claude/settings.json").path,
            File(ws, ".claude/settings.local.json").path,
            File(ws, ".git/hooks/pre-commit").path,
            File(ws, "CLAUDE.md").path,
            File(ws, "AGENTS.md").path,
            File(stateDir, "local-control-token").path,
        )
        for (path in forbidden) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
            bridge(PermissionMode.ACCEPT_EDITS, emitted, responses, scope).onControlRequest(write(path))
            assertTrue(emitted.none { it is PermissionAsk }, "$path: refused outright, never a card")
            assertEquals(1, responses.size, "$path")
            assertFalse(responses.single().allow, "$path must be DENIED even under an acceptEdits ceiling")
            assertNull(responses.single().deny?.takeIf { path in it }, "$path: the reason must not echo the path back")
            scope.cancel()
        }
    }

    @Test
    fun the_shim_never_auto_approves_bash_or_an_out_of_scope_write(): Unit = runBlocking {
        // Bash targets are not statically knowable, so no wall above the shim can have vetted them —
        // acceptEdits has never meant "run any command"
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        bridge(PermissionMode.ACCEPT_EDITS, emitted, responses, scope).onControlRequest(
            AgentEvent.ControlRequest("r", "Bash", buildJsonObject { put("command", "echo hi") }),
        )
        assertTrue(emitted.any { it is PermissionAsk }, "Bash still goes to the owner's card: $emitted")
        assertTrue(responses.isEmpty(), "…and is NOT auto-approved")
        scope.cancel()

        val s2 = CoroutineScope(Dispatchers.Unconfined)
        val e2 = mutableListOf<Frame>(); val r2 = mutableListOf<Resp>()
        bridge(PermissionMode.ACCEPT_EDITS, e2, r2, s2)
            .onControlRequest(write(File(ws.parentFile, "outside.txt").path))
        assertTrue(e2.none { it is PermissionAsk })
        assertFalse(r2.single().allow, "pathScope still bounds the shim")
        s2.cancel()
    }

    @Test
    fun a_default_ceiling_still_asks_for_every_write(): Unit = runBlocking {
        // the shim is keyed on the CEILING, not on being an execution session: DEFAULT keeps its card
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        bridge(PermissionMode.DEFAULT, emitted, responses, scope)
            .onControlRequest(write(File(ws, "src/main.kt").path))
        assertTrue(emitted.any { it is PermissionAsk }, "DEFAULT must still surface a card: $emitted")
        assertTrue(responses.isEmpty())
        scope.cancel()
    }
}
