package dev.ccpocket.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "is anyone behind?" rule behind the settings section and the nudge dot (issue #200). */
class VersionStatusTest {

    @Test
    fun a_daemon_that_never_reported_is_unknown_not_behind() {
        val s = VersionStatus(appVersion = "1.5.5")
        assertFalse(s.appBehind)
        assertFalse(s.daemonBehind)
        assertFalse(s.anyBehind) // no evidence either way — never nag on silence
    }

    @Test
    fun the_published_release_flags_whichever_side_is_behind() {
        val bothBehind = VersionStatus("1.5.0", daemonVersion = "1.5.0", latestVersion = "1.6.0")
        assertTrue(bothBehind.appBehind)
        assertTrue(bothBehind.daemonBehind)
        assertEquals("1.6.0", bothBehind.newestKnown)

        val onlyDaemon = VersionStatus("1.6.0", daemonVersion = "1.5.0", latestVersion = "1.6.0")
        assertFalse(onlyDaemon.appBehind)
        assertTrue(onlyDaemon.daemonBehind)

        val current = VersionStatus("1.6.0", daemonVersion = "1.6.0", latestVersion = "1.6.0")
        assertFalse(current.anyBehind)
    }

    @Test
    fun an_app_newer_than_its_daemon_is_itself_proof_the_daemon_is_behind() {
        // releases ship in lockstep, so this works with no release info at all — offline, or against a
        // daemon whose own update check has never succeeded
        val s = VersionStatus("1.6.0", daemonVersion = "1.5.0", latestVersion = null)
        assertTrue(s.daemonBehind)
        assertFalse(s.appBehind)
        assertEquals("1.6.0", s.newestKnown)
    }

    @Test
    fun a_daemon_ahead_of_this_app_does_not_read_as_behind() {
        val s = VersionStatus("1.5.0", daemonVersion = "1.6.0", latestVersion = "1.6.0")
        assertFalse(s.daemonBehind)
        assertTrue(s.appBehind) // the phone is the stale one here
    }

    @Test
    fun a_dev_daemon_is_never_behind() {
        // 0.0.0-dev sorts below everything; it updates by rebuild, so nagging its operator is noise
        val s = VersionStatus("1.6.0", daemonVersion = "0.0.0-dev", latestVersion = "1.6.0")
        assertFalse(s.daemonBehind)
        assertFalse(s.anyBehind)
        assertTrue(VersionStatus.isDevBuild("0.0.0-dev"))
        assertFalse(VersionStatus.isDevBuild("1.6.0"))
    }

    @Test
    fun newest_known_never_goes_backwards_from_a_stale_release_reading() {
        // a daemon that last checked before our release still must not make the app look behind
        val s = VersionStatus("1.6.0", daemonVersion = "1.6.0", latestVersion = "1.5.0")
        assertFalse(s.appBehind)
        assertFalse(s.daemonBehind)
        assertEquals("1.6.0", s.newestKnown)
    }
}
