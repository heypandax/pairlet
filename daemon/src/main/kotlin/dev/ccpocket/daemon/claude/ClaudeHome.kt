package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The daemon's OWN `CLAUDE_CONFIG_DIR` (issue #69 — credential isolation).
 *
 * Why: the daemon's claude and the user's terminal claude otherwise share one credential store, and
 * Claude Code's OAuth uses refresh-token ROTATION — two active claudes racing to refresh consume each
 * other's token and one gets logged out mid-work ("phone control logged out my terminal"). Verified on
 * macOS: a custom CLAUDE_CONFIG_DIR reads/writes its own credentials (`loggedIn:false` in a fresh dir
 * while the default store is signed in), so a separate dir = a separate login = no shared token to race.
 *
 * What stays SHARED (symlinked back to the real `~/.claude` / `~/.claude.json`): projects (session
 * history — resume/take-over across phone↔terminal keeps working, issue #70), todos, agents, commands,
 * skills, plugins, CLAUDE.md, settings.json, and `.claude.json` (user-scope MCP servers + project
 * trust; verified NOT to carry the login state). Only the credential store is private, which is the
 * whole point. Dangling file links are fine: claude creating the file writes THROUGH the link and
 * materializes the shared target.
 *
 * Failure mode: seeding can fail (Windows symlink privilege, exotic FS) — [prepare] then returns null
 * and the caller runs WITHOUT isolation, loudly. A wrongly-isolated claude that lost the user's
 * settings/history would be worse than the race this fixes.
 */
object ClaudeHome {
    private val log = logger("ClaudeHome")

    // directories whose CONTENT must be one copy across both claudes (history, memory, extensions)
    private val sharedDirs = listOf("projects", "todos", "agents", "commands", "skills", "plugins")

    // files claude reads/writes at the config root that must stay one copy
    private val sharedFiles = listOf("CLAUDE.md", "settings.json", "settings.local.json")

    /** Default location: beside identity.json / prefs.json (`~/.cc-pocket/claude-home`). */
    fun defaultHome(): File = File(Identity.defaultPath().parentFile, "claude-home")

    /**
     * Idempotently build the isolated config dir and return it, or null when seeding failed (caller
     * must then run without isolation). Never throws.
     */
    fun prepare(
        home: File = defaultHome(),
        realClaudeDir: File = File(System.getProperty("user.home"), ".claude"),
        realClaudeJson: File = File(System.getProperty("user.home"), ".claude.json"),
    ): Path? = runCatching {
        Files.createDirectories(home.toPath())
        // owner-only, like identity.json: this tree will hold a credential copy (security review L4).
        // Best-effort on non-POSIX; the parent (~/.cc-pocket) is tightened too.
        runCatching { Files.setPosixFilePermissions(home.toPath(), PosixFilePermissions.fromString("rwx------")) }
        home.parentFile?.let { runCatching { Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rwx------")) } }
        for (name in sharedDirs) {
            // materialize the real dir first: mkdir through a DANGLING dir-link fails (EEXIST on the
            // link), which would strand claude's own first-run setup
            val target = File(realClaudeDir, name)
            Files.createDirectories(target.toPath())
            link(File(home, name), target)
        }
        for (name in sharedFiles) link(File(home, name), File(realClaudeDir, name))
        link(File(home, ".claude.json"), realClaudeJson)
        // one-time migration where the credential store is a FILE (Linux/Windows): copy it so enabling
        // isolation doesn't force a re-login there. macOS keeps credentials in the Keychain — nothing to
        // copy; the user signs in once from the app (the daemon's own login flow).
        val srcCreds = File(realClaudeDir, ".credentials.json")
        val dst = File(home, ".credentials.json").toPath()
        // a pre-planted symlink here must never receive the token — the copy would write THROUGH it
        // (security review L2); drop the link and create the real file ourselves
        if (Files.isSymbolicLink(dst)) Files.delete(dst)
        if (srcCreds.isFile && !Files.exists(dst)) {
            createOwnerOnly(dst) // 0600 from birth, not as a fixup that can silently fail (review L3)
            Files.write(dst, Files.readAllBytes(srcCreds.toPath()))
            log.info("migrated file-based credentials into the isolated store")
        }
        home.toPath()
    }.onFailure {
        log.warn("claude-home seeding failed (${it.message}) — running WITHOUT credential isolation")
    }.getOrNull()

    /** Create [p] empty with owner-only permissions where the FS supports them; on non-POSIX (Windows)
     *  fall back to default ACL inheritance — LOUDLY, so a softer-than-0600 token copy is never silent. */
    private fun createOwnerOnly(p: java.nio.file.Path) {
        try {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(p)
            log.warn("credential copy created without POSIX 0600 (non-POSIX FS) — it inherits the home dir's ACL")
        }
    }

    /** Ensure [link] is a symlink to [target]; re-point a stale link, never touch real user data. */
    private fun link(link: File, target: File) {
        val lp = link.toPath()
        if (Files.isSymbolicLink(lp)) {
            if (runCatching { Files.readSymbolicLink(lp) }.getOrNull() == target.toPath()) return
            Files.delete(lp) // stale link (e.g. home dir moved) — re-point below
        } else if (link.exists()) {
            // a REAL file/dir sits where the share-link belongs (claude ran here before seeding?) —
            // leaving it diverges that entry, but silently replacing user data would be worse
            log.warn("claude-home entry '${link.name}' is a real file/dir, not a share link — left as is")
            return
        }
        Files.createSymbolicLink(lp, target.toPath())
    }
}
