package dev.ccpocket.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dir_picker_use_here
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

/**
 * The "open a project folder" picker end-to-end through demo mode (issue #152; no network —
 * demoRespond answers ListPathEntries synchronously with its small sample tree, and OpenSession with
 * SessionLive). Guards the picker's contract: only DIRECTORIES render (files never), drilling updates
 * the level from the daemon reply, and "start here" opens a brand-new session whose workdir is the
 * raw "~/…" form the daemon expands. Matched texts resolve via getString (JVM locale picks the
 * resource language per machine).
 */
@OptIn(ExperimentalTestApi::class)
class DirectoryPickerUiTest {

    private fun ComposeUiTest.composePicker(): PocketRepository {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            // clear the demo project list: no recents section, so the browse rows sit above the fold
            // (recents/badge derivations have their own DirectoryPickerLogicTest coverage)
            repo = remember { PocketRepository(scope).also { it.enterDemo(); it.directories.clear() } }
            PocketTheme {
                DirectoryPickerSheet(
                    repo,
                    onDismiss = {}, onTypePath = {}, onOptions = {},
                    onStart = { p -> repo.openSession(p) },
                )
            }
        }
        waitForIdle()
        return repo
    }

    @Test
    fun lists_directories_only_and_drills_into_a_child() = runComposeUiTest {
        composePicker()
        // demo home level: src (dir) + README.md / build.gradle.kts (files) → only the dir renders
        assertTrue(present("src"), "the child directory must be listed")
        assertFalse(present("README.md"), "files must never render in a folder picker")

        onAllNodes(hasText("src")).onFirst().performClick()
        waitForIdle()
        assertTrue(present("main"), "drilling in must render the daemon's child listing")
    }

    @Test
    fun start_here_opens_a_new_session_at_the_tilde_workdir() = runComposeUiTest {
        val repo = composePicker()
        onAllNodes(hasText("src")).onFirst().performClick()
        waitForIdle()

        val useHere = runBlocking { getString(Res.string.dir_picker_use_here) }
        onAllNodes(hasText(useHere)).onFirst().performClick()
        waitForIdle()

        assertNotNull(repo.convoId.value, "start-here must open the conversation")
        assertEquals("~/src", repo.workdir.value, "the session opens at the raw ~-anchored dir (daemon expands it)")
    }
}
