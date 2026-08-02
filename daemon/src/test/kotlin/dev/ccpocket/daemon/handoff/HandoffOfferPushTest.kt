package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.relay.PushPolicy
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.PROTO_V_HEADLESS
import dev.ccpocket.protocol.PROTO_V_TARGETED_PUSH
import dev.ccpocket.protocol.SessionHandoff
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline-recipient offer nudge (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4).
 *
 * Three properties, each of which fails differently and badly:
 *  1. the payload leaks NOTHING — an alert on someone else's lock screen must not name the project, the
 *     path, the session, the person, or the ask;
 *  2. it is only sent for a WAITING offer that is BOUND to a contact — the account fan-out is exactly what
 *     must not be used;
 *  3. it is only sent to a relay that understands targeted delivery — an older one silently degrades to
 *     that account fan-out and would ring the OWNER's phone instead.
 */
class HandoffOfferPushTest {

    /** The kind of content that must never reach a lock screen — modelled on a real handoff. */
    private val project = "acme-payments"
    private val workdir = "/Users/alice/work/$project"
    private val initiator = "Alice's MacBook"
    private val brief = "Review the refund idempotency fix before we ship it"

    private fun offer(id: String = "h-42", recipient: String? = "dev-bob", status: HandoffStatus = HandoffStatus.WAITING) =
        SessionHandoff(
            id = id,
            sourceSessionId = "11111111-2222-3333-4444-555555555555",
            workdir = workdir,
            initiatorDeviceId = "dev-alice",
            initiatorLabel = initiator,
            recipientDeviceId = recipient,
            recipientLabel = "Bob",
            kind = HandoffKind.REVIEW,
            status = status,
            access = HandoffAccess.REVIEW_READ_ONLY,
            brief = HandoffBrief(request = brief),
            createdAt = 1,
        )

    // ---- 1. the payload -------------------------------------------------------

    @Test fun the_offer_push_carries_no_identifying_content_at_all() {
        val push = PushPolicy.offerPush("h-42", "dev-bob")

        // the exact wire copy — generic, and identical for every offer from every contact
        assertEquals("cc-pocket", push.title)
        assertEquals("You have a new handoff offer. Open the app to see the details.", push.body)

        // nothing about the work may appear anywhere in the visible alert
        val visible = "${push.title} ${push.body}"
        for (secret in listOf(project, workdir, initiator, brief, "Bob", "Alice", "refund", "payments", "11111111")) {
            assertFalse(visible.contains(secret, ignoreCase = true), "the alert leaked \"$secret\": $visible")
        }

        // …nor in the silent routing data. workdir/sessionId are cleartext to the relay AND become the
        // deep-link payload — an offer routes by its opaque id alone.
        assertNull(push.workdir, "an offer alert must not carry the initiator's project path")
        assertNull(push.sessionId)
        assertEquals("h-42", push.handoffId)

        // addressed to the recipient, so the owner's own phones are not in the delivery set
        assertEquals("dev-bob", push.deviceId)
        // urgency is an account-fan-out lever (issue #91); on the targeted path the relay gates on the
        // recipient's own socket, so claiming urgency here would only be noise
        assertFalse(push.urgent)
    }

    @Test fun the_offer_push_copy_does_not_vary_with_the_offer() {
        val a = PushPolicy.offerPush("h-1", "dev-b")
        val b = PushPolicy.offerPush("h-2", "dev-c")
        assertEquals(a.title, b.title)
        assertEquals(a.body, b.body)
        // only the two opaque ids differ — which is the whole point of OfferPush taking nothing else
        assertEquals(a.copy(handoffId = b.handoffId, deviceId = b.deviceId), b)
    }

    // ---- 2. when it fires -----------------------------------------------------

    private class Recorder {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        fun hook() = OfferPush { id, dev -> sent += id to dev }
    }

    @Test fun a_waiting_bound_offer_is_announced_once() = runBlocking {
        val svc = HandoffService()
        val rec = Recorder()
        svc.offerPush = rec.hook()

        svc.announceOffer(offer())

        assertEquals(listOf("h-42" to "dev-bob"), rec.sent.toList())
    }

    @Test fun an_unbound_offer_is_never_announced() = runBlocking {
        val svc = HandoffService()
        val rec = Recorder()
        svc.offerPush = rec.hook()

        // the older open-invite shape: any owner device may accept, so there is no device to address — and
        // falling back to the account fan-out is precisely what §3.4 forbids
        svc.announceOffer(offer(recipient = null))

        assertTrue(rec.sent.isEmpty())
    }

    @Test fun only_a_WAITING_offer_is_announced() = runBlocking {
        val svc = HandoffService()
        val rec = Recorder()
        svc.offerPush = rec.hook()

        // a create whose internal sweep already settled the row, a recall, a return, an expiry: none of
        // these may buzz a phone about work that is over
        for (s in HandoffStatus.entries.filter { it != HandoffStatus.WAITING }) {
            svc.announceOffer(offer(status = s))
        }
        assertTrue(rec.sent.isEmpty(), "announced for: ${rec.sent}")

        svc.announceOffer(offer(status = HandoffStatus.WAITING))
        assertEquals(1, rec.sent.size)
    }

    @Test fun no_hook_and_a_throwing_hook_are_both_survivable() = runBlocking {
        // a LAN-only `serve` (and every unit test) has no relay to push over: silence, not a crash
        HandoffService().announceOffer(offer())

        // and a transport that fails to queue must not fail the create that earned the push
        val svc = HandoffService()
        svc.offerPush = OfferPush { _, _ -> error("relay outbox closed") }
        svc.announceOffer(offer())
    }

    // ---- 3. the capability gate ----------------------------------------------

    @Test fun the_offer_push_is_withheld_from_a_relay_that_cannot_target() {
        // 0 = an Attached with no relayProtoV key at all, i.e. every relay deployed before §3.4
        assertFalse(PushPolicy.offerPushAllowed(0), "an old relay ignores deviceId and would ring the OWNER's phone")
        assertFalse(PushPolicy.offerPushAllowed(1))
        assertFalse(PushPolicy.offerPushAllowed(PROTO_V_HEADLESS), "understanding bridges says nothing about targeting")
        assertTrue(PushPolicy.offerPushAllowed(PROTO_V_TARGETED_PUSH))
        assertTrue(PushPolicy.offerPushAllowed(PROTO_V_TARGETED_PUSH + 1), "a newer relay keeps the capability")
    }

    /**
     * The gate that actually protects the owner. The daemon's control outbox buffers ACROSS reconnects, so
     * an enqueue-time check alone fails open: a push that passed against a §3.4 relay can be flushed by the
     * next link's writer, and an in-place redeploy or rollback means that link is not guaranteed to be the
     * same build. On an older relay the unknown `deviceId` is ignored and the alert fans out to the ACCOUNT
     * — the owner's own phones. So the capability is re-checked at WRITE time, against the live link.
     */
    @Test fun a_queued_targeted_push_is_dropped_rather_than_flushed_to_an_older_relay() {
        val queued = PushPolicy.offerPush("h-42", "dev-bob")

        // the reconnect landed on a relay that predates §3.4 (or on no answer yet — protoV resets to 0)
        assertFalse(PushPolicy.mayWrite(queued, relayProtoV = 0), "flushing this would ring the OWNER's phone")
        assertFalse(PushPolicy.mayWrite(queued, relayProtoV = PROTO_V_HEADLESS))
        assertTrue(PushPolicy.mayWrite(queued, relayProtoV = PROTO_V_TARGETED_PUSH))
    }

    @Test fun the_write_gate_never_drops_anything_else() {
        // an ordinary account-level push, an urgent bridge approval, and the non-push control frames all
        // predate the capability and must keep flowing to an old relay exactly as before
        for (v in listOf(0, 1, PROTO_V_HEADLESS, PROTO_V_TARGETED_PUSH)) {
            assertTrue(PushPolicy.mayWrite(dev.ccpocket.protocol.NotifyPush("t", "b", workdir = "/w", sessionId = "s"), v))
            assertTrue(PushPolicy.mayWrite(dev.ccpocket.protocol.NotifyPush("t", "b", urgent = true), v))
            assertTrue(PushPolicy.mayWrite(dev.ccpocket.protocol.Ping(1), v))
            assertTrue(PushPolicy.mayWrite(dev.ccpocket.protocol.PairBegin("pub", headless = true, collaborator = true), v))
            assertTrue(PushPolicy.mayWrite(dev.ccpocket.protocol.RevokeDevice("dev-x"), v))
        }
    }
}
