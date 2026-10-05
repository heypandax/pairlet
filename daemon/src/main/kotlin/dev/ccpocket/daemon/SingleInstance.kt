package dev.ccpocket.daemon

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * The daemon owns singleton resources: the pair-loopback port (127.0.0.1:<pairPort>) AND one relay
 * identity. A second `run` would (a) crash on BindException binding the port, and (b) attach to the
 * relay under the SAME account as the first — the relay can't tell them apart, so the phone's routing
 * flaps between two backends ("can't fetch list", turns half-work). This happens whenever the Homebrew
 * cask's KeepAlive LaunchAgent is up and you also start a dev build (or two agents got registered).
 *
 * So before we bind/attach, two gates — either take over (stop the other) or exit cleanly, never run a
 * duplicate:
 *  1. an exclusive lock on `daemon.lock` in the state directory, taken first thing and held for the
 *     process's whole life ([InstanceLock]). This is what closes the window the port probe alone left open:
 *     the probe ran at the top of `run` but the port is only bound once startup finished, so two instances
 *     started together could both see it free. The OS drops the lock when the process dies — kill -9 and
 *     crashes included — so a stale file never needs cleaning up.
 *  2. the loopback connect probe on the pair port, kept as the second gate: it still catches a daemon from
 *     before the lock existed (or anything else squatting on the port).
 */
object SingleInstance {
    private val log = logger("SingleInstance")

    /** The lock this process holds as THE daemon; referenced here so it lives as long as the process. */
    @Volatile private var held: InstanceLock? = null

    /** `daemon.lock` beside identity.json — the state directory whose relay identity the lock protects. */
    fun defaultLockFile(): File = File(Identity.defaultPath().absoluteFile.parentFile, "daemon.lock")

    /**
     * Returns normally only when this process may run as THE daemon: true = it stopped a running one
     * (`--takeover`), false = none was running. Otherwise it ends the process through [exit] — injectable
     * so a test can observe a rejected start without the JVM going down with it. A refused start releases
     * the lock again before [exit], so it leaves nothing held behind.
     */
    fun ensureSolo(
        pairPort: Int,
        takeover: Boolean,
        exit: (Int) -> Nothing = { exitProcess(it) },
        lockFile: File = defaultLockFile(),
        waitMs: Long = 100,
        echo: (String) -> Unit,
    ): Boolean {
        var tookOver = false
        val first = try {
            InstanceLock.tryAcquire(lockFile)
        } catch (e: IOException) {
            // An unwritable state directory must not crash-loop the service: fall back to the port gate alone.
            log.warn("single-instance lock unavailable (${e.message}) — relying on the pair-port check only")
            echo("warning: could not open $lockFile (${e.message}) — single-instance check falls back to the pair port")
            return portGate(pairPort, takeover, null, exit, echo)
        }
        var lock = first
        // Held, but nobody serves the pair port: the holder is either still starting up or already on its way
        // out (shutdown hooks close the port before the JVM exits). Give a departing one a moment to let go.
        if (lock == null && !portInUse(pairPort)) lock = awaitLock(lockFile, GRACE_ATTEMPTS, waitMs)
        if (lock == null) {
            val owner = InstanceLock.owner(lockFile)?.let { " (pid $it)" }.orEmpty()
            if (!takeover) refuse("holds $lockFile$owner", exit, echo)
            echo("another cc-pocket daemon$owner holds $lockFile — stopping it and taking over")
            lock = takeOverLock(pairPort, lockFile, TAKEOVER_ATTEMPTS, waitMs)
            if (lock == null) {
                echo("could not take over $lockFile — the running daemon didn't exit; aborting")
                exit(69) // EX_UNAVAILABLE
            }
            tookOver = true
        }
        tookOver = portGate(pairPort, takeover, lock, exit, echo) || tookOver
        lock.recordOwner(ProcessHandle.current().pid())
        held = lock
        return tookOver
    }

    /** The second gate: a daemon from before the lock (or anything else) on the pair port. Releases [lock]
     *  before any refusal. True = it stopped the port's holder. */
    private fun portGate(
        pairPort: Int,
        takeover: Boolean,
        lock: InstanceLock?,
        exit: (Int) -> Nothing,
        echo: (String) -> Unit,
    ): Boolean {
        if (!portInUse(pairPort)) return false
        if (takeover) {
            echo("another cc-pocket daemon already holds 127.0.0.1:$pairPort — stopping it and taking over")
            if (stopRunning(pairPort)) return true
            lock?.close()
            echo("could not free 127.0.0.1:$pairPort — the running daemon didn't exit; aborting")
            exit(69) // EX_UNAVAILABLE
        }
        lock?.close()
        refuse("holds 127.0.0.1:$pairPort", exit, echo)
    }

    private fun refuse(what: String, exit: (Int) -> Nothing, echo: (String) -> Unit): Nothing {
        echo("another cc-pocket daemon is already running ($what) — leaving it alone, exiting.")
        echo("  two daemons would fight over the port and connect to the relay under one account.")
        echo("  to run THIS build instead: stop the other one first, or re-run with --takeover.")
        exit(0) // not a failure: a daemon IS running, just not this instance
    }

    /** Retry [lockFile] for up to [attempts] × [waitMs]. */
    internal fun awaitLock(lockFile: File, attempts: Int, waitMs: Long): InstanceLock? {
        repeat(attempts) {
            if (waitMs > 0) Thread.sleep(waitMs)
            InstanceLock.tryAcquire(lockFile)?.let { return it }
        }
        return null
    }

    /**
     * `--takeover` against a lock holder: stop whoever serves the pair port (the running daemon) and wait for
     * the lock — it is released only when the old process has really EXITED, which its shutdown hooks can
     * delay well past the moment the port closes. A holder still starting up binds the port within the wait,
     * so the port is re-checked and its holder stopped again (at most once a second) while we wait.
     */
    internal fun takeOverLock(pairPort: Int, lockFile: File, attempts: Int, waitMs: Long): InstanceLock? {
        val killEvery = if (waitMs > 0) maxOf(1, (1_000 / waitMs).toInt()) else 1
        repeat(attempts) { i ->
            InstanceLock.tryAcquire(lockFile)?.let { return it }
            if (i % killEvery == 0 && portInUse(pairPort)) killHolders(pairPort)
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return InstanceLock.tryAcquire(lockFile)
    }

    /** VISIBLE FOR TESTS ONLY: drop the lock a passing [ensureSolo] kept, as the process exiting would. */
    internal fun releaseForTest() {
        held?.close()
        held = null
    }

    /** A departing holder normally lets go within its shutdown hooks; this bounds the wait (× waitMs). */
    private const val GRACE_ATTEMPTS = 50

    /** How long `--takeover` waits for the old daemon to exit (× waitMs): its shutdown closes every session. */
    private const val TAKEOVER_ATTEMPTS = 150

    /** True iff something accepts a loopback TCP connection on [port] — i.e. a daemon is already listening. */
    internal fun portInUse(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
        true
    }.getOrDefault(false)

    /** Stop the current daemon and wait for its singleton port to be released. Used by both `--takeover`
     *  and the Windows updater: the Windows Scheduled Task launches through a detached WScript, so ending
     *  the task itself does not end the daemon process that WScript already spawned. */
    internal fun stopRunning(port: Int, attempts: Int = 50, waitMs: Long = 100): Boolean {
        if (!portInUse(port)) return true
        killHolders(port)
        repeat(attempts) {
            if (!portInUse(port)) return true
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return !portInUse(port)
    }

    /**
     * Run [bind] until it succeeds or [attempts] are spent; the last failure is rethrown. For the direct
     * listener after a `--takeover`: [stopRunning] waits only for the PAIR port, while the stopped daemon's
     * Ktor servers each close in their own shutdown hook, so the direct port may still be held a moment
     * longer. (TIME_WAIT is not the obstacle: JDK server channels set SO_REUSEADDR on POSIX and Ktor never
     * clears it.)
     */
    internal fun <T> retryBind(attempts: Int = 50, waitMs: Long = 100, bind: () -> T): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return bind()
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1 && waitMs > 0) Thread.sleep(waitMs)
            }
        }
        throw last ?: IllegalStateException("retryBind: no attempts")
    }

    /** Wait for a newly started daemon to claim its loopback singleton port. */
    internal fun waitUntilRunning(port: Int, attempts: Int = 200, waitMs: Long = 100): Boolean {
        repeat(attempts) {
            if (portInUse(port)) return true
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return portInUse(port)
    }

    /** Best-effort stop of whatever holds [port] (the other daemon): lsof on macOS/Linux, netstat+taskkill on Windows. */
    private fun killHolders(port: Int) {
        val win = System.getProperty("os.name").lowercase().contains("win")
        runCatching {
            val pids = if (win) {
                val out = ProcessBuilder("cmd", "/c", "netstat -ano -p tcp | findstr :$port")
                    .start().inputStream.bufferedReader().readText()
                windowsListeningPids(out, port)
            } else {
                // LISTEN only (audit L3): a plain `tcp:$port` also lists every process with a CONNECTION to the
                // port — a `pairlet pair` CLI mid-request, or the probing instance itself — and would kill it
                ProcessBuilder("lsof", "-t", "-iTCP:$port", "-sTCP:LISTEN")
                    .start().inputStream.bufferedReader().readText().trim().split("\n").filter { it.isNotBlank() }.toSet()
            }
            val self = ProcessHandle.current().pid().toString()
            pids.filter { it != self }.forEach { pid -> // never this process, whatever the listing says
                if (win) ProcessBuilder("taskkill", "/PID", pid, "/T", "/F").start().waitFor()
                else ProcessBuilder("kill", pid).start().waitFor()
            }
        }.onFailure { log.warn("takeover: couldn't enumerate/stop the port holder: ${it.message}") }
    }

    /** Parse only LISTENING rows whose LOCAL endpoint is exactly [port]. `findstr :8799` can also return
     *  a connection whose remote endpoint happens to use that port; killing that row's PID would stop an
     *  unrelated process during an update. Handles both IPv4 (`127.0.0.1:8799`) and IPv6 (`[::1]:8799`). */
    internal fun windowsListeningPids(netstat: String, port: Int): Set<String> = netstat.lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 5 && it[0].equals("TCP", ignoreCase = true) }
        .filter { it[3].equals("LISTENING", ignoreCase = true) }
        .filter { it[1].substringAfterLast(':').toIntOrNull() == port }
        .mapNotNull { it[4].takeIf { pid -> pid.all(Char::isDigit) } }
        .toSet()
}

/**
 * An exclusive lock on one file, held through an open [FileChannel] until [close] or process death.
 *
 * `FileChannel.tryLock` is an OS lock (fcntl on POSIX, LockFileEx on Windows): it is released by the kernel
 * when the holder dies, however it dies, so the file left behind is inert — the next start simply locks it
 * again. Java file locks are per JVM, not per channel, and on POSIX closing ANY descriptor of the file drops
 * every lock this process holds on it; [heldHere] therefore answers a second in-process attempt without
 * opening the file at all.
 */
internal class InstanceLock private constructor(
    private val key: String,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    /** Write our pid into the file — diagnostics only (a refused start names who holds the lock). */
    fun recordOwner(pid: Long) {
        runCatching {
            channel.truncate(0)
            channel.write(ByteBuffer.wrap("$pid\n".toByteArray()), 0)
            channel.force(false)
        }
    }

    override fun close() {
        if (!heldHere.remove(key)) return
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private val heldHere = ConcurrentHashMap.newKeySet<String>()

        /** The lock, or null while another holder (another process, or this one) has it. */
        fun tryAcquire(file: File): InstanceLock? {
            val f = file.absoluteFile
            f.parentFile?.mkdirs()
            val key = runCatching { f.canonicalPath }.getOrDefault(f.path)
            if (!heldHere.add(key)) return null
            val channel = try {
                FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                heldHere.remove(key)
                throw e
            }
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                heldHere.remove(key); channel.close()
                throw e
            }
            if (lock == null) {
                heldHere.remove(key); channel.close()
                return null
            }
            return InstanceLock(key, channel, lock)
        }

        /** The pid the current holder recorded, if readable (Windows forbids reading a locked range). */
        fun owner(file: File): String? =
            runCatching { file.readText().trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } }.getOrNull()
    }
}
