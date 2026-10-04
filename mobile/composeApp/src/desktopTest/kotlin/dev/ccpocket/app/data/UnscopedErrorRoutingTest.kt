package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A PocketError without a convoId is not automatically about the chat on screen. An archive refusal
 * answers a sidebar/list action: it must neither splice an "error:" row into an unrelated transcript
 * nor be taken as the refusal of an open that happens to be in flight.
 */
class UnscopedErrorRoutingTest {

    private fun repo(scope: CoroutineScope, sent: MutableList<Frame>) = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        onSendForTest = { sent += it }
    }

    private val archiveRefusal = PocketError("archive_failed", "could not update the archive for this session")

    @Test
    fun anArchiveRefusalStaysOutOfTheOpenChat() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
            r.receiveForTest(archiveRefusal)
            assertTrue(r.messages.none { it is ChatItem.Sys && it.text == archiveRefusal.message })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anArchiveRefusalDoesNotEndAnOpenInFlight() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val r = repo(scope, sent)
        try {
            assertTrue(r.openSession("/w", "sid-2"))
            assertTrue(sent.any { it is OpenSession }, "precondition: the open went out")
            assertTrue(r.opening.value)

            r.receiveForTest(archiveRefusal)
            assertTrue(r.opening.value, "an unrelated refusal must not release the open")

            // the open's own refusal still ends it
            r.receiveForTest(PocketError("bad_workdir", "not a readable directory: /w"))
            assertFalse(r.opening.value)
        } finally {
            scope.cancel()
        }
    }
}
