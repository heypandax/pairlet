package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class DaemonUpdateRequestTest {
    private val binding = PairedDaemon("wss://test", "update-test", "pk", "device", "credential")

    @Test fun old_daemons_and_non_owners_never_receive_update_requests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope)
            repo.onSendForTest = { sent += it }
            repo.paired.value = binding; repo.connected.value = true
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0"))
            assertFalse(repo.startDaemonUpdate())
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", daemonUpdateMode = "remote"))
            repo.paired.value = binding.copy(role = BindingRole.COLLABORATOR)
            assertFalse(repo.startDaemonUpdate())
            assertTrue(sent.none { it is DaemonUpdateStart })
        } finally { scope.cancel() }
    }

    @Test fun only_matching_reply_advances_progress_and_reconnect_proves_success() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope)
            repo.onSendForTest = { sent += it }
            repo.paired.value = binding; repo.connected.value = true
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", daemonUpdateMode = "remote"))
            assertTrue(repo.startDaemonUpdate())
            assertFalse(repo.startDaemonUpdate())
            val request = sent.filterIsInstance<DaemonUpdateStart>().single()
            repo.receiveForTest(DaemonUpdateStatus(request.requestId, "in_progress"))
            assertFalse(repo.daemonUpdateBusy, "a second client must be able to retry after the first updater fails")
            repo.receiveForTest(DaemonUpdateStatus("wrong-request", "failed"))
            assertEquals("in_progress", repo.daemonUpdateStatus.value?.state)
            repo.receiveForTest(DaemonUpdateStatus(request.requestId, "restarting", "2.0.0"))
            assertEquals("restarting", repo.daemonUpdateStatus.value?.state)
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.5.0", daemonUpdateMode = "remote"))
            assertEquals("restarting", repo.daemonUpdateStatus.value?.state)
            repo.receiveForTest(DaemonInfo(daemonVersion = "2.0.0", daemonUpdateMode = "remote"))
            assertEquals("updated", repo.daemonUpdateStatus.value?.state)
            repo.receiveForTest(DaemonUpdateStatus(request.requestId, "downloading", "2.0.0"))
            assertEquals("updated", repo.daemonUpdateStatus.value?.state)
        } finally { scope.cancel() }
    }
    @Test fun updating_pauses_new_messages_but_keeps_task_completion_and_recovers_on_failure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope)
            repo.onSendForTest = { sent += it }
            repo.paired.value = binding; repo.connected.value = true
            repo.convoId.value = "chat"
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", daemonUpdateMode = "remote"))
            assertTrue(repo.startDaemonUpdate())
            val request = sent.filterIsInstance<DaemonUpdateStart>().single()
            assertTrue(repo.daemonControlsBlocked)
            val before = repo.messages.size
            assertFalse(repo.sendPrompt("must stay in composer"))
            assertEquals(before, repo.messages.size, "a blocked message must not create a sent bubble")
            repo.cancelTurn()
            repo.fetchPresets()
            assertTrue(sent.none { it is SendPrompt })
            assertTrue(sent.any { it is CancelTurn }, "existing work must remain stoppable while the updater waits for idle")
            assertTrue(sent.any { it is FetchPresets }, "viewing must remain available")
            repo.receiveForTest(DaemonUpdateStatus(request.requestId, "failed", controlsBlocked = false))
            assertFalse(repo.daemonControlsBlocked)
            assertTrue(repo.sendPrompt("allowed after update failure"))
            assertTrue(sent.filterIsInstance<SendPrompt>().any { it.text == "allowed after update failure" })
        } finally { scope.cancel() }
    }

    @Test fun a_different_clients_update_is_visible_and_polling_unlocks_when_it_finishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope)
            repo.onSendForTest = { sent += it }
            repo.paired.value = binding; repo.connected.value = true
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", daemonUpdateMode = "remote", daemonUpdateInProgress = true))
            assertTrue(repo.daemonControlsBlocked)
            assertFalse(repo.startDaemonUpdate())
            withTimeout(5_000) { while (sent.none { it is DaemonUpdateGet }) delay(10) }
            val query = sent.filterIsInstance<DaemonUpdateGet>().first()
            repo.receiveForTest(DaemonUpdateStatus(query.requestId, "idle"))
            assertFalse(repo.daemonControlsBlocked)
        } finally { scope.cancel() }
    }

    @Test fun a_prompt_refused_during_update_stays_unsent_until_the_user_retries_after_unlock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope)
            repo.onSendForTest = { sent += it }
            repo.paired.value = binding; repo.connected.value = true
            repo.convoId.value = "chat"; repo.workdir.value = "/tmp"
            repo.receiveForTest(DaemonInfo(daemonVersion = "1.0.0", daemonUpdateMode = "remote"))
            assertTrue(repo.sendPrompt("preserve me"))
            val refused = sent.filterIsInstance<SendPrompt>().last()
            val before = sent.filterIsInstance<SendPrompt>().size
            assertTrue(repo.startDaemonUpdate())
            val updateId = sent.filterIsInstance<DaemonUpdateStart>().single().requestId
            repo.receiveForTest(PocketError("daemon_updating", "updating", convoId = "chat", promptId = refused.promptId))
            assertTrue(repo.turnStalled.value, "the user must see an explicit resend action")
            repo.resendStalledPrompt()
            assertEquals(before, sent.filterIsInstance<SendPrompt>().size, "the refused prompt is not retried during install")
            repo.receiveForTest(DaemonUpdateStatus(updateId, "failed", controlsBlocked = false))
            repo.resendStalledPrompt()
            assertEquals(before + 1, sent.filterIsInstance<SendPrompt>().size, "the user can retry once control resumes")
            assertEquals("preserve me", sent.filterIsInstance<SendPrompt>().last().text)
        } finally { scope.cancel() }
    }

}
