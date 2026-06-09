package dev.ccpocket.daemon.disk

import java.util.concurrent.TimeUnit

/**
 * Working directories of currently-running `claude` CLI processes. A session idle between turns
 * doesn't touch its transcript, so mtime alone misses it — but its process is still alive with the
 * project as its cwd. This catches those (terminal sessions included), so a project shows as "live"
 * whenever a claude is actually running in it, not only while it streams.
 */
object LiveProcesses {
    fun claudeCwds(): Set<String> {
        val pids = runCatching {
            ProcessHandle.allProcesses()
                .filter { it.info().command().orElse("").contains("/claude") } // .../bin/claude or .../share/claude/versions/<v>
                .map { it.pid().toString() }
                .toList()
        }.getOrDefault(emptyList())
        if (pids.isEmpty()) return emptySet()
        return runCatching {
            val proc = ProcessBuilder("lsof", "-a", "-d", "cwd", "-p", pids.joinToString(","), "-Fn")
                .redirectErrorStream(true).start()
            val cwds = proc.inputStream.bufferedReader().readLines()
                .filter { it.startsWith("n") }.map { it.substring(1) }.toSet()
            proc.waitFor(3, TimeUnit.SECONDS)
            cwds
        }.getOrDefault(emptySet())
    }
}
