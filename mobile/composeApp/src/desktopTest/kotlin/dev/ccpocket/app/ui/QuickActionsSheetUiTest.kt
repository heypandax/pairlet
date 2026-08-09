package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.fast_mode
import dev.ccpocket.app.resources.ho_menu_row
import dev.ccpocket.app.resources.label_effort
import dev.ccpocket.app.resources.label_mode
import dev.ccpocket.app.resources.qa_clear
import dev.ccpocket.app.resources.qa_clear_hint
import dev.ccpocket.app.resources.qa_compact
import dev.ccpocket.app.resources.qa_files
import dev.ccpocket.app.resources.qa_group_context
import dev.ccpocket.app.resources.qa_group_settings
import dev.ccpocket.app.resources.qa_group_tools
import dev.ccpocket.app.resources.qa_model
import dev.ccpocket.app.resources.qa_simplify
import dev.ccpocket.app.resources.quick_actions_title
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.resources.terminal_open
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chat Quick Actions UI 2.0 — the sheet behind Chat's top-right ⋯, at the release baseline (iPhone 17,
 * 402 × 874 pt).
 *
 * What this pins is the grammar the redesign is *about*: three written groups in a locked order, one
 * separated destructive row, and Handoff as an ordinary peer of Terminal and Changed files — no `NEW`,
 * no accent, no badge. Plus the two things a low-container list can quietly break: reachability of every
 * row at 200% type, and the two-tap arming of Clear.
 *
 * `Density(1f, fontScale)` makes one scene pixel one dp, so the same assertions double as the overflow
 * proof at 100% and at 200% type.
 */
@OptIn(ExperimentalTestApi::class)
class QuickActionsSheetUiTest {

    private val dir = "/Users/alex/code/cc-pocket"
    private val convo = "c-qa"
    private val modelId = "claude-sonnet-4-5" // not `model`: inside a PocketRepository receiver that is its state

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-qa", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    /** A live Claude session — [PocketRepository.clearConversation] needs the convo id to be real. */
    private fun sessionLive() = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = false, model = modelId, agent = AgentKind.CLAUDE,
    )

    /** Every capability gate open, so the sheet renders its full IA (effort, fast mode, Simplify). */
    private fun PocketRepository.seedEveryCapability() {
        receiveForTest(sessionLive())
        receiveForTest(
            ModelsList(
                agent = AgentKind.CLAUDE, models = listOf(modelId),
                modelCapabilities = listOf(
                    ModelCapabilities(
                        model = modelId,
                        reasoningEfforts = listOf("low", "medium", "high"),
                        serviceTiers = listOf(ModelServiceTier(id = "priority", name = "Fast")),
                    ),
                ),
            ),
        )
        receiveForTest(CommandList(convo, listOf(SlashCommand("simplify"), SlashCommand("compact"))))
    }

    /**
     * Compose the sheet against a paired repository in a real 402 × 874 pt scene.
     *
     * [autoAdvance] stays off for the settled-frame assertions (house style) and is turned on only by the
     * scrolling proof, whose `performScrollTo` rides an actual animation.
     */
    private fun sheet(
        fontScale: Float = 1f,
        handoff: Boolean = true,
        autoAdvance: Boolean = false,
        seed: PocketRepository.() -> Unit = { seedEveryCapability() },
        onDismiss: () -> Unit = {},
        assertions: SkikoComposeUiTest.(PocketRepository) -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = autoAdvance
        lateinit var repo: PocketRepository
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                val scope = rememberCoroutineScope()
                repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme {
                    Box(Modifier.fillMaxSize()) {
                        QuickActionsSheet(
                            repo,
                            onTerminal = {}, onMode = {}, onFiles = {}, onHelp = {},
                            onHandoff = if (handoff) ({}) else null,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertions(repo)
    }

    private fun SkikoComposeUiTest.topOf(text: String): Float =
        onAllNodes(hasText(text)).onFirst().getUnclippedBoundsInRoot().top.value

    /** Nothing may spill past the 402 pt viewport — the "no horizontal scroll" half of the responsive gate. */
    private fun SkikoComposeUiTest.assertWithinViewport(text: String) {
        val b = onAllNodes(hasText(text)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(b.left.value >= -0.5f, "\"$text\" starts off-screen at ${b.left}")
        assertTrue(b.right.value <= W + 0.5f, "\"$text\" overflows the ${W}pt viewport, ending at ${b.right}")
    }

    @Test
    fun theSheetReadsAsThreeWrittenGroupsThenASeparatedDestructiveRow() = sheet {
        assertTrue(present(str(Res.string.quick_actions_title)), "the sheet keeps its title")
        val settings = str(Res.string.qa_group_settings).uppercase()
        val tools = str(Res.string.qa_group_tools).uppercase()
        val context = str(Res.string.qa_group_context).uppercase()
        assertTrue(present(settings) && present(tools) && present(context), "each group is WRITTEN, not implied")
        // the locked reading order — settings, tools, context, then the destructive row below all three
        assertTrue(topOf(settings) < topOf(tools), "settings leads")
        assertTrue(topOf(tools) < topOf(context), "tools sits between settings and context")
        assertTrue(topOf(context) < topOf(str(Res.string.qa_clear)), "Clear is last, apart from every group")
        // and each group holds exactly the actions the IA assigns it
        assertTrue(topOf(str(Res.string.qa_model)) > topOf(settings) && topOf(str(Res.string.qa_model)) < topOf(tools))
        assertTrue(topOf(str(Res.string.label_effort)) < topOf(tools), "effort belongs to settings")
        assertTrue(topOf(str(Res.string.fast_mode)) < topOf(tools), "so does fast mode")
        assertTrue(topOf(str(Res.string.label_mode)) < topOf(tools), "…and the permission mode")
        assertTrue(topOf(str(Res.string.terminal_open)) > topOf(tools) && topOf(str(Res.string.terminal_open)) < topOf(context))
        assertTrue(topOf(str(Res.string.support_title)) < topOf(context), "help closes the tools group")
        assertTrue(topOf(str(Res.string.qa_compact)) > topOf(context), "compact is context maintenance")
        assertTrue(topOf(str(Res.string.qa_simplify)) > topOf(context), "and so is Simplify")
        // one useful named target per row: the action, plus the value it currently shows
        assertTrue(
            onAllNodes(hasContentDescription("${str(Res.string.qa_model)}, sonnet")).fetchSemanticsNodes().isNotEmpty(),
            "a row is named by its action and its current value, not by a chevron",
        )
    }

    @Test
    fun handoffIsAnOrdinaryPeerRowWithNoNewBadge() = sheet(handoff = true) {
        assertTrue(present(str(Res.string.ho_menu_row)), "the row is there while the session is handoff-free")
        assertFalse(present("NEW", substring = true), "availability is not news — no badge")
        // it sits inside Session tools, between Changed files and Help, like any other tool
        assertTrue(topOf(str(Res.string.qa_files)) < topOf(str(Res.string.ho_menu_row)))
        assertTrue(topOf(str(Res.string.ho_menu_row)) < topOf(str(Res.string.support_title)))
        // …with the same row geometry as its peers: same height, same left edge, same width
        val handoff = onAllNodes(hasText(str(Res.string.ho_menu_row))).onFirst().getUnclippedBoundsInRoot()
        val files = onAllNodes(hasText(str(Res.string.qa_files))).onFirst().getUnclippedBoundsInRoot()
        assertEquals(files.left.value, handoff.left.value, 0.5f, "no glyph indenting the label")
        assertEquals(files.right.value, handoff.right.value, 0.5f, "no badge shortening the row")
        assertEquals(
            (files.bottom - files.top).value, (handoff.bottom - handoff.top).value, 0.5f,
            "no unique weight or geometry",
        )
    }

    @Test
    fun aSessionThatAlreadyHandedOffLosesTheRowAndNothingAroundIt() = sheet(handoff = false) {
        assertFalse(present(str(Res.string.ho_menu_row)), "the gate hides the row…")
        assertTrue(present(str(Res.string.qa_group_tools).uppercase()), "…without taking its group with it")
        assertTrue(present(str(Res.string.terminal_open)) && present(str(Res.string.qa_files)) && present(str(Res.string.support_title)))
        // no placeholder, no gap: the list closes up and Help follows Changed files directly
        val files = onAllNodes(hasText(str(Res.string.qa_files))).onFirst().getUnclippedBoundsInRoot()
        val help = onAllNodes(hasText(str(Res.string.support_title))).onFirst().getUnclippedBoundsInRoot()
        assertEquals(files.bottom.value, help.top.value, 1.5f, "the neighbours stay adjacent (hairline only)")
    }

    @Test
    fun everyActionStaysReachableAtTwoHundredPercentType() = sheet(fontScale = 2f, autoAdvance = true) {
        // the sheet must not eat the whole viewport: the scrim above it is the only way out on iOS
        assertTrue(
            topOf(str(Res.string.quick_actions_title)) > 24f,
            "the sheet grew over the scrim — nothing left to tap to dismiss",
        )
        onAllNodes(hasText(str(Res.string.qa_model))).onFirst().assertIsDisplayed()
        assertWithinViewport(str(Res.string.qa_model))
        // …and the last row is still reachable, by scrolling rather than by shrinking the type
        onAllNodes(hasText(str(Res.string.qa_clear))).onFirst().performScrollTo().assertIsDisplayed()
        assertWithinViewport(str(Res.string.qa_clear))
        assertWithinViewport(str(Res.string.ho_menu_row))
    }

    @Test
    fun clearArmsOnTheFirstTapAndOnlyClearsOnTheSecond() {
        var dismissed = 0
        sheet(
            autoAdvance = true, // injected input rides the clock, and Clear may sit below the fold
            onDismiss = { dismissed++ },
            seed = {
                seedEveryCapability()
                receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test"))))
            },
        ) { repo ->
            assertTrue(repo.messages.isNotEmpty(), "there is a transcript to lose")
            assertFalse(present(str(Res.string.qa_clear_hint)), "…and nothing is armed yet")

            onAllNodes(hasText(str(Res.string.qa_clear))).onFirst().performScrollTo().performClick()
            waitForIdle()
            assertTrue(present(str(Res.string.qa_clear_hint)), "the first tap only arms, in words")
            assertTrue(repo.messages.isNotEmpty(), "…and clears nothing")
            assertEquals(0, dismissed, "…and keeps the sheet open")
            // the armed state rides the row's own accessible name, so it isn't danger-colour-only
            assertTrue(
                onAllNodes(hasContentDescription("${str(Res.string.qa_clear)}, ${str(Res.string.qa_clear_hint)}"))
                    .fetchSemanticsNodes().isNotEmpty(),
                "the confirmation state is exposed to accessibility, not just painted",
            )

            onAllNodes(hasText(str(Res.string.qa_clear))).onFirst().performScrollTo().performClick()
            waitForIdle()
            assertTrue(repo.messages.isEmpty(), "the second tap really clears")
            assertEquals(1, dismissed, "…and closes the sheet")
        }
    }

    private companion object {
        const val W = 402
        const val H = 874
    }
}
