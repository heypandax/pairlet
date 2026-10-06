package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.codex.CodexCatalogRpc.CatalogOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real transport against a FAKE `codex` — a shell script that speaks just enough of the app-server
 * protocol. No test here runs the Codex CLI, logs in, or starts a thread or turn. The point is the process
 * discipline: every path (success, hang → deadline, hang → caller cancelled, early exit, RPC error) must
 * return promptly and leave NO child behind. POSIX only: the fake is `/bin/sh`.
 */
class CodexCatalogRpcTest {

    private val posix = !System.getProperty("os.name").lowercase().contains("win")

    private class Fake(val exe: Path, val pidFile: Path, val log: Path, val childFile: Path) {
        /** The fake's own pid, once it has written it. */
        fun pid(): Long? = runCatching { Files.readString(pidFile).trim().toLong() }.getOrNull()
        fun received(): List<String> = runCatching { Files.readAllLines(log) }.getOrDefault(emptyList())
        fun childPid(): Long? = runCatching { Files.readString(childFile).trim().toLong() }.getOrNull()
    }

    private val fakes = mutableListOf<Fake>()

    @AfterTest fun cleanUpFakes() {
        // A regression must not leave our intentionally hung fixture running after the assertion fails.
        fakes.forEach { f ->
            listOfNotNull(f.pid(), f.childPid()).forEach { pid ->
                ProcessHandle.of(pid).ifPresent { h ->
                    h.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                    h.destroyForcibly()
                }
            }
            f.exe.parent.toFile().deleteRecursively()
        }
    }

    /** A `/bin/sh` fake: records its pid and every request line, then runs [body] per line (a `case`). */
    private fun fake(dir: Path, body: String, spawnChild: Boolean = false): Fake {
        val pidFile = dir.resolve("pid")
        val log = dir.resolve("received.log")
        val exe = dir.resolve("codex")
        val childFile = dir.resolve("child-pid")
        Files.writeString(
            exe,
            """
            #!/bin/sh
            echo $$ > "$pidFile"
            ${if (spawnChild) "sleep 120 &\necho ${'$'}! > \"$childFile\"" else ""}
            while IFS= read -r line; do
              printf '%s\n' "${'$'}line" >> "$log"
              case "${'$'}line" in
            $body
              esac
            done
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"))
        return Fake(exe, pidFile, log, childFile).also { fakes += it }
    }

    private val initializeReply = """echo '{"id":1,"result":{"userAgent":"codex_cli_rs/0.155.1 (test)","codexHome":"/tmp/x"}}'"""
    private val accountReply = """echo '{"id":2,"result":{"account":{"type":"chatgpt","email":"a@example.com","planType":"pro"},"requiresOpenaiAuth":true}}'"""
    private val configReply = """echo '{"id":3,"result":{"config":{"model":"gpt-6-astra","model_provider":"openai","profile":"work"},"origins":{}}}'"""

    private fun alive(pid: Long?): Boolean = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** True once the fake has exited (polled: a SIGKILLed child is reaped by the JVM's reaper a moment later). */
    private suspend fun gone(pid: Long?): Boolean {
        repeat(40) { if (!alive(pid)) return true; delay(50) }
        return false
    }

    @Test
    fun a_full_read_pages_to_the_end_carries_account_and_config_and_leaves_no_process() = runBlocking {
        assumeTrue(posix)
        val dir = Files.createTempDirectory("catalog-rpc")
        val cwd = Files.createDirectory(dir.resolve("project"))
        val f = fake(
            dir,
            """
                *'"id":1,'*) $initializeReply ;;
                *'"id":2,'*) $accountReply ;;
                *'"id":3,'*) $configReply ;;
                *'"id":4,'*) echo '{"method":"remoteControl/status/changed","params":{}}'
                             echo '{"id":4,"result":{"data":[{"id":"a","model":"a"}],"nextCursor":"c1"}}' ;;
                *'"id":5,'*) echo '{"id":5,"result":{"data":[{"id":"b","model":"b","hidden":true}],"nextCursor":null}}' ;;
            """.trimIndent(), spawnChild = true,
        )

        val out = withTimeout(10_000) { CodexCatalogRpc.read(f.exe, cwd = cwd, timeoutMs = 5_000) }

        val ok = assertIs<CatalogOutcome.Success>(out)
        assertEquals(listOf(listOf("a"), listOf("b")), ok.pages.map { page -> page.map { (it as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"') } })
        assertEquals("chatgpt", ok.account?.get("type").toString().trim('"'))
        assertNull(ok.accountError)
        assertEquals(true, ok.requiresOpenaiAuth)
        assertEquals("openai", ok.provider); assertEquals("work", ok.profile); assertNull(ok.configError)
        assertEquals("codex_cli_rs/0.155.1 (test)", ok.userAgent)
        // the request sequence is the documented one, `initialized` rides as a notification, config/read names the cwd
        val methods = f.received().map { Regex(""""method":"([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertEquals(listOf("initialize", "initialized", "account/read", "config/read", "model/list", "model/list"), methods)
        assertTrue(f.received()[3].contains("\"cwd\":\"$cwd\""), f.received()[3])
        assertTrue(f.received()[4].contains("\"includeHidden\":true") && !f.received()[4].contains("cursor"))
        assertTrue(f.received()[5].contains("\"cursor\":\"c1\""))
        assertTrue(gone(f.pid()), "the fake must be destroyed after a successful read")
        assertTrue(f.childPid() != null && gone(f.childPid()), "successful reads must also reap launcher descendants")
    }

    @Test
    fun a_child_that_never_answers_is_killed_at_the_deadline() = runBlocking {
        assumeTrue(posix)
        val dir = Files.createTempDirectory("catalog-rpc")
        val f = fake(dir, """  *'"id":1,'*) $initializeReply ;;""", spawnChild = true) // answers initialize, then swallows everything

        val t0 = System.nanoTime()
        val out = withTimeout(10_000) { CodexCatalogRpc.read(f.exe, cwd = dir, timeoutMs = 2_000) }
        val ms = (System.nanoTime() - t0) / 1_000_000

        val failure = assertIs<CatalogOutcome.Failure>(out)
        assertTrue("did not answer in time" in failure.reason, failure.reason)
        assertTrue(ms < 5_000, "returned after ${ms}ms — the deadline must not wait on the blocked reader")
        assertTrue(gone(f.pid()), "the hung fake must be destroyed by the deadline")
        assertTrue(f.childPid() != null && gone(f.childPid()), "the deadline must reap descendants holding the stdout pipe")
        // the hang was real: account/read went out and nothing came back
        assertTrue(f.received().any { "account/read" in it })
    }

    @Test
    fun cancelling_the_caller_destroys_a_blocked_child_promptly() = runBlocking {
        assumeTrue(posix)
        val dir = Files.createTempDirectory("catalog-rpc")
        val f = fake(dir, """  *'"id":1,'*) $initializeReply ;;""", spawnChild = true)

        var outcome: CatalogOutcome? = null
        val job = launch { outcome = CodexCatalogRpc.read(f.exe, cwd = dir, timeoutMs = 60_000) }
        // let it spawn and block on the never-answered account/read
        withTimeout(5_000) { while (f.pid() == null || f.received().none { "account/read" in it }) delay(20) }
        assertTrue(alive(f.pid()), "precondition: the fake is alive and the read is blocked on it")

        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { job.join() }
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertNull(outcome, "a cancelled read completes by exception, never by a verdict")
        assertTrue(gone(f.pid()), "cancellation must destroy the child, not orphan it under a blocked readLine")
        assertTrue(f.childPid() != null && gone(f.childPid()), "cancellation must reap launcher descendants too")
        assertTrue(ms < 4_000, "join took ${ms}ms")
    }

    @Test
    fun an_early_exit_and_an_rpc_error_are_reported_as_such_and_leave_no_process() = runBlocking {
        assumeTrue(posix)
        val d1 = Files.createTempDirectory("catalog-rpc")
        val exits = fake(d1, """  *'"id":1,'*) $initializeReply; exit 0 ;;""")
        val closed = withTimeout(10_000) { CodexCatalogRpc.read(exits.exe, cwd = d1, timeoutMs = 5_000) }
        assertTrue("closed the connection" in assertIs<CatalogOutcome.Failure>(closed).reason)
        assertTrue(gone(exits.pid()))

        val d2 = Files.createTempDirectory("catalog-rpc")
        val refuses = fake(
            d2,
            """
                *'"id":1,'*) $initializeReply ;;
                *'"id":2,'*) echo '{"id":2,"error":{"code":-32000,"message":"account read failed"}}' ;;
                *'"id":3,'*) echo '{"id":3,"error":{"code":-32000,"message":"config read failed"}}' ;;
                *'"id":4,'*) echo '{"id":4,"error":{"code":-32000,"message":"model list unavailable"}}' ;;
            """.trimIndent(),
        )
        val refused = withTimeout(10_000) { CodexCatalogRpc.read(refuses.exe, cwd = d2, timeoutMs = 5_000) }
        assertEquals("model list unavailable", assertIs<CatalogOutcome.RpcError>(refused).message)
        assertTrue(gone(refuses.pid()))
        // account/config errors did not abort the read — the pages were still asked for
        assertTrue(refuses.received().any { "model/list" in it })

        // a cwd that is not a directory falls back to the home directory rather than failing the spawn
        val d3 = Files.createTempDirectory("catalog-rpc")
        val plain = fake(
            d3,
            """
                *'"id":1,'*) $initializeReply ;;
                *'"id":2,'*) $accountReply ;;
                *'"id":3,'*) echo '{"id":3,"result":{"origins":{}}}' ;;
                *'"id":4,'*) echo '{"id":4,"result":{"data":[],"nextCursor":null}}' ;;
            """.trimIndent(),
        )
        val out = withTimeout(10_000) { CodexCatalogRpc.read(plain.exe, cwd = d3.resolve("missing"), timeoutMs = 5_000) }
        val sparse = assertIs<CatalogOutcome.Success>(out)
        assertTrue(sparse.configError.orEmpty().contains("without a config object"), "a config/read result with no config object is an error, not the default config")
        assertNull(sparse.provider)
        assertFalse(plain.received()[3].contains("\"cwd\""), "no cwd is sent for a directory that does not exist")
        assertTrue(gone(plain.pid()))
    }
}
