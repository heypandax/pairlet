package dev.ccpocket.daemon.service

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
        val plistPath = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.ccpocket.daemon.plist")
        val argsXml = (listOf(exec) + args).joinToString("\n") { "        <string>$it</string>" }
        val plist = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0"><dict>
            |    <key>Label</key><string>dev.ccpocket.daemon</string>
            |    <key>ProgramArguments</key><array>
            |$argsXml
            |    </array>
            |    <key>RunAtLoad</key><true/>
            |    <key>KeepAlive</key><true/>
            |</dict></plist>
        """.trimMargin()
        return if (apply) {
            Files.createDirectories(plistPath.parent)
            plistPath.writeText(plist)
            runCatching { ProcessBuilder("launchctl", "unload", plistPath.toString()).start().waitFor() }
            ProcessBuilder("launchctl", "load", plistPath.toString()).start().waitFor()
            "installed + loaded launchd agent: $plistPath"
        } else {
            "macOS launchd agent — write to $plistPath then `launchctl load <plist>` (or re-run with --apply):\n\n$plist"
        }
    }

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
