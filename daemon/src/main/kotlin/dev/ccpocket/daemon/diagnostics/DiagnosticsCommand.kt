package dev.ccpocket.daemon.diagnostics

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.ccpocket.observability.Component
import dev.ccpocket.observability.sentry.SentryRuntime

class DiagnosticsCommand : CliktCommand(name = "diagnostics") {
    private val sharing by option("--sharing", help = "Share safe diagnostic errors/logs with Sentry: on or off (default off)").choice("on", "off")
    private val dsh by option(
        "--dsh",
        help = "Print a redacted DeepSeek Harness profile/plugin summary (package names and versions only — no " +
            "settings, keys or paths) to paste into a support thread",
    ).flag()
    override fun run() {
        if (dsh) {
            val exe = runCatching { dev.ccpocket.daemon.dsh.DshLauncher.resolveExecutable() }.getOrNull()
            echo(
                dev.ccpocket.daemon.dsh.DshProfiles.report(
                    home = dev.ccpocket.daemon.dsh.DshPaths.dshHome(),
                    dshVersion = exe?.let(dev.ccpocket.daemon.dsh.DshProfiles::dshVersion),
                    homeOverridden = !System.getenv("DSH_HOME").isNullOrBlank(),
                ).trimEnd(),
            )
            return
        }
        sharing?.let { DaemonDiagnostics.setEnabled(it == "on") }
        echo("diagnostics sharing: ${if (DaemonDiagnostics.enabled()) "on" else "off"}")
        echo("Sentry configuration: ${if (SentryRuntime.configuredDsn(Component.DAEMON) != null) "present" else "absent"}")
        if (sharing != null) echo("The running daemon refreshes this setting within one second.")
    }
}
