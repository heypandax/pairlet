package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 (desktop M3): the tray / bell Allow answers the request its row showed — matched on the
 * conversation AND the askId.
 *
 * askId is only unique per agent connection (§18.1 P1-3): Codex and ZCode use the JSON-RPC request id, a
 * small integer, so two sessions both asking as "3" is ordinary. Matching on the askId alone, a row taken
 * while session A was focused approved session B's "3" once the user had switched to B.
 */
class AttentionResolveScopeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<PermissionVerdict>()
    private val repo = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        onSendForTest = { if (it is PermissionVerdict) sent += it }
    }
    private val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())

    @AfterTest fun tearDown() = scope.cancel()

    private fun ask(convo: String, cmd: String) = PermissionAsk(convo, "3", "Bash", cmd, timeoutSec = 60)

    @Test
    fun aRowFromOneSessionNeverApprovesAnotherSessionsAskWithTheSameId() {
        repo.convoId.value = "convo-a"
        repo.receiveForTest(ask("convo-a", "git status"))
        val row = model.attention.single()

        // the user switches to session B, which is blocked on its own ask "3"
        repo.convoId.value = "convo-b"
        repo.pendingAsk.value = ask("convo-b", "rm -rf ~/work")

        model.resolveAttention(row, allow = true)

        // audit H1: the row now comes off the account-wide list, so after the switch it still answers A's own "3"
        // (A is still waiting) — and never B's
        assertEquals(listOf(PermissionVerdict("convo-a", "3", Decision.ALLOW)), sent, "B's request was never shown on that row")
        assertEquals("convo-b", repo.pendingAsk.value?.convoId, "B's card is still waiting for its own decision")
    }

    @Test
    fun theOpenChatsCardOutsideTheListStillMatchesOnBothIds() {
        // a card the account-wide list doesn't hold (here: the list was replaced by a reply without it) is the
        // legacy focused path — which must keep the composite match
        repo.convoId.value = "convo-a"
        repo.pendingAsk.value = ask("convo-a", "git status")
        val row = model.attention.single()
        repo.convoId.value = "convo-b"
        repo.pendingAsk.value = ask("convo-b", "rm -rf ~/work")

        model.resolveAttention(row, allow = true)

        assertTrue(sent.isEmpty(), "B's request was never shown on that row: got $sent")
        assertEquals("convo-b", repo.pendingAsk.value?.convoId)
    }

    @Test
    fun theRowStillAnswersItsOwnRequest() {
        repo.convoId.value = "convo-a"
        repo.receiveForTest(ask("convo-a", "git status"))
        val row = model.attention.single()

        model.resolveAttention(row, allow = true)

        assertEquals(listOf(PermissionVerdict("convo-a", "3", Decision.ALLOW)), sent)
    }
}
