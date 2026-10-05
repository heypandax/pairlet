package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.parse
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.RelayPeerTransport
import dev.ccpocket.daemon.schedule.ScheduleEntry
import dev.ccpocket.daemon.schedule.ScheduleStore
import dev.ccpocket.protocol.AgentKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit H1 / design S2a: a second `pairlet run` that is turned away by the single-instance check must leave
 * NOTHING behind — it runs while the first daemon is serving, and launchd's KeepAlive replays it every ~10 s.
 *
 * Drives the REAL [RunCmd] in-process against a sandboxed home: the "running daemon" is a socket this test
 * holds on an ephemeral pair port, the process exit is replaced by a throw, and every startup side effect
 * the audit (and the follow-up review) found is armed with a seed it would visibly disturb:
 *
 *  - file deletion: a voice-memo job left in `voice-memo-tmp` ([dev.ccpocket.daemon.memo.MemoWorkDir.prepare]);
 *  - file writes: no `identity.json` yet ([dev.ccpocket.daemon.identity.Identity.loadOrCreate]), credential
 *    isolation on ([dev.ccpocket.daemon.claude.ClaudeHome.prepare]), a peer-link secret whose handshake counter
 *    a dial would bump — plus a whole-tree snapshot that catches any write not named here;
 *  - scheduled trigger + child process: a schedule already due whose "claude" is a script that leaves a marker;
 *  - outbound connection: a review peer link pointing at a local listener that counts accepts;
 *  - port binding: the E2E direct listener's port must still be free afterwards;
 *  - resident threads: the diagnostics preference watcher must not have been started.
 *
 * NEVER uses `--takeover`: the "other daemon" here is this test JVM, and takeover kills the port holder.
 */
class SecondInstanceNoSideEffectsTest {
    private class Rejected(val code: Int) : RuntimeException("exit($code)")

    private lateinit var home: File
    private var savedHome: String? = null
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeTest
    fun sandbox() {
        // Every store below resolves under user.home unless CC_POCKET_IDENTITY redirects it — then this test
        // could reach a real ~/.cc-pocket, so it refuses to run rather than risk it. Windows is skipped too: a
        // regression there would also self-register a logon task for the test JVM.
        assumeTrue(System.getenv("CC_POCKET_IDENTITY") == null, "CC_POCKET_IDENTITY set — not sandboxable")
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "POSIX-only sandbox")
        home = kotlin.io.path.createTempDirectory("second-instance-home").toFile()
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun restore() {
        savedHome?.let { System.setProperty("user.home", it) }
        closeables.asReversed().forEach { runCatching { it.close() } }
        if (::home.isInitialized) home.deleteRecursively()
    }

    @Test
    fun rejected_second_run_has_no_side_effects() {
        val store = File(home, ".cc-pocket").apply { mkdirs() }

        // the running daemon: something already accepts on the pair port
        val pairHolder = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also(closeables::add)
        val pairPort = pairHolder.localPort
        val directPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        // the running daemon's single-instance lock file (design S2b) — the file only: a lock held by THIS JVM
        // would be refused at the first gate, and the point here is that the port gate turns the start away
        // too (a daemon from before the lock). Its content must be untouched afterwards (snapshot below).
        File(store, "daemon.lock").writeText("")

        // ① file deletion — the running daemon's in-flight voice memo job
        val memoJob = File(store, "voice-memo-tmp/job-1/recording.m4a").apply { parentFile.mkdirs(); writeText("audio") }

        // ② file writes — isolation on, so ClaudeHome.prepare would build ~/.cc-pocket/claude-home
        File(store, "prefs.json").writeText("""{"isolatedClaudeAuth":true}""")

        // ③ scheduled trigger + child process — a due schedule whose agent binary leaves a marker
        val marker = File(home, "agent-was-spawned")
        val fakeClaude = File(home, "bin/claude").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\necho spawned >> '${marker.absolutePath}'\n")
            setExecutable(true)
        }
        val workdir = File(home, "work").apply { mkdirs() }
        val now = System.currentTimeMillis()
        ScheduleStore.load(File(store, "schedules.json")).add(
            ScheduleEntry(
                id = "due-1", workdir = workdir.absolutePath, prompt = "hello", runAtMs = now - 1_000,
                agent = AgentKind.CLAUDE, nextRunAtMs = now - 1_000,
            ),
        )

        // ④ outbound connection — a review peer link whose "relay" is a listener counting accepts
        val peerRelay = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also(closeables::add)
        val peerDials = AtomicInteger()
        Thread {
            while (!peerRelay.isClosed) runCatching { peerRelay.accept().close(); peerDials.incrementAndGet() }
        }.apply { isDaemon = true; start() }
        val keys = RelayPeerTransport().generateKeys()
        PeerLinkStore.load(File(store, "peer-links.json"), File(store, "peer-link-secrets.json")).put(
            PeerLink("pl_1", "Peer", "ws://127.0.0.1:${peerRelay.localPort}", "acct", keys.publicKeyB64, "devB", "fp", now),
            PeerLinkSecret("pl_1", "credential", keys.privateKeyB64, keys.publicKeyB64),
        )

        val diagnosticsThreadBefore = diagnosticsWatcherRunning()
        val before = snapshot(home)

        val failure = AtomicReference<Throwable?>()
        val runner = Thread {
            try {
                RunCmd(exit = { throw Rejected(it) }).parse(
                    listOf(
                        "--pair-port", pairPort.toString(),
                        "--port", directPort.toString(),
                        "--direct-bind", "127.0.0.1",
                        "--relay", "ws://127.0.0.1:1", // never dialled: the start is refused first
                        "--claude-bin", fakeClaude.absolutePath,
                    ),
                )
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { isDaemon = true; start() }
        runner.join(30_000)
        assertTrue(!runner.isAlive, "the second `run` did not stop — it got past the single-instance check")
        val rejected = failure.get() as? Rejected ?: fail("expected a clean refusal, got ${failure.get()}")
        assertEquals(0, rejected.code, "an already-running daemon is not a failure: exit 0")

        // give anything the refused start may have launched (schedule pump, peer dial, sweeps) time to act
        Thread.sleep(SETTLE_MS)

        val violations = buildList {
            if (!memoJob.isFile) add("deleted the running daemon's voice-memo scratch (${memoJob.name})")
            if (File(store, "identity.json").exists()) add("created identity.json")
            if (File(store, "claude-home").exists()) add("built the isolated claude-home")
            if (marker.exists()) add("fired the due schedule and spawned an agent")
            if (peerDials.get() > 0) add("dialled the review peer's relay ${peerDials.get()}×")
            if (SingleInstance.portInUse(directPort)) add("bound the direct listener port $directPort")
            if (!diagnosticsThreadBefore && diagnosticsWatcherRunning()) add("started the diagnostics watcher thread")
            val after = snapshot(home)
            if (after != before) {
                val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
                add("changed files under the sandboxed home: $changed")
            }
        }
        assertTrue(violations.isEmpty(), "a refused second instance must have no side effects, but it:\n - " + violations.joinToString("\n - "))
    }

    private fun diagnosticsWatcherRunning(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "cc-pocket-diagnostic-preference" && it.isAlive }

    /** path → content hash (directories map to "dir"), for every entry under [root]. JNA's native-library
     *  cache is left out: Clikt's terminal layer (Mordant) extracts it for ANY `pairlet` command's first
     *  echo — the refusal message included — and it is not daemon state. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it != root }
        .filterNot { f -> f.relativeTo(root).path.let { it == "Library" || it.startsWith("Library/Caches") } }
        .associate { f ->
            f.relativeTo(root).path to if (f.isDirectory) "dir" else sha256(f)
        }

    private fun sha256(f: File): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("unreadable")

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
