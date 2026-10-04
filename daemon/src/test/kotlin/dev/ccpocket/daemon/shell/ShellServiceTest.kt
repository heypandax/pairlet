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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.readText
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

    // ------------------------------------------------- subprocess lifetime

    @TempDir
    lateinit var tmp: Path

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun a_background_job_holding_stdout_does_not_wedge_the_terminal() {
        assumeTrue(!isWindows)
        val pidFile = tmp.resolve("bg.pid")
        val shell = ShellService(CoroutineScope(Dispatchers.Unconfined))
        try {
            // `npm run dev &`: the shell exits at once, the job it left behind keeps our stdout pipe open
            val first = within(15_000) { runBypass(shell, "echo hi; sleep 60 & echo \$! > '$pidFile'") }
            assertNotNull(first, "ShellResult never came: the background job's pipe wedged execute()")
            assertEquals(0, first.exitCode)
            assertTrue(first.stdout.contains("hi"), first.stdout)
            // and the session's terminal is free again
            val second = within(15_000) { runBypass(shell, "echo again") }
            assertNotNull(second)
            assertEquals(null, second.error)
            assertTrue(second.stdout.contains("again"), second.stdout)
        } finally {
            pidFrom(pidFile)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    private fun runBypass(shell: ShellService, command: String, timeoutMs: Long = 30_000): ShellResult = runBlocking {
        val out = CopyOnWriteArrayList<Frame>()
        shell.run(RunShellCommand("c1", command, tmp.toString(), timeoutMs), PermissionMode.BYPASS_PERMISSIONS) { out += it }
        out.filterIsInstance<ShellResult>().single()
    }

    /** Run [block] on its own thread and give up after [ms] — null means it was still blocked. */
    private fun <T> within(ms: Long, block: () -> T): T? {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "within").apply { isDaemon = true } }
        return try {
            pool.submit(Callable { block() }).get(ms, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } finally {
            pool.shutdown()
        }
    }

    private fun pidFrom(file: Path, waitMs: Long = 5_000): Long? {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            runCatching { file.readText().trim().toLong() }.getOrNull()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }
}
