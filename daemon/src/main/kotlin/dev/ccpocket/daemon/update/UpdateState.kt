package dev.ccpocket.daemon.update

import dev.ccpocket.protocol.DaemonInfo

/**
 * What this daemon knows about its own version and the newest published one — the one place the CLI
 * (`version`, `status`) and the phone (`DaemonInfo`, issue #200) both read, so they can never disagree
 * about what is running or how to update it.
 *
 * [latest] is filled by [UpdateChecker]'s daily round-trip and stays null until the first one succeeds
 * (and forever on a dev build, which never checks): the honest answer there is "unknown", not "up to
 * date". [updateCommand] is resolved once — how this binary was installed cannot change while it runs.
 */
object UpdateState {

    /** The version actually running (`0.0.0-dev` for a build that came from gradle, not a release). */
    val current: String get() = UpdateService.currentVersion()

    /** Newest published release the daily check has seen; null = never successfully checked. */
    @Volatile
    var latest: String? = null
        private set

    /** The single command that updates THIS install — see [UpdateService.updateCommand]. */
    val updateCommand: String by lazy { UpdateService.updateCommand(UpdateService.installKind(UpdateService.selfExe())) }

    /** Record what a check saw (newer or not — an up-to-date daemon still tells the phone the latest
     *  version, which is how the APP learns it is the one that's behind). True when the value changed. */
    fun recordLatest(version: String): Boolean {
        if (latest == version) return false
        latest = version
        return true
    }

    /** True when a release newer than what we run is known to exist. */
    fun behind(): Boolean = latest?.let { UpdateService.isNewer(it, current) } == true

    /** Stamp the version-visibility fields onto a [DaemonInfo] about to go out to a device. */
    fun stamp(info: DaemonInfo): DaemonInfo =
        info.copy(daemonVersion = current, latestVersion = latest, updateCommand = updateCommand)

    /** Test seam — the daily check is a singleton, so state leaks between tests otherwise. */
    internal fun resetForTest() { latest = null }
}
