package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.sidebar_recent_show_less
import dev.ccpocket.app.resources.sidebar_sessions_show_more
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A RECENT project's session rows show five at first; "Show more" brings in five more per click, "Show less" once
 * they all show folds back to five. One fold per slice: the flat list, or each custom group of a sectioned project.
 * Rendered by the real [Sidebar]; "hidden" means the list does not emit the row (IndexForKey -1), not scrolled away.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarSessionsMoreTest {

    private class Model(
        override val sessionGroups: List<DkSessionGroup>,
        override val customGroups: List<DkGroup> = emptyList(),
        override val canEditGroups: Boolean = false,
        val seed: SeedDesktopModel = SeedDesktopModel(),
    ) : DesktopModel by seed {
        override val pins: List<DkPin> = emptyList()
        override val runningRows: List<DkRunningRow> = emptyList()
        var selected by mutableStateOf<String?>(null)
        override val selectedSessionId: String? get() = selected
        override fun selectSession(s: DkSession) { selected = s.sessionId }
    }

    private fun sessions(n: Int, group: String? = null) = List(n) { k -> DkSession("s$k", P, "s$k", group = group) }

    private fun ComposeUiTest.show(model: DesktopModel) {
        setContent { PocketTheme { Box(Modifier.height(640.dp)) { Sidebar(model) } } }
        waitForIdle()
    }

    private fun ComposeUiTest.rowIndex(key: String): Int =
        onNodeWithTag("sidebar-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    private fun ComposeUiTest.label(scope: String): String {
        onNodeWithTag("sidebar-list").performScrollToKey("sessions-more:$scope")
        waitForIdle()
        return onNodeWithTag("sessions-more:$scope").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    }

    private fun ComposeUiTest.toggle(scope: String) {
        onNodeWithTag("sidebar-list").performScrollToKey("sessions-more:$scope")
        waitForIdle()
        onNodeWithTag("sessions-more:$scope").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shown(range: IntRange) = range.filter { rowIndex("s:$P:s$it") >= 0 }

    @Test
    fun fiveSessionsShowWithNoFold() = runComposeUiTest {
        show(Model(listOf(DkSessionGroup(P, "p", current = false, sessions = sessions(5)))))
        assertEquals((0..4).toList(), shown(0..4))
        onAllNodes(hasTestTag("sessions-more:$P")).assertCountEquals(0)
    }

    @Test
    fun aFlatListPagesByFiveAndShowLessFoldsBackToFive() = runComposeUiTest {
        show(Model(listOf(DkSessionGroup(P, "p", current = false, sessions = sessions(12)))))
        assertEquals((0..4).toList(), shown(0..11), "the first five only")
        assertEquals(rowIndex("s:$P:s4") + 1, rowIndex("sessions-more:$P"), "the fold sits right after the fifth row")
        assertEquals(str(Res.string.sidebar_sessions_show_more), label(P))

        toggle(P)
        assertEquals((0..9).toList(), shown(0..11), "one more page, not everything")
        assertEquals(str(Res.string.sidebar_sessions_show_more), label(P))

        toggle(P)
        assertEquals((0..11).toList(), shown(0..11), "the last two")
        assertEquals(str(Res.string.sidebar_recent_show_less), label(P))

        toggle(P)
        assertEquals((0..4).toList(), shown(0..11), "Show less is back to the first five")
    }

    @Test
    fun selectingASessionPastTheFoldBringsItsPageIn() = runComposeUiTest {
        val model = Model(listOf(DkSessionGroup(P, "p", current = false, sessions = sessions(12))))
        show(model)
        assertEquals(-1, rowIndex("s:$P:s7"))

        model.selectSession(model.sessionGroups[0].sessions[7])
        waitForIdle()
        assertTrue(rowIndex("s:$P:s7") >= 0, "the selected row is in the list")
        assertEquals((0..9).toList(), shown(0..11), "…as its whole page (6–10), not the page after it")
    }

    @Test
    fun eachCustomGroupFoldsOnItsOwn() = runComposeUiTest {
        val model = Model(
            listOf(DkSessionGroup(P, "p", current = true, sessions = sessions(7, group = "g-a") + List(3) { DkSession("u$it", P, "u$it") })),
            customGroups = listOf(DkGroup("g-a", "A", 0)),
            canEditGroups = true,
        )
        show(model)
        val a = "$P/g-a"
        assertEquals((0..4).toList(), shown(0..6), "group A shows its first five")
        assertTrue((0..2).all { rowIndex("s:$P:u$it") >= 0 }, "Ungrouped's three all show")
        onAllNodes(hasTestTag("sessions-more:$P/__ungrouped__")).assertCountEquals(0)

        toggle(a)
        assertEquals((0..6).toList(), shown(0..6))
        assertEquals(str(Res.string.sidebar_recent_show_less), label(a))
    }

    @Test
    fun theFoldAndTheHeaderCollapseAreRememberedByTheModelNotTheSidebar() = runComposeUiTest {
        val model = Model(
            listOf(
                DkSessionGroup(P, "p", current = false, sessions = sessions(12)),
                DkSessionGroup(Q, "q", current = false, sessions = List(3) { DkSession("q$it", Q, "q$it") }),
            ),
        )
        var mounted by mutableStateOf(true)
        setContent { PocketTheme { Box(Modifier.height(640.dp)) { if (mounted) Sidebar(model) } } }
        waitForIdle()
        toggle(P) // p shows ten
        onNodeWithTag("sidebar-list").performScrollToKey("h:$Q")
        waitForIdle()
        onAllNodes(androidx.compose.ui.test.hasText("q") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("sidebar-list")))
            .onFirst().performClick() // fold q's header
        waitForIdle()
        assertEquals(10, model.sessionsShown(P, null))
        assertTrue(model.projectCollapsed(Q))

        mounted = false // a fresh sidebar over the same model — what a restart over the persisted store looks like
        waitForIdle()
        mounted = true
        waitForIdle()
        assertEquals((0..9).toList(), shown(0..11), "p still shows ten")
        assertEquals(-1, rowIndex("s:$Q:q0"), "q is still folded")
    }

    private companion object {
        const val P = "~/p/p1"
        const val Q = "~/p/q"
    }
}