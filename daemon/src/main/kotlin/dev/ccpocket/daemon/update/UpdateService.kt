package dev.ccpocket.daemon.update

import dev.ccpocket.daemon.DEFAULT_RELAY
import dev.ccpocket.daemon.SingleInstance
import dev.ccpocket.daemon.service.ServiceInstaller
import dev.ccpocket.daemon.util.DaemonVersion
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseVersions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The daemon's alias for the shared release type — kept so existing call sites read unchanged. */
private typealias Release = ReleaseClient.Release

/**
 * Self-update for curl/irm-managed installs — the Claude Code distribution model: each version in
 * `<root>/cc-pocket/versions/<ver>/`, a stable launcher path the service points at, atomic switch,
 * old versions pruned. Installs owned by a package manager (Homebrew cask / Scoop) are deliberately
 * NOT touched — two updaters fighting over one tree ends badly; we print the right upgrade command
 * instead. All the pure decision logic (version compare, asset naming, root detection, sums parsing)
 * is separated out for unit tests; the effectful parts are thin wrappers over tar/zip + Files.move.
 */
object UpdateService {
    private const val REPO = "heypandax/cc-pocket"
    private val log = logger("Update")

    /** A curl/irm-managed install: the versions dir, the stable launcher path the service runs, and
     *  whether the registered service actually points at that stable path (safe to hot-swap). */
    data class ManagedInstall(val versionsDir: Path, val launcher: Path, val serviceAnchored: Boolean)

    fun currentVersion(): String = DaemonVersion.CURRENT

    // ── pure decision logic (unit-tested) ────────────────────────────────────────────────────────

    /** Dotted-numeric compare, `v` prefix and non-digit suffixes ignored; true = strictly newer. */
    fun isNewer(candidate: String, current: String): Boolean = ReleaseVersions.isNewer(candidate, current)

    /** This platform's release asset name, or null when we don't publish one for it. */
    fun assetNameFor(
        version: String,
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String? {
        val v = version.removePrefix("v")
        val a = when (arch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> return null
        }
        val o = os.lowercase()
        return when {
            o.contains("mac") -> "cc-pocket-daemon-$v-macos-$a.tar.gz"
            o.contains("win") -> if (a == "x86_64") "cc-pocket-daemon-$v-windows-x86_64.zip" else null
            else -> "cc-pocket-daemon-$v-linux-$a.tar.gz"
        }
    }

    /** Detect the managed install [exe] runs from: a path under `…/cc-pocket/versions/<ver>/…`.
     *  Null for brew/scoop/dev builds. [home] is injectable for tests. */
    fun managedInstallOf(exe: Path?, home: Path = Path.of(System.getProperty("user.home"))): ManagedInstall? {
        var p = exe?.toAbsolutePath()?.normalize() ?: return null
        var versionDir: Path? = null
        while (true) {
            val parent = p.parent ?: return null
            if (parent.fileName?.toString() == "versions" && parent.parent?.fileName?.toString() == "cc-pocket") {
                versionDir = p; break
            }
            p = parent
        }
        val versionsDir = versionDir!!.parent
        val launcher = if (isWindows()) {
            // Stable .cmd shim on a fixed PATH dir (…/cc-pocket/bin), rewritten to the current version by
            // apply() — the Windows analogue of the mac/linux ~/.local/bin symlink (issue #59). The logon
            // task keeps pointing at the versioned exe (re-registered per update via restartService); this
            // shim is what an interactive `cc-pocket-daemon …` resolves to.
            versionsDir.parent!!.resolve("bin").resolve("cc-pocket-daemon.cmd")
        } else {
            home.resolve(".local").resolve("bin").resolve("cc-pocket-daemon")
        }
        return ManagedInstall(versionsDir, launcher, serviceAnchored = serviceAnchoredTo(launcher, home))
    }

    /** SHA256SUMS format: `<hex>  <filename>` per line. Returns filename → sha256. */
    fun parseSums(text: String): Map<String, String> = ReleaseVersions.parseSums(text)

    // ── effectful ────────────────────────────────────────────────────────────────────────────────

    fun selfExe(): Path? = ProcessHandle.current().info().command().orElse(null)?.let { runCatching { Path.of(it) }.getOrNull() }

    /** Which package manager (if any) owns this binary — for the "not ours to update" hint. */
    fun ownerHint(exe: Path?): String = when (packageManagerOf(exe)) {
        InstallKind.HOMEBREW -> "this install is managed by Homebrew — upgrade with:  ${updateCommand(InstallKind.HOMEBREW)}"
        InstallKind.SCOOP -> "this install is managed by Scoop — upgrade with:  ${updateCommand(InstallKind.SCOOP)}"
        else -> genericHint()
    }

    private fun genericHint() =
        "this daemon wasn't installed by the cc-pocket installer — re-install with the one-liner " +
            "(https://github.com/$REPO#quick-start) to enable self-update"

    // ── how this install updates itself (issue #200) ─────────────────────────────────────────────

    /** Who owns this binary, hence who is allowed to update it. Only [MANAGED] ever self-overwrites. */
    enum class InstallKind { MANAGED, HOMEBREW, SCOOP, UNKNOWN }

    /** Package-manager ownership from the path shape alone (no filesystem walk). */
    private fun packageManagerOf(exe: Path?): InstallKind {
        val s = exe?.toString()?.lowercase() ?: return InstallKind.UNKNOWN
        return when {
            "caskroom" in s || "/homebrew/" in s -> InstallKind.HOMEBREW
            "scoop" in s -> InstallKind.SCOOP
            else -> InstallKind.UNKNOWN
        }
    }

    /** Classify the install [exe] runs from. The installer tree wins: a managed layout is authoritative
     *  regardless of where it happens to sit, and only it may be hot-swapped by [apply]. */
    fun installKind(exe: Path?, home: Path = Path.of(System.getProperty("user.home"))): InstallKind =
        if (managedInstallOf(exe, home) != null) InstallKind.MANAGED else packageManagerOf(exe)

    /**
     * The ONE line that updates a [kind] install. Shown by `version`/`status` and — the point of issue
     * #200 — sent to the phone in `DaemonInfo`, so "your daemon is behind" always arrives with the exact
     * command for THIS machine instead of a generic "go update it". An unrecognized install has no
     * updater to invoke, so it gets the installer one-liner (re-running it converts the tree to managed).
     */
    fun updateCommand(kind: InstallKind, os: String = System.getProperty("os.name")): String = when (kind) {
        InstallKind.MANAGED -> "cc-pocket-daemon update"
        InstallKind.HOMEBREW -> "brew upgrade --cask heypandax/tap/cc-pocket"
        InstallKind.SCOOP -> "scoop update cc-pocket-daemon"
        InstallKind.UNKNOWN ->
            if (os.lowercase().contains("win")) "irm https://raw.githubusercontent.com/$REPO/main/scripts/install.ps1 | iex"
            else "curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh | bash"
    }

    fun latestRelease(): Release? = ReleaseClient.latest(REPO)

    /**
     * Download + verify + extract [release] into `versions/<ver>` and switch the stable launcher to it.
     * mac/linux: atomic symlink flip (the service ExecStart points at the symlink, so a restart lands on
     * the new version). Windows: rewrites the on-PATH .cmd shim to the new exe (the logon task is re-pointed
     * onto the new exe separately by [restartService]). Returns the new version's real exe path (which
     * [restartService] registers). Throws with a human message on any failure.
     */
    fun apply(release: Release, install: ManagedInstall): Path {
        val asset = assetNameFor(release.version) ?: error("no prebuilt artifact for this platform")
        val url = release.assetUrls[asset] ?: error("release v${release.version} has no asset $asset")
        val tmp = Files.createTempDirectory("cc-pocket-update")
        try {
            val file = tmp.resolve(asset)
            log.info("downloading $asset")
            ReleaseClient.download(url, file)
            if (ReleaseClient.verifyAgainstSums(release, asset, file, onSkip = { log.warn(it) })) log.info("checksum OK ($asset)")

            val extracted = tmp.resolve("x").also { Files.createDirectories(it) }
            extract(file, extracted)
            // one rule everywhere: versions/<ver>/ holds the archive's top-level entry UNCHANGED —
            // macOS ships a signed cc-pocket-daemon.app bundle, Windows/Linux a cc-pocket-daemon/ dir
            val inner = sequenceOf("cc-pocket-daemon.app", "cc-pocket-daemon")
                .map(extracted::resolve).firstOrNull { it.isDirectory() }
                ?: error("unexpected archive layout (no cc-pocket-daemon[.app] top-level entry)")

            val target = install.versionsDir.resolve(release.version)
            if (target.exists()) target.toFile().deleteRecursively()
            Files.createDirectories(target)
            Files.move(inner, target.resolve(inner.fileName))

            val newLauncher = launcherUnder(target)
                ?: error("launcher missing after extraction under $target")
            if (isWindows()) {
                // No symlinks on Windows: rewrite the stable .cmd shim (install.launcher, on PATH) to forward
                // to the new version's exe — the analogue of the symlink flip below, so an interactive
                // `cc-pocket-daemon …` lands on the just-installed version (issue #59). The logon task is
                // re-pointed at newLauncher separately by restartService().
                Files.createDirectories(install.launcher.parent)
                install.launcher.writeText("@echo off\r\n\"$newLauncher\" %*\r\n")
            } else {
                check(newLauncher.isExecutable()) { "launcher not executable: $newLauncher" }
                // atomic flip: the service's ExecStart is the stable symlink, so the next (re)start runs this
                val tmpLink = install.launcher.resolveSibling(".cc-pocket-daemon.new")
                Files.deleteIfExists(tmpLink)
                Files.createDirectories(install.launcher.parent)
                Files.createSymbolicLink(tmpLink, newLauncher)
                Files.move(tmpLink, install.launcher, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            prune(install.versionsDir, keep = setOf(release.version, currentVersion()))
            return newLauncher
        } finally {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    /** Restart the background service so the new binary takes over (CLI path; the in-daemon auto path
     *  just exits and lets KeepAlive/Restart=always relaunch through the flipped symlink). */
    fun restartService(newLauncher: Path, pairPort: Int = 8799) {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") -> {
                val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
                ProcessBuilder("launchctl", "kickstart", "-k", "gui/$uid/dev.ccpocket.daemon").inheritIO().start().waitFor()
            }
            os.contains("win") -> restartWindowsService(newLauncher, pairPort)
            else -> ProcessBuilder("systemctl", "--user", "restart", "cc-pocket-daemon").inheritIO().start().waitFor()
        }
    }

    /**
     * Windows' Scheduled Task runs a WScript which intentionally detaches the daemon to avoid a console
     * window. Consequently `schtasks /End` only ends an already-finished wrapper and cannot stop the old
     * daemon. The handoff has to be ordered explicitly:
     *
     *  1. register the task on the new executable WITHOUT starting it;
     *  2. stop the process that owns the daemon's singleton pair port;
     *  3. start the newly registered task exactly once and verify that it claims the port.
     *
     * The effect lambdas make this ordering regression-testable on non-Windows CI.
     */
    internal fun restartWindowsService(
        newLauncher: Path,
        pairPort: Int,
        register: (Path, Int) -> Unit = { launcher, port ->
            val result = ServiceInstaller.windows(
                launcher.toString(),
                listOf("run", "--relay", DEFAULT_RELAY, "--pair-port", port.toString()),
                apply = true,
                startNow = false,
            )
            check(!result.startsWith("could not register")) { result }
        },
        stopOld: (Int) -> Boolean = SingleInstance::stopRunning,
        startNew: () -> Unit = ::startWindowsTask,
        awaitNew: (Int) -> Boolean = SingleInstance::waitUntilRunning,
    ) {
        register(newLauncher, pairPort)
        check(stopOld(pairPort)) {
            "could not stop the old daemon on 127.0.0.1:$pairPort — the new task is registered; " +
                "close the old daemon and run: schtasks /Run /TN ${ServiceInstaller.WINDOWS_TASK}"
        }
        startNew()
        check(awaitNew(pairPort)) {
            "the updated daemon did not start on 127.0.0.1:$pairPort — inspect " +
                "%USERPROFILE%\\.cc-pocket\\logs\\daemon.log, then run: " +
                "schtasks /Run /TN ${ServiceInstaller.WINDOWS_TASK}"
        }
    }

    private fun startWindowsTask() {
        val proc = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-Command",
            "Start-ScheduledTask -TaskName '${ServiceInstaller.WINDOWS_TASK}'",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        check(code == 0) {
            "could not start Scheduled Task '${ServiceInstaller.WINDOWS_TASK}' (exit $code)" +
                out.takeIf { it.isNotEmpty() }?.let { ":\n$it" }.orEmpty()
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** The real launcher inside a version dir, per platform layout (see [apply]'s one-rule comment). */
    internal fun launcherUnder(versionDir: Path): Path? = sequenceOf(
        versionDir.resolve("cc-pocket-daemon.app").resolve("Contents").resolve("MacOS").resolve("cc-pocket-daemon"),
        versionDir.resolve("cc-pocket-daemon").resolve("cc-pocket-daemon.exe"),
        versionDir.resolve("cc-pocket-daemon").resolve("bin").resolve("cc-pocket-daemon"),
    ).firstOrNull { it.exists() }

    /** True when the registered service's exec is the stable launcher path (symlink) — the
     *  precondition for a hot swap; a legacy unit pinned to an old real path would relaunch stale. */
    private fun serviceAnchoredTo(launcher: Path, home: Path): Boolean = runCatching {
        val os = System.getProperty("os.name").lowercase()
        val text = when {
            os.contains("mac") -> home.resolve("Library/LaunchAgents/dev.ccpocket.daemon.plist").takeIf { it.exists() }?.readText()
            os.contains("win") -> home.resolve(".cc-pocket/cc-pocket-daemon-service.vbs").takeIf { it.exists() }?.readText()
            else -> home.resolve(".config/systemd/user/cc-pocket-daemon.service").takeIf { it.exists() }?.readText()
        } ?: return false
        // Windows is never "anchored" (no symlink): update always re-registers the task instead
        !os.contains("win") && text.contains(launcher.toString())
    }.getOrDefault(false)

    private fun extract(archive: Path, dest: Path) {
        if (archive.name.endsWith(".zip")) {
            ZipInputStream(Files.newInputStream(archive)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = dest.resolve(e.name).normalize()
                    check(out.startsWith(dest)) { "zip entry escapes destination: ${e.name}" } // zip-slip guard
                    if (e.isDirectory) Files.createDirectories(out) else {
                        Files.createDirectories(out.parent)
                        Files.newOutputStream(out).use { z.copyTo(it) }
                    }
                }
            }
        } else {
            // system tar preserves the exec bits jpackage set — Java's TarInputStream story is worse
            val code = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString())
                .redirectErrorStream(true).start().also { it.inputStream.bufferedReader().readText() }.waitFor()
            check(code == 0) { "tar extraction failed (exit $code)" }
        }
    }

    /** Drop version dirs beyond the newest two (never the ones in [keep]) — disk hygiene à la Claude Code. */
    private fun prune(versionsDir: Path, keep: Set<String>) = runCatching {
        val dirs = Files.newDirectoryStream(versionsDir).use { it.toList() }.filter { it.isDirectory() }
        dirs.sortedWith(compareByDescending { d -> d.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 } .let { v -> v.getOrElse(0){0} * 1_000_000 + v.getOrElse(1){0} * 1_000 + v.getOrElse(2){0} } })
            .drop(2)
            .filterNot { it.fileName.toString() in keep }
            .forEach { runCatching { it.toFile().deleteRecursively() } }
    }
}
