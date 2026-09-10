package dev.ccpocket.daemon.diagnostics

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.ccpocket.observability.Component
import dev.ccpocket.observability.sentry.SentryRuntime

class DiagnosticsCommand : CliktCommand(name = "diagnostics") {
    private val sharing by option("--sharing", help = "Share safe diagnostic errors/logs with Sentry: on or off (default off)").choice("on", "off")
    override fun run() {
        sharing?.let { DaemonDiagnostics.setEnabled(it == "on") }
        echo("diagnostics sharing: ${if (DaemonDiagnostics.enabled()) "on" else "off"}")
        echo("Sentry configuration: ${if (SentryRuntime.configuredDsn(Component.DAEMON) != null) "present" else "absent"}")
        if (sharing != null) echo("The running daemon refreshes this setting within one second.")
    }
}
