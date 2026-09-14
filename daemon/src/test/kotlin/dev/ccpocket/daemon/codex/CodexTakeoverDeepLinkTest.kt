package dev.ccpocket.daemon.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #347: after a takeover fork the daemon asks the Codex desktop app to hydrate the new branch via
 *  its own deep link — macOS only, and never anything but `open -g codex://threads/<id>`. */
class CodexTakeoverDeepLinkTest {
    @Test
    fun deep_link_is_the_apps_threads_url() {
        assertEquals("codex://threads/01a09e58-af94-76c0-bc3f-1340354eccd8", CodexTakeoverLineage.desktopDeepLink("01a09e58-af94-76c0-bc3f-1340354eccd8"))
    }

    @Test
    fun macos_opens_in_background_without_stealing_focus() {
        assertEquals(listOf("/usr/bin/open", "-g", "codex://threads/abc"), CodexTakeoverLineage.desktopOpenCommand("abc", osName = "Mac OS X"))
    }

    @Test
    fun other_hosts_do_nothing() {
        assertNull(CodexTakeoverLineage.desktopOpenCommand("abc", osName = "Windows 11"))
        assertNull(CodexTakeoverLineage.desktopOpenCommand("abc", osName = "Linux"))
    }
}
