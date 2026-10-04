package dev.ccpocket.daemon.shell

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** issue #100: the quick-terminal approval carried the same "silent 30s auto-deny + zombie card" defect as the
 *  agent bridge. On timeout it must now retire the phone's card (AskWithdrawn) as well as report the command
 *  denied. (Late-verdict feedback is the bridge's job — RequestRouter forwards an unclaimed shell verdict there.) */
class ShellServiceTest {
    @Test
    fun clear_rule_reaches_the_quick_terminal_store() = runBlocking {
        // §18.1 P1-7: "收紧" must clear THIS store too — a session rule here kept auto-running after the
        // chip's ClearAllowRule only cleared the conversation store
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = CopyOnWriteArrayList<Frame>()
        val coordinator = dev.ccpocket.daemon.approval.ApprovalCoordinator(scope)
        val shell = ShellService(scope, coordinator = coordinator, verdictTimeoutMs = 5_000)

        // approve once with session memory → the rule forms
        val first = scope.async { shell.run(RunShellCommand("c1", "git status", "/tmp"), PermissionMode.DEFAULT) { emitted += it } }
        kotlinx.coroutines.withTimeout(5_000) {
            while (emitted.filterIsInstance<PermissionAsk>().isEmpty()) delay(10)
        }
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        coordinator.onVerdict(dev.ccpocket.protocol.PermissionVerdict("c1", ask.askId, dev.ccpocket.protocol.Decision.ALLOW, remember = true))
        first.await()

        shell.clearRule("c1", ask.rule) // the tighten path
        emitted.clear()
        val second = scope.async { shell.run(RunShellCommand("c1", "git status", "/tmp"), PermissionMode.DEFAULT) { emitted += it } }
        kotlinx.coroutines.withTimeout(5_000) {
            while (emitted.filterIsInstance<PermissionAsk>().isEmpty()) delay(10)
        }
        val again = emitted.filterIsInstance<PermissionAsk>().single() // re-asks: the shadow rule is gone
        coordinator.onVerdict(dev.ccpocket.protocol.PermissionVerdict("c1", again.askId, dev.ccpocket.protocol.Decision.DENY))
        second.await()
        scope.cancel()
    }

    @Test
    fun session_rule_never_auto_runs_a_chained_command() = runBlocking {
        // audit 2026-10-04 M3: the quick terminal shares the two-token session rule, so a remembered
        // `echo hi` must not auto-run `echo hi && …` — chained lines re-ask; plain same-prefix lines still ride
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = CopyOnWriteArrayList<Frame>()
        val coordinator = dev.ccpocket.daemon.approval.ApprovalCoordinator(scope)
        val shell = ShellService(scope, coordinator = coordinator, verdictTimeoutMs = 5_000)
        val workdir = System.getProperty("java.io.tmpdir")

        // returns the ask if one was shown (then denied so nothing runs), or null if the command auto-ran
        suspend fun runOnce(command: String): PermissionAsk? {
            emitted.clear()
            val job = scope.async { shell.run(RunShellCommand("c1", command, workdir), PermissionMode.DEFAULT) { emitted += it } }
            kotlinx.coroutines.withTimeout(5_000) {
                while (emitted.none { it is PermissionAsk || it is ShellResult }) delay(10)
            }
            val ask = emitted.filterIsInstance<PermissionAsk>().firstOrNull()
            if (ask != null) {
                coordinator.onVerdict(dev.ccpocket.protocol.PermissionVerdict("c1", ask.askId, dev.ccpocket.protocol.Decision.DENY))
            }
            job.await()
            return ask
        }

        // seed the rule: approve `echo hi` with session memory
        val seedJob = scope.async { shell.run(RunShellCommand("c1", "echo hi", workdir), PermissionMode.DEFAULT) { emitted += it } }
        kotlinx.coroutines.withTimeout(5_000) { while (emitted.none { it is PermissionAsk }) delay(10) }
        val seed = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals("echo hi", seed.rule)
        coordinator.onVerdict(dev.ccpocket.protocol.PermissionVerdict("c1", seed.askId, dev.ccpocket.protocol.Decision.ALLOW, remember = true))
        seedJob.await()

        // harmless payloads only: should the gate regress, the test still runs nothing destructive
        val chained = listOf(
            "echo hi && echo chained", "echo hi ; echo chained", "echo hi | cat", "echo hi \$(echo chained)",
            "echo hi `echo chained`", "echo hi\necho chained", "echo hi > /dev/null", "echo hi ;echo chained",
            "echo hi&&echo chained", "echo hi;echo chained", "echo hi|cat",
        )
        val autoRun = chained.filter { runOnce(it) == null }
        assertTrue(autoRun.isEmpty(), "quick-terminal session rule auto-ran chained commands: ${autoRun.map { it.replace("\n", "\\n") }}")

        // a plain same-prefix command still rides the remembered rule — no card
        assertEquals(null, runOnce("echo hi there"), "plain same-prefix command must auto-run")
        assertEquals(0, emitted.filterIsInstance<ShellResult>().single().exitCode)
        scope.cancel()
    }

    @Test
    fun approval_timeout_withdraws_the_card_and_reports_denied() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = CopyOnWriteArrayList<Frame>() // gate completes on a background delay thread
        val shell = ShellService(scope, verdictTimeoutMs = 50)

        // default mode → the command must be approved; we never answer, so it times out
        scope.launch { shell.run(RunShellCommand("c1", "rm -rf build", "/tmp"), PermissionMode.DEFAULT) { emitted += it } }
        delay(600)

        // the card is shown carrying its real window, then retired with TIMED_OUT
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals("Bash", ask.tool)
        assertNotNull(ask.timeoutSec) // the phone can align its local fallback (was absent pre-#100)
        val withdrawn = emitted.filterIsInstance<AskWithdrawn>().single()
        assertEquals(ask.askId, withdrawn.askId)
        assertEquals(AskWithdrawnReason.TIMED_OUT, withdrawn.reason)
        // and the command is reported denied — never run
        assertTrue(emitted.filterIsInstance<ShellResult>().single().denied)
        scope.cancel()
    }
}
