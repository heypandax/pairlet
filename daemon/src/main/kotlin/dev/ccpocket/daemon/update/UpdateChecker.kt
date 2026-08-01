package dev.ccpocket.daemon.update

import dev.ccpocket.daemon.relay.RelayClient
import dev.ccpocket.daemon.util.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * The daemon's daily new-version check (the Claude Code trick: after the first install, the tool
 * keeps ITSELF current, so the original channel stops mattering). Default behavior is check + notify
 * (a log line, plus one push to the phone per new version). With [autoApply] — and only for a
 * curl-managed install whose service points at the stable launcher — it downloads, verifies, flips
 * the symlink and EXITS: launchd KeepAlive / systemd Restart=always relaunch straight onto the new
 * version. Windows never auto-applies (a Scheduled Task doesn't restart an exited process);
 * `cc-pocket-daemon update` covers it manually.
 */
object UpdateChecker {
    private val log = logger("Update")
    private val noticeFile: Path = Path.of(System.getProperty("user.home"), ".cc-pocket", "update-notice")

    fun start(relay: RelayClient, autoApply: Boolean) {
        thread(isDaemon = true, name = "update-checker") {
            Thread.sleep(FIRST_CHECK_DELAY_MS) // let boot + relay attach settle first
            while (true) {
                runCatching { checkOnce(relay, autoApply) }
                    .onFailure { log.warn("update check failed: ${it.message}") }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }
    }

    internal fun checkOnce(relay: RelayClient, autoApply: Boolean) {
        val current = UpdateService.currentVersion()
        if (current == "0.0.0-dev") return // dev builds don't self-update
        val latest = UpdateService.latestRelease() ?: return
        // Publish what we saw even when we're already current (issue #200): DaemonInfo carries this to the
        // phone, and the APP compares it against its OWN version — that's how a device with no GitHub access
        // of its own learns it's behind. Re-announce only on a change, so this is at most one frame a day.
        if (UpdateState.recordLatest(latest.version)) {
            // bounded: the send path suspends on a full outbox, and a once-a-day nicety must never park
            // this thread behind a backed-up relay link
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(REANNOUNCE_TIMEOUT_MS) { relay.reannounceDaemonInfo() }
                }
            }.onFailure { log.warn("could not re-announce daemon info: ${it.message}") }
        }
        if (!UpdateService.isNewer(latest.version, current)) return

        log.info("update available: $current → ${latest.version} (cc-pocket-daemon update)")
        val install = UpdateService.managedInstallOf(UpdateService.selfExe())
        val canAuto = autoApply && install != null && install.serviceAnchored &&
            !System.getProperty("os.name").lowercase().contains("win")

        if (canAuto) {
            log.info("auto-updating to ${latest.version}")
            UpdateService.apply(latest, install!!)
            log.info("switched — exiting so the service supervisor relaunches v${latest.version}")
            exitProcess(0) // KeepAlive / Restart=always brings the new binary up within seconds
        }

        // notify the phone once per version (the relay only pushes when the app isn't attached;
        // an attached user sees the daemon log / release notes anyway)
        if (lastNotified() != latest.version) {
            // the SAME line DaemonInfo carries and `version` prints — one answer to "how do I update this?"
            runCatching { kotlinx.coroutines.runBlocking { relay.notifyPhone("cc-pocket ${latest.version} available", UpdateState.updateCommand) } }
            saveNotified(latest.version)
        }
    }

    private fun lastNotified(): String? = runCatching { noticeFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    private fun saveNotified(v: String) = runCatching {
        Files.createDirectories(noticeFile.parent)
        noticeFile.writeText(v)
    }

    private const val REANNOUNCE_TIMEOUT_MS = 5_000L
    private const val FIRST_CHECK_DELAY_MS = 5 * 60 * 1000L
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
}
