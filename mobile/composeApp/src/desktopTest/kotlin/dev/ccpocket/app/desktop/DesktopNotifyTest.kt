package dev.ccpocket.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure parts of the notification path (issue #99): the osascript fallback's AppleScript source must
 * stay shell-quote-safe and length-capped. The native channel itself (MacNotifier) is deliberately NOT
 * exercised here — it would post a real banner on a mac dev box; its attribution is verified out-of-band
 * (packaged-app probe + release-build eyeball).
 */
class DesktopNotifyTest {

    @Test
    fun escEscapesQuotesAndBackslashes() {
        assertEquals("say \\\"hi\\\" via C:\\\\path", DesktopNotify.esc("say \"hi\" via C:\\path"))
    }

    @Test
    fun escCapsRunawayBodies() {
        assertEquals(180, DesktopNotify.esc("x".repeat(4_000)).length)
    }

    @Test
    fun osascriptSourceEmbedsEscapedBodyAndTitle() {
        val src = DesktopNotify.osascriptSource(title = "A \"B\"", body = "done \\ end")
        assertEquals("display notification \"done \\\\ end\" with title \"A \\\"B\\\"\"", src)
        // paranoia: nothing un-escaped can terminate the AppleScript string early
        assertTrue(Regex("(?<!\\\\)\"").findAll(src.removePrefix("display notification ")).count() % 2 == 0)
    }
}
