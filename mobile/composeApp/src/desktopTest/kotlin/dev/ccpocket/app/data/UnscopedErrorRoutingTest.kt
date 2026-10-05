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

    /**
     * …but it must still reach the user. The archive confirmation toast went up the moment the user archived
     * (optimistically, "Archived · Restore"); a refusal flips that same toast to the failure, for the session
     * that was asked about, so the phone no longer reports a success that did not happen.
     */
    @Test
    fun anArchiveRefusalTurnsTheArchiveToastIntoTheFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.setSessionArchived("/w", "sid-9", archived = true, title = "Fix relay", running = false)
            assertFalse(r.archiveToast.value!!.failed, "precondition: the optimistic confirmation")

            r.receiveForTest(archiveRefusal)

            val t = r.archiveToast.value
            assertTrue(t != null && t.failed && t.sessionId == "sid-9" && t.archived && t.title == "Fix relay", "got $t")
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

    // ── refusals that NAME the request they answer (the daemon's guard / unhandled-frame messages) ──

    private fun sysRows(r: PocketRepository) = r.messages.filterIsInstance<ChatItem.Sys>()

    /** A folder-share guest's quota refresh is refused by the daemon's guard — the message names the frame. It
     *  answered the allowance pill, not the session the user just tapped. */
    @Test
    fun aGuardRefusalNamingAQuotaRequestLeavesTheOpenAndTheChatAlone() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
            r.fetchClaudeQuota()
            assertTrue(r.openSession("/w", "sid-2"))
            assertTrue(r.opening.value && r.claudeQuotaLoading.value, "preconditions")

            r.receiveForTest(PocketError("share_forbidden", "not permitted for a folder-share guest: ClaudeQuotaGet"))

            assertTrue(r.opening.value, "the quota refusal must not end the open")
            assertTrue(sysRows(r).isEmpty(), "nor land in the chat: ${sysRows(r)}")
            assertFalse(r.claudeQuotaLoading.value, "it ends the quota request it answers")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anUnhandledFrameRefusalNamingUsageEndsTheUsageRequestNotTheChat() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
            r.fetchUsage()
            r.receiveForTest(PocketError("unsupported", "frame not handled by daemon: FetchUsage"))
            assertTrue(sysRows(r).isEmpty(), "${sysRows(r)}")
            assertFalse(r.usageLoading.value, "the usage page stops spinning")
        } finally {
            scope.cancel()
        }
    }

    /** A request whose handler threw answers with a bare `internal`. With exactly one non-session request
     *  outstanding and nothing session-side in flight, that request is the only thing it can answer. */
    @Test
    fun anInternalErrorDuringTheOnlyPendingPanelRequestFailsThatPanel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
            r.fetchGitStatus()
            assertTrue(r.gitStatusLoading.value)
            r.receiveForTest(PocketError("internal", "git exited 128"))
            assertTrue(sysRows(r).isEmpty(), "${sysRows(r)}")
            assertFalse(r.gitStatusLoading.value)
            assertTrue(r.gitStatusUnavailable.value, "the panel settles into its own failure state")
        } finally {
            scope.cancel()
        }
    }

    // ── what cannot be attributed keeps the old behaviour ──

    @Test
    fun anInternalErrorWhileAnOpenIsInFlightStillEndsTheOpen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.fetchUsage()
            assertTrue(r.openSession("/w", "sid-2"))
            r.receiveForTest(PocketError("internal", "request failed"))
            assertFalse(r.opening.value, "ambiguous: the open is as likely as the usage fetch — keep failing the open")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aRefusalNamingOpenSessionStillEndsTheOpen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.fetchUsage()
            assertTrue(r.openSession("/w", "sid-2"))
            r.receiveForTest(PocketError("share_forbidden", "not permitted for a folder-share guest: OpenSession"))
            assertFalse(r.opening.value)
            assertTrue(r.usageLoading.value, "the usage request is still waiting for its own answer")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anInternalErrorWithTwoPanelRequestsPendingIsNotGuessed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val r = repo(scope, mutableListOf())
        try {
            r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
            r.fetchGitStatus()
            r.fetchUsage()
            r.receiveForTest(PocketError("internal", "request failed"))
            assertTrue(r.gitStatusLoading.value && r.usageLoading.value, "neither request is failed on a guess")
            assertTrue(sysRows(r).isNotEmpty(), "unattributable: the old behaviour (a row in the chat)")
        } finally {
            scope.cancel()
        }
    }
}
