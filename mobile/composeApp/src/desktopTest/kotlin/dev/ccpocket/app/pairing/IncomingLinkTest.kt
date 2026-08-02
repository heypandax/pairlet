package dev.ccpocket.app.pairing

import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.ShareInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        relay = "wss://relay.test", accountId = "acct-a", daemonPub = "PUBKEY", ticket = "tkt-1", ownerLabel = "Panda",
    )
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
}
