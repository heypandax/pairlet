package dev.ccpocket.app.pairing

import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.ShareInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unified deep-link dispatch table (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §7).
 *
 * The bug this replaces: every entry point had its own parser, so `ccpocket://collab#…` scanned in the
 * pairing screen (or opened from iOS/Android) fell through the pair-only path and read as an invalid link.
 * The rules that must hold for every caller now:
 *
 *  - the HOST decides the route, before any base64 is touched;
 *  - a link that names itself `collab`/`share` and then fails to decode is INVALID, never retried as
 *    something else — a truncated fragment must not be probed as a pairing URL;
 *  - a bare base64 blob is only an invite where a human explicitly pasted one.
 */
class IncomingLinkTest {

    private val collab = CollaboratorInvite(
        relay = "wss://relay.test", accountId = "acct-a", daemonPub = dev.ccpocket.app.TEST_DAEMON_PUB,
        ticket = "tkt-1", ownerLabel = "Panda",
    )
    /** The same establishment material, minted for the OTHER feature (REVIEW-REQUEST.md §13.3). */
    private val reviewContact = collab.copy(ticket = "tkt-3", purpose = CollaboratorPurpose.REVIEW)
    private val share = ShareInvite(
        relay = "wss://relay.test", accountId = "acct-a", daemonPub = "PUBKEY", ticket = "tkt-2", folderName = "cc-pocket",
        tier = dev.ccpocket.protocol.AccessTier.REVIEW, expiresAt = 1_800_000_000_000, ttlSec = 600,
    )

    // ── full URIs route by host ───────────────────────────────────────────────────────────────────

    @Test
    fun fullCollaboratorUriRoutesToTheConfirmFlow() {
        val link = parseIncomingLink(collab.encode())
        assertIs<IncomingLink.Collab>(link)
        assertEquals("tkt-1", link.invite.ticket)
        assertEquals("Panda", link.invite.ownerLabel)
    }

    @Test
    fun fullShareUriRoutesToTheGuestPreview() {
        val link = parseIncomingLink(share.encode())
        assertIs<IncomingLink.Share>(link)
        assertEquals("cc-pocket", link.invite.folderName)
    }

    @Test
    fun pairUriAndShortCodeStayOnTheirOldPaths() {
        assertIs<IncomingLink.Pair>(parseIncomingLink("ccpocket://pair?relay=wss%3A%2F%2Fr&acct=a&dpk=k&ticket=t"))
        assertEquals("123456", (parseIncomingLink("ccpocket://pair?code=123456") as IncomingLink.Code).code)
        // pre-scheme material (printed codes, older QRs) must keep working
        assertEquals("654321", (parseIncomingLink("654321") as IncomingLink.Code).code)
    }

    @Test
    fun pushRoutesResolveToTheirTargets() {
        val s = parseIncomingLink("ccpocket://session?wd=%2FUsers%2Fp%2Fcc-pocket&sid=sess-1")
        assertIs<IncomingLink.Session>(s)
        assertEquals("/Users/p/cc-pocket", s.workdir) // percent-decoded: a workdir is full of slashes
        assertEquals("sess-1", s.sessionId)
        assertEquals("h-42", (parseIncomingLink("ccpocket://handoff?id=h-42") as IncomingLink.Handoff).handoffId)
    }

    // ── malformed input fails loudly, in the right lane ───────────────────────────────────────────

    @Test
    fun aCollabLinkWithNoFragmentIsInvalidRatherThanMisrouted() {
        // a share sheet / IM client that ate the `#…` leaves the scheme + host intact
        val link = parseIncomingLink("ccpocket://collab")
        assertEquals(IncomingLink.Unknown, link)
    }

    @Test
    fun badBase64UnderAKnownHostIsInvalid() {
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://collab#!!!not-base64!!!"))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://share#!!!not-base64!!!"))
        // valid base64 of the WRONG payload is still not an invite
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://collab#" + dev.ccpocket.app.util.B64Url.encode("{}".encodeToByteArray())))
    }

    @Test
    fun unknownHostsAndEmptyInputAreRejected() {
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://whatever?x=1"))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("   "))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("https://example.com/collab#abc"))
    }

    // ── bare blobs: paste-only ────────────────────────────────────────────────────────────────────

    @Test
    fun bareBlobsDecodeOnlyAtAnExplicitPasteEntry() {
        val bare = collab.encode().removePrefix(COLLAB_URI_PREFIX)
        assertIs<IncomingLink.Collab>(parseIncomingLink(bare, allowBareBlob = true))
        // …and are NOT guessed at from a generic deep link: an arbitrary string must not be probed as an
        // invite just because it happens to be base64
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = false))
    }

    @Test
    fun bareShareBlobStillWorksAtThePasteEntry() {
        val bare = share.encode().removePrefix(SHARE_URI_PREFIX)
        val link = parseIncomingLink(bare, allowBareBlob = true)
        assertIs<IncomingLink.Share>(link)
        assertEquals("tkt-2", link.invite.ticket)
    }

    @Test
    fun theTwoInviteCodecsNeverClaimEachOthersBlobs() {
        // both are base64url JSON — the paste path tries share first, so this guards the ordering
        assertTrue(decodeShareInvite(collab.encode()) == null)
        assertTrue(decodeCollaboratorInvite(share.encode()) == null)
    }

    // ── the two collaborator DOORS (REVIEW-REQUEST.md §13.3) ──────────────────────────────────────
    //
    // A Session Handoff invite and a Review contact invite are the same bytes but for one trailing
    // field, and the ticket inside either is SINGLE USE. So crossing the doors is not a routing bug —
    // it burns the ticket the other side is still waiting for, and leaves both people re-scanning.
    //
    // Two gates, because they stop different peers: the URI host is the only one an ALREADY-RELEASED
    // app can act on (it does not read the trailing `purpose` at all), and the embedded purpose is the
    // only one left when a human strips the prefix and pastes the bare blob.

    /**
     * A v1.6.0-MINTED invite — the exact bytes in the field today: no `purpose` key, no `ttlSec`.
     *
     * Hand-written rather than produced by [encode], because that is the whole point: `encode` now emits
     * `"purpose":"session_handoff"`, so a fixture built through it would prove the two codecs agree with
     * THEMSELVES and nothing about the artifact a shipped app already put on someone's screen. Both new
     * gates ride on this path — the `purpose == want` match (satisfied by the ABSENT-key default) and
     * `validDaemonPub` (which is strictly tighter than the shipped `isNotBlank`).
     */
    private fun legacyCollabBlob(): String = dev.ccpocket.app.util.B64Url.encode(
        ("""{"relay":"wss://relay.test","accountId":"acct-a",""" +
            """"daemonPub":"${dev.ccpocket.app.TEST_DAEMON_PUB}","ticket":"tkt-1","ownerLabel":"Panda"}""")
            .encodeToByteArray(),
    )

    @Test
    fun aPreReleaseCollabInviteStillDecodesAtItsOwnDoor() {
        val blob = legacyCollabBlob()

        val decoded = decodeCollaboratorInvite(COLLAB_URI_PREFIX + blob)
        assertNotNull(decoded, "a v1.6.0 invite must keep working — it is printed and pasted in the field")
        assertEquals(CollaboratorPurpose.SESSION_HANDOFF, decoded.purpose, "an ABSENT purpose keeps its historical meaning")
        assertEquals(600, decoded.ttlSec, "…and the pre-existing ttl default is untouched")

        assertIs<IncomingLink.Collab>(parseIncomingLink(COLLAB_URI_PREFIX + blob))
        assertIs<IncomingLink.Collab>(parseIncomingLink("ccpocket://collab#$blob"))
        assertNotNull(decodeCollaboratorInvite(blob), "a bare pre-purpose blob still pastes")
        assertIs<IncomingLink.Collab>(parseIncomingLink(blob, allowBareBlob = true))

        // …and it is still not a review peer, at either form of that door
        assertNull(decodeReviewContactInvite(REVIEW_CONTACT_URI_PREFIX + blob))
        assertNull(decodeReviewContactInvite(blob))
    }

    @Test
    fun theLegacyCollabHostIsUnchangedAndTheReviewHostIsItsOwn() {
        assertTrue(collab.encode().startsWith(COLLAB_URI_PREFIX), collab.encode())
        assertTrue(reviewContact.encode().startsWith(REVIEW_CONTACT_URI_PREFIX), reviewContact.encode())

        // an old collab link keeps routing exactly where it always did
        assertIs<IncomingLink.Collab>(parseIncomingLink(collab.encode()))

        // …and a review link gets its own lane, carrying the RAW uri (the daemon redeems it, not us)
        val link = parseIncomingLink(reviewContact.encode())
        assertIs<IncomingLink.ReviewContact>(link)
        assertEquals("tkt-3", link.invite.ticket)
        assertEquals(CollaboratorPurpose.REVIEW, link.invite.purpose)
        assertEquals(reviewContact.encode(), link.uri, "the join flow needs the line verbatim")
    }

    @Test
    fun neitherDoorAcceptsTheOthersTicket() {
        // a review blob dressed up under the collab host — the shape a mis-built producer would emit
        val reviewBlobUnderCollabHost = COLLAB_URI_PREFIX + reviewContact.encode().removePrefix(REVIEW_CONTACT_URI_PREFIX)
        assertEquals(IncomingLink.Unknown, parseIncomingLink(reviewBlobUnderCollabHost))
        assertNull(decodeCollaboratorInvite(reviewBlobUnderCollabHost))

        // …and the reverse
        val handoffBlobUnderReviewHost = REVIEW_CONTACT_URI_PREFIX + collab.encode().removePrefix(COLLAB_URI_PREFIX)
        assertEquals(IncomingLink.Unknown, parseIncomingLink(handoffBlobUnderReviewHost))
        assertNull(decodeReviewContactInvite(handoffBlobUnderReviewHost))

        // a full URI is never re-probed at the other codec's door either
        assertNull(decodeReviewContactInvite(collab.encode()))
        assertNull(decodeCollaboratorInvite(reviewContact.encode()))
    }

    @Test
    fun aBareReviewBlobStaysAReviewTicketAtThePasteEntry() {
        val bare = reviewContact.encode().removePrefix(REVIEW_CONTACT_URI_PREFIX)
        // the prefix is gone, so only the embedded purpose is left — and it still decides
        assertIs<IncomingLink.ReviewContact>(parseIncomingLink(bare, allowBareBlob = true))
        assertNull(decodeCollaboratorInvite(bare), "a bare review blob must never redeem as a phone contact")
        // and, like every bare blob, it is not guessed at from a generic deep link
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = false))
    }

    /** A purpose only a NEWER build knows fails closed at both doors rather than defaulting into one. */
    @Test
    fun anUnreadablePurposeIsAcceptedByNeitherDoor() {
        val json = """{"relay":"wss://relay.test","accountId":"acct-a",""" +
            """"daemonPub":"${dev.ccpocket.app.TEST_DAEMON_PUB}","ticket":"tkt-9","purpose":"pair_programming"}"""
        val blob = dev.ccpocket.app.util.B64Url.encode(json.encodeToByteArray())
        assertEquals(IncomingLink.Unknown, parseIncomingLink(COLLAB_URI_PREFIX + blob))
        assertEquals(IncomingLink.Unknown, parseIncomingLink(REVIEW_CONTACT_URI_PREFIX + blob))
        assertEquals(IncomingLink.Unknown, parseIncomingLink(blob, allowBareBlob = true))
    }

    @Test
    fun aReviewLinkWithNoFragmentIsInvalidRatherThanMisrouted() {
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://review-contact"))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://review-contact#!!!not-base64!!!"))
    }

    /**
     * `parseIncomingLink` routes on a case-INSENSITIVE scheme and a lowercased host, but the codecs match
     * their accept prefixes literally — so a case-variant URI reaches a door whose prefix check misses.
     *
     * That split is only safe in one direction and it must be the fail-closed one: an odd-cased link
     * reads as invalid (the user re-copies it), never as "close enough, redeem it". The accept branches
     * deliberately stay case-sensitive — `ccpocket://collab#` is frozen, so neither port may start
     * accepting spellings the released build rejects — while the "some other host" REFUSAL is
     * case-insensitive, so the guard covers every string that can actually reach the door instead of
     * letting an odd-cased one fall through to the bare-blob branch and lean only on `purpose`.
     *
     * The cases below cover both halves: an odd-cased HOST (scheme still lowercase, so the refusal
     * branch fires) and an odd-cased SCHEME (which only the ignoreCase refusal catches).
     */
    @Test
    fun caseVariantHostsFailClosedAtBothDoors() {
        listOf(
            "ccpocket://Review-Contact#" to reviewContact,
            "ccpocket://REVIEW-CONTACT#" to reviewContact,
            "ccpocket://Collab#" to collab,
            "ccpocket://COLLAB#" to collab,
            "CCPOCKET://collab#" to collab,
            "CCPocket://review-contact#" to reviewContact,
        ).forEach { (host, invite) ->
            val blob = invite.encode().substringAfter('#')
            assertEquals(IncomingLink.Unknown, parseIncomingLink(host + blob), host)
            assertNull(decodeCollaboratorInvite(host + blob), host)
            assertNull(decodeReviewContactInvite(host + blob), host)
        }
    }
}
