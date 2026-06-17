package dev.ccpocket.daemon.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Generates (and optionally installs) a background-service definition for the current OS. */
object ServiceInstaller {

    fun install(exec: String, runArgs: List<String>, apply: Boolean): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> mac(exec, runArgs, apply)
            os.contains("win") -> windows(exec, runArgs)
            else -> linux(exec, runArgs)
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

    private fun linux(exec: String, args: List<String>): String {
        val unit = """
            |[Unit]
            |Description=cc-pocket daemon
            |After=network-online.target
            |
            |[Service]
            |ExecStart=$exec ${args.joinToString(" ")}
            |Restart=always
            |RestartSec=3
            |
            |[Install]
            |WantedBy=default.target
        """.trimMargin()
        return "Linux systemd --user unit — write to ~/.config/systemd/user/cc-pocket-daemon.service then " +
            "`systemctl --user daemon-reload && systemctl --user enable --now cc-pocket-daemon`:\n\n$unit"
    }

    private fun windows(exec: String, args: List<String>): String =
        "Windows — wrap with WinSW, or (as Administrator):\n" +
            "  sc.exe create cc-pocket-daemon binPath= \"$exec ${args.joinToString(" ")}\" start= auto\n" +
            "  sc.exe start cc-pocket-daemon"
}
