package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRecoveryReviewTest {
    private fun repository(scope: CoroutineScope, sent: MutableList<Frame>) = PocketRepository(scope).also {
        it.onSendForTest = { frame -> sent += frame }
        it.paired.value = PairedDaemon("wss://test", java.util.UUID.randomUUID().toString(), "pk", "device", "credential")
        it.connected.value = true
        it.convoId.value = "main"
        it.workdir.value = "/review"
        it.sessionAgent.value = AgentKind.CODEX
    }

    @Test fun refused_main_prompt_survives_the_previous_turn_and_reconnect_until_explicit_retry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repository(scope, sent)
            repo.streaming.value = true // a task admitted before the update is still running
            assertTrue(repo.sendPrompt("retained main"))
            val refused = sent.filterIsInstance<SendPrompt>().single()
            repo.receiveForTest(PocketError("daemon_updating", "updating", "main", promptId = refused.promptId))
            assertTrue(repo.streaming.value, "rejecting a queued prompt cannot end the older running turn")
            repo.receiveForTest(AssistantChunk("main", 1, StreamPiece.Text("older output")))
            repo.receiveForTest(TurnDone("main"))
            repo.receiveForTest(DaemonInfo(daemonVersion = "2.6.1", daemonUpdateMode = "remote", supportsPromptRecovery = true))
            repo.receiveForTest(SessionLive("main", "/review", "main-session", agent = AgentKind.CODEX, executing = false))
            repo.receiveForTest(SessionGone("main"))
            assertTrue(repo.turnStalled.value)
            assertEquals(1, sent.filterIsInstance<SendPrompt>().size, "a refused prompt is never replayed automatically")
            val bubbles = repo.messages.count { it is ChatItem.User }
            repo.resendStalledPrompt()
            assertEquals(listOf("retained main", "retained main"), sent.filterIsInstance<SendPrompt>().map { it.text })
            assertEquals(bubbles, repo.messages.count { it is ChatItem.User })
        } finally { scope.cancel() }
    }

    @Test fun refused_side_prompt_survives_the_previous_turn_and_reconnect_until_explicit_retry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repository(scope, sent)
            val pane = repo.sidePanes.open("/review", "side-session", "Side", AgentKind.CODEX, PermissionMode.DEFAULT)!!
            repo.receiveForTest(SessionLive("side", "/review", "side-session", agent = AgentKind.CODEX, executing = true))
            assertTrue(repo.sidePanes.sendPrompt(pane, "retained side"))
            val refused = sent.filterIsInstance<SendPrompt>().single()
            repo.receiveForTest(PocketError("daemon_updating", "updating", "side", promptId = refused.promptId))
            assertTrue(pane.streaming.value)
            repo.receiveForTest(AssistantChunk("side", 1, StreamPiece.Text("older output")))
            repo.receiveForTest(TurnDone("side"))
            repo.receiveForTest(DaemonInfo(daemonVersion = "2.6.1", daemonUpdateMode = "remote"))
            repo.receiveForTest(SessionLive("side", "/review", "side-session", agent = AgentKind.CODEX, executing = false))
            assertTrue(pane.turnStalled.value)
            assertEquals(1, sent.filterIsInstance<SendPrompt>().size)
            val bubbles = pane.messages.count { it is ChatItem.User }
            repo.sidePanes.resendStalled(pane)
            assertEquals(listOf("retained side", "retained side"), sent.filterIsInstance<SendPrompt>().map { it.text })
            assertEquals(bubbles, pane.messages.count { it is ChatItem.User })
        } finally { scope.cancel() }
    }

    @Test fun harmless_binding_metadata_updates_do_not_discard_an_authorized_update() = runTest {
        val sent = mutableListOf<Frame>()
        val repo = repository(backgroundScope, sent)
        repo.daemonUpdateMode.value = "remote"
        assertTrue(repo.startDaemonUpdate())
        repo.paired.value = repo.paired.value!!.copy(label = "renamed", hostName = "new hostname", directUrl = "ws://127.0.0.1:8765")
        runCurrent()
        assertEquals(1, sent.filterIsInstance<DaemonUpdateStart>().size)
    }

    @Test fun updating_keeps_the_visible_approval_reading_lease_alive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repository(scope, sent)
            val ask = PermissionAsk("main", "ask", "Bash", "run tests", grantOptions = emptyList())
            repo.pendingAsk.value = ask
            repo.daemonUpdateStatus.value = DaemonUpdateStatus("update", "waiting", controlsBlocked = true)
            repo.sendAskHeartbeat(true)
            assertEquals(ApprovalAttentionHeartbeat("main", "ask", true), sent.filterIsInstance<ApprovalAttentionHeartbeat>().single())
            assertEquals(ask, repo.pendingAsk.value)
        } finally { scope.cancel() }
    }
}
