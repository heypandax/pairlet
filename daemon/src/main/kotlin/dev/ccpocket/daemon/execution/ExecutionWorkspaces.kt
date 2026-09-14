package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.PathScope
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Workspace alias resolution, performed ON THE TARGET (#367 G0). The source names an alias; it never
 * supplies a root. Rules:
 *  - an alias is a short token, so an absolute path, `~`, or anything with a separator is not an alias;
 *  - the alias's root is re-canonicalised at every resolution and must still equal what the owner approved
 *    AND still be the same directory object ([WorkspaceAlias.fileKey]) — a root later replaced by a symlink,
 *    removed, or recreated as a new directory at the same path stops resolving;
 *  - an optional relative sub-path may not be absolute or contain `..`, and after symlink resolution must
 *    stay under the root by PATH SEGMENT ([PathScope.underRoot]), so `/a/bc` is not inside `/a/b`.
 */
object ExecutionWorkspaces {

    private val ALIAS = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    fun validAlias(alias: String): Boolean = ALIAS.matches(alias)

    /**
     * The LEXICAL half of "canonical", checkable without touching the disk (so a stored row can be validated
     * at load even if the directory is currently absent): absolute, not the filesystem root, no empty / `.` /
     * `..` segments, no trailing separator, no NUL.
     */
    fun lexicallyCanonical(root: String): Boolean {
        if (root.isEmpty() || root.indexOf('\u0000') >= 0) return false
        val f = File(root)
        if (!f.isAbsolute || f.parentFile == null) return false
        if (root.endsWith(File.separator) || root.endsWith("/")) return false
        val segments = root.split('/', File.separatorChar).drop(1)
        return segments.isNotEmpty() && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Canonical, existing, absolute directory that is not the filesystem root; null otherwise. */
    fun canonicalRoot(raw: String): String? {
        if (raw.isBlank() || raw.indexOf('\u0000') >= 0 || !File(raw).isAbsolute) return null
        val c = PathScope.canonical(raw) ?: return null
        if (!File(c).isDirectory || !lexicallyCanonical(c)) return null
        return c
    }

    /** Filesystem identity of [path] without following a final symlink; null when unavailable. */
    fun fileKeyOf(path: String): String? = runCatching {
        Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()?.toString()
    }.getOrNull()

    sealed interface Resolution {
        data class Ok(val dir: String) : Resolution
        data class Deny(val code: String) : Resolution
    }

    fun resolve(grant: ExecutionGrant, alias: String, relativePath: String? = null): Resolution {
        if (!validAlias(alias)) return Resolution.Deny("workspace_alias_invalid")
        val ws = grant.workspaces.firstOrNull { it.alias == alias } ?: return Resolution.Deny("workspace_not_allowed")
        val now = PathScope.canonical(ws.canonicalRoot)
        if (now == null || now != ws.canonicalRoot || !File(now).isDirectory) return Resolution.Deny("workspace_root_changed")
        if (ws.fileKey != null && fileKeyOf(now) != ws.fileKey) return Resolution.Deny("workspace_root_changed")
        if (relativePath.isNullOrEmpty()) return Resolution.Ok(now)
        if (relativePath.indexOf('\u0000') >= 0 || File(relativePath).isAbsolute ||
            relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.startsWith("~") ||
            (relativePath.length >= 2 && relativePath[1] == ':')
        ) return Resolution.Deny("workspace_path_escape")
        if (relativePath.split('/', '\\').any { it == ".." }) return Resolution.Deny("workspace_path_escape")
        val c = PathScope.canonical(File(now, relativePath).path) ?: return Resolution.Deny("workspace_path_invalid")
        if (!PathScope.underRoot(c, now)) return Resolution.Deny("workspace_path_escape")
        return Resolution.Ok(c)
    }
}
