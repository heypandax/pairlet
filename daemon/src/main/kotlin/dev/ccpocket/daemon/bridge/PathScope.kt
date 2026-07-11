package dev.ccpocket.daemon.bridge

import java.io.File

/**
 * The one canonical-containment primitive the folder-share scope is built on (issue #115) — the same
 * `../`/symlink-escape-safe check #91's [BridgeGuard] pins for bridge workdirs, factored out so the guest
 * ingress guard ([GuestGuard]) and the tool-call path guard ([dev.ccpocket.daemon.agent.PermissionBridge])
 * enforce EXACTLY the same boundary. One definition = the daemon's scope check can't drift between "which
 * folder may a guest open a session in" and "which file may that session's agent read".
 *
 * The rule (mirrors #90/#67's proven pattern): resolve to a REAL path — `File.canonicalFile` collapses
 * `..` and follows symlinks — then require it to be the root itself or a descendant with a path-SEPARATOR
 * boundary, so `/a/bees` is NOT under `/a/be` (a raw `startsWith` would wrongly admit it).
 */
object PathScope {

    /** Canonical absolute path of [path], or null if it can't be resolved (never throws). Symlinks are
     *  followed and `..` collapsed, so the result is the real on-disk location — the only safe basis for
     *  a containment test against an attacker-influenced path. */
    fun canonical(path: String): String? = runCatching { File(path).canonicalFile.path }.getOrNull()

    /** True if [child] (already canonical) is [root] (already canonical) itself or a descendant of it,
     *  path-segment aware. */
    fun underRoot(child: String, root: String): Boolean =
        child == root || child.startsWith(root + File.separator)

    /** True if [path] canonicalizes to somewhere inside ANY of [roots] (each assumed already canonical).
     *  Empty [roots] → false (fail closed: an unscoped guard admits nothing). A path that can't be
     *  canonicalized (gone, unreadable parent) → false. */
    fun contains(roots: List<String>, path: String): Boolean {
        if (roots.isEmpty()) return false
        val c = canonical(path) ?: return false
        return roots.any { underRoot(c, it) }
    }
}
