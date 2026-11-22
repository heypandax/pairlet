package dev.ccpocket.daemon.update

import dev.ccpocket.daemon.DEFAULT_RELAY
import dev.ccpocket.daemon.service.ServiceInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsServiceArgsTest {
    private val exec = "C:\\Program Files\\cc-pocket\\cc-pocket-daemon.exe"
    private val args = listOf("run", "--relay", "wss://my-relay.example", "--pair-port", "18899", "--port", "18765",
        "--codex-bin", "C:\\tools\\codex.cmd")

    @Test
    fun vbs_written_by_the_frozen_installer_round_trips_every_argument() {
        // The dry-run text embeds the exact VBS body ServiceInstaller writes.
        val rendered = ServiceInstaller.windows(exec, args, apply = false, startNow = false)
        assertEquals(listOf(exec) + args, WindowsServiceArgs.parseVbsTokens(rendered))
        assertNull(WindowsServiceArgs.parseVbsTokens("MsgBox \"hello\""))
    }

    @Test
    fun update_keeps_installed_ports_and_relay_and_watches_the_installed_pair_port() {
        val runArgs = WindowsServiceArgs.forUpdate(args, 8799)
        assertEquals(args, runArgs)
        assertEquals(18899, WindowsServiceArgs.pairPortOf(runArgs, 8799))
    }

    @Test
    fun unreadable_installation_falls_back_to_the_historical_defaults() {
        val runArgs = WindowsServiceArgs.forUpdate(null, 8799)
        assertEquals(listOf("run", "--relay", DEFAULT_RELAY, "--pair-port", "8799"), runArgs)
        assertEquals(8799, WindowsServiceArgs.pairPortOf(runArgs, 1))
    }
}
