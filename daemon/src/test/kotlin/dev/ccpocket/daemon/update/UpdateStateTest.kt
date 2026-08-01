package dev.ccpocket.daemon.update

import dev.ccpocket.protocol.DaemonInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon half of issue #200: what the CLI prints and what `DaemonInfo` carries come from here. */
class UpdateStateTest {

    @BeforeTest fun clear() = UpdateState.resetForTest()

    @AfterTest fun cleanUp() = UpdateState.resetForTest()

    @Test
    fun latest_starts_unknown_and_records_only_real_changes() {
        assertNull(UpdateState.latest)
        assertFalse(UpdateState.behind()) // unknown is NOT "up to date", but it's also not a nudge

        assertTrue(UpdateState.recordLatest("9.9.9"))
        assertEquals("9.9.9", UpdateState.latest)
        // a re-announce costs a sealed frame per attached device — only fire when the value actually moved
        assertFalse(UpdateState.recordLatest("9.9.9"))
        assertTrue(UpdateState.recordLatest("9.9.10"))
    }

    @Test
    fun behind_compares_the_recorded_release_against_what_we_run() {
        UpdateState.recordLatest("9999.0.0")
        assertTrue(UpdateState.behind())
        // the same version we run is not a reason to nag
        UpdateState.recordLatest(UpdateState.current)
        assertFalse(UpdateState.behind())
    }

    @Test
    fun stamp_fills_the_version_fields_without_touching_the_rest() {
        UpdateState.recordLatest("9999.0.0")
        val stamped = UpdateState.stamp(DaemonInfo(lanUrl = "ws://10.0.0.2:8765/v1/ws", hostname = "host", bridgeControl = true))
        assertEquals("ws://10.0.0.2:8765/v1/ws", stamped.lanUrl)
        assertEquals("host", stamped.hostname)
        assertTrue(stamped.bridgeControl)
        assertEquals(UpdateState.current, stamped.daemonVersion)
        assertEquals("9999.0.0", stamped.latestVersion)
        assertEquals(UpdateState.updateCommand, stamped.updateCommand)
    }

    @Test
    fun stamp_reports_an_unknown_latest_as_null_not_as_the_current_version() {
        val stamped = UpdateState.stamp(DaemonInfo())
        assertEquals(UpdateState.current, stamped.daemonVersion)
        assertNull(stamped.latestVersion) // never checked → the app must show "unknown", not "current"
    }
}
