package dev.ccpocket.app.pairing

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ShareInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The folder-share invite codec (issue #115): a self-contained invite must round-trip through the QR /
 *  copyable-code string, decode tolerantly from a bare blob, and reject garbage instead of crashing. */
class ShareInviteCodecTest {
    private val sample = ShareInvite(
        relay = "wss://relay.example", accountId = "acct-123", daemonPub = "pubkey-abc",
        ticket = "ticket-xyz", folderName = "acme-api", tier = AccessTier.COLLABORATE,
        expiresAt = 1_700_000_000_000L, ttlSec = 900, ownerLabel = "Alex-Mac",
    )

    @Test fun roundTrips() {
        val s = sample.encode()
        assertTrue(s.startsWith(SHARE_URI_PREFIX))
        assertEquals(sample, decodeShareInvite(s))
    }

    @Test fun decodesBareBlobWithoutScheme() {
        val blob = sample.encode().removePrefix(SHARE_URI_PREFIX)
        assertEquals(sample, decodeShareInvite(blob))
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals(sample, decodeShareInvite("  \n" + sample.encode() + "  "))
    }

    @Test fun rejectsGarbage() {
        assertNull(decodeShareInvite("not a real invite code"))
        assertNull(decodeShareInvite(""))
        assertNull(decodeShareInvite(SHARE_URI_PREFIX)) // empty fragment
    }

    @Test fun toPairingInfoCarriesRedeemFields() {
        val p = sample.toPairingInfo()
        assertEquals(sample.relay, p.relay)
        assertEquals(sample.accountId, p.accountId)
        assertEquals(sample.daemonPub, p.daemonPub)
        assertEquals(sample.ticket, p.ticket)
    }
}
