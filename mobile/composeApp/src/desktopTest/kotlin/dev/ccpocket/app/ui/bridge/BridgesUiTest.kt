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
 * and the target keeps its floor. The three geometries are the design's own proofs — 390 dp Chinese wraps
 * 3 + 1, 320 dp wraps 2 + 2, 200% type wraps 2 + 2 at the larger floor.
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

    /** 390 dp Chinese: four whole controls, wrapped 3 + 1. Restart · Stop · Edit, then Revoke below. */
    @Test
    fun fourActionsWrapThreePlusOneAt390dp() = zhScene(390, items = listOf(runningBridge())) {
        val restart = action(str(Res.string.bridge_runner_restart))
        val stop = action(str(Res.string.bridge_runner_stop))
        val edit = action(str(Res.string.bridge_edit))
        val revoke = action(str(Res.string.share_revoke))

        assertEquals(restart.top(), stop.top(), "Restart and Stop share a row")
        assertEquals(restart.top(), edit.top(), "…and so does Edit")
        assertTrue(revoke.top() > restart.top(), "Revoke wraps to the next row rather than squeezing the other three")
        listOf(restart, stop, edit, revoke).forEach {
            assertTrue(it.height() >= 48.dp, "every action keeps its 48 dp floor")
            assertTrue(it.right() <= 390.dp, "no action is pushed out of the viewport")
        }
    }

    /** 320 dp stress width: the same four controls, wrapped 2 + 2. Still no compression. */
    @Test
    fun fourActionsWrapTwoPlusTwoAt320dp() = zhScene(320, items = listOf(runningBridge())) {
        val restart = action(str(Res.string.bridge_runner_restart))
        val stop = action(str(Res.string.bridge_runner_stop))
        val edit = action(str(Res.string.bridge_edit))
        val revoke = action(str(Res.string.share_revoke))

        assertEquals(restart.top(), stop.top(), "Restart and Stop share the first row")
        assertTrue(edit.top() > restart.top(), "Edit drops to the second row instead of being squeezed")
        assertEquals(edit.top(), revoke.top(), "…where Revoke joins it")
        assertOneHorizontalLine("编辑")
        listOf(restart, stop, edit, revoke).forEach {
            assertTrue(it.width() >= 96.dp, "every action keeps its 96 dp width floor")
            assertTrue(it.right() <= 320.dp, "no action is pushed out of the viewport")
        }
    }

    /** 200% type: the floor GROWS with the text. Labels stay whole, controls wrap 2 + 2. */
    @Test
    fun atTwoHundredPercentTypeActionsGrowAndWrap() =
        zhScene(390, fontScale = 2f, items = listOf(runningBridge())) {
            assertOneHorizontalLine("编辑")
            assertOneHorizontalLine("重启")
            listOf(
                str(Res.string.bridge_runner_restart), str(Res.string.bridge_runner_stop),
                str(Res.string.bridge_edit), str(Res.string.share_revoke),
            ).forEach { assertTargetFloor(it, minWidth = 150.dp, minHeight = 60.dp) }

            val restart = action(str(Res.string.bridge_runner_restart))
            val stop = action(str(Res.string.bridge_runner_stop))
            val edit = action(str(Res.string.bridge_edit))
            val revoke = action(str(Res.string.share_revoke))
            assertEquals(restart.top(), stop.top(), "two actions share the first row at 200% type")
            assertTrue(edit.top() > restart.top(), "the other two wrap")
            assertEquals(edit.top(), revoke.top(), "…onto one second row")
        }

    // ── the product truths the new shape must not have changed ────────────────────────────────────

    /** Every card names the ceiling this bridge was granted — the security-relevant fact. */
    @Test
    fun everyCardCarriesItsAccessTier() =
        scene(390, items = listOf(runningBridge(), stoppedBridge())) {
            assertPresent(str(Res.string.share_tier_collaborate))
            assertPresent(str(Res.string.share_tier_review))
        }

    /** A running managed adapter offers Restart · Stop · Edit — never Start — plus Revoke. */
    @Test
    fun aRunningManagedBridgeOffersRestartStopEditAndRevoke() = scene(390, items = listOf(runningBridge())) {
        listOf(
            str(Res.string.bridge_runner_restart), str(Res.string.bridge_runner_stop),
            str(Res.string.bridge_edit), str(Res.string.share_revoke),
        ).forEach { action(it).assertExists() }
        assertFalse(present(str(Res.string.bridge_runner_start)), "a running adapter cannot be started")
        // the facts that share the card with them, and the tag's REAL wording
        assertPresent(str(Res.string.bridge_online))
        assertPresent(str(Res.string.bridge_no_approval_tag))
        assertPresent(str(Res.string.share_sessions_live, 2))
    }

    /** A stopped managed adapter offers Start · Edit · Revoke, and nothing that would fail. */
    @Test
    fun aStoppedManagedBridgeOffersStartEditAndRevoke() = scene(390, items = listOf(stoppedBridge())) {
        action(str(Res.string.bridge_runner_start)).assertExists()
        action(str(Res.string.bridge_edit)).assertExists()
        action(str(Res.string.share_revoke)).assertExists()
        assertFalse(present(str(Res.string.bridge_runner_stop)), "a stopped adapter cannot be stopped")
        assertFalse(present(str(Res.string.bridge_runner_restart)), "…nor restarted")
        // no trust tag was configured on this one, so none is claimed
        assertFalse(present(str(Res.string.bridge_no_approval_tag)))
        assertFalse(present(str(Res.string.share_sessions_live, 0)), "zero live sessions is not a fact worth a line")
    }

    /** An adapter the owner runs themselves has no process to control — only the plug to pull. */
    @Test
    fun anUnmanagedBridgeOffersNoRunnerActions() = scene(390, items = listOf(unmanagedBridge())) {
        listOf(
            str(Res.string.bridge_runner_start), str(Res.string.bridge_runner_stop),
            str(Res.string.bridge_runner_restart), str(Res.string.bridge_edit),
        ).forEach { assertFalse(present(it), "\"$it\" cannot be offered for an adapter we do not run") }
        action(str(Res.string.share_revoke)).assertExists()
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
