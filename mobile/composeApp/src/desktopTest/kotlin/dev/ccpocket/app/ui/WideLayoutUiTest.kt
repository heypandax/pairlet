package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dir_projects
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.wide_pick_session
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wide layout (issue #334): the SAME derived routing, rendered as two panes on a tablet-sized window
 * and as one column on a phone-sized one.
 *
 * The scene is driven through repository state rather than taps on purpose — the routing IS that
 * state, and seeding it keeps the assertions about layout instead of about the demo backend's timing.
 * Panes are identified by a string only their own screen renders: the sessions list by its one CTA,
 * Projects by its title, and the empty right pane by its placeholder.
 */
@OptIn(ExperimentalTestApi::class)
class WideLayoutUiTest {

    private val dir = "/Users/alex/code/relay-server"
    private val convo = "c-wide"

    private fun live() = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s-wide", mode = PermissionMode.DEFAULT,
        executing = false, model = "claude-fable-5", agent = AgentKind.CLAUDE,
    )

    /** Mounts the app's real content router under a measured scope of exactly [width] × [height]. */
    private fun scene(
        width: Int,
        height: Int,
        seed: PocketRepository.() -> Unit = {},
        assertions: SkikoComposeUiTest.(PocketRepository) -> Unit,
    ) = runDesktopComposeUiTest(width, height) {
        // a mounted chat animates (pulses, tickers); every assertion here is about a settled frame
        mainClock.autoAdvance = false
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember { PocketRepository(scope).also { it.enterDemo(); it.seed() } }
            PocketTheme { WideLayoutScope(Modifier.fillMaxSize()) { ContentRouter(repo) } }
        }
        waitForIdle()
        assertions(repo)
    }

    private fun SkikoComposeUiTest.sessionsListShowing() = present(str(Res.string.new_session_cta))
    private fun SkikoComposeUiTest.projectsShowing() = present(str(Res.string.dir_projects))
    private fun SkikoComposeUiTest.placeholderShowing() = present(str(Res.string.wide_pick_session))

    @Test
    fun aWideWindowShowsTheListAndTheChatAtTheSameTime() = scene(
        WIDE_W, WIDE_H,
        seed = { listSessions(dir) },
    ) { repo ->
        // nothing open yet: the list owns the left pane, the right pane says what to do
        assertTrue(sessionsListShowing(), "the wide left pane must show the sessions list")
        assertTrue(placeholderShowing(), "the wide right pane must offer the empty-state placeholder")

        // opening a session fills the RIGHT pane and must not cost the left one — the whole point of #334
        repo.receiveForTest(live())
        waitForIdle()
        assertNotNull(repo.convoId.value, "the seeded SessionLive must have opened the conversation")
        assertTrue(sessionsListShowing(), "opening a session must NOT hide the wide left pane")
        assertFalse(placeholderShowing(), "…and the right pane is the chat now, not the placeholder")

        // the chat's own Back closes the chat only: right pane → placeholder, left pane untouched
        repo.backToBrowse()
        waitForIdle()
        assertNull(repo.convoId.value)
        assertNotNull(repo.sessionsDir.value, "leaving the chat must not leave the project")
        assertTrue(placeholderShowing(), "back from the chat empties the right pane")
        assertTrue(sessionsListShowing(), "…and leaves the left pane exactly where it was")

        // the sessions list's Back walks the left pane out to Projects; the right pane stays empty
        repo.backToDirectories()
        waitForIdle()
        assertTrue(projectsShowing(), "back from the sessions list returns the left pane to Projects")
        assertTrue(placeholderShowing(), "…with the right pane still on the placeholder")
    }

    @Test
    fun aPhoneWindowStillRendersOneColumn() = scene(
        PHONE_W, PHONE_H,
        seed = { listSessions(dir) },
    ) { repo ->
        assertTrue(sessionsListShowing(), "the phone shows the sessions list")
        assertFalse(placeholderShowing(), "the empty-pane placeholder must never exist on a phone")

        // the chat REPLACES the list on a phone — the narrow branch is untouched by #334
        repo.receiveForTest(live())
        waitForIdle()
        assertNotNull(repo.convoId.value)
        assertFalse(sessionsListShowing(), "a phone chat replaces the list rather than sitting beside it")
        assertFalse(placeholderShowing(), "…and still has no placeholder anywhere")

        repo.backToBrowse()
        waitForIdle()
        assertTrue(sessionsListShowing(), "back returns the phone to the list it came from")
        assertFalse(placeholderShowing())
    }

    private companion object {
        // iPad-ish landscape vs. the 402×874 phone frame the rest of the UI suite pins
        const val WIDE_W = 1024
        const val WIDE_H = 768
        const val PHONE_W = 402
        const val PHONE_H = 874
    }
}
