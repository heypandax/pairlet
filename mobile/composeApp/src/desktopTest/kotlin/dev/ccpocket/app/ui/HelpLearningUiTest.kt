package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.USER_MANUAL_URL
import dev.ccpocket.app.SUPPORT_CHAT_URL
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.files_title
import dev.ccpocket.app.resources.help_action_open_changes
import dev.ccpocket.app.resources.help_direct_unavailable
import dev.ccpocket.app.resources.help_task_changes_title
import dev.ccpocket.app.resources.help_task_schedule_title
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HelpLearningUiTest {

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid",
        accountId = "acct-help",
        daemonPub = "pub",
        deviceId = "dev",
        credential = "cred",
    )

    @Test
    fun chatGuideCanOpenRealChangedFilesDestination() = runComposeUiTest {
        var opened = false
        setContent {
            PocketTheme {
                HelpCenterScreen(
                    entryPoint = HelpEntryPoint.CHAT,
                    onBack = {},
                    onOpenChanges = { opened = true },
                )
            }
        }
        waitForIdle()

        assertPresent(str(Res.string.help_task_changes_title))
        onNodeWithText(str(Res.string.help_task_changes_title)).performClick()
        waitForIdle()
        val action = str(Res.string.help_action_open_changes)
        onNodeWithText(action).performScrollTo().performClick()
        waitForIdle()

        assertTrue(opened, "the guide action must invoke ChatScreen's real changed-files route")
    }

    @Test
    fun projectEntryExplainsWhyContextualActionIsUnavailable() = runComposeUiTest {
        setContent {
            PocketTheme {
                HelpCenterScreen(
                    entryPoint = HelpEntryPoint.PROJECTS,
                    onBack = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText(str(Res.string.help_task_changes_title)).performClick()
        waitForIdle()
        assertPresent(str(Res.string.help_direct_unavailable))
        assertFalse(
            present(str(Res.string.help_action_open_changes)),
            "a project-level guide must not pretend it can open a session-scoped destination",
        )
    }

    @Test
    fun chatHelpRoundTripReachesTheChangedFilesSheet() = runComposeUiTest {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, account()).apply {
                    receiveForTest(
                        SessionLive(
                            convoId = "convo-help",
                            workdir = "/work/help",
                            sessionId = "session-help",
                            mode = PermissionMode.DEFAULT,
                            executing = false,
                            model = "claude-sonnet-4-5",
                            agent = AgentKind.CLAUDE,
                        ),
                    )
                }
            }
            PocketTheme {
                Box(Modifier.requiredSize(390.dp, 600.dp)) {
                    ChatScreen(repo)
                }
            }
        }
        waitForIdle()

        onNodeWithText("⋯").performClick()
        onNodeWithText(str(Res.string.support_title)).performClick()
        onNodeWithText(str(Res.string.help_task_changes_title)).performClick()
        waitForIdle()
        onNodeWithText(str(Res.string.help_action_open_changes)).performScrollTo().performClick()
        waitForIdle()

        assertPresent(str(Res.string.files_title))
        assertEquals("convo-help", repo.convoId.value, "the destination must preserve the current session for back navigation")
    }

    @Test
    fun taskCatalogExposesDiscoveryBeyondTheDefaultGuide() = runComposeUiTest {
        setContent {
            PocketTheme {
                HelpCenterScreen(
                    entryPoint = HelpEntryPoint.SETTINGS,
                    onBack = {},
                )
            }
        }
        waitForIdle()

        val schedule = str(Res.string.help_task_schedule_title)
        repeat(6) {
            if (!present(schedule)) {
                onRoot().performTouchInput { swipeUp() }
                waitForIdle()
            }
        }
        assertPresent(schedule)
    }

    @Test
    fun everyTaskUsesTheStablePublicManualSearch() {
        HelpTaskId.entries.forEach { task ->
            assertEquals("${USER_MANUAL_URL}?q=${task.query}", helpGuideUrl(task))
        }
        assertEquals("2026-07-25", HELP_CONTENT_VERIFIED_AT)
        assertEquals("https://pocket.ark-nexus.cc/support/?mode=chat&source=app", SUPPORT_CHAT_URL)
    }
}
