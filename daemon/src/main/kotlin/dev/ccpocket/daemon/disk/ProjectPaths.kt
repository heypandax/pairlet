package dev.ccpocket.daemon.disk

import java.nio.file.Path

/** Maps a working directory to its `~/.claude/projects/<dir-key>` transcript folder. */
object ProjectPaths {
    fun projectsRoot(): Path = Path.of(System.getProperty("user.home"), ".claude", "projects")

    /**
     * claude 2.1.x encodes the cwd into its `~/.claude/projects/<key>` folder name by replacing
     * every character that is NOT `[A-Za-z0-9-]` with '-'. That means '/', '.', '_', spaces, etc.
     * all become '-', while existing hyphens are kept and runs are NOT collapsed (so `…/-x`/`._`
     * → `--`). Verified against on-disk dirs, e.g. `…/j_c3x2gb/work` → `…-j-c3x2gb-work` and
     * `…/skdbg.IYBb` → `…-skdbg-IYBb`. An earlier version replaced only '/', which broke session
     * fetch/open for any cwd containing '_' or '.' (the computed dir didn't exist on disk).
     */
    fun dirKey(absPath: String): String = absPath.replace(Regex("[^A-Za-z0-9-]"), "-")

    fun dirFor(workdir: String): Path = projectsRoot().resolve(dirKey(workdir))
}
