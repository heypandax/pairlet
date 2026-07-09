package dev.ccpocket.app.desktop

import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseVersions
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Self-update for the Compose Desktop app — the counterpart to the daemon's UpdateService, but for the
 * separately-distributed GUI binary (a standalone .dmg/.msi, or a package manager's copy). It reuses the
 * shared [ReleaseClient] / [ReleaseVersions] (the exact version-check + SHA256-verify the daemon uses) and
 * only adds what's app-specific: which artifact this platform downloads and how a running GUI replaces
 * itself.
 *
 * The install source decides the action — mirroring the daemon's "package-manager copies don't self-update"
 * rule so two updaters never fight over one tree:
 *  - STANDALONE (dmg/msi / portable): full self-update — download + verify + swap + relaunch.
 *  - BREW / SCOOP: never self-overwrite; the UI shows the right `brew`/`scoop` upgrade command.
 *  - UNKNOWN (a dev/gradle run, or an unrecognized layout): open the releases page.
 *
 * The pure pieces (source classification, asset naming) are separated for unit tests; the swap is a thin
 * detached-helper shell-out because a process can't overwrite its own running image and then relaunch it.
 */
object DesktopUpdater {
    const val RELEASES_URL = "https://github.com/heypandax/cc-pocket/releases/latest"
    private const val REPO = ReleaseClient.DEFAULT_REPO

    private val osName = System.getProperty("os.name").lowercase()
    private val isMac = osName.contains("mac")
    private val isWin = osName.contains("win")

    // ── pure decision logic (unit-tested) ─────────────────────────────────────────────────────────

    /** How this app was installed — decides whether it may overwrite itself or must defer to a package
     *  manager. [appPath] is the packaged app's own location (null = not a packaged app-image: a dev run). */
    fun classifyInstall(appPath: String?): DkInstallSource {
        val p = appPath?.lowercase() ?: return DkInstallSource.UNKNOWN
        return when {
            "/caskroom/" in p || "/homebrew/" in p || "/cellar/" in p -> DkInstallSource.BREW
            "\\scoop\\" in p || "/scoop/" in p -> DkInstallSource.SCOOP
            else -> DkInstallSource.STANDALONE
        }
    }

    /** This platform's desktop release asset name, or null when none is published for it (Linux, or an arch
     *  we don't build) — the caller falls back to opening the releases page. The version-less names are the
     *  ones published on EVERY release (the release uploads a per-arch `cc-pocket-desktop-macos-<arch>.dmg`
     *  and `cc-pocket-desktop-windows-x86_64.msi`; the versioned dmg exists too but Windows has no versioned
     *  MSI, so version-less is the one shape both platforms share). The actual bytes come from the release's
     *  own asset URL, so this resolves to the checked release regardless of the missing version in the name. */
    fun desktopAssetFor(
        os: String = osName,
        arch: String = System.getProperty("os.arch"),
    ): String? {
        val a = when (arch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> return null
        }
        val o = os.lowercase()
        return when {
            o.contains("mac") -> "cc-pocket-desktop-macos-$a.dmg"
            o.contains("win") -> if (a == "x86_64") "cc-pocket-desktop-windows-x86_64.msi" else null
            else -> null // no Linux desktop artifact is published
        }
    }

    /** The package-manager upgrade command for [source] (to copy), or null when it self-updates / has none. */
    fun upgradeCommandFor(source: DkInstallSource): String? = when (source) {
        DkInstallSource.BREW -> "brew upgrade --cask heypandax/tap/cc-pocket"
        DkInstallSource.SCOOP -> "scoop update cc-pocket"
        else -> null
    }

    fun isNewer(candidate: String, current: String): Boolean = ReleaseVersions.isNewer(candidate, current)

    // ── effectful ─────────────────────────────────────────────────────────────────────────────────

    /** The packaged app root — the macOS `.app` bundle or the Windows install dir — verified to be OUR
     *  jpackage bundle (its `app/` holds the composeApp jar). Null for a dev/gradle run, or a JVM whose
     *  runtime isn't inside a cc-pocket bundle, so we never target a stranger's `.app`. */
    fun packagedAppRoot(javaHome: String? = System.getProperty("java.home")): File? {
        val home = javaHome?.let(::File) ?: return null
        val root = when {
            // …/CC Pocket.app/Contents/runtime/Contents/Home → walk up to the .app
            isMac -> generateSequence(home) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
                ?.takeIf { hasComposeJar(File(it, "Contents/app")) }
            // …\CC Pocket\runtime → the parent is the install dir
            isWin -> home.parentFile?.takeIf { hasComposeJar(File(it, "app")) }
            else -> null
        }
        return root
    }

    private fun hasComposeJar(appDir: File): Boolean =
        appDir.isDirectory && appDir.listFiles()?.any { it.name.startsWith("composeApp") && it.name.endsWith(".jar") } == true

    /** How this running app was installed (STANDALONE / BREW / SCOOP / UNKNOWN). */
    fun currentSource(): DkInstallSource = classifyInstall(packagedAppRoot()?.path)

    /** The latest published release, or null when GitHub is unreachable. */
    fun latest(): ReleaseClient.Release? = ReleaseClient.latest(REPO)

    /**
     * Full standalone self-update: download this platform's dmg/msi, verify its SHA256 against the release's
     * SHA256SUMS, then swap the running app for the new one and relaunch. Does not return on success (the
     * process exits so the swap helper / installer can replace the files). Throws with a human-readable
     * message when there's no artifact for this platform, the download/verify fails, or the app can't be
     * located — the caller surfaces that and stays on the current version.
     */
    fun applyStandalone(release: ReleaseClient.Release) {
        val asset = desktopAssetFor() ?: error("no desktop build is published for this platform")
        val url = release.assetUrls[asset] ?: error("release v${release.version} has no asset $asset")
        val tmp = Files.createTempDirectory("cc-pocket-desktop-update")
        val file = tmp.resolve(asset)
        ReleaseClient.download(url, file)
        // present-mismatch throws (corrupt/tampered); a missing SHA256SUMS entry passes, as on the daemon
        ReleaseClient.verifyAgainstSums(release, asset, file.toAbsolutePath())
        when {
            isMac -> swapMacApp(file.toFile())
            isWin -> launchWindowsInstaller(file.toFile())
            else -> error("self-update isn't supported on this platform")
        }
    }

    // macOS: mount the dmg, copy the new .app OUT of the read-only image, detach, then hand a detached
    // helper the swap — a process can't replace its own running bundle, so the helper waits for this PID to
    // exit, moves the new bundle into place, and relaunches. Then we exit so it can proceed.
    private fun swapMacApp(dmg: File) {
        val appRoot = packagedAppRoot()
            ?: error("couldn't locate the installed app to replace — run the packaged app to self-update")
        val mount = Files.createTempDirectory("cc-pocket-dmg").toFile()
        val staged = Files.createTempDirectory("cc-pocket-staged").toFile()
        run("/usr/bin/hdiutil", "attach", "-nobrowse", "-noverify", "-mountpoint", mount.path, dmg.path)
        val stagedApp: File
        try {
            val newApp = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") }
                ?: error("the downloaded disk image has no .app")
            stagedApp = File(staged, newApp.name)
            run("/usr/bin/ditto", newApp.path, stagedApp.path)
        } finally {
            runCatching { run("/usr/bin/hdiutil", "detach", "-quiet", mount.path) }
        }
        // Positional args (not string interpolation) so bundle paths with spaces ("CC Pocket.app") stay intact.
        val script = File(staged, "swap.sh").apply {
            writeText(
                """
                #!/bin/sh
                pid="${'$'}1"; app="${'$'}2"; new="${'$'}3"
                while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.4; done
                /usr/bin/ditto "${'$'}new" "${'$'}app.new" && /bin/rm -rf "${'$'}app" && /bin/mv "${'$'}app.new" "${'$'}app"
                /usr/bin/open "${'$'}app"
                """.trimIndent(),
            )
        }
        ProcessBuilder(
            "/bin/sh", "-c", "nohup /bin/sh \"$0\" \"$1\" \"$2\" \"$3\" >/dev/null 2>&1 &",
            script.path, ProcessHandle.current().pid().toString(), appRoot.path, stagedApp.path,
        ).start()
        exitProcess(0)
    }

    // Windows: hand the verified MSI to Windows Installer (it replaces the running install on the next launch)
    // and exit so no files are locked. /qb = basic progress UI, no wizard clicking.
    private fun launchWindowsInstaller(msi: File) {
        ProcessBuilder("msiexec", "/i", msi.absolutePath, "/qb").start()
        exitProcess(0)
    }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        check(code == 0) { "command failed (exit $code): ${cmd.joinToString(" ")}\n$out" }
    }
}
