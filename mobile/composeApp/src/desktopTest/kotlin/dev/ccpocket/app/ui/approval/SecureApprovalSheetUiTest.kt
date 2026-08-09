package dev.ccpocket.app.ui.approval

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow_for_task
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.allow_session_option
import dev.ccpocket.app.resources.always_allow
import dev.ccpocket.app.resources.ap_fail_closed
import dev.ccpocket.app.resources.ap_authority_wait_title
import dev.ccpocket.app.resources.ap_legacy_note
import dev.ccpocket.app.resources.ap_more_options
import dev.ccpocket.app.resources.ap_required
import dev.ccpocket.app.resources.ap_waiting_title
import dev.ccpocket.app.resources.auto_denied_title
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.dismiss
import dev.ccpocket.app.resources.retry_safer
import dev.ccpocket.app.resources.risk_high
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionRiskUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Secure Approval SHELL: what the sheet actually puts on screen, and — more importantly — what it
 * refuses to do. The classifier is pinned separately in [ApprovalUiModelTest]; this file is the chrome.
 *
 * System back cannot be exercised here: the desktop `SystemBackHandler` actual is a no-op, so back
 * interception is a construction guarantee (`SystemBackHandler(enabled = true) { }`) rather than an
 * assertion. The scrim — the other half of "a stray tap answers nothing" — IS asserted below.
 */
@OptIn(ExperimentalTestApi::class)
class SecureApprovalSheetUiTest {

    private fun ask(
        grants: List<String>? = listOf("once", "task", "session"),
        danger: Boolean = false,
        noAutoDeny: Boolean = false,
        timeoutSec: Int? = 600,
    ) = PermissionAsk(
        convoId = "c1", askId = "a1", tool = "Bash", title = "Run command",
        inputPreview = "./gradlew :protocol:allTests", rule = "Bash(./gradlew:*)",
        grantOptions = grants, danger = danger, noAutoDeny = noAutoDeny, timeoutSec = timeoutSec,
    )

    // ── the security contract: nothing outside a decision resolves the request ─────────────────────

    @Test
    fun theOverlayDeclaresItselfAsADialog() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(approvalUi(ask()), onDeny = {}, onAllowOnce = {})
            }
        }
        onNode(isDialog()).assertExists()
    }

    @Test
    fun scrimTapsResolveNothing() = runComposeUiTest {
        mainClock.autoAdvance = false
        var decisions = 0
        var dismissed = 0
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask(), workdir = "/w/cc-pocket"),
                    onDeny = { decisions++ }, onAllowOnce = { decisions++ }, onAllowTask = { decisions++ },
                    onAllowSession = { decisions++ }, onAlwaysAllow = { decisions++ },
                    onRetrySafer = { decisions++ }, onDismiss = { dismissed++ },
                )
            }
        }
        // the sheet is bottom-anchored; the top-left corner is scrim by construction
        onRoot().performTouchInput { click(Offset(4f, 4f)) }
        assertEquals(0, decisions, "a scrim tap must never answer for the user")
        assertEquals(0, dismissed, "…and must not close the sheet either")
        assertPresent(str(Res.string.ap_required), substring = true)

        // positive control: the same harness DOES deliver a tap to a real decision, so the assertion
        // above is about the scrim swallowing it — not about clicks being inert in this test
        onAllNodes(hasText(str(Res.string.deny))).onFirst().performClick()
        assertEquals(1, decisions)
    }

    @Test
    fun aLongPayloadGrowsTheBodyAndNeverPushesADecisionOffScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        val huge = (1..400).joinToString("\n") { "+ added line $it, long enough that it has to wrap on a phone" }
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask().copy(diff = huge), workdir = "/w/cc-pocket"),
                    onDeny = {}, onAllowOnce = {},
                )
            }
        }
        // pinned header and pinned decisions both stay on screen; only the body scrolled
        onAllNodes(hasText(str(Res.string.ap_required))).onFirst().assertIsDisplayed()
        onAllNodes(hasText(str(Res.string.deny))).onFirst().assertIsDisplayed()
        onAllNodes(hasText(str(Res.string.allow_once))).onFirst().assertIsDisplayed()
        onAllNodes(hasText(str(Res.string.allow_for_task))).onFirst().assertIsDisplayed()
    }

    // ── ordinary V2: the offered set, neutral, with session one tap away ───────────────────────────

    @Test
    fun ordinaryV2ShowsTheOfferedActionsAndHidesSessionBehindMoreOptions() = runComposeUiTest {
        mainClock.autoAdvance = false
        var session = 0
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask(), workdir = "/w/cc-pocket"),
                    onDeny = {}, onAllowOnce = {}, onAllowSession = { session++ },
                )
            }
        }
        assertPresent(str(Res.string.deny))
        assertPresent(str(Res.string.allow_once))
        assertPresent(str(Res.string.retry_safer))
        assertPresent(str(Res.string.allow_for_task))
        assertFalse(present(str(Res.string.always_allow)), "a grant-aware daemon never shows the legacy rule")
        assertFalse(
            present(str(Res.string.allow_session_option)),
            "the broadest grant is not on the main card",
        )

        onAllNodes(hasText(str(Res.string.ap_more_options))).onFirst().performClick()
        assertPresent(str(Res.string.allow_session_option))
        assertEquals(0, session, "revealing the option is not taking it")
        onAllNodes(hasText(str(Res.string.allow_session_option))).onFirst().performClick()
        assertEquals(1, session)
    }

    @Test
    fun v2WithoutTaskScopeDrawsNoTaskTile() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(approvalUi(ask(grants = listOf("once"))), onDeny = {}, onAllowOnce = {})
            }
        }
        assertPresent(str(Res.string.retry_safer))
        assertFalse(present(str(Res.string.allow_for_task)), "no cell is reserved for an unoffered scope")
        assertFalse(present(str(Res.string.ap_more_options)))
        assertFalse(present(str(Res.string.always_allow)))
    }

    @Test
    fun dangerKeepsTheSameActionsAndSurfacesItsNote() = runComposeUiTest {
        mainClock.autoAdvance = false
        val note = "Removes every untracked file in the working tree."
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(
                        ask(danger = true).copy(dangerNote = note),
                        risk = PermissionRiskUpdated("c1", "a1", "high", reason = "deletes files"),
                    ),
                    onDeny = {}, onAllowOnce = {},
                )
            }
        }
        assertPresent(str(Res.string.deny))
        assertPresent(str(Res.string.allow_once))
        assertPresent(str(Res.string.retry_safer))
        assertPresent(str(Res.string.allow_for_task), substring = true)
        assertPresent(note)
        assertPresent(str(Res.string.risk_high))
        assertPresent("deletes files")
    }

    // ── legacy peer ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun legacyShowsAlwaysAllowAndItsCompatibilityNote() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme(mode = ThemeMode.LIGHT) {
                SecureApprovalSheet(approvalUi(ask(grants = null)), onDeny = {}, onAllowOnce = {})
            }
        }
        assertPresent(str(Res.string.always_allow))
        assertPresent(str(Res.string.ap_legacy_note))
        assertFalse(present(str(Res.string.retry_safer)), "a pre-M2 daemon can't act on a structured retry")

        val denyBounds = onAllNodes(hasText(str(Res.string.deny))).onFirst().getUnclippedBoundsInRoot()
        val alwaysBounds = onAllNodes(hasText(str(Res.string.always_allow))).onFirst().getUnclippedBoundsInRoot()
        val denyWidth = denyBounds.right - denyBounds.left
        val alwaysWidth = alwaysBounds.right - alwaysBounds.left
        assertTrue(
            alwaysWidth <= denyWidth + 1.dp,
            "a persistent legacy rule must not have a larger hit target than a one-time action",
        )
    }

    @Test
    fun aGrantAwarePeerCarriesNoLegacyNote() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { PocketTheme { SecureApprovalSheet(approvalUi(ask()), onDeny = {}, onAllowOnce = {}) } }
        assertFalse(present(str(Res.string.ap_legacy_note)))
    }

    // ── #201 · noAutoDeny says what is true and draws no clock ─────────────────────────────────────

    @Test
    fun noAutoDenyShowsAWaitingStateWithNoTimerAndNoInfinity() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask(grants = listOf("once"), noAutoDeny = true, timeoutSec = 86_400)),
                    onDeny = {}, onAllowOnce = {},
                )
            }
        }
        assertPresent(str(Res.string.ap_waiting_title))
        assertFalse(present("∞"), "the renewal chain is bounded — an infinity glyph would be a promise, not a fact")
        assertFalse(
            present(str(Res.string.ap_fail_closed)),
            "no fail-closed sentence: this ask is renewed, not expired",
        )
        assertFalse(present("86400s"), "and no number, because there is no deadline to count to")
        // it is still answerable whenever the user gets back — that is the whole point of the mode
        assertPresent(str(Res.string.deny))
        assertPresent(str(Res.string.allow_once))
    }

    @Test
    fun aReEmittedAskWithANewWindowRestartsOnlyTheDisplayClock() = runComposeUiTest {
        mainClock.autoAdvance = false
        var current by mutableStateOf(approvalUi(ask(timeoutSec = 2)))
        setContent {
            PocketTheme {
                SecureApprovalSheet(current, onDeny = {}, onAllowOnce = {})
            }
        }
        assertPresent("2s")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertPresent("1s")

        // A renewal keeps (convoId, askId), but the authoritative timeout window changes.
        current = approvalUi(ask(timeoutSec = 9))
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertPresent("9s")
    }

    @Test
    fun grantAwareLocalZeroShowsTheAuthoritativeWaitingStateAndRemainsAnswerable() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask(grants = listOf("once"), timeoutSec = 1)),
                    onDeny = {}, onAllowOnce = {},
                )
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        assertPresent(str(Res.string.ap_authority_wait_title))
        assertFalse(present("0s"), "local zero is not the daemon's verdict")
        assertFalse(present(str(Res.string.ap_fail_closed)), "the UI must not promise an auto-deny that has not happened")
        assertPresent(str(Res.string.deny))
        assertPresent(str(Res.string.allow_once))
    }

    // ── the one dismissible state ──────────────────────────────────────────────────────────────────

    @Test
    fun theTimeoutTerminalReplacesEveryDecisionWithASingleDismiss() = runComposeUiTest {
        mainClock.autoAdvance = false
        var decisions = 0
        var dismissed = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(
                    approvalUi(ask(), workdir = "/w/cc-pocket", timedOutSignal = true),
                    onDeny = { decisions++ }, onAllowOnce = { decisions++ }, onAllowTask = { decisions++ },
                    onAllowSession = { decisions++ }, onAlwaysAllow = { decisions++ },
                    onDismiss = { dismissed = true },
                )
            }
        }
        // the request stays readable — a card that silently vanished read as success
        assertPresent("./gradlew :protocol:allTests", substring = true)
        assertPresent(str(Res.string.auto_denied_title))
        assertFalse(present(str(Res.string.allow_once)), "the CLI stopped waiting — offering Allow would lie")
        assertFalse(present(str(Res.string.deny)))
        assertFalse(present(str(Res.string.allow_for_task)))
        assertFalse(present(str(Res.string.ap_more_options)))

        onAllNodes(hasText(str(Res.string.dismiss))).onFirst().performClick()
        assertTrue(dismissed)
        assertEquals(0, decisions, "a late tap never sends a verdict")
    }

    // ── the body carries the literal request, not a summary of it ──────────────────────────────────

    @Test
    fun theBodyShowsTitleToolLiteralPayloadAndTheRealProject() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme {
                SecureApprovalSheet(approvalUi(ask(), workdir = "/w/cc-pocket"), onDeny = {}, onAllowOnce = {})
            }
        }
        assertPresent("Run command")
        assertPresent("Bash")
        assertPresent("./gradlew :protocol:allTests", substring = true)
        assertPresent("/w/cc-pocket", substring = true)
    }

    @Test
    fun anAbsentProjectAndAbsentRiskLeaveNoPlaceholder() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            PocketTheme { SecureApprovalSheet(approvalUi(ask(), workdir = null), onDeny = {}, onAllowOnce = {}) }
        }
        assertFalse(present(str(Res.string.risk_high)), "absent risk is not drawn at all — never as low or safe")
        assertFalse(present("/w/cc-pocket", substring = true))
    }
}
