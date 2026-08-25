package dev.ccpocket.app.ui.bridge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.bridge_edit
import dev.ccpocket.app.resources.bridge_expand_toggle
import dev.ccpocket.app.resources.bridge_no_approval_tag
import dev.ccpocket.app.resources.bridge_offline
import dev.ccpocket.app.resources.bridge_online
import dev.ccpocket.app.resources.bridge_projects
import dev.ccpocket.app.resources.bridge_runner_restart
import dev.ccpocket.app.resources.bridge_runner_start
import dev.ccpocket.app.resources.bridge_runner_stop
import dev.ccpocket.app.resources.bridge_runner_unmanaged
import dev.ccpocket.app.resources.bridge_unbind
import dev.ccpocket.app.resources.bridge_waiting_adapter
import dev.ccpocket.app.resources.bridges_title
import dev.ccpocket.app.resources.settings_connected_to
import dev.ccpocket.app.resources.share_revoke
import dev.ccpocket.app.resources.share_sessions_live
import dev.ccpocket.app.resources.share_tier_collaborate
import dev.ccpocket.app.resources.share_tier_review
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.BridgeRunnerState
import dev.ccpocket.protocol.RUNNER_KIND_FEISHU
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Bridges card, after UI 2.1 · C2 split facts from actions.
 *
 * What shipped before this: name, status, trust tag and every runner button shared one horizontal Row, so
 * four controls divided whatever width the facts left over. On a 390 dp Chinese phone that gave `编辑`
 * about one glyph of room and Compose did the only thing it could — wrapped the label, stacking 编 over
 * 辑. A button rendered vertically.
 *
 * So this file asserts the LAYOUT MODEL rather than a literal width: at every stress width and text scale
 * an action label stays one horizontal line, the whole control wraps to a later row instead of shrinking,
 * and the target keeps its floor. The three geometries are the design's own proofs — 390 dp Chinese fits
 * the three process chips on one row, 320 dp wraps them 2 + 1, 200% type wraps at the larger floor.
 *
 * Issue #259 then split that row in two, and the file now asserts the TIER as well: the process chips keep
 * every guarantee above, while the one destructive action leaves the chip species entirely — below the
 * hairline, right-aligned in a footer that sits in the same corner in all three states, and renamed from
 * 「撤销」 (the share feature's word, which promises an undo) to 「解除桥接…」, ellipsis and all.
 *
 * The Chinese cases really render Chinese: the defect is a CJK line-break, and an English label would
 * "pass" this file while `编辑` still stacked on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class BridgesUiTest {

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-bridges", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    private fun runner(running: Boolean, noApproval: Boolean = false) = BridgeRunnerState(
        kind = RUNNER_KIND_FEISHU, scriptPath = "", running = running, noApproval = noApproval,
    )

    /** The busiest truthful card: managed + running + trust allowed + live sessions = four actions. */
    private fun runningBridge() = BridgeInfo(
        name = "research-adapter",
        workdirs = listOf("/Users/alex/work/cc-pocket"),
        deviceId = "bridge-device-2f9c",
        online = true,
        activeSessions = 2,
        runner = runner(running = true, noApproval = true),
        tier = AccessTier.COLLABORATE,
    )

    private fun stoppedBridge() = BridgeInfo(
        name = "design-review-bot", workdirs = listOf("/Users/alex/work/pocket-nightly"),
        deviceId = "bridge-device-77aa", online = false, runner = runner(running = false),
        tier = AccessTier.REVIEW,
    )

    private fun unmanagedBridge() = BridgeInfo(
        name = "self-run-adapter", workdirs = listOf("/Users/alex/work/alpha"),
        deviceId = "bridge-device-0001", online = true, runner = null, tier = AccessTier.REVIEW,
    )

    // ── harness ───────────────────────────────────────────────────────────────────────────────────

    /**
     * One bridges screen at an exact dp geometry.
     *
     * `Density(1f, fontScale)` makes one scene pixel one dp, so every bound below is the real thing and a
     * wrap proof is a measurement rather than a screenshot.
     */
    private fun scene(
        width: Int,
        height: Int = 874,
        fontScale: Float = 1f,
        items: List<BridgeInfo>,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width, height) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                val scope = rememberCoroutineScope()
                val repo = remember {
                    PocketRepository(scope, account()).apply {
                        bridgeControl.value = true          // the daemon advertises a bridge control plane
                        receiveForTest(BridgeListing(items))
                    }
                }
                PocketTheme { BridgesScreen(repo, onBack = {}) }
            }
        }
        waitForIdle()
        assertions()
    }

    /** The same, rendered in Simplified Chinese — `str()` resolves the zh strings for the assertions too. */
    private fun zhScene(
        width: Int,
        height: Int = 874,
        fontScale: Float = 1f,
        items: List<BridgeInfo>,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try {
            // if the locale did not take, everything below would silently prove the English layout instead
            assertEquals("编辑", str(Res.string.bridge_edit), "these cases must really render Chinese")
            scene(width, height, fontScale, items, assertions)
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** The whole control behind a label (the merged clickable), not the text inside it. */
    private fun SkikoComposeUiTest.action(label: String): SemanticsNodeInteraction =
        onAllNodes(hasClickAction() and hasText(label)).onFirst()

    /** The raw label node — unmerged, so its bounds are the TEXT's and not its button's. */
    private fun SkikoComposeUiTest.label(text: String): SemanticsNodeInteraction =
        onAllNodes(hasText(text), useUnmergedTree = true).onFirst()

    private fun SemanticsNodeInteraction.width(): Dp = getUnclippedBoundsInRoot().width
    private fun SemanticsNodeInteraction.height(): Dp = getUnclippedBoundsInRoot().height
    private fun SemanticsNodeInteraction.top(): Dp = getUnclippedBoundsInRoot().top
    private fun SemanticsNodeInteraction.bottom(): Dp = getUnclippedBoundsInRoot().bottom
    private fun SemanticsNodeInteraction.left(): Dp = getUnclippedBoundsInRoot().left
    private fun SemanticsNodeInteraction.right(): Dp = getUnclippedBoundsInRoot().right

    /**
     * A two-glyph CJK label is a single horizontal line iff it is WIDER than it is tall.
     *
     * Stacked, `编` over `辑` is one glyph wide and two lines tall — the ratio inverts, and no font metric
     * or text size can make a stacked pair pass this.
     */
    private fun SkikoComposeUiTest.assertOneHorizontalLine(text: String) {
        val node = label(text)
        val w = node.width()
        val h = node.height()
        assertTrue(w > h * 1.3f, "\"$text\" must render on one horizontal line, but measured ${w} × ${h}")
    }

    private fun SkikoComposeUiTest.assertTargetFloor(label: String, minWidth: Dp, minHeight: Dp) {
        val bounds = action(label).getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= minHeight, "\"$label\" target is ${bounds.height} tall, floor is $minHeight")
        assertTrue(bounds.width >= minWidth, "\"$label\" target is ${bounds.width} wide, floor is $minWidth")
    }

    /**
     * The harness itself, proven from an ENGLISH default — which is what CI has and this developer's
     * machine does not. Without this, a broken locale switch would quietly turn every `编辑` proof below
     * into an `Edit` proof, and the reported defect is a CJK line-break that `Edit` cannot reproduce.
     */
    @Test
    fun theChineseHarnessReallySwitchesLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("Edit", str(Res.string.bridge_edit))
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("编辑", str(Res.string.bridge_edit))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ── the defect ────────────────────────────────────────────────────────────────────────────────

    /** The reported bug, at the width it was reported on. */
    @Test
    fun theChineseEditActionStaysOneHorizontalLineAt390dp() = zhScene(390, items = listOf(runningBridge())) {
        assertPresent("编辑")
        assertOneHorizontalLine("编辑")
        // …and it is a real target that fits on the screen, not a sliver pushed off the edge
        assertTargetFloor("编辑", minWidth = 96.dp, minHeight = 48.dp)
        assertTrue(action("编辑").right() <= 390.dp, "the action must stay inside the viewport")
    }

    /**
     * 390 dp Chinese: the three PROCESS controls share one row, and the destructive action is not among
     * them (issue #259).
     *
     * The tier break is what this asserts: 重启 · 停止 · 编辑 on one FlowRow line, then a gap wide enough
     * for the hairline, then 解除桥接… alone in a footer, pushed to the right edge past where the chip
     * grid ends. A fourth chip would have shared their left margin; this one cannot.
     */
    @Test
    fun threeProcessChipsShareOneRowAndTheUnbindActionSitsInTheFooterAt390dp() =
        zhScene(390, items = listOf(runningBridge())) {
            val restart = action(str(Res.string.bridge_runner_restart))
            val stop = action(str(Res.string.bridge_runner_stop))
            val edit = action(str(Res.string.bridge_edit))
            val unbind = action(str(Res.string.bridge_unbind))

            assertEquals(restart.top(), stop.top(), "Restart and Stop share a row")
            assertEquals(restart.top(), edit.top(), "…and so does Edit")
            assertTrue(
                unbind.top() >= restart.bottom(),
                "the destructive action is BELOW the chip row, not a fourth member of it",
            )
            assertTrue(unbind.left() > edit.left(), "…and it is right-aligned, not sharing the chip grid")
            assertTrue(unbind.right() >= edit.right(), "…flush with the card's content edge")
            listOf(restart, stop, edit).forEach {
                assertTrue(it.height() >= 48.dp, "every process chip keeps its 48 dp floor")
                assertTrue(it.right() <= 390.dp, "no chip is pushed out of the viewport")
            }
            assertTrue(unbind.height() >= 44.dp, "the destructive action keeps a 44 dp target")
            assertTrue(unbind.right() <= 390.dp, "…and stays inside the viewport")
        }

    /** 320 dp stress width: the process chips wrap 2 + 1 as WHOLE controls; the footer is unaffected. */
    @Test
    fun processChipsWrapWholeAt320dpAndTheFooterStaysPut() = zhScene(320, items = listOf(runningBridge())) {
        val restart = action(str(Res.string.bridge_runner_restart))
        val stop = action(str(Res.string.bridge_runner_stop))
        val edit = action(str(Res.string.bridge_edit))
        val unbind = action(str(Res.string.bridge_unbind))

        assertEquals(restart.top(), stop.top(), "Restart and Stop share the first row")
        assertTrue(edit.top() > restart.top(), "Edit drops to the second row instead of being squeezed")
        assertOneHorizontalLine("编辑")
        listOf(restart, stop, edit).forEach {
            assertTrue(it.width() >= 96.dp, "every process chip keeps its 96 dp width floor")
            assertTrue(it.right() <= 320.dp, "no chip is pushed out of the viewport")
        }
        assertTrue(unbind.top() >= edit.bottom(), "the destructive action stays below the whole chip block")
        assertTrue(unbind.right() <= 320.dp, "…and inside the viewport at the stress width")
    }

    /** 200% type: the chip floor GROWS with the text, labels stay whole, and the footer still holds. */
    @Test
    fun atTwoHundredPercentTypeActionsGrowAndWrap() =
        zhScene(390, fontScale = 2f, items = listOf(runningBridge())) {
            assertOneHorizontalLine("编辑")
            assertOneHorizontalLine("重启")
            listOf(
                str(Res.string.bridge_runner_restart), str(Res.string.bridge_runner_stop),
                str(Res.string.bridge_edit),
            ).forEach { assertTargetFloor(it, minWidth = 150.dp, minHeight = 60.dp) }

            val restart = action(str(Res.string.bridge_runner_restart))
            val stop = action(str(Res.string.bridge_runner_stop))
            val edit = action(str(Res.string.bridge_edit))
            val unbind = action(str(Res.string.bridge_unbind))
            assertEquals(restart.top(), stop.top(), "two chips share the first row at 200% type")
            assertTrue(edit.top() > restart.top(), "the third wraps whole")
            assertTrue(unbind.top() >= edit.bottom(), "the destructive action is still the footer, not a chip")
            assertTrue(unbind.right() <= 390.dp, "…and doubled type does not push it off the screen")
        }

    /**
     * The label is the promise: 「解除桥接…」 / “Disconnect…”, ellipsis included.
     *
     * The trailing U+2026 is the system's "this opens a sheet" mark — the reason the confirm sheet reads as
     * the answer to the tap rather than a surprise interrogation. And 「撤销」 ("undo my last step") is gone
     * from this card for good: it is the share feature's word, on a control that deletes a credential.
     */
    @Test
    fun theDestructiveActionIsNamedForWhatItDoesAndPromisesTheSheet() =
        zhScene(390, items = listOf(runningBridge())) {
            assertEquals("解除桥接…", str(Res.string.bridge_unbind))
            assertTrue(str(Res.string.bridge_unbind).endsWith("…"), "the ellipsis is part of the spec")
            action(str(Res.string.bridge_unbind)).assertExists()
            assertFalse(present(str(Res.string.share_revoke)), "the bridge card no longer borrows 撤销")
        }

    // ── the product truths the new shape must not have changed ────────────────────────────────────

    /** Every card names the ceiling this bridge was granted — the security-relevant fact. */
    @Test
    fun everyCardCarriesItsAccessTier() =
        scene(390, items = listOf(runningBridge(), stoppedBridge())) {
            assertPresent(str(Res.string.share_tier_collaborate))
            assertPresent(str(Res.string.share_tier_review))
        }

    /** A running managed adapter offers Restart · Stop · Edit — never Start — plus the unbind footer. */
    @Test
    fun aRunningManagedBridgeOffersRestartStopEditAndUnbind() = scene(390, items = listOf(runningBridge())) {
        listOf(
            str(Res.string.bridge_runner_restart), str(Res.string.bridge_runner_stop),
            str(Res.string.bridge_edit), str(Res.string.bridge_unbind),
        ).forEach { action(it).assertExists() }
        assertFalse(present(str(Res.string.bridge_runner_start)), "a running adapter cannot be started")
        // the facts that share the card with them, and the tag's REAL wording
        assertPresent(str(Res.string.bridge_online))
        assertPresent(str(Res.string.bridge_no_approval_tag))
        assertPresent(str(Res.string.share_sessions_live, 2))
    }

    /** A stopped managed adapter offers Start · Edit · unbind, and nothing that would fail. */
    @Test
    fun aStoppedManagedBridgeOffersStartEditAndUnbind() = scene(390, items = listOf(stoppedBridge())) {
        action(str(Res.string.bridge_runner_start)).assertExists()
        action(str(Res.string.bridge_edit)).assertExists()
        action(str(Res.string.bridge_unbind)).assertExists()
        assertFalse(present(str(Res.string.bridge_runner_stop)), "a stopped adapter cannot be stopped")
        assertFalse(present(str(Res.string.bridge_runner_restart)), "…nor restarted")
        // no trust tag was configured on this one, so none is claimed
        assertFalse(present(str(Res.string.bridge_no_approval_tag)))
        assertFalse(present(str(Res.string.share_sessions_live, 0)), "zero live sessions is not a fact worth a line")
    }

    /**
     * An adapter the owner runs themselves has no process to control — only the plug to pull.
     *
     * Tier 1 is empty here, so the hint that explains WHY takes its slot above the hairline — visible
     * without expanding the card, because it answers a question the owner is asking while looking at the
     * controls. The footer is then the card's only control, which is exactly when it must not look like a
     * chip you can casually poke.
     */
    @Test
    fun anUnmanagedBridgeOffersNoRunnerActionsAndSaysWhy() = scene(390, items = listOf(unmanagedBridge())) {
        listOf(
            str(Res.string.bridge_runner_start), str(Res.string.bridge_runner_stop),
            str(Res.string.bridge_runner_restart), str(Res.string.bridge_edit),
        ).forEach { assertFalse(present(it), "\"$it\" cannot be offered for an adapter we do not run") }
        assertPresent(str(Res.string.bridge_runner_unmanaged))
        val unbind = action(str(Res.string.bridge_unbind))
        unbind.assertExists()
        assertTrue(
            unbind.top() > label(str(Res.string.bridge_runner_unmanaged)).top(),
            "the hint sits ABOVE the divider, the destructive action below it",
        )
    }

    /**
     * Link state is the ADAPTER's link and nothing else.
     *
     * This bridge has a managed runner that is up while its ticket is still outstanding — the daemon
     * process being alive says nothing about whether the adapter ever connected, and reporting "Online"
     * here would tell the owner a bot is reachable when no bot has ever called in.
     */
    @Test
    fun pendingLinkStateIsIndependentOfTheRunner() = scene(
        390,
        items = listOf(
            BridgeInfo(
                name = "just-minted", workdirs = listOf("/Users/alex/work/cc-pocket"),
                pendingTicket = true, online = false, runner = runner(running = true),
                tier = AccessTier.REVIEW,
            ),
        ),
    ) {
        assertPresent(str(Res.string.bridge_waiting_adapter))
        assertFalse(present(str(Res.string.bridge_online)), "a live runner is not a live link")
        assertFalse(present(str(Res.string.bridge_offline)))
        // …and it is still a managed adapter, so its controls are there
        action(str(Res.string.bridge_runner_stop)).assertExists()
    }

    /** The name renders once, and the device identity behind it never renders at all. */
    @Test
    fun theCardNamesTheBridgeOnceAndNeverItsDeviceId() = scene(390, items = listOf(runningBridge())) {
        assertEquals(
            1,
            onAllNodes(hasText("research-adapter"), useUnmergedTree = true).fetchSemanticsNodes().size,
            "the bridge name is rendered exactly once",
        )
        assertFalse(present("bridge-device-2f9c", substring = true), "a device id is never shown")
        assertFalse(present("/Users/alex/work/cc-pocket", substring = true), "nor an absolute path")
    }

    /** The card is still the expand affordance — and now it says so out loud. */
    @Test
    fun theExpandAffordanceIsNamedForAScreenReader() = scene(390, items = listOf(runningBridge())) {
        val card = onAllNodes(hasText("research-adapter")).onFirst()
        assertEquals(
            str(Res.string.bridge_expand_toggle),
            card.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label,
            "an unlabelled full-card target reads out as nothing",
        )
        assertFalse(present(str(Res.string.bridge_projects)), "diagnostics stay subordinate until asked for")
        // tap the name rather than the card's centre — the centre now lands in the action zone, where a
        // button would (correctly) swallow the tap
        label("research-adapter").performClick()
        waitForIdle()
        assertPresent(str(Res.string.bridge_projects))
        assertPresent("cc-pocket") // basename only — never the machine path it came from
    }

    /** The screen wears the same chrome as the family it is reached through. */
    @Test
    fun theScreenUsesTheFirstHopHeaderWithOnlyFactualCopy() = scene(390, items = listOf(runningBridge())) {
        assertPresent(str(Res.string.bridges_title))
        assertPresent(str(Res.string.settings_connected_to, "alex-macbook"))
    }
}
