package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.always_allow
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.co_both_ways
import dev.ccpocket.app.resources.co_connect_new
import dev.ccpocket.app.resources.co_connect_cta
import dev.ccpocket.app.resources.co_no_daemon
import dev.ccpocket.app.resources.co_offer_view
import dev.ccpocket.app.resources.ho_accept
import dev.ccpocket.app.resources.ho_accepting
import dev.ccpocket.app.resources.ho_access_honest
import dev.ccpocket.app.resources.ho_access_readonly
import dev.ccpocket.app.resources.ho_access_review_title
import dev.ccpocket.app.resources.ho_b_see_cmds
import dev.ccpocket.app.resources.ho_bash_recorded
import dev.ccpocket.app.resources.ho_copy_invite
import dev.ccpocket.app.resources.ho_decline
import dev.ccpocket.app.resources.ho_honest_shell
import dev.ccpocket.app.resources.ho_kind_continue_title
import dev.ccpocket.app.resources.ho_mark_reviewed
import dev.ccpocket.app.resources.ho_not_supported
import dev.ccpocket.app.resources.ho_recall
import dev.ccpocket.app.resources.ho_view_invite
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.handoff.CollaboratorPickerPage
import dev.ccpocket.app.ui.handoff.ConfirmConnectionScreen
import dev.ccpocket.app.ui.handoff.FindingSeverity
import dev.ccpocket.app.ui.handoff.HandoffAcceptScreen
import dev.ccpocket.app.ui.handoff.HandoffFindingUi
import dev.ccpocket.app.ui.handoff.HandoffLockBanner
import dev.ccpocket.app.ui.handoff.HandoffOfferCard
import dev.ccpocket.app.ui.handoff.HandoffResultCard
import dev.ccpocket.app.ui.handoff.HandoffResultUi
import dev.ccpocket.app.ui.handoff.handoffRecipients
import dev.ccpocket.app.ui.handoff.recentHandoffRecipients
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffKind
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.collaboratorFingerprint
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handoff/collaborator UI states as behaviour tests (design session-handoff/ + -contacts/):
 * each state the two-role repo test proves the DATA for, this file proves the CHROME for —
 * picker rows select, the offer is a doorway, the lock banner's actions match the delivery mode.
 */
@OptIn(ExperimentalTestApi::class)
class HandoffUiStateTest {

    private fun str(res: org.jetbrains.compose.resources.StringResource, vararg args: Any) =
        runBlocking { getString(res, *args) }

    private val frank = Collaborator("dev-frank", "Frank", CollaboratorDirection.MUTUAL, connectedAt = 1, lastHandoffAt = 5)
    private val aiko = Collaborator("dev-aiko", "Aiko", CollaboratorDirection.OUTBOUND, connectedAt = 2, hasDaemon = false)

    // a ReviewRequest peer (somebody's DAEMON) and a purpose this build can't read — neither is a
    // Session Handoff recipient (§13.3). Mika deliberately carries the NEWEST lastHandoffAt.
    private val mika = Collaborator(
        "dev-mika", "Mika", CollaboratorDirection.OUTBOUND, connectedAt = 3, lastHandoffAt = 9,
        purpose = CollaboratorPurpose.REVIEW,
    )
    private val nova = Collaborator(
        "dev-nova", "Nova", CollaboratorDirection.OUTBOUND, connectedAt = 4,
        purpose = CollaboratorPurpose.UNKNOWN,
    )

    // ── Frame 1: picker rows select-and-pop; the QR path is exactly one row ──

    @Test
    fun picker_tapSelectsContact_andConnectRowIsPresent() = runComposeUiTest {
        var picked: Collaborator? = null
        setContent {
            PocketTheme { CollaboratorPickerPage(listOf(frank, aiko), onPick = { picked = it }, onConnectNew = {}, onBack = {}) }
        }
        onAllNodes(hasText("Aiko")).onFirst().performClick()
        assertEquals("dev-aiko", picked?.deviceId)
        onAllNodes(hasText(str(Res.string.co_connect_new))).assertCountEquals(1) // the only QR doorway
        onAllNodes(hasText(str(Res.string.co_no_daemon))).assertCountEquals(1)   // quiet fact, still selectable
    }

    // ── §13.3 boundary: the picker offers RECIPIENTS, and a REVIEW peer is not one ──

    @Test
    fun recipientHelpers_offerOnlySessionHandoffContacts() {
        val gone = frank.copy(deviceId = "dev-gone", label = "Gone", removed = true)
        val all = listOf(frank, aiko, mika, nova, gone)
        assertEquals(
            listOf("dev-frank", "dev-aiko"), handoffRecipients(all).map { it.deviceId },
            "a legacy/default SESSION_HANDOFF contact stays offered; REVIEW, UNKNOWN and removed do not",
        )
        assertEquals(listOf("dev-frank"), handoffRecipients(all, "fra").map { it.deviceId }, "search filters the SAME set")
        assertEquals(
            listOf("dev-frank"), recentHandoffRecipients(all).map { it.deviceId },
            "RECENT ranks by lastHandoffAt but never promotes an ineligible contact into the picker",
        )
    }

    @Test
    fun picker_hidesReviewPeers_andKeepsLegacyContacts() = runComposeUiTest {
        setContent {
            PocketTheme { CollaboratorPickerPage(listOf(frank, mika, nova), onPick = {}, onConnectNew = {}, onBack = {}) }
        }
        assertTrue(present("Frank"), "a contact minted before `purpose` existed is still a handoff recipient")
        assertFalse(present("Mika"), "a REVIEW contact is a colleague's daemon — selecting it could only fail at send")
        assertFalse(present("Nova"), "an unreadable purpose fails closed")
    }

    @Test
    fun picker_reviewOnlyContacts_showTheConnectEmptyState() = runComposeUiTest {
        var connect = false
        setContent {
            PocketTheme { CollaboratorPickerPage(listOf(mika), onPick = {}, onConnectNew = { connect = true }, onBack = {}) }
        }
        assertFalse(present("Mika"))
        onAllNodes(hasText(str(Res.string.co_connect_cta))).onFirst().performClick()
        assertTrue(connect, "with no eligible recipient the picker is the first-run empty state, not an empty list")
    }

    @Test
    fun picker_emptyState_offersOnlyConnect() = runComposeUiTest {
        var connect = false
        setContent {
            PocketTheme { CollaboratorPickerPage(emptyList(), onPick = {}, onConnectNew = { connect = true }, onBack = {}) }
        }
        onAllNodes(hasText(str(Res.string.co_connect_cta))).onFirst().performClick()
        assertTrue(connect)
    }

    // ── Frame 8 delta: the banner's actions follow the delivery mode ──

    @Test
    fun lockBanner_directDelivery_hasRecallOnly() = runComposeUiTest {
        setContent {
            PocketTheme {
                HandoffLockBanner("Frank", metaLine = "offer sent", onCopyInvite = {}, onRecall = {}, directDelivery = true)
            }
        }
        onAllNodes(hasText(str(Res.string.ho_recall))).assertCountEquals(1)
        onAllNodes(hasText(str(Res.string.ho_copy_invite))).assertCountEquals(0) // no artefact to copy
    }

    @Test
    fun lockBanner_legacyInvite_keepsCopyInvite_andNonInitiatorSeesViewInvite() = runComposeUiTest {
        setContent {
            PocketTheme {
                androidx.compose.foundation.layout.Column {
                    HandoffLockBanner("Frank", "meta", onCopyInvite = {}, onRecall = {})
                    HandoffLockBanner("Frank", "meta", onCopyInvite = {}, onRecall = {}, onViewInvite = {})
                }
            }
        }
        onAllNodes(hasText(str(Res.string.ho_copy_invite))).assertCountEquals(1)
        onAllNodes(hasText(str(Res.string.ho_view_invite))).assertCountEquals(1)
    }

    // ── Frame 7: the offer card is a doorway — View/Decline fire, no scope details listed ──

    @Test
    fun offerCard_viewAndDeclineFire() = runComposeUiTest {
        var viewed = false; var declined = false
        val offer = SessionHandoff(
            id = "h1", sourceSessionId = "s", workdir = "/w/cc-pocket",
            initiatorLabel = "Panda", recipientDeviceId = "dev-frank", status = HandoffStatus.WAITING,
        )
        setContent { PocketTheme { HandoffOfferCard(offer, onView = { viewed = true }, onDecline = { declined = true }) } }
        onAllNodes(hasText(str(Res.string.co_offer_view))).onFirst().performClick()
        onAllNodes(hasText(str(Res.string.ho_decline))).onFirst().performClick()
        assertTrue(viewed); assertTrue(declined)
    }

    // ── Frame 4: the fingerprint renders the same words both sides derive from the key ──

    @Test
    fun confirmScreen_rendersDerivedFingerprintWords() = runComposeUiTest {
        val invite = CollaboratorInvite(relay = "wss://r", accountId = "a", daemonPub = "PUBKEY_TEST", ticket = "t", ownerLabel = "Panda")
        setContent { PocketTheme { ConfirmConnectionScreen(invite, confirming = false, onConfirm = {}, onCancel = {}) } }
        val firstLine = collaboratorFingerprint("PUBKEY_TEST").substringBefore("·").trim().replace("-", " — ")
        onAllNodes(hasText(firstLine)).assertCountEquals(1)
    }

    @Test
    fun confirmScreen_cancelNeverRedeems() = runComposeUiTest {
        var confirmed = false; var cancelled = false
        val invite = CollaboratorInvite(relay = "wss://r", accountId = "a", daemonPub = "PUBKEY_TEST", ticket = "t", ownerLabel = "Panda")
        setContent {
            PocketTheme { ConfirmConnectionScreen(invite, confirming = false, onConfirm = { confirmed = true }, onCancel = { cancelled = true }) }
        }
        onAllNodes(hasText(str(Res.string.cancel))).onFirst().performClick()
        assertTrue(cancelled)
        assertFalse(confirmed, "backing out of the fingerprint screen must never mint a credential (§7)")
    }

    // ── §2.2 / §6: the accept screen says what actually holds, and only for grants v1 implements ──

    @Test
    fun acceptScreen_reviewStatesTheHonestBoundaryInsteadOfBareReadOnly() = runComposeUiTest {
        setContent {
            PocketTheme {
                HandoffAcceptScreen(
                    ownerLabel = "Panda", sessionTitle = "relay ACK", path = "/w/cc-pocket", branch = null,
                    returnsIn = "2h", roots = listOf("/w/cc-pocket"), briefSections = emptyList(),
                    expiredNote = null, accepting = false, onAccept = {}, onDecline = {}, onClose = {},
                    kind = HandoffKind.REVIEW, access = HandoffAccess.REVIEW_READ_ONLY,
                )
            }
        }
        // the words the review demands, on the screen where consent happens
        assertTrue(present(str(Res.string.ho_access_review_title)))
        assertTrue(present(str(Res.string.ho_access_honest)))
        assertTrue(present(str(Res.string.ho_b_see_cmds)), "shell is listed as something the recipient CAN do")
        assertTrue(present(str(Res.string.ho_honest_shell)), "…and the footer says an approved command can still write")
        assertFalse(present(str(Res.string.ho_access_readonly)), "the bare \"Read-only\" claim is gone")
    }

    @Test
    fun acceptScreen_rendersTheDaemonsGrantAndRefusesUnimplementedCombinations() = runComposeUiTest {
        var accepted = false
        setContent {
            PocketTheme {
                HandoffAcceptScreen(
                    ownerLabel = "Panda", sessionTitle = "relay ACK", path = "/w/cc-pocket", branch = null,
                    returnsIn = "2h", roots = listOf("/w/cc-pocket"), briefSections = emptyList(),
                    expiredNote = null, accepting = false, onAccept = { accepted = true }, onDecline = {}, onClose = {},
                    // a CONTINUE grant a raw client could have created: v1 doesn't implement it
                    kind = HandoffKind.CONTINUE, access = HandoffAccess.CONTINUE_SCOPED,
                )
            }
        }
        assertTrue(present(str(Res.string.ho_kind_continue_title)), "the screen states the REAL kind, not \"Code review\"")
        assertTrue(present(str(Res.string.ho_not_supported)))
        onAllNodes(hasText(str(Res.string.ho_accept))).onFirst().performClick()
        assertFalse(accepted, "an unimplemented grant can't be accepted (§6)")
    }

    @Test
    fun acceptScreen_waitsForTheDaemonInsteadOfPretending() = runComposeUiTest {
        var accepted = false
        setContent {
            PocketTheme {
                HandoffAcceptScreen(
                    ownerLabel = "Panda", sessionTitle = "relay ACK", path = "/w", branch = null,
                    returnsIn = "2h", roots = listOf("/w"), briefSections = emptyList(),
                    expiredNote = null, accepting = true, onAccept = { accepted = true }, onDecline = {}, onClose = {},
                    errorNote = null,
                )
            }
        }
        assertTrue(present(str(Res.string.ho_accepting)), "the button holds a real waiting state (§3.2.7)")
        onAllNodes(hasText(str(Res.string.ho_accepting))).onFirst().performClick()
        assertFalse(accepted, "a second tap while waiting is not a second accept")
    }

    // ── §2.2: a Bash approval during a REVIEW handoff loses "Always allow" and says it's recorded ──

    @Test
    fun permissionSheet_bashUnderReviewHandoffIsPerCommandAndRecorded() = runComposeUiTest {
        // the card runs a live auto-deny countdown; let it tick and the sheet flips to its terminal
        // state mid-assertion — freeze the clock so we are looking at the DECISION card
        mainClock.autoAdvance = false
        val ask = dev.ccpocket.protocol.PermissionAsk(
            askId = "a1", convoId = "c1", tool = "Bash", title = "Run command",
            inputPreview = "rm -rf build", rule = "Bash(rm:*)",
        )
        setContent { PocketTheme { PermissionSheet(ask, "/w", handoffReview = true, onDeny = {}, onOnce = {}, onAlways = {}, onDismiss = {}) } }
        assertTrue(present(str(Res.string.ho_bash_recorded)))
        assertFalse(present(str(Res.string.always_allow)), "a remembered shell rule would undo \"confirmed one by one\"")
    }

    @Test
    fun permissionSheet_bashOutsideAHandoffKeepsAlwaysAllow() = runComposeUiTest {
        // the card runs a live auto-deny countdown; let it tick and the sheet flips to its terminal
        // state mid-assertion — freeze the clock so we are looking at the DECISION card
        mainClock.autoAdvance = false
        val ask = dev.ccpocket.protocol.PermissionAsk(
            askId = "a1", convoId = "c1", tool = "Bash", title = "Run command",
            inputPreview = "ls", rule = "Bash(ls:*)",
        )
        setContent { PocketTheme { PermissionSheet(ask, "/w", onDeny = {}, onOnce = {}, onAlways = {}, onDismiss = {}) } }
        assertTrue(present(str(Res.string.always_allow)), "the ordinary approval card is untouched")
        assertFalse(present(str(Res.string.ho_bash_recorded)))
    }

    @Test
    fun permissionSheet_nonShellToolsUnderAHandoffKeepAlwaysAllow() = runComposeUiTest {
        // the card runs a live auto-deny countdown; let it tick and the sheet flips to its terminal
        // state mid-assertion — freeze the clock so we are looking at the DECISION card
        mainClock.autoAdvance = false
        val ask = dev.ccpocket.protocol.PermissionAsk(
            askId = "a1", convoId = "c1", tool = "WebFetch", title = "Fetch", inputPreview = "https://x", rule = "WebFetch",
        )
        setContent { PocketTheme { PermissionSheet(ask, "/w", handoffReview = true, onDeny = {}, onOnce = {}, onAlways = {}, onDismiss = {}) } }
        assertTrue(present(str(Res.string.always_allow)), "only the command runner loses the standing rule")
    }

    // ── Frame 9: Mark reviewed is the one COMPLETED transition on the result card ──

    @Test
    fun resultCard_markReviewedFires() = runComposeUiTest {
        var completed = false
        val ui = HandoffResultUi(
            verdict = "Approve with fixes",
            findings = listOf(HandoffFindingUi(FindingSeverity.HIGH, "Race in refresh", "R.kt:88")),
            returnedByLabel = "Frank",
        )
        setContent { PocketTheme { HandoffResultCard(ui, onMarkReviewed = { completed = true }, onOpenFull = {}) } }
        onAllNodes(hasText(str(Res.string.ho_mark_reviewed))).onFirst().performClick()
        assertTrue(completed)
    }

    // ── contacts detail chip law: mutual shows Both ways (green completion, never presence) ──

    @Test
    fun mutualDirection_showsBothWaysChip() = runComposeUiTest {
        setContent {
            PocketTheme {
                dev.ccpocket.app.ui.handoff.CollaboratorDetailScreen(frank, history = emptyList(), onRemove = {}, onBack = {})
            }
        }
        onAllNodes(hasText("✓ " + str(Res.string.co_both_ways))).assertCountEquals(1)
    }
}
