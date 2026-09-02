package dev.ccpocket.daemon.git

import dev.ccpocket.daemon.util.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Which children of ONE directory `.gitignore` excludes — the only git dependency the file browser's
 * smart filter ([dev.ccpocket.protocol.PATH_FILTER_SMART]) has.
 *
 * One `git check-ignore --stdin -z` process per LISTING, never one per name: a directory with 800
 * children would otherwise mean 800 processes for a single tap. Everything here is bounded (a name cap,
 * a wait, a forced kill) and EVERY failure degrades to null — "we could not ask" — which the caller reads
 * as "show them all". A missing git, a directory outside any repository, or a wedged process can slow a
 * listing down by at most [TIMEOUT_MS]; none of them can fail it or hang it.
 *
 * Three `check-ignore` properties this leans on (probed against git 2.50; a drift here is visible rather
 * than silent — the browser goes back to showing `node_modules`, which is why there is no wire probe):
 *  - names arrive on STDIN, so a file literally called `--cached` is a name and never an option;
 *  - the index is consulted by default (no `--no-index`), so a TRACKED file that happens to match an
 *    ignore pattern is correctly NOT reported — it is in the repository, the browser must show it;
 *  - exit 0 = at least one path ignored, 1 = none ignored, anything else (128 = not a repository, or a
 *    git too old for these flags) = cannot answer.
 *
 * The batch's first entry is the literal `.`, a self-probe. When the LISTED directory is itself ignored
 * (a session opened inside `build/`), every child is ignored by inheritance and filtering would present
 * an empty folder for a directory the user deliberately navigated into. `.` coming back ignored is how
 * that is detected, and the answer is then null. It is `.` rather than the directory's absolute path
 * because ONE path outside the worktree aborts the WHOLE batch with exit 128.
 */
internal object GitIgnoreProbe {

    private val log = logger("GitIgnore")

    /**
     * The subset of [names] (direct children of [dir], plain names with no separator) that git excludes.
     * Null means "no answer" — not "nothing is ignored" — and the caller must then show everything.
     */
    suspend fun ignored(dir: Path, names: List<String>): Set<String>? {
        if (names.isEmpty() || names.size > MAX_NAMES) return null
        val exe = GitBin.resolve() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(listOf(exe.toString(), "check-ignore", "--stdin", "-z"))
                    .directory(dir.toFile())
                    .redirectErrorStream(false)
                pb.environment().apply {
                    put("GIT_TERMINAL_PROMPT", "0")  // a read must never stop for a prompt
                    put("GIT_OPTIONAL_LOCKS", "0")   // …nor fight the agent for index.lock
                    put("LC_ALL", "C")
                    put("LANG", "C")
                }
                val proc = pb.start()
                // Drain stdout WHILE writing stdin: the reply is one echoed path per ignored name, so a
                // node_modules-sized batch can fill the pipe buffer long before we finish writing — and
                // then both sides block on each other forever.
                val out = async { proc.inputStream.readBytes() }
                val err = async { proc.errorStream.readBytes() }
                // A child that already died (128 on a non-repo) makes this write fail; that is a normal
                // path, and the exit code below is what actually decides the answer.
                runCatching {
                    proc.outputStream.use { s ->
                        s.write(SELF.encodeToByteArray()); s.write(NUL.code)
                        names.forEach { n -> s.write(n.encodeToByteArray()); s.write(NUL.code) }
                    }
                }
                val finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) proc.destroyForcibly()
                // Safe to await unconditionally: the process is either exited or destroyed, so both pipes
                // are closed and the readers see EOF. `check-ignore` starts no grandchild that could hold
                // one open — the case the panel's separate drain timeout exists for.
                val stdout = out.await().decodeToString()
                val stderr = err.await().decodeToString()
                when {
                    !finished -> null.also { log.debug("check-ignore timed out in {}", dir) }
                    proc.exitValue() == 0 -> parse(stdout)
                    proc.exitValue() == 1 -> emptySet() // asked and answered: nothing here is ignored
                    else -> null.also { log.debug("check-ignore said: {}", stderr.lineSequence().firstOrNull()) }
                }
            } catch (e: Exception) {
                log.debug("check-ignore failed to run", e)
                null
            }
        }
    }

    /** `-z` output is the echoed pathnames, NUL-terminated. [SELF] present = the whole directory is out. */
    private fun parse(stdout: String): Set<String>? {
        val reported = stdout.split(NUL).filterTo(HashSet()) { it.isNotEmpty() }
        return if (SELF in reported) null else reported
    }

    private const val SELF = "."

    /** git's `-z` separator, on both sides of the pipe. */
    private val NUL = Char(0)

    /** Past this a listing is pathological anyway, and the browser's cap has long since truncated it —
     *  paying for a multi-megabyte round trip through a pipe to filter it is the wrong trade. */
    private const val MAX_NAMES = 10_000

    /** A directory listing is an interactive tap. Git answers in milliseconds on a warm repository; this
     *  is only here so a network filesystem or a wedged index cannot turn one into a spinner. */
    private const val TIMEOUT_MS = 2_000L
}
