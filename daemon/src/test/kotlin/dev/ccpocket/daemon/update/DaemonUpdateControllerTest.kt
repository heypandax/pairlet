package dev.ccpocket.daemon.update

import dev.ccpocket.protocol.DaemonUpdateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class DaemonUpdateControllerTest {
    @Test fun work_started_during_download_can_answer_its_ask_and_release_the_update() = runBlocking {
        var awaitingApproval = false
        var restarted = false
        val states = mutableListOf<String>()
        val verdict = dev.ccpocket.protocol.PermissionVerdict("scheduled-chat", "ask", dev.ccpocket.protocol.Decision.ALLOW)
        val controller = DaemonUpdateController(
            mode = { "remote" }, isBusy = { awaitingApproval },
            prepare = { DaemonUpdateController.PreparedUpdate("2.7.0") { awaitingApproval = true } },
            pause = {
                assertTrue(dev.ccpocket.protocol.DaemonUpdateAccess.allowed(verdict))
                assertFalse(dev.ccpocket.protocol.DaemonUpdateAccess.allowed(dev.ccpocket.protocol.SendPrompt("scheduled-chat", "new task")))
                awaitingApproval = false // the existing task receives its owner's answer and completes
            },
            restart = { assertFalse(awaitingApproval); restarted = true },
        )
        withTimeout(1_000) { controller.request("attempt") { states += it.state } }
        assertEquals(listOf("checking", "downloading", "waiting", "restarting"), states)
        assertTrue(restarted)
        assertFalse(UpdateState.controlsBlocked)
    }

    @Test fun unsupported_installs_never_download_or_restart() = runBlocking {
        val states = mutableListOf<String>()
        val controller = DaemonUpdateController(
            mode = { "manual" }, isBusy = { false },
            prepare = { error("must not download") }, restart = { error("must not restart") },
        )
        controller.request("attempt") { states += it.state }
        assertEquals(listOf("manual"), states)
    }

    @Test fun busy_computer_refuses_before_checking_or_downloading() = runBlocking {
        val states = mutableListOf<String>()
        val controller = DaemonUpdateController(
            mode = { "remote" }, isBusy = { true },
            prepare = { error("must not check") }, restart = { error("must not restart") },
        )
        controller.request("attempt") { states += it.state }
        assertEquals(listOf("busy"), states)
    }

    @Test fun work_started_during_download_defers_restart_until_idle() = runBlocking {
        val events = mutableListOf<String>()
        var busy = false
        val controller = DaemonUpdateController(
            mode = { "remote" }, isBusy = { busy },
            prepare = { DaemonUpdateController.PreparedUpdate("2.6.0") { busy = true; events += "apply" } },
            restart = { assertFalse(busy); events += "restart" },
            pause = { events += "wait"; busy = false },
        )
        controller.request("attempt") { events += it.state; assertEquals("attempt", it.requestId) }
        assertEquals(listOf("checking", "downloading", "apply", "waiting", "wait", "restarting", "restart"), events)
    }

    @Test fun concurrent_request_does_not_apply_twice() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var applications = 0
        val controller = DaemonUpdateController(
            mode = { "remote" }, isBusy = { false },
            prepare = { DaemonUpdateController.PreparedUpdate("2.6.0") { applications++; entered.complete(Unit); finish.await() } },
            restart = {},
        )
        val first = launch { controller.request("first") {} }
        entered.await()
        val replies = mutableListOf<DaemonUpdateStatus>()
        controller.request("second") { replies += it }
        assertEquals("in_progress", replies.single().state)
        finish.complete(Unit); first.join()
        assertEquals(1, applications)
    }

    @Test fun failure_never_restarts_and_releases_update_claim() = runBlocking {
        val states = mutableListOf<String>()
        val controller = DaemonUpdateController(
            mode = { "remote" }, isBusy = { false },
            prepare = {
                assertTrue(UpdateState.controlsBlocked, "control must stop before preparing or installing")
                DaemonUpdateController.PreparedUpdate("2.6.0") { error("download failed") }
            },
            restart = { error("must not restart") },
        )
        controller.request("first") { states += it.state }
        controller.request("second") { states += it.state }
        assertEquals(listOf("checking", "downloading", "failed", "checking", "downloading", "failed"), states)
        assertFalse(UpdateState.controlsBlocked, "failed updates must release the control gate")
    }

    @Test fun no_update_never_restarts() = runBlocking {
        val states = mutableListOf<String>()
        DaemonUpdateController(mode = { "remote" }, isBusy = { false }, prepare = { null },
            restart = { error("must not restart") }).request("attempt") { states += it.state }
        assertEquals(listOf("checking", "up_to_date"), states)
    }

    @Test fun requester_disconnect_does_not_interrupt_installation() = runBlocking {
        var applied = false
        var restarted = false
        DaemonUpdateController(mode = { "remote" }, isBusy = { false },
            prepare = { DaemonUpdateController.PreparedUpdate("2.6.0") { applied = true } },
            restart = { restarted = true }).request("attempt") { throw kotlinx.coroutines.CancellationException("sink closed") }
        assertTrue(applied); assertTrue(restarted)
    }

    @Test fun install_mode_requires_managed_anchored_posix_service() {
        assertEquals("manual", DaemonUpdateController.modeFor(null, "Mac OS X"))
        val install = UpdateService.ManagedInstall(java.nio.file.Path.of("versions"), java.nio.file.Path.of("launcher"), true)
        assertEquals("remote", DaemonUpdateController.modeFor(install, "Mac OS X"))
        assertEquals("remote", DaemonUpdateController.modeFor(install, "Linux"))
        assertEquals("manual", DaemonUpdateController.modeFor(install.copy(serviceAnchored = false), "Linux"))
        assertEquals("manual", DaemonUpdateController.modeFor(install, "Windows 11"))
    }
}
