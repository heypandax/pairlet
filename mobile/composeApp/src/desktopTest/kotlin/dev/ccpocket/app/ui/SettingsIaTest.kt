package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.about_section
import dev.ccpocket.app.resources.action_back
import dev.ccpocket.app.resources.af_show_from
import dev.ccpocket.app.resources.appearance_section
import dev.ccpocket.app.resources.co_screen_title
import dev.ccpocket.app.resources.context_window_section
import dev.ccpocket.app.resources.default_mode_section
import dev.ccpocket.app.resources.default_model_section
import dev.ccpocket.app.resources.exit
import dev.ccpocket.app.resources.full_control_expiry
import dev.ccpocket.app.resources.join_title
import dev.ccpocket.app.resources.notifications_section
import dev.ccpocket.app.resources.per_model_section
import dev.ccpocket.app.resources.rv_settings_row
import dev.ccpocket.app.resources.schedule_tasks_title
import dev.ccpocket.app.resources.security_section
import dev.ccpocket.app.resources.settings_bridges
import dev.ccpocket.app.resources.settings_cat_agent
import dev.ccpocket.app.resources.settings_cat_agent_sub
import dev.ccpocket.app.resources.settings_cat_connections
import dev.ccpocket.app.resources.settings_cat_connections_sub
import dev.ccpocket.app.resources.settings_cat_general
import dev.ccpocket.app.resources.settings_cat_general_sub
import dev.ccpocket.app.resources.settings_cat_security
import dev.ccpocket.app.resources.settings_cat_security_sub
import dev.ccpocket.app.resources.settings_cat_support
import dev.ccpocket.app.resources.settings_cat_support_sub
import dev.ccpocket.app.resources.settings_categories
import dev.ccpocket.app.resources.settings_connected_to
import dev.ccpocket.app.resources.settings_manual_title
import dev.ccpocket.app.resources.settings_paired_computers
import dev.ccpocket.app.resources.settings_shared_folders
import dev.ccpocket.app.resources.settings_title
import dev.ccpocket.app.resources.settings_troubleshooting
import dev.ccpocket.app.resources.settings_usage
import dev.ccpocket.app.resources.text_size_section
import dev.ccpocket.app.resources.updates_section
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings, after the long scroll became a landing plus five category pages.
 *
 * The hazard this file exists for is not a wrong pixel, it is a LOST control: regrouping ~25 settings is
 * exactly the change that silently drops one, and nobody notices until someone goes looking for a switch
 * that used to be there. So the map is walked exhaustively as pure data, and the pages are then rendered to
 * prove the map is what the screen actually does.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsIaTest {

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-set", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    // ── the map ───────────────────────────────────────────────────────────────────────────────────

    /** Every destination lands somewhere, exactly once — no orphan, no duplicate. */
    @Test
    fun everyDestinationHasExactlyOneHome() {
        val placed = SettingsCategory.entries.flatMap { destinationsOf(it) }
        assertEquals(placed.size, placed.toSet().size, "a destination may not appear on two pages")
        val landing = SettingsDest.entries.filter { it.category == null }
        assertEquals(
            SettingsDest.entries.size, placed.size + landing.size,
            "every destination is either on a category page or on the landing",
        )
    }

    /** The landing keeps the two utilities and nothing else — everything that is a SETTING drills down. */
    @Test
    fun onlyTheTwoUtilitiesStayOnTheLanding() {
        assertEquals(
            listOf(SettingsDest.USAGE, SettingsDest.SCHEDULES),
            SettingsDest.entries.filter { it.category == null },
        )
    }

    /** Five categories, none of them empty — a category row that opened an empty page would be a dead end. */
    @Test
    fun everyCategoryOwnsSomething() {
        assertEquals(5, SettingsCategory.entries.size)
        SettingsCategory.entries.forEach {
            assertTrue(destinationsOf(it).isNotEmpty(), "$it must own at least one destination")
        }
    }

    /** The pairings that would be wrong in a way a user would feel. */
    @Test
    fun theRiskiestDestinationsSitWhereTheyBelong() {
        assertEquals(SettingsCategory.SECURITY, SettingsDest.APPROVAL_NO_AUTO_DENY.category)
        assertEquals(SettingsCategory.SECURITY, SettingsDest.FULL_CONTROL_EXPIRY.category)
        assertEquals(SettingsCategory.SECURITY, SettingsDest.APP_LOCK.category)
        assertEquals(SettingsCategory.CONNECTIONS, SettingsDest.JOIN_FOLDER.category)
        assertEquals(SettingsCategory.AGENT, SettingsDest.PER_MODEL_WINDOWS.category)
        assertEquals(SettingsCategory.SUPPORT, SettingsDest.EXIT.category)
    }

    // ── the screen ────────────────────────────────────────────────────────────────────────────────

    /** The landing is short: two destinations, five categories, and one factual line about the computer. */
    @Test
    fun theLandingIsTheFiveCategoriesAndNothingElse() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope(), account())
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        waitForIdle()
        assertPresent(str(Res.string.settings_title))
        assertPresent(str(Res.string.settings_connected_to, "alex-macbook"))
        assertPresent(str(Res.string.settings_usage))
        assertPresent(str(Res.string.schedule_tasks_title))
        assertPresent(str(Res.string.settings_categories).uppercase())
        listOf(
            Res.string.settings_cat_general, Res.string.settings_cat_agent, Res.string.settings_cat_connections,
            Res.string.settings_cat_security, Res.string.settings_cat_support,
        ).forEach { assertPresent(str(it)) }
        // no control has leaked onto it
        assertFalse(present(str(Res.string.appearance_section)), "controls live on their category page")
        assertFalse(present(str(Res.string.exit)), "…including the way out")
    }

    /**
     * UI 2.1: the two utilities and the five categories are two VISIBLY different groups.
     *
     * The A3 correction is structural, so it is asserted structurally: the utilities stay bare on the page
     * gutter, a written label separates them, and the five categories are inset — which is what being
     * inside one container looks like from the outside. Heights are floors, not fixed sizes, so a longer
     * localisation or bigger type grows a row rather than cropping its second line.
     */
    @Test
    fun theLandingSeparatesBareUtilitiesFromOneCategoryContainer() = runDesktopComposeUiTest(402, 874) {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope(), account())
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        waitForIdle()

        fun text(s: String) = onAllNodes(hasText(s), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
        fun row(s: String) = onAllNodes(hasText(s)).onFirst().getUnclippedBoundsInRoot()

        val usage = text(str(Res.string.settings_usage))
        val schedules = text(str(Res.string.schedule_tasks_title))
        val label = text(str(Res.string.settings_categories).uppercase())
        val categories = SettingsCategory.entries.map { text(str(settingsCategoryTitleRes(it))) }

        // order: the two destinations, then the label, then the five categories
        assertTrue(schedules.top > usage.top, "Scheduled tasks follows Token usage")
        assertTrue(label.top > schedules.top, "the section label comes after both utilities")
        categories.forEach { assertTrue(it.top > label.top, "every category sits under the label") }
        categories.zipWithNext { a, b -> assertTrue(b.top > a.top, "the five categories keep their order") }

        // the categories are inset from the page gutter the utilities sit on — one container holds them
        assertEquals(usage.left, schedules.left, "both utilities sit on the page gutter")
        categories.forEach {
            assertTrue(it.left > usage.left, "a category row is inset inside its container")
        }

        // floors, so 200% type grows a row instead of cropping it
        listOf(str(Res.string.settings_usage), str(Res.string.schedule_tasks_title)).forEach {
            assertTrue(row(it).height >= 56.dp, "\"$it\" keeps the 56 dp utility floor")
        }
        SettingsCategory.entries.forEach {
            val title = str(settingsCategoryTitleRes(it))
            assertTrue(row(title).height >= 64.dp, "\"$title\" keeps the 64 dp category floor")
        }
    }

    @Test
    fun everyCategorySubtitleExplainsWhatLivesInside() = runComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val repo = remember { PocketRepository(scope, account()) }
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        waitForIdle()
        listOf(
            Res.string.settings_cat_general_sub, Res.string.settings_cat_agent_sub,
            Res.string.settings_cat_connections_sub,
            Res.string.settings_cat_security_sub, Res.string.settings_cat_support_sub,
        ).forEach { assertPresent(str(it)) }
    }

    /** Each category page renders the controls its map claims. Walked one page at a time, by opening it. */
    @Test
    fun eachCategoryPageRendersWhatItsMapClaims() {
        fun openCategory(categoryLabel: String, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
            runComposeUiTest {
                setContent {
                    val repo = PocketRepository(rememberCoroutineScope(), account())
                    PocketTheme { SettingsScreen(repo, onBack = {}) }
                }
                waitForIdle()
                onAllNodes(hasText(categoryLabel)).onFirst().performClick()
                waitForIdle()
                assertions()
            }

        openCategory(str(Res.string.settings_cat_general)) {
            assertPresent(str(Res.string.appearance_section))
            assertPresent(str(Res.string.text_size_section))
            assertPresent(str(Res.string.notifications_section))
        }
        openCategory(str(Res.string.settings_cat_agent)) {
            assertPresent(str(Res.string.default_mode_section))
            assertPresent(str(Res.string.default_model_section))
            assertPresent(str(Res.string.context_window_section))
            assertPresent(str(Res.string.per_model_section))
            assertPresent(str(Res.string.af_show_from))
        }
        openCategory(str(Res.string.settings_cat_connections)) {
            assertPresent(str(Res.string.settings_paired_computers))
            assertPresent(str(Res.string.settings_shared_folders))
            assertPresent(str(Res.string.join_title))       // the row most at risk of being dropped
            assertPresent(str(Res.string.co_screen_title))
            assertPresent(str(Res.string.rv_settings_row))
            assertPresent(str(Res.string.settings_bridges))
        }
        openCategory(str(Res.string.settings_cat_security)) {
            // the approval half is gated on a daemon reply this scene never got — app lock is not
            assertPresent(str(Res.string.security_section))
        }
        openCategory(str(Res.string.settings_cat_support)) {
            assertPresent(str(Res.string.settings_manual_title))
            assertPresent(str(Res.string.settings_troubleshooting))
            assertPresent(str(Res.string.updates_section))
            assertPresent(str(Res.string.about_section))
            assertPresent(str(Res.string.exit))
        }
    }

    /** Back is deterministic: a category returns to the landing, and only the landing leaves Settings. */
    @Test
    fun backPopsTheCategoryFirstAndOnlyThenLeaves() = runComposeUiTest {
        var left = 0
        setContent {
            val repo = PocketRepository(rememberCoroutineScope(), account())
            PocketTheme { SettingsScreen(repo, onBack = { left++ }) }
        }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.settings_cat_general))).onFirst().performClick()
        waitForIdle()
        assertPresent(str(Res.string.appearance_section))

        onAllNodes(hasContentDescription(str(Res.string.action_back))).onFirst().performClick()
        waitForIdle()
        assertEquals(0, left, "backing out of a category must not leave Settings")
        assertPresent(str(Res.string.settings_cat_general))
        assertFalse(present(str(Res.string.appearance_section)), "…and the page is really gone")

        onAllNodes(hasContentDescription(str(Res.string.action_back))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, left, "from the landing, back leaves")
    }

    /** The approval controls stay behind their capability gate — no daemon reply, no switch. */
    @Test
    fun approvalControlsAppearOnlyOnceTheDaemonProvesItCanHonorThem() = runComposeUiTest {
        setContent {
            val repo = PocketRepository(rememberCoroutineScope(), account())
            PocketTheme { SettingsScreen(repo, onBack = {}) }
        }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.settings_cat_security))).onFirst().performClick()
        waitForIdle()
        assertFalse(
            present(str(Res.string.full_control_expiry)),
            "a switch that silently did nothing would be worse than none",
        )
    }
}
