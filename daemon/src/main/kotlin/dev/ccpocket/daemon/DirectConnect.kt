package dev.ccpocket.daemon

import dev.ccpocket.daemon.relay.LoopbackDirect

/**
 * Where the E2E direct listener binds when `run` gets no `--direct-bind` — the persisted
 * `pairlet config --direct-connect local|lan|off`. A service-managed daemon (launchd / systemd / the
 * Windows logon task) starts as a bare `run`, so this preference is the only way such a daemon can open
 * the listener to the LAN without hand-editing the service definition.
 *
 * The listener is Noise-gated whatever it binds to (see [dev.ccpocket.daemon.server.LanE2E]): only a
 * device paired to this computer gets past the handshake. The mode decides who can REACH the port.
 */
enum class DirectConnectMode(val wire: String, val bindHost: String) {
    /** 127.0.0.1 — the desktop App on this computer only. The built-in default. */
    LOCAL("local", "127.0.0.1"),

    /** 0.0.0.0 — every interface, so paired phones on the same network connect without the relay. */
    LAN("lan", "0.0.0.0"),

    /** No direct listener at all; every device goes through the relay. */
    OFF("off", DirectConnect.NONE),
    ;

    companion object {
        /** The persisted / typed value, case-insensitive; anything else (a typo, a hand-edited file) is null. */
        fun parse(s: String?): DirectConnectMode? {
            val v = s?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.wire == v }
        }
    }
}

object DirectConnect {
    /** The `--direct-bind` value that disables the listener (kept from before the preference existed). */
    const val NONE = "none"

    val DEFAULT = DirectConnectMode.LOCAL

    /** The bind `run` uses, and whether the command line chose it (then the preference is ignored). */
    data class Resolved(val bind: String, val fromFlag: Boolean)

    /**
     * Precedence: an explicit `--direct-bind` (any value, including `127.0.0.1` or a specific IP) beats the
     * preference, which beats the built-in [DEFAULT]. [flag] is null only when the option was not given at
     * all — `run`'s option deliberately has no clikt default, so "typed the default" stays distinguishable.
     */
    fun resolveBind(flag: String?, pref: DirectConnectMode?): Resolved =
        if (flag != null) Resolved(flag, fromFlag = true)
        else Resolved((pref ?: DEFAULT).bindHost, fromFlag = false)

    /**
     * What [dev.ccpocket.protocol.DaemonInfo.lanUrl] advertises for [bind] (unchanged from before the
     * preference): a specific address is advertised as is (127.0.0.1 → the same-machine desktop App dials
     * it); 0.0.0.0 → the current LAN IPv4, looked up per call so a DHCP move heals; `none` or no usable LAN
     * interface → null (devices clear their stored address and stay on the relay).
     */
    fun advertisedUrl(bind: String, port: Int, lanIp: () -> String?): String? = when (bind) {
        NONE -> null
        "0.0.0.0" -> lanIp()?.let { "ws://$it:$port/v1/ws" }
        else -> "ws://$bind:$port/v1/ws"
    }

    /** The mode a bind amounts to, as reported by `status`: one of the [DirectConnectMode] wires or `custom`
     *  (a specific interface given with `--direct-bind <ip>`). */
    fun modeOf(bind: String): String = when (bind) {
        NONE -> DirectConnectMode.OFF.wire
        "0.0.0.0" -> DirectConnectMode.LAN.wire
        "127.0.0.1", "localhost", "::1" -> DirectConnectMode.LOCAL.wire
        else -> "custom"
    }

    /**
     * The per-OS command that restarts the BACKGROUND service, so a changed preference takes effect. Never
     * `pairlet run`: a foreground daemon next to the service is the two-daemons failure (AGENTS.md).
     * Windows has no one-step restart — the logon task launches the daemon detached, so starting the task
     * while the old daemon lives is refused by the single-instance check. Same order as the updater
     * (UpdateService.restartWindowsService): stop whoever holds the pair port, then start the task.
     */
    fun restartCommand(osName: String = System.getProperty("os.name")): String {
        val os = osName.lowercase()
        return when {
            os.contains("win") ->
                "taskkill /T /F /PID (Get-NetTCPConnection -LocalPort 8799 -State Listen).OwningProcess; " +
                    "Start-Sleep 2; Start-ScheduledTask -TaskName ${dev.ccpocket.daemon.service.ServiceInstaller.WINDOWS_TASK}" +
                    "    (in PowerShell)"
            os.contains("mac") -> "launchctl kickstart -k gui/\$(id -u)/dev.ccpocket.daemon"
            else -> "systemctl --user restart cc-pocket-daemon"
        }
    }

    /** What `config --direct-connect lan` prints: what is opened, who gets through, how to close it again. */
    fun lanNotice(): List<String> = listOf(
        "direct-connect lan — what this opens:",
        "  After the restart the daemon listens on TCP port 8765 (the `run --port` default) on every network",
        "  interface, so any device on the same network can reach that port. Each connection must first",
        "  complete the end-to-end (Noise) handshake with the key of a device paired to this computer;",
        "  anything else (an unpaired device, a scanner, a bridge credential) is refused during the handshake",
        "  and never reaches a session. Paired devices' traffic stays end-to-end encrypted, as over the relay.",
        "  macOS may ask once whether to allow incoming connections; if they are blocked, devices keep using the relay.",
        "  Turn it off:  pairlet config --direct-connect local   (this computer only)   or   --direct-connect off",
    )

    /**
     * The `direct:` lines of `pairlet status`. [running] is what the running daemon reports (null = an older
     * daemon that doesn't, or no daemon); [configured] is the saved preference, to flag a change that is
     * waiting for a restart.
     */
    fun statusLines(running: LoopbackDirect?, configured: DirectConnectMode?, daemonUp: Boolean, osName: String = System.getProperty("os.name")): List<String> {
        val want = configured ?: DEFAULT
        val wantLabel = "${want.wire}${if (configured == null) " (default)" else ""}"
        if (!daemonUp) return listOf("  direct:   configured: $wantLabel — applies when the daemon starts")
        if (running == null) {
            return listOf("  direct:   – not reported by the running daemon (older version); configured: $wantLabel")
        }
        val addr = "${running.bind}:${running.port}"
        val head = when {
            running.mode == DirectConnectMode.OFF.wire -> "off — every device uses the relay"
            !running.listening -> "✗ could not listen on $addr (port in use?) — relay only"
            running.mode == DirectConnectMode.LOCAL.wire -> "✓ this computer only (${running.url ?: "ws://$addr/v1/ws"})"
            running.mode == DirectConnectMode.LAN.wire -> running.url
                ?.let { "✓ LAN — $it (all interfaces, paired devices only)" }
                ?: "✓ LAN — listening on $addr, but no LAN address found to advertise (devices stay on the relay)"
            else -> "✓ ${running.bind} — ${running.url ?: "ws://$addr/v1/ws"} (paired devices only)"
        }
        val lines = mutableListOf("  direct:   $head")
        if (running.fromFlag) {
            if (configured != null && configured.wire != running.mode) {
                lines += "            (set by --direct-bind on the daemon's command line; `config --direct-connect ${configured.wire}` is ignored)"
            }
        } else if (want.wire != running.mode) {
            lines += "            configured: ${want.wire} — restart the daemon to apply: ${restartCommand(osName)}"
        }
        return lines
    }
}
