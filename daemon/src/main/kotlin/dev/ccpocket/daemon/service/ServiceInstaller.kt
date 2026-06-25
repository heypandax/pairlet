package dev.ccpocket.daemon.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Generates (and optionally installs) a background-service definition for the current OS. */
object ServiceInstaller {

    const val WINDOWS_TASK = "cc-pocket-daemon" // per-user logon Scheduled Task name (issue #16 self-heal + service-install)

    fun install(exec: String, runArgs: List<String>, apply: Boolean): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> mac(exec, runArgs, apply)
            os.contains("win") -> windows(exec, runArgs, apply)
            else -> linux(exec, runArgs, apply)
        }
    }

    /** True iff the Windows logon Scheduled Task is already registered (queried via schtasks; false on any error). */
    fun isWindowsTaskInstalled(taskName: String = WINDOWS_TASK): Boolean = runCatching {
        val p = ProcessBuilder("schtasks", "/Query", "/TN", taskName).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText() // drain so the child can exit cleanly
        p.waitFor() == 0 // 0 = task exists, non-zero = not found
    }.getOrDefault(false)

    /**
     * Windows self-heal (issue #16): if `cc-pocket-daemon run` starts without the logon Scheduled Task
     * registered, register it so the daemon survives closing the terminal. Idempotent — a no-op on non-Windows
     * and when the task already exists. It registers WITHOUT starting now: the current foreground process is
     * already serving, and launching a second hidden instance would put two daemons on the relay under one
     * account — so the task instead takes over at the next logon. Never throws; returns a short status/hint
     * line, or null when there's nothing to say. Mirrors macOS's auto-install via the cask postflight, for
     * raw-binary installs (no Scoop post_install) or a post_install that silently failed.
     */
    fun selfInstallIfMissingWindows(exec: String, runArgs: List<String>): String? {
        if (!System.getProperty("os.name").lowercase().contains("win")) return null
        if (isWindowsTaskInstalled()) return null
        if (exec.isBlank() || !File(exec).canExecute()) {
            return "note: can't auto-register the background service (launcher not found) — the daemon stops " +
                "when this window closes. Fix: cc-pocket-daemon service-install --apply"
        }
        val msg = runCatching { windows(exec, runArgs, apply = true, startNow = false) }.getOrElse {
            return "note: couldn't auto-register the background service (${it.message}) — the daemon stops " +
                "when this window closes. Fix: cc-pocket-daemon service-install --apply"
        }
        return if (msg.startsWith("could not register")) {
            "note: $msg"
        } else {
            "registered a logon background service — from your next sign-in the daemon starts automatically " +
                "(this window keeps it running until then)"
        }
    }

    private fun mac(exec: String, args: List<String>, apply: Boolean): String {
        val home = System.getProperty("user.home")
        val plistPath = Path.of(home, "Library", "LaunchAgents", "dev.ccpocket.daemon.plist")
        val logDir = Path.of(home, "Library", "Logs", "cc-pocket")
        val outLog = logDir.resolve("daemon.out.log")
        val errLog = logDir.resolve("daemon.err.log")

        // launchd starts login agents with a bare PATH (/usr/bin:/bin:/usr/sbin:/sbin) — no Homebrew,
        // no node — so the daemon can't reliably find `claude`. We're invoked from the user's shell (or a
        // Homebrew postflight), so seed the agent's PATH from ours and UNION in the well-known bin dirs;
        // that keeps it correct even when the caller's environment was sanitized.
        val wellKnown = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")
        val path = (System.getenv("PATH")?.split(":").orEmpty() + wellKnown)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(":")

        val argsXml = (listOf(exec) + args).joinToString("\n") { "        <string>${xml(it)}</string>" }
        val plist = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0"><dict>
            |    <key>Label</key><string>dev.ccpocket.daemon</string>
            |    <key>ProgramArguments</key><array>
            |$argsXml
            |    </array>
            |    <key>EnvironmentVariables</key><dict>
            |        <key>PATH</key><string>${xml(path)}</string>
            |        <key>HOME</key><string>${xml(home)}</string>
            |    </dict>
            |    <key>RunAtLoad</key><true/>
            |    <key>KeepAlive</key><true/>
            |    <key>ThrottleInterval</key><integer>10</integer>
            |    <key>StandardOutPath</key><string>${xml(outLog.toString())}</string>
            |    <key>StandardErrorPath</key><string>${xml(errLog.toString())}</string>
            |</dict></plist>
        """.trimMargin()

        if (!apply) {
            return "macOS launchd agent — write to $plistPath then `launchctl load <plist>` (or re-run with --apply):\n\n$plist"
        }
        // Refuse to install a plist whose launcher is missing — that's the classic
        // "/usr/local/bin on Apple Silicon" footgun that fails at every boot with EX_CONFIG (78).
        require(File(exec).canExecute()) {
            "launcher not executable: $exec — pass --exec with the real cc-pocket-daemon path"
        }
        Files.createDirectories(logDir)
        Files.createDirectories(plistPath.parent)
        plistPath.writeText(plist)
        runCatching { ProcessBuilder("launchctl", "unload", plistPath.toString()).start().waitFor() }
        ProcessBuilder("launchctl", "load", plistPath.toString()).start().waitFor()
        return "installed + loaded launchd agent: $plistPath\n  logs: $errLog"
    }

    /** Minimal XML text escaping for plist <string> values. */
    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun linux(exec: String, args: List<String>, apply: Boolean): String {
        val home = System.getProperty("user.home")
        val unitPath = Path.of(home, ".config", "systemd", "user", "cc-pocket-daemon.service")

        // A systemd --user service inherits the user manager's environment, which often lacks the
        // Homebrew/node bin dirs the daemon needs to find `claude`. Seed PATH from ours, union in the
        // well-known dirs — same footgun (and fix) as the launchd agent on macOS.
        val wellKnown = listOf("/home/linuxbrew/.linuxbrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
        val path = (System.getenv("PATH")?.split(":").orEmpty() + wellKnown)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(":")

        val unit = """
            |[Unit]
            |Description=cc-pocket daemon
            |After=network-online.target
            |
            |[Service]
            |ExecStart=$exec ${args.joinToString(" ")}
            |Environment=PATH=$path
            |Restart=always
            |RestartSec=3
            |
            |[Install]
            |WantedBy=default.target
        """.trimMargin()

        if (!apply) {
            return "Linux systemd --user unit — write to $unitPath then " +
                "`systemctl --user daemon-reload && systemctl --user enable --now cc-pocket-daemon` " +
                "(or re-run with --apply):\n\n$unit"
        }
        // Same guard as macOS: refuse to install a unit whose launcher is missing — it would just
        // fail at every (re)start with status=203/EXEC.
        require(File(exec).canExecute()) {
            "launcher not executable: $exec — pass --exec with the real cc-pocket-daemon path"
        }
        Files.createDirectories(unitPath.parent)
        unitPath.writeText(unit)
        ProcessBuilder("systemctl", "--user", "daemon-reload").start().waitFor()
        ProcessBuilder("systemctl", "--user", "enable", "--now", "cc-pocket-daemon").start().waitFor()
        // Best-effort: let the --user service keep running after logout (no-op if already enabled).
        runCatching { ProcessBuilder("loginctl", "enable-linger", System.getenv("USER") ?: "").start().waitFor() }
        return "installed + started systemd --user unit: $unitPath\n" +
            "  logs: journalctl --user -u cc-pocket-daemon -f"
    }

    // Windows has no launchd/systemd; the closest no-admin, no-extra-deps equivalent is a per-user
    // Scheduled Task triggered at logon. The launcher is a --win-console exe, so a logon task running it
    // directly would flash a console window every login — instead we point the task at a tiny VBScript
    // that launches the daemon with window style 0 (hidden) and returns immediately. Registered via
    // PowerShell cmdlets (Execute/Argument passed separately → avoids schtasks /tr quote hell).
    private fun windows(exec: String, args: List<String>, apply: Boolean, startNow: Boolean = true): String {
        val home = System.getProperty("user.home")
        val taskName = WINDOWS_TASK
        val ccDir = Path.of(home, ".cc-pocket")
        val vbs = ccDir.resolve("cc-pocket-daemon-service.vbs")

        // VBScript escapes a double-quote by doubling it. Quote every token so spaces in the exe path
        // are safe: WScript.Shell.Run "<cmd>", 0, False  (0 = hidden window, False = don't wait).
        val cmd = (listOf(exec) + args).joinToString(" ") { "\"\"$it\"\"" }
        val vbsBody = "CreateObject(\"WScript.Shell\").Run \"$cmd\", 0, False\n"

        val register = """
            |${'$'}a = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument '"$vbs"'
            |${'$'}t = New-ScheduledTaskTrigger -AtLogOn
            |${'$'}s = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
            |Register-ScheduledTask -TaskName '$taskName' -Action ${'$'}a -Trigger ${'$'}t -Settings ${'$'}s -Force | Out-Null
        """.trimMargin()
        // self-heal from a running `run` registers for next logon WITHOUT starting a second instance now (the
        // foreground process already serves) — avoids a duplicate relay connection under one account (issue #16).
        val ps = if (startNow) "$register\nStart-ScheduledTask -TaskName '$taskName'" else register

        if (!apply) {
            return "Windows logon Scheduled Task — write $vbs with:\n\n$vbsBody\n" +
                "then register it in PowerShell (or re-run with --apply):\n\n$ps"
        }
        // Same guard as macOS/Linux: refuse to register a task whose launcher is missing.
        require(File(exec).canExecute()) {
            "launcher not executable: $exec — pass --exec with the real cc-pocket-daemon.exe path"
        }
        Files.createDirectories(ccDir)
        vbs.writeText(vbsBody)
        val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            return "could not register the Scheduled Task (exit $code):\n$out\n" +
                "the daemon still runs by hand: \"$exec\" run"
        }
        return "installed + started logon Scheduled Task '$taskName'\n  hidden launcher: $vbs"
    }
}
