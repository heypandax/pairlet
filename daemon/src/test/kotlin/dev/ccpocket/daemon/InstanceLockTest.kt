package dev.ccpocket.daemon

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design S2b: the single-instance check is an exclusive file lock held for the process's life, with the
 * pair-port probe kept as the second gate. The port probe alone left a window — it ran at the top of `run`,
 * but the port is bound only once startup finished — so two instances started together could both pass.
 *
 * The "other daemon" is a real second JVM ([InstanceLockHolder]): Java file locks are per process.
 */
class InstanceLockTest {
    private val dir: File = createTempDirectory("instance-lock").toFile()
    private val lockFile = File(dir, "state/daemon.lock")
    private val children = mutableListOf<Process>()

    @AfterTest
    fun cleanup() {
        SingleInstance.releaseForTest()
        children.forEach { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
        dir.deleteRecursively()
    }

    private class Exited(val code: Int) : RuntimeException("exit($code)")

    /** Start a holder JVM and wait for its READY line. */
    private fun holder(pairPort: Int? = null): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = listOf(
            InstanceLockHolderClass, // the test classes (the holder's main)
            InstanceLock::class.java, // the daemon's main classes
            Unit::class.java, // kotlin-stdlib
        ).joinToString(File.pathSeparator) { File(it.protectionDomain.codeSource.location.toURI()).path }
        val cmd = listOf(java, "-cp", classpath, "dev.ccpocket.daemon.InstanceLockHolderKt", lockFile.path) +
            listOfNotNull(pairPort?.toString())
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start().also(children::add)
        val line = p.inputStream.bufferedReader().readLine()
        assertEquals("READY", line, "holder JVM did not take the lock")
        return p
    }

    private fun freePort(): Int = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun a_second_process_cannot_take_the_lock_while_the_first_holds_it() {
        holder()
        assertNull(InstanceLock.tryAcquire(lockFile), "two processes held the instance lock at once")
    }

    /** No stale lock: the OS drops it with the process, even on kill -9 — the file stays and is simply re-locked. */
    @Test
    fun the_lock_is_free_again_once_the_holder_dies_even_by_kill_9() {
        val p = holder()
        p.destroyForcibly()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertTrue(lockFile.exists(), "the lock file is left behind by a killed holder…")
        val lock = assertNotNull(InstanceLock.tryAcquire(lockFile), "…and must not need cleaning up by hand")
        lock.close()
    }

    @Test
    fun one_process_cannot_hold_the_lock_twice_and_closing_frees_it() {
        val first = assertNotNull(InstanceLock.tryAcquire(lockFile))
        assertNull(InstanceLock.tryAcquire(lockFile), "a second in-process attempt must not succeed")
        first.close()
        assertNotNull(InstanceLock.tryAcquire(lockFile)).close()
    }

    /** The race S2b closes: another instance holds the lock but has not bound the pair port yet. */
    @Test
    fun ensureSolo_refuses_while_another_process_holds_the_lock_even_with_the_port_free() {
        holder()
        val port = freePort()
        val out = mutableListOf<String>()
        val code = runCatching {
            SingleInstance.ensureSolo(port, takeover = false, exit = { throw Exited(it) }, lockFile = lockFile, waitMs = 10) { out += it }
        }.exceptionOrNull()
        assertEquals(0, (code as? Exited)?.code, "expected a clean refusal, got $code")
        assertTrue(out.first().contains("already running"), out.joinToString("\n"))
        assertNull(InstanceLock.tryAcquire(lockFile), "the holder keeps its lock")
    }

    /** A holder on its way out (port already closed, JVM not yet gone) is waited for, not refused. */
    @Test
    fun ensureSolo_waits_out_a_departing_holder() {
        val p = holder()
        Thread { Thread.sleep(300); p.outputStream.close() }.apply { isDaemon = true; start() }
        val tookOver = SingleInstance.ensureSolo(
            freePort(), takeover = false, exit = { error("refused: exit($it)") }, lockFile = lockFile, waitMs = 50,
        ) {}
        assertFalse(tookOver)
        assertEquals(ProcessHandle.current().pid().toString(), lockFile.readText().trim(), "the new holder records its pid")
    }

    @Test
    fun ensureSolo_passes_on_a_free_lock_and_keeps_it_until_released() {
        val tookOver = SingleInstance.ensureSolo(freePort(), takeover = false, exit = { error("exit($it)") }, lockFile = lockFile) {}
        assertFalse(tookOver)
        assertNull(InstanceLock.tryAcquire(lockFile), "the passing instance holds the lock for its whole life")
        SingleInstance.releaseForTest()
        assertNotNull(InstanceLock.tryAcquire(lockFile)).close()
    }

    /**
     * `--takeover` against a current daemon (lock + pair port): stop it through the port, then — only once the
     * old process has really exited and the OS released its lock — take the lock. Returns "took over".
     */
    @Test
    fun takeover_stops_the_holder_and_takes_the_lock_only_after_it_exited() {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "lsof/kill path")
        val port = freePort()
        val old = holder(pairPort = port)
        val out = mutableListOf<String>()
        val tookOver = SingleInstance.ensureSolo(
            port, takeover = true, exit = { error("takeover aborted: exit($it)\n$out") }, lockFile = lockFile, waitMs = 50,
        ) { out += it }
        assertTrue(tookOver)
        assertTrue(old.waitFor(1, TimeUnit.SECONDS) && !old.isAlive, "the lock was taken while the old daemon still ran")
        assertFalse(SingleInstance.portInUse(port), "the old daemon's pair port is gone")
        assertEquals(ProcessHandle.current().pid().toString(), lockFile.readText().trim())
    }

    /** A lock holder that never binds the pair port cannot be stopped through it: abort, never run beside it. */
    @Test
    fun takeover_aborts_when_the_holder_cannot_be_stopped() {
        holder()
        val code = runCatching {
            SingleInstance.ensureSolo(freePort(), takeover = true, exit = { throw Exited(it) }, lockFile = lockFile, waitMs = 2) {}
        }.exceptionOrNull()
        assertEquals(69, (code as? Exited)?.code, "expected EX_UNAVAILABLE, got $code")
    }

    private companion object {
        val InstanceLockHolderClass: Class<*> = Class.forName("dev.ccpocket.daemon.InstanceLockHolderKt")
    }
}
