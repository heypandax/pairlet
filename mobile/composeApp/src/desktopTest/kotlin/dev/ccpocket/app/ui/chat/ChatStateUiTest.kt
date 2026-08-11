package dev.ccpocket.app.ui.chat

import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chat's pinned state (Mobile UI 2.0 · A Master Core v1 frame 02).
 *
 * The load-bearing rule is the demotion: "Chat never shows Running alone while an approval is outstanding."
 * These lock that, plus the refusal to invent a state while the user is simply reading back history.
 */
class ChatStateUiTest {

    private fun approval(title: String = "Run command") = PermissionAsk(
        convoId = "c1", askId = "a1", tool = "Bash", inputPreview = "./gradlew test", title = title,
    )

    private fun question(title: String = "Claude wants to confirm") = PermissionAsk(
        convoId = "c1", askId = "q1", tool = "AskUserQuestion", inputPreview = "", title = title,
        questions = listOf(AskQuestion(question = "Which palette?", options = listOf(AskOption("Warm", null)))),
    )

    @Test
    fun approval_leads_and_running_is_demoted_to_a_qualifier() {
        val ui = assertNotNullUi(chatStateUi(approval(), sessionDegraded = false, streaming = true))
        assertEquals(SurfaceState.APPROVAL, ui.state)
        assertTrue(ui.alsoRunning, "a genuinely streaming turn under an open approval is the qualifying line")
        assertEquals(StateMark.DIAMOND, ui.mark)
    }

    @Test
    fun question_leads_over_streaming_too() {
        val ui = assertNotNullUi(chatStateUi(question(), sessionDegraded = false, streaming = true))
        assertEquals(SurfaceState.ANSWER, ui.state)
        assertTrue(ui.alsoRunning)
    }

    @Test
    fun an_approval_that_is_not_streaming_carries_no_qualifier() {
        val ui = assertNotNullUi(chatStateUi(approval(), sessionDegraded = false, streaming = false))
        assertFalse(ui.alsoRunning, "the qualifier must come from a real streaming turn, never from the ask")
    }

    @Test
    fun degraded_outranks_running_and_carries_no_qualifier() {
        val ui = assertNotNullUi(chatStateUi(null, sessionDegraded = true, streaming = true))
        assertEquals(SurfaceState.FAILURE, ui.state)
        assertFalse(ui.alsoRunning, "Running qualifies an intervention, not a failure")
        assertNull(ui.detail, "a failure has no daemon-supplied title to quote")
    }

    @Test
    fun an_approval_outranks_a_degraded_session() {
        assertEquals(
            SurfaceState.APPROVAL,
            assertNotNullUi(chatStateUi(approval(), sessionDegraded = true, streaming = false)).state,
        )
    }

    @Test
    fun streaming_alone_pins_nothing() {
        assertNull(
            chatStateUi(null, sessionDegraded = false, streaming = true),
            "the composer note + Stop already write a running turn once; a pinned Running band said it twice",
        )
    }

    @Test
    fun reading_history_pins_nothing() {
        assertNull(
            chatStateUi(null, sessionDegraded = false, streaming = false),
            "an idle session must not invent a Complete / New result block just to fill the slot",
        )
    }

    @Test
    fun the_detail_is_the_real_ask_title_and_a_blank_one_is_omitted() {
        assertEquals(
            "Upload coverage to Codecov",
            assertNotNullUi(chatStateUi(approval("Upload coverage to Codecov"), false, false)).detail,
        )
        assertNull(
            assertNotNullUi(chatStateUi(approval(""), false, false)).detail,
            "a blank title is dropped, never replaced with a client-authored summary",
        )
    }

    private fun assertNotNullUi(ui: ChatStateUi?): ChatStateUi {
        assertTrue(ui != null, "expected a pinned state")
        return ui
    }
}
