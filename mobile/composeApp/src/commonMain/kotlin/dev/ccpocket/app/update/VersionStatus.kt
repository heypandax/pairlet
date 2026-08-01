package dev.ccpocket.app.update

import dev.ccpocket.protocol.update.ReleaseVersions

/**
 * Who is out of date, from what the daemon told us in `DaemonInfo` (issue #200). Pure so the "is anyone
 * behind?" rule is testable and identical everywhere it's read (the settings section and the nudge dot).
 *
 * The phone never talks to GitHub itself — it borrows the daemon's daily check via [latestVersion].
 * Two independent signals feed the verdict:
 *  - [latestVersion] vs each side: the published release, when the daemon has managed a check.
 *  - this app's own version vs [daemonVersion]: releases ship in lockstep, so an app NEWER than the
 *    daemon it's talking to is itself proof the daemon is behind — this works offline and on a daemon
 *    whose update check has never succeeded.
 *
 * Nulls mean "unknown", never "up to date": a daemon that predates version reporting omits the fields.
 */
data class VersionStatus(
    val appVersion: String,
    val daemonVersion: String? = null,
    val latestVersion: String? = null,
    /** The one line that updates the DAEMON's machine — the daemon knows its own install layout. */
    val updateCommand: String? = null,
) {
    /** The newest version we have evidence exists (see the class doc's second signal). */
    val newestKnown: String
        get() = latestVersion?.takeIf { ReleaseVersions.isNewer(it, appVersion) } ?: appVersion

    val appBehind: Boolean
        get() = latestVersion != null && ReleaseVersions.isNewer(latestVersion, appVersion)

    /** A dev daemon is never "behind" — its 0.0.0-dev sorts below everything and it updates by rebuild. */
    val daemonBehind: Boolean
        get() = daemonVersion != null && !isDevBuild(daemonVersion) && ReleaseVersions.isNewer(newestKnown, daemonVersion)

    val anyBehind: Boolean get() = appBehind || daemonBehind

    companion object {
        fun isDevBuild(version: String): Boolean = version.startsWith("0.0.0") || version.contains("dev")
    }
}
