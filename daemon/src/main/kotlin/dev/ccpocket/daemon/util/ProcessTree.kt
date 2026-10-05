package dev.ccpocket.daemon.util

import java.util.concurrent.TimeUnit

/**
 * Stop a timed-out child AND everything it started, politely first.
 *
 * `Process.destroyForcibly()` alone is SIGKILL to one pid: git gets no chance to run its lockfile cleanup
 * (a stale `.git/index.lock` then fails every later git command, the agent's included), and a hook's,
 * filter's or shell's own children are reparented and live on — still holding our output pipes.
 *
 * So: snapshot the descendants BEFORE signalling (once the parent dies they are no longer its
 * descendants), SIGTERM the whole set, give it [graceMs] to exit, then SIGKILL whatever is left. A
 * ProcessHandle remembers its start time, so a pid recycled during the grace is never hit. On Windows
 * `destroy()` is already a hard terminate; the walk over descendants is what matters there.
 */
internal object ProcessTree {

    const val GRACE_MS = 2_000L

    fun terminate(proc: Process, graceMs: Long = GRACE_MS) {
        val root = proc.toHandle()
        val tree = listOf(root) + runCatching { root.descendants().toList() }.getOrDefault(emptyList())
        tree.forEach { runCatching { it.destroy() } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        for (h in tree) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            runCatching { h.onExit().get(left, TimeUnit.NANOSECONDS) }
        }
        tree.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
    }

    /** [terminate] off the caller's thread — for a cancelled caller that must return now, not after the grace. */
    fun terminateInBackground(proc: Process, graceMs: Long = GRACE_MS) {
        Thread({ terminate(proc, graceMs) }, "process-tree-stop").apply { isDaemon = true }.start()
    }
}
