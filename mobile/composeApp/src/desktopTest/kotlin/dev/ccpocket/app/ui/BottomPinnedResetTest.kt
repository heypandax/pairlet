package dev.ccpocket.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Stick to the bottom" is per CONVERSATION, not per screen (issue #165).
 *
 * The mobile chat used to be reachable only by entering from a session list, so a fresh mount reset this
 * for free and the un-keyed [rememberBottomPinned] was harmless. The cross-project switcher made chat→chat
 * possible WITHOUT a remount, and the previous session's "user scrolled up" then rode along — the session
 * you switched into opened parked mid-transcript instead of at its latest message. ChatScreen now passes
 * its conversation as the reset key, which is what the desktop pane always did.
 *
 * SCOPE, honestly: this guards the HELPER's reset contract. It does NOT prove ChatScreen passes the key —
 * an end-to-end version was attempted and abandoned because unpinning needs a real drag gesture, and a
 * synthetic swipe in the headless scene doesn't produce the DragInteraction the helper listens for, so the
 * test passed with the fix reverted. A test that cannot fail is worse than none. The screen-level wiring
 * is verified on device instead.
 */
@OptIn(ExperimentalTestApi::class)
class BottomPinnedResetTest {

    @Test
    fun theResetKeyRepinsAndSamenessPreservesTheReadersPosition() = runComposeUiTest {
        val convo = mutableStateOf<String?>("c1")
        lateinit var read: () -> Boolean
        lateinit var unpin: () -> Unit
        setContent {
            val listState = rememberLazyListState()
            val state = rememberBottomPinned(listState, convo.value)
            val pinned by state
            read = { pinned }
            unpin = { state.value = false }
        }
        waitForIdle()
        assertTrue(read(), "a conversation opens pinned to the latest message")

        unpin() // the user scrolls up to re-read this session
        waitForIdle()
        convo.value = "c1" // same conversation — recomposition must not yank the reader back down
        waitForIdle()
        assertTrue(!read(), "re-reading history in the SAME session survives recomposition")

        convo.value = "c2" // …but switching sessions must start at the latest message again
        waitForIdle()
        assertTrue(read(), "a different conversation re-pins")
    }
}
