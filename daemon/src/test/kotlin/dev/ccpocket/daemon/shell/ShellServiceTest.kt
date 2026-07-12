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
