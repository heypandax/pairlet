package dev.ccpocket.app.pairing

import dev.ccpocket.protocol.COLLAB_INVITE_URI_PREFIX
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.REVIEW_CONTACT_INVITE_URI_PREFIX
import dev.ccpocket.protocol.ShareInvite
import dev.ccpocket.protocol.inviteUriPrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The unified deep-link dispatch table (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §7).
 *
 * The bug this replaced: every entry point had its own parser, so an invite scanned in the pairing screen
 * (or opened from iOS/Android) fell through the pair-only path and read as an invalid link. The rules that
 * must hold for every caller now:
 *
 *  - the HOST decides the route, before any base64 is touched;
 *  - a link that names itself `share` and then fails to decode is INVALID, never retried as something
 *    else — a truncated fragment must not be probed as a pairing URL;
 *  - a link for a RETIRED feature (`collab`, `review-contact`, `handoff`) says so whatever its payload;
 *  - a bare base64 blob is only an invite where a human explicitly pasted one.
 */
class IncomingLinkTest {

    /** Establishment material an older build put behind `ccpocket://collab#…` (Collaborator Links, retired). */
    private val collab = CollaboratorInvite(
        relay = "wss://relay.test", accountId = "acct-a", daemonPub = dev.ccpocket.app.TEST_DAEMON_PUB,
        ticket = "tkt-1", ownerLabel = "Panda",
    )
    /** The same establishment material, minted for the OTHER (also retired) feature (REVIEW-REQUEST.md §13.3). */
    private val reviewContact = collab.copy(ticket = "tkt-3", purpose = CollaboratorPurpose.REVIEW)
    private val share = ShareInvite(
        relay = "wss://relay.test", accountId = "acct-a", daemonPub = "PUBKEY", ticket = "tkt-2", folderName = "cc-pocket",
        tier = dev.ccpocket.protocol.AccessTier.REVIEW, expiresAt = 1_800_000_000_000, ttlSec = 600,
    )

    /** The bytes an older build published for an invite (this build no longer has that codec): the
     *  purpose's own door + the JSON as base64url. */
    private fun CollaboratorInvite.legacyUri(): String =
        inviteUriPrefix(purpose) +
            dev.ccpocket.app.util.B64Url.encode(PocketJson.encodeToString(CollaboratorInvite.serializer(), this).encodeToByteArray())

    private val retiredCollab = IncomingLink.Retired(RetiredFeature.COLLABORATOR)
    private val retiredReview = IncomingLink.Retired(RetiredFeature.REVIEW)
    private val retiredHandoff = IncomingLink.Retired(RetiredFeature.HANDOFF)

    // ── full URIs route by host ───────────────────────────────────────────────────────────────────

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
        // a Session Handoff offer link names a retired feature: it routes nowhere
        assertEquals(retiredHandoff, parseIncomingLink("ccpocket://handoff?id=h-42"))
    }

    // ── malformed input fails loudly, in the right lane ───────────────────────────────────────────

    @Test
    fun badBase64UnderAKnownHostIsInvalid() {
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://share#!!!not-base64!!!"))
    }

    @Test
    fun unknownHostsAndEmptyInputAreRejected() {
        assertEquals(IncomingLink.Unknown, parseIncomingLink("ccpocket://whatever?x=1"))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("   "))
        assertEquals(IncomingLink.Unknown, parseIncomingLink("https://example.com/collab#abc"))
    }

    // ── bare blobs: paste-only ────────────────────────────────────────────────────────────────────

    @Test
    fun bareShareBlobStillWorksAtThePasteEntry() {
        val bare = share.encode().removePrefix(SHARE_URI_PREFIX)
        val link = parseIncomingLink(bare, allowBareBlob = true)
        assertIs<IncomingLink.Share>(link)
        assertEquals("tkt-2", link.invite.ticket)
    }

    @Test
    fun theShareCodecNeverClaimsACollaboratorBlob() {
        // both were base64url JSON — the paste path tries share first, so this guards the ordering
        assertTrue(decodeShareInvite(collab.legacyUri()) == null)
    }

    // ── retired features: the host alone decides ─────────────────────────────────────────────────

    /** Collaborator Links are retired: `ccpocket://collab` says so whatever its fragment — a current invite,
     *  a v1.6.0 one without a `purpose` key, none at all, corrupt base64, the wrong JSON, another purpose's
     *  blob, a purpose only a newer build knows. Never "invalid link", never a pairing (the repository side
     *  is pinned in RetiredFeaturesTest). */
    @Test
    fun aCollaboratorLinkIsRetiredWhateverItsFragment() {
        val preRelease = dev.ccpocket.app.util.B64Url.encode(
            ("""{"relay":"wss://relay.test","accountId":"acct-a",""" +
                """"daemonPub":"${dev.ccpocket.app.TEST_DAEMON_PUB}","ticket":"tkt-1","ownerLabel":"Panda"}""")
                .encodeToByteArray(),
        )
        val unreadablePurpose = dev.ccpocket.app.util.B64Url.encode(
            ("""{"relay":"wss://relay.test","accountId":"acct-a",""" +
                """"daemonPub":"${dev.ccpocket.app.TEST_DAEMON_PUB}","ticket":"tkt-9","purpose":"pair_programming"}""")
                .encodeToByteArray(),
        )
        listOf(
            collab.legacyUri(),
            COLLAB_INVITE_URI_PREFIX + preRelease,
            "ccpocket://collab#$preRelease",
            "ccpocket://collab",
            "ccpocket://collab#!!!not-base64!!!",
            "ccpocket://collab#" + dev.ccpocket.app.util.B64Url.encode("{}".encodeToByteArray()),
            COLLAB_INVITE_URI_PREFIX + reviewContact.legacyUri().removePrefix(REVIEW_CONTACT_INVITE_URI_PREFIX),
            COLLAB_INVITE_URI_PREFIX + unreadablePurpose,
        ).forEach { raw ->
            assertEquals(retiredCollab, parseIncomingLink(raw), raw)
            assertEquals(retiredCollab, parseIncomingLink(raw, allowBareBlob = true), "the paste entry agrees: $raw")
        }
        // hosts are matched case-insensitively like every host
        listOf("ccpocket://Collab#", "ccpocket://COLLAB#", "CCPOCKET://collab#").forEach { host ->
            assertEquals(retiredCollab, parseIncomingLink(host + preRelease), host)
        }
    }

    /** A bare collaborator blob (the `#…` part, pasted alone) is no invite anywhere any more — and, like every
     *  bare blob, it is not guessed at from a generic deep link either. */
    @Test
    fun aBareCollaboratorBlobIsNoInvite() {
        val bare = collab.legacyUri().removePrefix(COLLAB_INVITE_URI_PREFIX)
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = true))
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = false))
    }

    /** Session Handoff is retired: the offer host is retired whatever its query. */
    @Test
    fun aHandoffLinkIsRetiredWhateverItsQuery() {
        listOf("ccpocket://handoff?id=h-42", "ccpocket://handoff", "ccpocket://handoff?id=", "ccpocket://HANDOFF?id=%zz")
            .forEach { assertEquals(retiredHandoff, parseIncomingLink(it), it) }
    }

    @Test
    fun theReviewHostIsItsOwnRetiredLane() {
        // a review link keeps its own lane — which now says the feature is retired
        assertEquals(retiredReview, parseIncomingLink(reviewContact.legacyUri()))
        // …and a handoff blob under the retired review host is retired, never redeemed
        val handoffBlobUnderReviewHost = REVIEW_CONTACT_INVITE_URI_PREFIX + collab.legacyUri().removePrefix(COLLAB_INVITE_URI_PREFIX)
        assertEquals(retiredReview, parseIncomingLink(handoffBlobUnderReviewHost))
    }

    @Test
    fun aBareReviewBlobIsNoTicketAtThePasteEntry() {
        val bare = reviewContact.legacyUri().removePrefix(REVIEW_CONTACT_INVITE_URI_PREFIX)
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = true))
        // and, like every bare blob, it is not guessed at from a generic deep link
        assertEquals(IncomingLink.Unknown, parseIncomingLink(bare, allowBareBlob = false))
    }

    /** A purpose only a NEWER build knows is not routed into anything live. */
    @Test
    fun anUnreadablePurposeIsAcceptedByNoDoor() {
        val json = """{"relay":"wss://relay.test","accountId":"acct-a",""" +
            """"daemonPub":"${dev.ccpocket.app.TEST_DAEMON_PUB}","ticket":"tkt-9","purpose":"pair_programming"}"""
        val blob = dev.ccpocket.app.util.B64Url.encode(json.encodeToByteArray())
        assertEquals(retiredReview, parseIncomingLink(REVIEW_CONTACT_INVITE_URI_PREFIX + blob))
        assertEquals(IncomingLink.Unknown, parseIncomingLink(blob, allowBareBlob = true))
    }

    /** ReviewRequest is retired: its host alone decides, so a missing or corrupt fragment is retired too —
     *  never "invalid link", and never a failed pairing (the repository side is pinned in RetiredFeaturesTest). */
    @Test
    fun aReviewLinkIsRetiredWhateverItsFragment() {
        val retired = IncomingLink.Retired(RetiredFeature.REVIEW)
        assertEquals(retired, parseIncomingLink("ccpocket://review-contact"))
        assertEquals(retired, parseIncomingLink("ccpocket://review-contact#!!!not-base64!!!"))
        assertEquals(retired, parseIncomingLink(reviewContact.legacyUri(), allowBareBlob = true), "the paste entry agrees")
    }

    /** The retired review host is matched case-insensitively like every host: retired, never redeemed. */
    @Test
    fun caseVariantReviewHostsAreRetired() {
        listOf("ccpocket://Review-Contact#", "ccpocket://REVIEW-CONTACT#", "CCPocket://review-contact#").forEach { host ->
            val blob = reviewContact.legacyUri().substringAfter('#')
            assertEquals(IncomingLink.Retired(RetiredFeature.REVIEW), parseIncomingLink(host + blob), host)
        }
    }
}
