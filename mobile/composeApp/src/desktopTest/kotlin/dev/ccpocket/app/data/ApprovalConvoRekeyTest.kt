package dev.ccpocket.app.data

import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 (mobile-repository M7): the same session re-announced under a NEW convoId (daemon restart /
 * reclaimed process → cold resume, handoff migration, rewind branch) retires the old conversation's cards.
 *
 * Before: SessionLive only re-pointed `convoId`. The old card stayed up, the new process's real ask queued
 * behind it, and Allow sent `PermissionVerdict(new convo, OLD askId)` — an id the daemon no longer holds, so
 * nothing ran while the user believed they had approved. The session's "always allow" chips kept listing
 * rules the new process never had.
 */
class ApprovalConvoRekeyTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repo = PocketRepository(scope)
    private val sent = mutableListOf<PermissionVerdict>()

    init {
        repo.onSendForTest = { if (it is PermissionVerdict) sent += it }
        repo.convoId.value = "c-old"
        repo.sessionKey.value = "s-1"
        repo.workdir.value = "/w/proj"
    }

    @AfterTest fun tearDown() = scope.cancel()

    @Test
    fun aNewConvoIdForTheSameSessionRetiresTheOldCardsAndRules() {
        repo.receiveForTest(PermissionAsk("c-old", "6", "Bash", "npm test", timeoutSec = 60, rule = "Bash(npm test:*)"))
        repo.resolve(Decision.ALLOW, remember = true, grantScope = "session")
        assertEquals(listOf("Bash(npm test:*)"), repo.allowRules.toList(), "sanity: a session rule is listed")
        repo.receiveForTest(PermissionAsk("c-old", "7", "Bash", "git push", timeoutSec = 60))
        sent.clear()

        // the daemon restarted; the reconnect re-opened the same session under a fresh convoId
        repo.receiveForTest(SessionLive(convoId = "c-new", workdir = "/w/proj", sessionId = "s-1"))

        assertEquals("c-new", repo.convoId.value, "sanity: the announce was accepted")
        assertNull(repo.pendingAsk.value, "the old process's card is gone — its ask died with it")
        assertNull(repo.askQueueProgress.value)
        assertTrue(repo.allowRules.isEmpty(), "the new process holds none of the old session rules")

        // the new process asks again: it is the card on screen, and Allow answers it
        repo.receiveForTest(PermissionAsk("c-new", "1", "Bash", "git push", timeoutSec = 60))
        assertEquals(PermissionAsk("c-new", "1", "Bash", "git push", timeoutSec = 60), repo.pendingAsk.value)
        repo.resolve(Decision.ALLOW)
        assertEquals(listOf("c-new" to "1"), sent.map { it.convoId to it.askId })
    }

    @Test
    fun reattachingUnderTheSameConvoIdKeepsTheCardAndRules() {
        repo.receiveForTest(PermissionAsk("c-old", "6", "Bash", "npm test", timeoutSec = 60, rule = "Bash(npm test:*)"))
        repo.resolve(Decision.ALLOW, remember = true, grantScope = "session")
        repo.receiveForTest(PermissionAsk("c-old", "7", "Bash", "git push", timeoutSec = 60))

        // a plain reconnect to the still-live conversation: same convoId, nothing to retire
        repo.receiveForTest(SessionLive(convoId = "c-old", workdir = "/w/proj", sessionId = "s-1"))

        assertEquals("7", repo.pendingAsk.value?.askId)
        assertEquals(listOf("Bash(npm test:*)"), repo.allowRules.toList())
    }
}
