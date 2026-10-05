package dev.ccpocket.app.data

import dev.ccpocket.app.TEST_DAEMON_PUB
import dev.ccpocket.app.pairing.IncomingLink
import dev.ccpocket.app.pairing.RetiredFeature
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.status_feature_retired
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ReviewRequest is retired in the App (2026-10). What is left of it on this side is only how the App
 * behaves when the outside world still speaks it:
 *
 *  - a `ccpocket://review-contact#…` link (a QR on a colleague's screen, an old chat message) says the
 *    feature has been retired — and it is NOT a failed pairing: no failure card, no `pair_failed`;
 *  - an older daemon may still push review frames; they are dropped without a trace.
 *
 * Unconfined makes `handle()` synchronous, so no daemon and no clock are needed.
 */
class RetiredReviewTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val seen = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()

    @BeforeTest fun tap() {
        seen.clear()
        telemetryTap = { e, p -> synchronized(seen) { seen += e to p } }
    }

    @AfterTest fun tearDown() {
        telemetryTap = null
        scope.cancel()
    }

    private fun repo() = PocketRepository(scope)

    private val reviewInvite = CollaboratorInvite(
        relay = "wss://relay.test", accountId = "acct-frank", daemonPub = TEST_DAEMON_PUB,
        ticket = "ONE-TIME-TICKET", ownerLabel = "Frank", purpose = CollaboratorPurpose.REVIEW,
    )

    @Test
    fun aReviewContactLinkSaysRetiredAndIsNotAFailedPairing() {
        // a well-formed invite and a corrupt one alike: the host decides, the payload is never read
        for (raw in listOf(reviewInvite.encode(), "ccpocket://review-contact#!!!not-base64!!!")) {
            synchronized(seen) { seen.clear() }
            val r = repo()

            val link = r.handleIncomingLink(raw)

            assertEquals(IncomingLink.Retired(RetiredFeature.REVIEW), link, raw)
            assertEquals(StatusMsg(Res.string.status_feature_retired), r.status.value, raw)
            assertNull(r.pairFailure.value, "a retired feature is not a failed pairing ($raw)")
            assertEquals(0, r.pairFailureSeq.value, "no failure card was armed ($raw)")
            assertTrue(
                synchronized(seen) { seen.none { it.first == TelEvent.PairFailed || it.first == TelEvent.PairStarted } },
                "no pairing attempt and no pair_failed for a retired link, saw $seen",
            )
            assertNull(r.pendingCollabInvite.value, "nothing parks at the collaborator confirm screen ($raw)")
            assertNull(r.pendingShareInvite.value, "…or at the share preview ($raw)")
        }

        // control: the same seam DOES see an unroutable link's pair_failed, so the silence above is real
        synchronized(seen) { seen.clear() }
        repo().handleIncomingLink("ccpocket://whatever?x=1")
        assertTrue(synchronized(seen) { seen.any { it.first == TelEvent.PairFailed } }, "control: saw $seen")
    }
}
