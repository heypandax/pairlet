package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.PocketError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #158: a refused rename (`rename_failed`) must surface on the SESSIONS list — keyed to the row
 * that asked — and never as a Sys line in a chat transcript. The most common refusal (renaming a
 * terminal-held session from the sidebar) fires with NO chat open at all, so the old chat-only surface
 * showed nothing; and whatever chat IS open is an unrelated session that must not absorb the error.
 */
class RenameFeedbackTest {

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
    }

    @Test
    fun renameRefusalLandsOnTheAskingSessionNeverInChat() = runBlocking {
        val r = repo()
        r.renameSession("sid-a", "New name", wd = "/tmp/proj")
        r.receiveForTest(PocketError("rename_failed", "session is live in another client — rename it there (/rename) or stop it first"))

        val err = assertNotNull(r.renameError.value, "the refusal must surface as rename feedback")
        assertEquals("sid-a", err.sessionId, "…keyed back to the row that asked")
        assertTrue("live in another client" in err.message, err.message)
        assertTrue(r.messages.filterIsInstance<ChatItem.Sys>().isEmpty(), "a rename refusal must not land in a chat transcript")
    }

    @Test
    fun aFreshAttemptClearsAndRetargetsTheRefusal() = runBlocking {
        val r = repo()
        r.renameSession("sid-a", "x", wd = "/tmp/proj")
        r.receiveForTest(PocketError("rename_failed", "nope"))
        assertNotNull(r.renameError.value)

        r.renameSession("sid-b", "y", wd = "/tmp/proj") // a retry elsewhere clears the stale error…
        assertNull(r.renameError.value, "a fresh attempt must clear the previous refusal")
        r.receiveForTest(PocketError("rename_failed", "still refused"))
        assertEquals("sid-b", assertNotNull(r.renameError.value).sessionId, "…and a new refusal targets the new row")
    }

    @Test
    fun dismissClearsTheRefusal() = runBlocking {
        val r = repo()
        r.renameSession("sid-a", "x", wd = "/tmp/proj")
        r.receiveForTest(PocketError("rename_failed", "nope"))
        r.dismissRenameError()
        assertNull(r.renameError.value)
    }

    @Test
    fun otherPocketErrorsStillReachTheChatSurface() = runBlocking {
        val r = repo()
        r.receiveForTest(PocketError("agent_unavailable", "claude CLI not found"))
        assertTrue(r.messages.filterIsInstance<ChatItem.Sys>().any { "not found" in it.text }, "non-rename errors keep the chat surface")
        assertNull(r.renameError.value, "…and must not light the rename surface")
    }
}
