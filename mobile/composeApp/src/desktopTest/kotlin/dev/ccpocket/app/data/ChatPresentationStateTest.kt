package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateListOf
import dev.ccpocket.app.ui.chat.ChatPresentationState
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #380 review P2-4 — an OPEN fold must stay open when its head changes: a page of older history
 * lands tools in front of it, or a tool that started earlier finishes after the ones behind it (parallel
 * calls completing out of order). Both give the run a new first member and therefore a new group key.
 */
class ChatPresentationStateTest {

    private fun ok(p: String) = ChatItem.Tool("Bash", p, ok = true)

    private fun ChatPresentationState.group(row: Int) = presentation.rows[row] as ChatRow.ProcessGroup

    @Test
    fun anOpenFoldStaysOpenWhenAnEarlierToolJoinsItsHead() {
        val list = mutableStateListOf<ChatItem>(ChatItem.User("go"), ok("A"), ok("B"), ChatItem.Assistant("done"))
        val state = ChatPresentationState({ list }, { true })
        val before = state.group(1)
        state.toggle(before.groupKey)
        assertTrue(state.group(1).expanded)

        list.add(1, ok("Z")) // lands in front of the open run
        val after = state.group(1)
        assertNotEquals(before.groupKey, after.groupKey, "sanity: the run's first member changed")
        assertTrue(after.expanded, "the reader opened this run; a new head must not snap it shut")
        assertTrue(after.sourceIndices == 1..3)
    }

    @Test
    fun anOpenFoldStaysOpenWhenAToolAheadOfItFinishesLate() {
        val running = ChatItem.Tool("Bash", "slow", taskId = "t-slow")
        val list = mutableStateListOf<ChatItem>(ChatItem.User("go"), running, ok("A"), ok("B"))
        val state = ChatPresentationState({ list }, { true })
        val open = state.group(2)
        state.toggle(open.groupKey)

        list[1] = running.copy(ok = true) // the earlier call completes after the later ones
        val merged = state.group(1)
        assertTrue(merged.sourceIndices == 1..3)
        assertTrue(merged.expanded)
    }

    @Test
    fun closingTheInheritedFoldClosesAllOfIt() {
        val list = mutableStateListOf<ChatItem>(ok("A"), ok("B"))
        val state = ChatPresentationState({ list }, { true })
        state.toggle(state.group(0).groupKey)
        list.add(0, ok("Z"))
        state.toggle(state.group(0).groupKey)
        assertTrue(!state.group(0).expanded, "one tap closes the whole run, new head included")
    }
}
