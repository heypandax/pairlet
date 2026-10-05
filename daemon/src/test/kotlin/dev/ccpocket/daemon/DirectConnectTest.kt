package dev.ccpocket.daemon

import dev.ccpocket.daemon.relay.LoopbackDirect
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How `run` turns `--direct-bind` / `config --direct-connect` into a bind and an advertised address. */
class DirectConnectTest {

    private val fakeLan = { "192.168.7.20" }

    @Test
    fun no_flag_and_no_preference_is_this_computer_only() {
        assertEquals(DirectConnect.Resolved("127.0.0.1", fromFlag = false), DirectConnect.resolveBind(null, null))
    }

    @Test
    fun each_preference_maps_to_its_bind_when_no_flag_is_given() {
        assertEquals("127.0.0.1", DirectConnect.resolveBind(null, DirectConnectMode.LOCAL).bind)
        assertEquals("0.0.0.0", DirectConnect.resolveBind(null, DirectConnectMode.LAN).bind)
        assertEquals("none", DirectConnect.resolveBind(null, DirectConnectMode.OFF).bind)
        DirectConnectMode.values().forEach { assertFalse(DirectConnect.resolveBind(null, it).fromFlag) }
    }

    @Test
    fun an_explicit_flag_beats_the_preference_even_when_it_is_the_default_value() {
        // typed the default: still the command line's choice — the LAN preference does NOT widen it
        assertEquals(DirectConnect.Resolved("127.0.0.1", fromFlag = true), DirectConnect.resolveBind("127.0.0.1", DirectConnectMode.LAN))
        assertEquals(DirectConnect.Resolved("0.0.0.0", fromFlag = true), DirectConnect.resolveBind("0.0.0.0", DirectConnectMode.OFF))
        assertEquals(DirectConnect.Resolved("none", fromFlag = true), DirectConnect.resolveBind("none", DirectConnectMode.LAN))
        // a specific interface keeps working exactly as before
        assertEquals(DirectConnect.Resolved("10.1.2.3", fromFlag = true), DirectConnect.resolveBind("10.1.2.3", null))
    }

    @Test
    fun lan_advertises_a_non_loopback_lan_address() {
        val url = assertNotNull(DirectConnect.advertisedUrl(DirectConnectMode.LAN.bindHost, 8765, fakeLan))
        assertEquals("ws://192.168.7.20:8765/v1/ws", url)
        assertFalse(InetAddress.getByName(URI(url).host).isLoopbackAddress)
        // no usable LAN interface: nothing to advertise, devices stay on the relay
        assertNull(DirectConnect.advertisedUrl(DirectConnectMode.LAN.bindHost, 8765) { null })
    }

    @Test
    fun local_keeps_advertising_loopback_for_the_same_machine_app_and_never_a_lan_address() {
        var lanAsked = false
        val url = assertNotNull(DirectConnect.advertisedUrl(DirectConnectMode.LOCAL.bindHost, 8765) { lanAsked = true; "192.168.7.20" })
        assertEquals("ws://127.0.0.1:8765/v1/ws", url) // unchanged: the desktop App on this computer dials it
        assertTrue(InetAddress.getByName(URI(url).host).isLoopbackAddress)
        assertFalse(lanAsked, "local must not look up or leak the LAN address")
    }

    @Test
    fun off_advertises_nothing() {
        assertNull(DirectConnect.advertisedUrl(DirectConnectMode.OFF.bindHost, 8765, fakeLan))
    }

    @Test
    fun a_specific_interface_is_advertised_as_given() {
        assertEquals("ws://10.1.2.3:9000/v1/ws", DirectConnect.advertisedUrl("10.1.2.3", 9000, fakeLan))
    }

    @Test
    fun parse_accepts_only_the_three_words() {
        assertEquals(DirectConnectMode.LAN, DirectConnectMode.parse(" LAN "))
        assertEquals(DirectConnectMode.LOCAL, DirectConnectMode.parse("local"))
        assertEquals(DirectConnectMode.OFF, DirectConnectMode.parse("off"))
        listOf(null, "", "0.0.0.0", "none", "on", "lan2").forEach { assertNull(DirectConnectMode.parse(it), "$it") }
    }

    @Test
    fun mode_of_a_bind() {
        assertEquals("off", DirectConnect.modeOf("none"))
        assertEquals("local", DirectConnect.modeOf("127.0.0.1"))
        assertEquals("lan", DirectConnect.modeOf("0.0.0.0"))
        assertEquals("custom", DirectConnect.modeOf("10.1.2.3"))
    }

    @Test
    fun restart_commands_restart_the_service_and_never_start_a_foreground_daemon() {
        assertEquals("launchctl kickstart -k gui/\$(id -u)/dev.ccpocket.daemon", DirectConnect.restartCommand("Mac OS X"))
        assertEquals("systemctl --user restart cc-pocket-daemon", DirectConnect.restartCommand("Linux"))
        val win = DirectConnect.restartCommand("Windows 11")
        assertTrue("Start-ScheduledTask -TaskName cc-pocket-daemon" in win, win)
        assertTrue(win.indexOf("taskkill") < win.indexOf("Start-ScheduledTask"), "stop the old daemon first: $win")
        listOf("Mac OS X", "Linux", "Windows 11").forEach { assertFalse("pairlet run" in DirectConnect.restartCommand(it)) }
    }

    // ---- `pairlet status` ----

    private fun running(mode: String, bind: String, listening: Boolean = true, url: String? = null, fromFlag: Boolean = false) =
        LoopbackDirect(mode = mode, bind = bind, port = 8765, listening = listening, url = url, fromFlag = fromFlag)

    @Test
    fun status_shows_off_local_and_lan_with_address() {
        val off = DirectConnect.statusLines(running("off", "none"), DirectConnectMode.OFF, daemonUp = true, osName = "Mac OS X")
        assertEquals(listOf("  direct:   off — every device uses the relay"), off)

        val local = DirectConnect.statusLines(running("local", "127.0.0.1", url = "ws://127.0.0.1:8765/v1/ws"), null, daemonUp = true, osName = "Mac OS X")
        assertEquals(listOf("  direct:   ✓ this computer only (ws://127.0.0.1:8765/v1/ws)"), local)

        val lan = DirectConnect.statusLines(running("lan", "0.0.0.0", url = "ws://192.168.7.20:8765/v1/ws"), DirectConnectMode.LAN, daemonUp = true, osName = "Mac OS X")
        assertEquals(listOf("  direct:   ✓ LAN — ws://192.168.7.20:8765/v1/ws (all interfaces, paired devices only)"), lan)
    }

    @Test
    fun status_reports_a_failed_bind_and_a_change_waiting_for_restart() {
        val lines = DirectConnect.statusLines(running("local", "127.0.0.1", listening = false), DirectConnectMode.LAN, daemonUp = true, osName = "Linux")
        assertTrue("could not listen on 127.0.0.1:8765" in lines[0], lines.toString())
        assertTrue(lines.any { "configured: lan — restart the daemon to apply: systemctl --user restart cc-pocket-daemon" in it }, lines.toString())
    }

    @Test
    fun status_says_when_the_command_line_overrides_the_setting() {
        val lines = DirectConnect.statusLines(running("local", "127.0.0.1", url = "ws://127.0.0.1:8765/v1/ws", fromFlag = true), DirectConnectMode.LAN, daemonUp = true)
        assertTrue(lines.any { "--direct-bind" in it && "ignored" in it }, lines.toString())
        assertFalse(lines.any { "restart the daemon" in it }, "a restart would not change a flag-set bind")
    }

    @Test
    fun status_without_a_daemon_or_from_an_older_daemon() {
        assertEquals(
            listOf("  direct:   configured: local (default) — applies when the daemon starts"),
            DirectConnect.statusLines(null, null, daemonUp = false),
        )
        assertTrue("older version" in DirectConnect.statusLines(null, DirectConnectMode.LAN, daemonUp = true).single())
    }
}
