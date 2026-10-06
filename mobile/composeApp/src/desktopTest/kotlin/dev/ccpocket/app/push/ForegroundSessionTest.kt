package dev.ccpocket.app.push

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #382: with turn pushes no longer suppressed by presence, a phone that is in the foreground AND
 * showing the very session the push is about must not also pop a banner — the chat already shows it.
 * Since #382 was applied to asks (2026-10) that covers approvals too: the ask card is on screen. Everything
 * about other sessions, and anything without session routing, still presents.
 */
class ForegroundSessionTest {

    @AfterTest fun reset() = ForegroundSession.update(null)

    @Test fun turn_push_for_the_viewed_session_is_hidden() {
        assertFalse(shouldPresentForegroundPush(viewingSessionId = "s1", pushSessionId = "s1", pushKind = null))
    }

    @Test fun approval_for_the_viewed_session_is_hidden_too() {
        // the daemon now pushes owner asks regardless of presence; the viewed chat already shows the ask card
        assertFalse(shouldPresentForegroundPush("s1", "s1", "approval"))
    }

    @Test fun approval_for_another_session_or_without_routing_presents() {
        assertTrue(shouldPresentForegroundPush("s1", "s2", "approval"))
        assertTrue(shouldPresentForegroundPush(null, "s1", "approval"))
        assertTrue(shouldPresentForegroundPush("s1", null, "approval"))
    }

    @Test fun other_session_or_no_open_chat_presents() {
        assertTrue(shouldPresentForegroundPush("s1", "s2", null))
        assertTrue(shouldPresentForegroundPush(null, "s1", null))
    }

    @Test fun push_without_session_routing_presents() {
        // e.g. a Handoff offer (only `hid`) or a daemon notice with no session
        assertTrue(shouldPresentForegroundPush("s1", null, null))
        assertTrue(shouldPresentForegroundPush("s1", "", null))
        assertTrue(shouldPresentForegroundPush("", "", null))
    }

    @Test fun published_session_requires_an_open_chat_and_a_live_link() {
        assertEquals("s1", foregroundSessionOf(sessionKey = "s1", convoId = "c1", connected = true))
        // link dropped while the chat page stays up: the phone will NOT get the turn on the data plane → publish null
        assertNull(foregroundSessionOf("s1", "c1", connected = false))
        // sessionKey survives backToBrowse as a draft key; without a convo nothing is on screen
        assertNull(foregroundSessionOf("s1", null, connected = true))
        assertNull(foregroundSessionOf(null, "c1", connected = true))
    }

    @Test fun holder_tracks_the_open_session() {
        ForegroundSession.update("s1")
        assertFalse(ForegroundSession.shouldPresent("s1", null))
        assertFalse(ForegroundSession.shouldPresent("s1", "approval"))
        assertTrue(ForegroundSession.shouldPresent("s2", "approval"))
        ForegroundSession.update(null)
        assertTrue(ForegroundSession.shouldPresent("s1", null))
        assertTrue(ForegroundSession.shouldPresent("s1", "approval"))
    }
}
