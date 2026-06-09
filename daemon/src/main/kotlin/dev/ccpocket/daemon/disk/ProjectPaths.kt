package dev.ccpocket.daemon.disk

import java.nio.file.Path

/** Maps a working directory to its `~/.claude/projects/<dir-key>` transcript folder. */
object ProjectPaths {
    fun projectsRoot(): Path = Path.of(System.getProperty("user.home"), ".claude", "projects")

    /** claude 2.1.x encodes the cwd by replacing '/' with '-' (dots/underscores preserved). */
    fun dirKey(absPath: String): String = absPath.replace('/', '-')

    fun dirFor(workdir: String): Path = projectsRoot().resolve(dirKey(workdir))
}
