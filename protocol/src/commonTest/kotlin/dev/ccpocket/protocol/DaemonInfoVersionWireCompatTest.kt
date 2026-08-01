package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-#200 `pocket/daemon.info` shape — an OLD app decoding a NEW daemon's frame. */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("pocket/daemon.info")
private data class OldDaemonInfo(
    val lanUrl: String? = null,
    val hostname: String? = null,
    val gatewayBaseUrl: String? = null,
    val bridgeControl: Boolean = false,
)

/**
 * Wire compatibility for the version-visibility fields (issue #200). The daemon and the app ship on
 * independent schedules, so both mixed-version directions have to work: an old app must ignore the new
 * trailing fields, and a new app must read their ABSENCE as "unknown" rather than as "up to date".
 */
class DaemonInfoVersionWireCompatTest {

    @Test
    fun daemonInfo_roundtrips_the_version_fields() {
        val env = Envelope(
            id = "1", ts = 3,
            body = DaemonInfo(
                lanUrl = "ws://192.168.1.5:8765/v1/ws", hostname = "Pandas-MacBook-Pro", bridgeControl = true,
                daemonVersion = "1.5.5", latestVersion = "1.6.0", updateCommand = "cc-pocket-daemon update",
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/daemon.info\"" in json, json)
        assertTrue("\"daemonVersion\":\"1.5.5\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun an_old_daemons_frame_decodes_with_the_version_fields_null() {
        // exactly what a pre-#200 daemon puts on the wire
        val json = """{"t":"pocket/daemon.info","lanUrl":"ws://10.0.0.2:8765/v1/ws","bridgeControl":true}"""
        val info = PocketJson.decodeFromString<Frame>(json) as DaemonInfo
        assertEquals("ws://10.0.0.2:8765/v1/ws", info.lanUrl)
        assertTrue(info.bridgeControl)
        // null = "unknown", which is what keeps the app from claiming an ancient daemon is current
        assertNull(info.daemonVersion)
        assertNull(info.latestVersion)
        assertNull(info.updateCommand)
    }

    @Test
    fun an_old_app_still_decodes_a_new_daemons_frame() {
        val json = PocketJson.encodeToString<Frame>(
            DaemonInfo(lanUrl = "ws://10.0.0.2:8765/v1/ws", bridgeControl = true, daemonVersion = "1.6.0", latestVersion = "1.6.0"),
        )
        val old = PocketJson.decodeFromString<OldDaemonInfo>(json)
        assertEquals("ws://10.0.0.2:8765/v1/ws", old.lanUrl)
        assertTrue(old.bridgeControl)
    }

    @Test
    fun a_daemon_that_never_checked_omits_the_unknown_fields_entirely() {
        // explicitNulls=false: "haven't checked yet" costs no bytes and reads identically to an old daemon
        val json = PocketJson.encodeToString<Frame>(DaemonInfo(daemonVersion = "1.5.5", updateCommand = "cc-pocket-daemon update"))
        assertFalse("latestVersion" in json, json)
        assertEquals("1.5.5", (PocketJson.decodeFromString<Frame>(json) as DaemonInfo).daemonVersion)
    }
}
