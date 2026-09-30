package dev.ccpocket.daemon.memo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoProcessRunnerTest {

    private val runner = MemoProcessRunner(termGraceMs = 300, killConfirmMs = 2_000, postExitDrainMs = 1_000)
    private lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "POSIX shell tools required")
        dir = Files.createTempDirectory("ccp-memo-runner")
    }

    private fun sh(script: String, stdin: ByteArray? = null, out: Int = 64 * 1024, err: Int = 64 * 1024, deadlineMs: Long = 10_000) =
        MemoProcessSpec(listOf("/bin/sh", "-c", script), cwd = dir, stdin = stdin, stdoutLimit = out, stderrLimit = err, deadlineMs = deadlineMs)

    private fun pid(file: String): Long {
        val p = dir.resolve(file)
        val end = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < end) {
            if (p.exists()) p.readText().trim().toLongOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("pid file $file never appeared")
    }

    private fun assertGone(pid: Long) {
        val end = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < end) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false).not()) return
            Thread.sleep(20)
        }
        error("process $pid still alive")
    }

    @Test
    fun normal_exit_returns_code_and_both_streams() = runBlocking {
        val r = assertIs<MemoProcessResult.Exited>(runner.run(sh("echo hi; echo oops >&2; exit 3")))
        assertEquals(3, r.exitCode)
        assertEquals("hi\n", r.stdout.decodeToString())
        assertEquals("oops\n", r.stderr.decodeToString())
        assertFalse(r.stdoutTruncated)
    }

    @Test
    fun stdin_is_delivered_and_closed() = runBlocking {
        val payload = "{\"transcript\":\"检查 mobile build\"}".encodeToByteArray()
        val r = assertIs<MemoProcessResult.Exited>(runner.run(sh("cat", stdin = payload)))
        assertEquals(0, r.exitCode)
        assertContentEquals(payload, r.stdout)
    }

    @Test
    fun no_stdin_means_immediate_eof() = runBlocking {
        val r = assertIs<MemoProcessResult.Exited>(runner.run(sh("cat; echo done")))
        assertEquals("done\n", r.stdout.decodeToString())
    }

    @Test
    fun stdout_over_limit_is_truncated_and_drained_without_deadlock() = runBlocking {
        // 4 MB is far past any pipe buffer: without concurrent draining the child would block forever
        val r = assertIs<MemoProcessResult.Exited>(
            runner.run(sh("head -c 4000000 /dev/zero; echo tail >&2", out = 1024, err = 1024)),
        )
        assertEquals(0, r.exitCode)
        assertEquals(1024, r.stdout.size)
        assertTrue(r.stdoutTruncated)
        assertEquals("tail\n", r.stderr.decodeToString())
    }

    @Test
    fun stderr_flood_does_not_block_the_child() = runBlocking {
        val r = assertIs<MemoProcessResult.Exited>(
            runner.run(sh("head -c 4000000 /dev/zero >&2; echo ok", err = 512)),
        )
        assertEquals("ok\n", r.stdout.decodeToString())
        assertEquals(512, r.stderr.size)
        assertTrue(r.stderrTruncated)
    }

    @Test
    fun timeout_kills_the_whole_tree() = runBlocking {
        val t0 = System.nanoTime()
        val r = runner.run(sh("sleep 30 & echo \$! > child.pid; echo \$\$ > parent.pid; wait", deadlineMs = 800))
        assertEquals(MemoProcessResult.TimedOut, r)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 6_000)
        assertGone(pid("parent.pid"))
        assertGone(pid("child.pid"))
    }

    @Test
    fun coroutine_cancellation_kills_the_tree_and_propagates() = runBlocking {
        val job = async(Dispatchers.Default) {
            runner.run(sh("sleep 30 & echo \$! > child.pid; echo \$\$ > parent.pid; wait", deadlineMs = 60_000))
        }
        val parent = pid("parent.pid")
        val child = pid("child.pid")
        delay(200)
        job.cancel()
        withTimeout(6_000) { assertFailsWith<CancellationException> { job.await() } }
        assertGone(parent)
        assertGone(child)
    }

    @Test
    fun cancelled_before_start_never_launches() = runBlocking {
        val job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            runner.run(sh("echo \$\$ > parent.pid; sleep 30"))
        }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        delay(300)
        assertFalse(dir.resolve("parent.pid").exists())
    }

    @Test
    fun cancel_right_after_launch_leaves_nothing_behind() = runBlocking {
        val job = async(Dispatchers.Default) {
            runner.run(sh("echo \$\$ > parent.pid; sleep 30", deadlineMs = 60_000))
        }
        delay(50)
        job.cancel()
        withTimeout(6_000) { assertFailsWith<CancellationException> { job.await() } }
        // the child may or may not have got as far as writing its pid; if it did, it must be gone
        delay(300)
        if (dir.resolve("parent.pid").exists()) assertGone(pid("parent.pid"))
    }

    @Test
    fun leftover_descendant_holding_the_pipe_does_not_hang_the_job() = runBlocking {
        val t0 = System.nanoTime()
        val r = assertIs<MemoProcessResult.Exited>(runner.run(sh("sleep 30 & echo \$! > child.pid; sleep 0.3; echo hi", deadlineMs = 10_000)))
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.decodeToString().startsWith("hi"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 6_000)
        assertGone(pid("child.pid"))
    }

    @Test
    fun missing_binary_is_start_failed() = runBlocking {
        val r = runner.run(MemoProcessSpec(listOf(dir.resolve("nope").toString()), stdoutLimit = 10, stderrLimit = 10, deadlineMs = 1_000))
        assertEquals(MemoProcessResult.StartFailed, r)
    }

    @Test
    fun exhausted_deadline_does_not_start() = runBlocking {
        val r = runner.run(sh("echo \$\$ > parent.pid", deadlineMs = 0))
        assertEquals(MemoProcessResult.TimedOut, r)
        assertFalse(dir.resolve("parent.pid").exists())
    }

    @Test
    fun abort_predicate_stops_the_child() = runBlocking {
        val marker = dir.resolve("grow")
        val r = runner.run(
            MemoProcessSpec(
                listOf("/bin/sh", "-c", "echo \$\$ > parent.pid; touch grow; sleep 30"),
                cwd = dir,
                stdoutLimit = 100,
                stderrLimit = 100,
                deadlineMs = 10_000,
                abortWhen = { marker.exists() },
            ),
        )
        assertEquals(MemoProcessResult.Aborted, r)
        assertGone(pid("parent.pid"))
    }

    @Test
    fun env_edit_reaches_only_the_child() = runBlocking {
        val r = assertIs<MemoProcessResult.Exited>(
            runner.run(
                MemoProcessSpec(
                    listOf("/bin/sh", "-c", "printf %s \"\$MEMO_TEST_VAR\""),
                    env = { it["MEMO_TEST_VAR"] = "child-only" },
                    stdoutLimit = 100,
                    stderrLimit = 100,
                    deadlineMs = 5_000,
                ),
            ),
        )
        assertEquals("child-only", r.stdout.decodeToString())
        assertEquals(null, System.getenv("MEMO_TEST_VAR"))
    }
}
