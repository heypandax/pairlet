package dev.ccpocket.daemon

import dev.ccpocket.daemon.update.UpdateService
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** The short CLI is an alias of the legacy stable launcher, never another service or install. */
internal object CliAliases {
    const val WINDOWS_SHIM = "@echo off\r\n@\"%~dp0cc-pocket-daemon.cmd\" %*\r\n"
    // A nested relative symlink breaks macOS jpackage's cfg lookup. Exec the original stable
    // launcher so its native executable name/path and the service's update anchor stay intact.
    val UNIX_SHIM = """
        |#!/bin/sh
        |exec "${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)/cc-pocket-daemon" "${'$'}@"
        |
    """.trimMargin()

    /** Old updaters only switch cc-pocket-daemon. On the new daemon's first start, add the alias
     *  for those users too. Package-manager trees are owned exclusively by their manifests. */
    fun ensureForManagedInstall(
        exe: Path? = UpdateService.selfExe(),
        home: Path = Path.of(System.getProperty("user.home")),
    ) {
        val install = UpdateService.managedInstallOf(exe, home) ?: return
        ensureAlias(install.launcher)
    }

    /** CREATE_NEW makes repeated starts safe and avoids clobbering another tool called pairlet.
     *  A sibling-forwarding script follows upgrades even when the installation is moved. */
    fun ensureAlias(launcher: Path, windows: Boolean = System.getProperty("os.name").lowercase().contains("win")) {
        if (!Files.exists(launcher)) return
        val alias = launcher.resolveSibling(if (windows) "pairlet.cmd" else "pairlet")
        val content = if (windows) WINDOWS_SHIM else UNIX_SHIM
        try {
            Files.writeString(alias, content, CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            val ours = !Files.isSymbolicLink(alias) && Files.isRegularFile(alias, NOFOLLOW_LINKS) &&
                Files.readString(alias) == content
            check(ours) { "$alias already exists; keeping it. Use cc-pocket-daemon instead." }
        }
        if (!windows) check(alias.toFile().setExecutable(true, false)) { "could not make $alias executable; use cc-pocket-daemon instead." }
    }
}
