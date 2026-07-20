package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mobile [PathOpener] (read-doc-inline handoff): a phone has no local disk to stat, so [exists] is
 * OPTIMISTIC — every regex-matched path lights up as openable — and [open] hands the tap straight to the
 * daemon read. A miss lands on the file viewer's own error state, not a dead click, so optimism is safe.
 */
class RemotePathOpenerTest {

    @Test
    fun exists_is_optimistic_for_every_shape() {
        val o = RemotePathOpener { }
        assertTrue(o.exists("/abs/report.md"))
        assertTrue(o.exists("~/Desktop/report.md"))
        assertTrue(o.exists("notes/2026/plan.md"))
        assertTrue(o.exists("C:\\Users\\x\\report.md"))
    }

    @Test
    fun open_delegates_the_exact_path_to_the_daemon_read() {
        var opened: String? = null
        val o = RemotePathOpener { opened = it }
        o.open("~/project/summary.md")
        assertEquals("~/project/summary.md", opened) // verbatim — the daemon expands ~ and gates it
    }
}
