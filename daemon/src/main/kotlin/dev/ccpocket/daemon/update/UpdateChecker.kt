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
 * version — once no session is busy, or after at most [MAX_DEFER_MS] (see [applyWhenIdle]). Windows never auto-applies (a Scheduled Task doesn't restart an exited process);
 * `pairlet update` covers it manually.
 */
object UpdateChecker {
    private val log = logger("Update")
    private val noticeFile: Path = Path.of(System.getProperty("user.home"), ".cc-pocket", "update-notice")

    /**
     * Issue #244: is auto-apply on for this daemon? Highest wins:
     *  1. `--auto-update` on the `run` command (a flag can only be present, so only ever forces ON),
     *  2. the `CC_POCKET_AUTO_UPDATE` env toggle — "1"/"true"/"on" forces ON, "0"/"false"/"off" forces
     *     OFF (an unrecognized value is ignored rather than silently meaning "off"),
     *  3. the persisted preference from `config --auto-update on|off`,
     *  4. otherwise the default: ON. Curl-installed daemons keep themselves current the way Claude Code
     *     does. This is safe to default because [checkOnce]'s `canAuto` still requires a managed install
     *     whose service points at the stable launcher, and never fires on Windows — brew/scoop/dev
     *     layouts have no managed install and stay notify-only no matter what this returns.
     */
    fun resolveAutoApply(flag: Boolean, env: String?, pref: Boolean?): Boolean {
        if (flag) return true
        when (env?.trim()?.lowercase()) {
            "1", "true", "on", "yes" -> return true
            "0", "false", "off", "no" -> return false
            else -> {} // unset or unrecognized: fall through
        }
        return pref ?: DEFAULT_AUTO_APPLY
    }

    /** What a fresh install gets: keep itself current (see [resolveAutoApply]). */
    const val DEFAULT_AUTO_APPLY = true

    /** [isBusy]: is any session doing or awaiting work a daemon exit would destroy? (auto-apply's gate) */
    fun start(relay: RelayClient, autoApply: Boolean, isBusy: () -> Boolean) {
        // once per process, never per check: is self-update signature-enforced in this build, or not configured?
        log.info(dev.ccpocket.protocol.update.ReleaseSignature.modeLine())
        thread(isDaemon = true, name = "update-checker") {
            Thread.sleep(FIRST_CHECK_DELAY_MS) // let boot + relay attach settle first
            while (true) {
                runCatching { checkOnce(relay, autoApply, isBusy) }
                    .onFailure {
                        dev.ccpocket.observability.Diagnostics.report(dev.ccpocket.observability.ErrorPath.UPDATE,
                            dev.ccpocket.observability.Stage.REQUEST, dev.ccpocket.observability.ErrorCode.UNAVAILABLE, it,
                            isError = false) // apply() owns the primary failure; this is the periodic loop boundary
                        log.warn("update check failed: ${it.message}")
                    }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * The auto-apply step (audit U2): apply the update, then exit for the supervisor to relaunch — but only
     * while no session is busy, because the exit takes every running agent turn down with it. Waits for
     * idle before the download and again before the exit (a turn may start while it downloads), polling
     * every [pollMs], against ONE deadline of [maxDeferMs] so a daemon that never goes idle still updates.
     * A failing [isBusy] probe counts as busy. Runs on the update-checker thread; the manual
     * `pairlet update` command never comes through here.
     */
    internal fun applyWhenIdle(
        isBusy: () -> Boolean,
        apply: () -> Unit,
        exit: () -> Unit,
        maxDeferMs: Long = MAX_DEFER_MS,
        pollMs: Long = IDLE_POLL_MS,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ) {
        val deadline = now() + maxDeferMs
        fun awaitIdle(step: String) {
            var announced = false
            while (runCatching(isBusy).getOrDefault(true)) {
                val left = deadline - now()
                if (left <= 0) {
                    log.warn("auto-update: sessions still busy after ${maxDeferMs / 60_000} min — proceeding to $step anyway")
                    return
                }
                if (!announced) {
                    log.info("auto-update: a session is busy — holding the $step until idle (checking every ${pollMs / 1_000}s)")
                    announced = true
                }
                sleep(minOf(pollMs, left))
            }
        }
        awaitIdle("download")
        apply()
        awaitIdle("restart")
        exit()
    }

    internal fun checkOnce(relay: RelayClient, autoApply: Boolean, isBusy: () -> Boolean) {
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

        log.info("update available: $current → ${latest.version} (pairlet update)")
        val install = UpdateService.managedInstallOf(UpdateService.selfExe())
        val canAuto = autoApply && install != null && install.serviceAnchored &&
            !System.getProperty("os.name").lowercase().contains("win")

        if (canAuto) {
            applyWhenIdle(
                isBusy = isBusy,
                apply = {
                    log.info("auto-updating to ${latest.version}")
                    // background path: no terminal to draw on, and a redrawn line would flood the daemon log (#381)
                    UpdateService.apply(latest, install!!, UpdateProgressListener.QUIET)
                },
                exit = {
                    log.info("switched — exiting so the service supervisor relaunches v${latest.version}")
                    exitProcess(0) // KeepAlive / Restart=always brings the new binary up within seconds
                },
            )
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
    // Longest an auto-update waits for the daemon to go idle: one check interval. A found update then lands
    // no later than the next scheduled check would have (never more than a cycle late) and the wait never
    // overlaps that check; 24h also outlasts any realistic agent turn, while a never-ending background job
    // (a dev server left running) can't pin the daemon on an old version for good.
    internal const val MAX_DEFER_MS = CHECK_INTERVAL_MS
    internal const val IDLE_POLL_MS = 60_000L
}
