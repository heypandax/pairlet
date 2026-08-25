package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.pairing.decodeCollaboratorInvite
import dev.ccpocket.app.pairing.decodeReviewContactInvite
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_join_accept
import dev.ccpocket.app.resources.rv_join_bad
import dev.ccpocket.app.resources.rv_loading
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.review.ReviewInviteScreen
import dev.ccpocket.app.ui.review.ReviewJoinScreen
import dev.ccpocket.app.ui.review.inviteFingerprint
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.collaboratorFingerprint
import dev.ccpocket.protocol.e2e.E2ECrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BILATERAL fingerprint verification, in the UI (REVIEW-REQUEST.md §4.1).
 *
 * "Compare the fingerprint" only detects anything when BOTH people can read the words. The joiner's
 * screen has always shown them; if the inviter's does not, one side is confirming a value the other
 * cannot check — a ritual that catches nothing, including the case it exists for (the wrong QR, or a
 * relay that swapped a key).
 *
 * These run against a REAL generated daemon key, not a placeholder: the fingerprint is derived from
 * exactly the bytes a peer would pin, and the invite codec now refuses anything that is not a usable
 * P-256 key — so a fixture that isn't one would silently stop testing the path.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewFingerprintUiTest {

    private val daemonKey = E2ECrypto.generateKeyPair()
    private val daemonPub = B64Url.encode(daemonKey.publicRaw)

    /** REVIEW-purpose: that is what a Review Center mints, and it is what decides the URI door the
     *  invite is published under (REVIEW-REQUEST.md §13.3). */
    private fun invite(pub: String = daemonPub) = CollaboratorInvite(
        relay = "wss://relay.example", accountId = "acct-frank", daemonPub = pub,
        ticket = "ONE-TIME-TICKET", ownerLabel = "Frank · MacBook", ttlSec = 120,
        purpose = CollaboratorPurpose.REVIEW,
    )

    /** As [FingerprintBlock] renders it: one line per half, hyphens spaced out for reading aloud. */
    private fun renderedLines(fingerprint: String) =
        fingerprint.split("·").map { it.trim().replace("-", " — ") }.filter { it.isNotEmpty() }

    @Test
    fun theInviterSeesTheSameWordsTheJoinerIsAskedToConfirm() = runComposeUiTest {
        val uri = invite().encode()
        val expected = collaboratorFingerprint(daemonPub)
        val lines = renderedLines(expected)
        assertEquals(2, lines.size, "sanity: a fingerprint renders as two halves")

        // the INVITER's screen, derived from the minted URI alone — no extra wire field to get wrong
        setContent { PocketTheme { ReviewInviteScreen(invite = uri, ttlSec = 120, creating = false, error = null, copied = false, onCopy = {}) } }
        lines.forEach { assertPresent(it) }

        // the JOINER's confirmation, driven the way a human reaches it: paste, then look before accepting
        setContent { PocketTheme { ReviewJoinScreen(joining = false, error = null, onJoin = {}) } }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput(uri)
        onAllNodes(hasText(str(Res.string.rv_join_accept))).onFirst().performClick()
        // IDENTICAL words on both screens, from one key — that is the whole content of the check
        lines.forEach { assertPresent(it) }
        assertEquals(expected, inviteFingerprint(uri))
    }

    /** The inviter's screen must not invent a fingerprint before there is an invite to fingerprint. */
    @Test
    fun nothingIsShownWhileTheInviteIsStillBeingMinted() = runComposeUiTest {
        setContent { PocketTheme { ReviewInviteScreen(invite = null, ttlSec = 0, creating = true, error = null, copied = false, onCopy = {}) } }
        assertTrue(present(str(Res.string.rv_loading)))
    }

    /**
     * A fingerprint of a key no handshake can complete is a convincing word group for an identity that
     * does not exist — a human would verify it. So the decoder refuses the key first, and both the
     * invite and the join screens derive from that same decode.
     */
    @Test
    fun onlyARealDaemonKeyEverProducesWordsToCompare() {
        assertNotNull(decodeReviewContactInvite(invite().encode()), "a real key decodes")
        assertNotNull(inviteFingerprint(invite().encode()))

        // 32 bytes — the size an X25519-shaped assumption would have accepted; the suite is P-256/65
        assertNull(inviteFingerprint(invite(B64Url.encode(ByteArray(32) { 9 })).encode()))
        // 65 bytes, wrong prefix
        val wrongPrefix = daemonKey.publicRaw.copyOf().also { it[0] = 3 }
        assertNull(inviteFingerprint(invite(B64Url.encode(wrongPrefix)).encode()))
        // 65 bytes, 0x04 prefix, coordinates that are not on the curve
        val offCurve = daemonKey.publicRaw.copyOf().also { it[40] = (it[40] + 1).toByte() }
        assertNull(inviteFingerprint(invite(B64Url.encode(offCurve)).encode()))
        // and plain garbage
        assertNull(inviteFingerprint("ccpocket://review-contact#not-base64url-at-all"))
        assertNull(inviteFingerprint(""))
    }

    /**
     * The join screen fingerprints REVIEW tickets only (REVIEW-REQUEST.md §13.3). A Session Handoff
     * invite pasted here is somebody's App — showing words for it would walk the user through a
     * comparison ritual that ends in a redeem the daemon must refuse anyway, one ticket later.
     */
    @Test
    fun theJoinScreenDoesNotFingerprintASessionHandoffTicket() {
        val handoff = invite().copy(purpose = dev.ccpocket.protocol.CollaboratorPurpose.SESSION_HANDOFF)
        assertNull(inviteFingerprint(handoff.encode()), "the other door's ticket has no words to compare here")
        assertNotNull(decodeCollaboratorInvite(handoff.encode()), "…it is perfectly valid at its OWN door")
        // …and the same holds for the bare blob, where only the embedded purpose is left to judge on
        assertNull(inviteFingerprint(handoff.encode().removePrefix(dev.ccpocket.app.pairing.COLLAB_URI_PREFIX)))
    }

    /**
     * A `ccpocket://review-contact#` deep link lands on the FINGERPRINT step, not on a redeem
     * (REVIEW-REQUEST.md §13.3). Routing a link into a screen is not the user agreeing to these
     * particular words — and the ticket is single use, so a screen that acted on arrival would spend it
     * before anybody had a chance to compare anything.
     */
    @Test
    fun aRoutedInviteShowsItsWordsAndStillWaitsForAHumanYes() = runComposeUiTest {
        val uri = invite().encode()
        val lines = renderedLines(collaboratorFingerprint(daemonPub))
        var joinedWith: String? = null

        setContent {
            PocketTheme {
                ReviewJoinScreen(joining = false, error = null, prefill = uri, onJoin = { joinedWith = it })
            }
        }

        // the words are up for comparison…
        lines.forEach { assertPresent(it) }
        assertNull(joinedWith, "a deep link must not redeem on sight")

        // …and only the explicit accept hands the line to the daemon
        onAllNodes(hasText(str(Res.string.rv_join_accept))).onFirst().performClick()
        assertEquals(uri, joinedWith)
    }

    /** A routed link this build cannot read is refused HONESTLY — it says so, and still consumes
     *  nothing, so the user can take the same line to a machine that understands it. */
    @Test
    fun anUnreadableRoutedInviteIsRefusedWithoutBeingConsumed() = runComposeUiTest {
        var joinedWith: String? = null
        setContent {
            PocketTheme {
                ReviewJoinScreen(
                    joining = false, error = null,
                    prefill = invite().copy(purpose = dev.ccpocket.protocol.CollaboratorPurpose.SESSION_HANDOFF).encode(),
                    onJoin = { joinedWith = it },
                )
            }
        }
        assertTrue(present(str(Res.string.rv_join_bad)), "the wrong door says so rather than failing silently")
        assertNull(joinedWith)
    }

    /** The App's decode and the daemon's must agree, or one end offers to establish what the other
     *  refuses — the confusing half-failure a user cannot diagnose. */
    @Test
    fun theAppAndTheDaemonAgreeOnWhatCountsAsAUsableKey() {
        val real = daemonKey.publicRaw
        assertTrue(E2ECrypto.isValidPublicKey(real))
        assertTrue(!E2ECrypto.isValidPublicKey(ByteArray(32) { 9 }))
        assertTrue(!E2ECrypto.isValidPublicKey(real.copyOf().also { it[0] = 3 }))
        assertTrue(!E2ECrypto.isValidPublicKey(real.copyOf().also { it[40] = (it[40] + 1).toByte() }))
        assertTrue(!E2ECrypto.isValidPublicKey(ByteArray(0)))
    }
}
