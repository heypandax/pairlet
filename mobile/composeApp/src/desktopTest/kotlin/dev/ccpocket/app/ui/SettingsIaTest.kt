package dev.ccpocket.app.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
import dev.ccpocket.app.resources.settings_cat_connections
import dev.ccpocket.app.resources.settings_cat_general
import dev.ccpocket.app.resources.settings_cat_security
import dev.ccpocket.app.resources.settings_cat_support
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
        listOf(
            Res.string.settings_cat_general, Res.string.settings_cat_agent, Res.string.settings_cat_connections,
            Res.string.settings_cat_security, Res.string.settings_cat_support,
        ).forEach { assertPresent(str(it)) }
        // no control has leaked onto it
        assertFalse(present(str(Res.string.appearance_section)), "controls live on their category page")
        assertFalse(present(str(Res.string.exit)), "…including the way out")
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
