package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import dev.ccpocket.app.resources.default_effort_section
import dev.ccpocket.app.resources.fast_mode
import dev.ccpocket.app.resources.join_title
import dev.ccpocket.app.resources.mode_plan_label
import dev.ccpocket.app.resources.mode_default_label
import dev.ccpocket.app.resources.mode_auto_label
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
import dev.ccpocket.app.resources.settings_default_agent
import dev.ccpocket.app.resources.settings_manual_title
import dev.ccpocket.app.resources.settings_paired_computers
import dev.ccpocket.app.resources.settings_shared_folders
import dev.ccpocket.app.resources.settings_title
import dev.ccpocket.app.resources.settings_troubleshooting
import dev.ccpocket.app.resources.settings_usage
import dev.ccpocket.app.resources.text_size_section
import dev.ccpocket.app.resources.updates_section
import dev.ccpocket.app.resources.value_default
import dev.ccpocket.app.resources.value_off
import dev.ccpocket.app.resources.value_on
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Test
    fun defaultModelOptionsFollowAgentAndKeepTheCurrentChoice() {
        assertEquals(
            listOf(null, "gpt-custom", "gpt-5.6-sol", "gpt-5.5"),
            settingsDefaultModelOptions(
                AgentKind.CODEX,
                selected = "gpt-custom",
                discovered = listOf("gpt-5.6-sol", "gpt-5.5"),
            ),
        )
        val claude = settingsDefaultModelOptions(
            AgentKind.CLAUDE,
            selected = "sonnet",
            discovered = listOf("gpt-5.6-sol"),
        )
        assertTrue("sonnet" in claude)
        assertFalse("gpt-5.6-sol" in claude, "Claude keeps its existing alias table")
    }

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

    // ── the read-only summary (#237 · S3) ─────────────────────────────────────────────────────────

    /**
     * The one node that carries BOTH a group's label and a value is the summary pair: the group's own
     * SectionLabel is a bare label, and the editable rows below carry values without their group's name.
     * Matching on the pair is therefore what pins this to the summary rather than to the control below it.
     */
    private fun SemanticsNodeInteractionsProvider.summaryPair(label: String, value: String) =
        onAllNodes(hasText(label) and hasText(value))

    private fun SemanticsNodeInteractionsProvider.summaryPairs(label: String, value: String) =
        summaryPair(label, value).fetchSemanticsNodes().size

    /** How many summary pairs also claim to be a control. Must always be zero: this is a readout. */
    private fun SemanticsNodeInteractionsProvider.clickableSummaryPairs(label: String, value: String) =
        onAllNodes(hasText(label) and hasText(value) and hasClickAction()).fetchSemanticsNodes().size

    /** Open Settings ▸ Agent & session defaults against a repo the caller already seeded. */
    private fun agentPage(
        repo: PocketRepository,
        width: Int = 402,
        height: Int = 874,
        assertions: androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { PocketTheme { SettingsScreen(repo, onBack = {}) } }
        waitForIdle()
        onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
        waitForIdle()
        assertions()
    }

    /** A repo carrying a complete stored Codex launch configuration, including a priority-tier model. */
    private fun codexRepo(scope: CoroutineScope, model: String = "gpt-5.6-sol", priority: Boolean = true) =
        PocketRepository(scope, account()).apply {
            setDefaultAgent(AgentKind.CODEX)
            setDefaultMode(PermissionMode.PLAN)
            setDefaultModelFor(AgentKind.CODEX, model)
            setDefaultEffortFor(AgentKind.CODEX, "ultra")
            setDefaultServiceTier("priority")
            receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf(model, "gpt-5.5"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = model,
                            reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
                            serviceTiers = if (priority) listOf(ModelServiceTier("priority", "Fast")) else emptyList(),
                        ),
                    ),
                ),
            )
        }

    private fun resetDefaults(repo: PocketRepository) {
        repo.setDefaultAgent(AgentKind.CLAUDE)
        repo.setDefaultMode(PermissionMode.DEFAULT)
        repo.setDefaultModelFor(AgentKind.CODEX, null)
        repo.setDefaultEffortFor(AgentKind.CODEX, null)
        repo.setDefaultServiceTier(null)
    }

    /**
     * The summary states what would really launch — the stored agent, mode, model, effort and Fast — using
     * the groups' own labels and the daemon's own values. It is a READOUT: no click action, so it neither
     * enters the tab order nor offers a second place to change a default.
     */
    @Test
    fun theAgentDefaultsSummaryPrintsTheStoredLaunchConfiguration() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = codexRepo(scope)
        try {
            agentPage(repo) {
                val pairs = listOf(
                    str(Res.string.settings_default_agent) to "Codex",
                    str(Res.string.default_mode_section) to str(Res.string.mode_plan_label),
                    str(Res.string.default_model_section) to "gpt-5.6-sol",
                    str(Res.string.default_effort_section) to "ultra",
                    str(Res.string.fast_mode) to str(Res.string.value_on),
                )
                pairs.forEach { (label, value) ->
                    assertEquals(1, summaryPairs(label, value), "the summary reports $label as $value exactly once")
                    assertEquals(
                        0, clickableSummaryPairs(label, value),
                        "\"$label\" is a readout, not a second control",
                    )
                }
                // the editable groups below are untouched: every one of them is still on the page
                listOf(
                    Res.string.default_mode_section, Res.string.default_model_section,
                    Res.string.default_effort_section, Res.string.context_window_section,
                    Res.string.per_model_section, Res.string.af_show_from,
                ).forEach { assertPresent(str(it)) }
                assertPresent("${str(Res.string.default_model_section)} · Codex")
                assertPresent("${str(Res.string.default_effort_section)} · Codex")
            }
        } finally {
            resetDefaults(repo)
            scope.cancel()
        }
    }

    /** Claude Auto stays stored when merely changing the inspected agent, but Codex launches in DEFAULT. */
    @Test
    fun switchingAwayFromClaudeAutoShowsTheBackendEffectivePermissionMode() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope, account()).apply {
            setDefaultAgent(AgentKind.CLAUDE)
            setDefaultAutoMode()
            receiveForTest(
                ModelsList(
                    agent = AgentKind.CLAUDE,
                    permissionModes = listOf(CLAUDE_PERMISSION_MODE_AUTO),
                ),
            )
        }
        try {
            agentPage(repo) {
                val auto = str(Res.string.mode_auto_label)
                assertEquals(
                    1,
                    onAllNodes(hasText(auto) and isSelected() and hasClickAction()).fetchSemanticsNodes().size,
                    "the page starts on Claude's stored Auto row",
                )
                onAllNodes(hasText("Codex") and hasClickAction()).onFirst().performClick()
                waitForIdle()

                val effective = str(Res.string.mode_default_label)
                assertEquals(
                    1,
                    summaryPairs(str(Res.string.default_mode_section), effective),
                    "the summary must report the mode Codex will actually receive",
                )
                assertEquals(
                    1,
                    onAllNodes(hasText(effective) and isSelected()).fetchSemanticsNodes().size,
                    "the editable mode rows and summary must agree after switching away from Claude Auto",
                )
                assertEquals(AgentKind.CODEX, repo.defaultAgent.value)
                assertEquals(
                    CLAUDE_PERMISSION_MODE_AUTO,
                    repo.defaultPermissionMode.value,
                    "inspecting another backend must not erase Claude's stored Auto choice",
                )
                assertEquals(0, onAllNodes(hasText(auto) and hasClickAction()).fetchSemanticsNodes().size)
            }
        } finally {
            resetDefaults(repo)
            scope.cancel()
        }
    }

    /** `Default` is a real stored choice, and the summary must say so rather than inventing a model id. */
    @Test
    fun theSummaryReportsTheNullableDefaultAsTheDefaultValue() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = codexRepo(scope).apply {
            setDefaultModelFor(AgentKind.CODEX, null)
            setDefaultEffortFor(AgentKind.CODEX, null)
        }
        try {
            agentPage(repo) {
                val default = str(Res.string.value_default)
                assertEquals(1, summaryPairs(str(Res.string.default_model_section), default))
                assertEquals(1, summaryPairs(str(Res.string.default_effort_section), default))
            }
        } finally {
            resetDefaults(repo)
            scope.cancel()
        }
    }

    /**
     * Fast rides the same priority-tier gate the switch does. Under a model that does not advertise
     * `priority` neither exists — a summary that named a control the page is not showing would be lying
     * about the configuration it claims to state.
     */
    @Test
    fun theSummaryShowsFastOnlyWhereThePriorityTierIsAdvertised() {
        listOf(true, false).forEach { priority ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = codexRepo(scope, priority = priority)
            try {
                agentPage(repo) {
                    val fast = str(Res.string.fast_mode)
                    if (priority) {
                        assertEquals(1, summaryPairs(fast, str(Res.string.value_on)), "priority advertises Fast")
                    } else {
                        assertFalse(present(fast), "no priority tier, so neither the switch nor the summary claims Fast")
                    }
                }
            } finally {
                resetDefaults(repo)
                scope.cancel()
            }
        }
    }

    /** Off is written as Off — the row is present under the gate whichever way the stored value points. */
    @Test
    fun theSummaryWritesFastOffWhenTheStoredTierIsNotPriority() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = codexRepo(scope).apply { setDefaultServiceTier(null) }
        try {
            agentPage(repo) {
                assertEquals(1, summaryPairs(str(Res.string.fast_mode), str(Res.string.value_off)))
            }
        } finally {
            resetDefaults(repo)
            scope.cancel()
        }
    }

    /**
     * It reads LIVE state: picking a model in the group below moves the summary, and the pick still lands
     * in the repository exactly as before — the summary owns no callback and intercepts none.
     */
    @Test
    fun theSummaryTracksThePickTheRowsBelowMake() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = codexRepo(scope)
        try {
            agentPage(repo) {
                val label = str(Res.string.default_model_section)
                assertEquals(1, summaryPairs(label, "gpt-5.6-sol"))
                onAllNodes(hasText("gpt-5.5") and hasClickAction()).onFirst().performScrollTo().performClick()
                waitForIdle()
                assertEquals(1, summaryPairs(label, "gpt-5.5"), "the summary follows the row that owns the value")
                assertEquals(0, summaryPairs(label, "gpt-5.6-sol"), "…and stops reporting the stale one")
            }
            assertEquals("gpt-5.5", repo.defaultModelFor(AgentKind.CODEX), "the pick still reaches the repository")
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX), "…and rewrites nothing else")
        } finally {
            resetDefaults(repo)
            scope.cancel()
        }
    }

    /**
     * A long custom id stays COMPLETE in the summary at both stress widths and at 200% type: the pair
     * stacks and the value wraps, rather than truncating, shrinking or scrolling sideways. And the row
     * below it remains selectable with the same id.
     */
    @Test
    fun aLongCustomModelIdStaysWholeInTheSummaryAtEveryStressWidth() {
        val longId = "gpt-5.6-codex-specialized-variant-with-a-very-long-context-window"
        listOf(Triple(320, 720, 1f), Triple(280, 700, 1f), Triple(402, 874, 2f)).forEach { (w, h, scaleValue) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = codexRepo(scope, model = longId)
            try {
                runDesktopComposeUiTest(w, h) {
                    setContent {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalDensity provides
                                androidx.compose.ui.unit.Density(1f, scaleValue),
                        ) { PocketTheme { SettingsScreen(repo, onBack = {}) } }
                    }
                    waitForIdle()
                    onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                    waitForIdle()

                    val pair = onAllNodes(hasText(str(Res.string.default_model_section)) and hasText(longId)).onFirst()
                    pair.performScrollTo()
                    waitForIdle()
                    val bounds = pair.getUnclippedBoundsInRoot()
                    assertTrue(bounds.left.value >= -0.5f, "the summary starts on screen at ${w}pt/${scaleValue}x: $bounds")
                    assertTrue(bounds.right.value <= w + 0.5f, "…and never scrolls sideways: $bounds")
                    assertTrue(bounds.height >= 30.dp, "the id wrapped onto its own lines instead of being cut: $bounds")

                    // the editable row still owns the value and is still selectable
                    val row = onAllNodes(hasText(longId) and hasClickAction()).onFirst()
                    row.performScrollTo()
                    waitForIdle()
                    val rowHeight = with(density) { row.fetchSemanticsNode().size.height.toDp() }
                    assertTrue(rowHeight >= 48.dp, "the row keeps its 48 dp touch floor at ${w}pt: $rowHeight")
                    row.performClick()
                    waitForIdle()
                }
                assertEquals(longId, repo.defaultModelFor(AgentKind.CODEX))
            } finally {
                resetDefaults(repo)
                scope.cancel()
            }
        }
    }

    @Test
    fun codexDefaultsUseDiscoveredModelsAndDoNotRewriteClaudeEffort() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope, account())
        try {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultEffort("high")
            repo.setDefaultEffortFor(AgentKind.CODEX, "low")
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.6-sol", "gpt-5.5"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = "gpt-5.6-sol",
                            reasoningEfforts = listOf("low", "ultra"),
                        ),
                    ),
                ),
            )

            runComposeUiTest {
                setContent { PocketTheme { SettingsScreen(repo, onBack = {}) } }
                waitForIdle()
                onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                waitForIdle()

                // Exercise the real mobile route: Codex defaults are discoverable from a fresh Claude
                // setting, not only when a test pre-seeds repo.defaultAgent before opening Settings.
                onAllNodes(hasText("Codex")).onFirst().performClick()
                waitForIdle()
                assertPresent("gpt-5.6-sol")
                assertFalse(present("Opus"), "Codex defaults must not render Claude model aliases")
                onAllNodes(hasText("gpt-5.6-sol")).onFirst().performScrollTo().performClick()
                waitForIdle()
                onAllNodes(hasText("ultra")).onFirst().performScrollTo().performClick()
                waitForIdle()
            }

            assertEquals("gpt-5.6-sol", repo.defaultModelFor(AgentKind.CODEX))
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX))
            assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))
            assertEquals(AgentKind.CODEX, repo.defaultAgent.value)
        } finally {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            repo.setDefaultEffort(null)
            scope.cancel()
        }
    }

    @Test
    fun narrowMobileSettingsKeepsALongCodexCatalogAsFullWidthTouchRows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope, account())
        val models = (1..9).map { i -> "gpt-5.6-codex-specialized-variant-$i-with-long-context" }
        try {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.receiveForTest(ModelsList(agent = AgentKind.CODEX, models = models))

            runDesktopComposeUiTest(280, 720) {
                setContent { PocketTheme { SettingsScreen(repo, onBack = {}) } }
                waitForIdle()
                onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                waitForIdle()
                onAllNodes(hasText("Codex")).onFirst().performClick()
                waitForIdle()

                models.forEach { id ->
                    // hasClickAction scopes this to the editable row: the read-only summary above also
                    // prints the selected id, and measuring THAT would prove nothing about the control
                    val row = onAllNodes(hasText(id) and hasClickAction()).onFirst()
                    row.performScrollTo()
                    waitForIdle()
                    val size = row.fetchSemanticsNode().size
                    val rowHeight = with(density) { size.height.toDp() }
                    val rowWidth = with(density) { size.width.toDp() }
                    assertTrue(rowHeight >= 48.dp, "$id keeps a 48 dp touch floor at narrow width: $rowHeight")
                    assertTrue(rowWidth >= 200.dp, "$id remains a full-width readable row: $rowWidth")
                }
                onAllNodes(hasText(models.last())).onFirst().performClick()
                waitForIdle()
            }

            assertEquals(models.last(), repo.defaultModelFor(AgentKind.CODEX))
        } finally {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun narrowMobileSettingsKeepsLegacyAndLongEffortsAsFullWidthTouchRows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope, account())
        val legacy = listOf("low", "medium", "high", "xhigh", "max")
        val longSelected = "custom-reasoning-effort-with-a-very-long-backend-label"
        try {
            repo.setDefaultAgent(AgentKind.CODEX)
            // A stale/custom selected value remains visible beside the old daemon's five-value fallback.
            // This exercises Default + 5 legacy rows + a long label in the actual scrolling Settings page.
            repo.setDefaultEffortFor(AgentKind.CODEX, longSelected)
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.5"),
                ),
            )

            runDesktopComposeUiTest(280, 720) {
                setContent { PocketTheme { SettingsScreen(repo, onBack = {}) } }
                waitForIdle()
                onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                waitForIdle()

                val defaultLabel = str(Res.string.value_default)
                val labels = listOf(defaultLabel) + legacy + longSelected
                labels.forEach { label ->
                    // Model and context have their own selected "Default" rows. The effort default is the
                    // unselected one because [longSelected] is active, which pins this assertion to the
                    // control under test instead of accidentally measuring the model row above it.
                    // hasClickAction excludes the read-only summary, which prints the same stored value.
                    val matcher = if (label == defaultLabel) {
                        hasText(label) and isNotSelected() and hasClickAction()
                    } else {
                        hasText(label) and hasClickAction()
                    }
                    val row = onAllNodes(matcher).onFirst()
                    row.performScrollTo()
                    waitForIdle()
                    val size = row.fetchSemanticsNode().size
                    val rowHeight = with(density) { size.height.toDp() }
                    val rowWidth = with(density) { size.width.toDp() }
                    assertTrue(rowHeight >= 48.dp, "$label keeps a 48 dp touch floor at narrow width: $rowHeight")
                    assertTrue(rowWidth >= 200.dp, "$label remains a full-width readable row: $rowWidth")
                }
                onAllNodes(hasText("max")).onFirst().performScrollTo().performClick()
                waitForIdle()
            }

            assertEquals("max", repo.defaultEffortFor(AgentKind.CODEX))
        } finally {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
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
