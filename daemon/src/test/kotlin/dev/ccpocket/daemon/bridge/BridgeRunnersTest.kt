package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeRunnerSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of daemon-managed adapter processes. These drive REAL processes (a tiny python script) and
 * REAL files, because the things that matter here — does the secret leak, does the credential land where
 * the adapter looks, does a stopped process actually die — are exactly what a mock would paper over.
 */
class BridgeRunnersTest {
    private val tmp: File = Files.createTempDirectory("ccp-runners").toFile()
    private val root = File(tmp, "runners")
    private val store = File(tmp, "runners.json")
    private val runners = BridgeRunners(rootDir = root, store = store)

    private val python = listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3")
        .firstOrNull { File(it).canExecute() }

    @AfterTest fun cleanup(): Unit = runBlocking {
        runners.stopAll()
        tmp.deleteRecursively()
        Unit
    }

    private fun cred(name: String = "feishu-bot") = BridgeCredential(
        name = name, accountId = "acct", daemonPub = "pub", ticket = "TICKET-SECRET",
        relay = "wss://relay.example", workdirs = listOf("/p/alpha"), ttlSec = 120,
    )

    /** A script that just reports what the daemon handed it, then exits. */
    private fun script(body: String): String {
        val f = File(tmp, "adapter_${body.hashCode().toUInt()}.py")
        f.writeText(body)
        return f.path
    }

    @Test
    fun attach_lands_the_credential_where_the_adapter_looks_and_at_0600() = runBlocking {
        val spec = BridgeRunnerSpec(scriptPath = script("pass"))
        runners.attach("feishu-bot", spec, cred())

        val credFile = File(runners.dirFor("feishu-bot"), "bridge-credential.json")
        assertTrue(credFile.isFile, "credential must be written into the runner's dir")
        assertTrue("TICKET-SECRET" in credFile.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(credFile.toPath())))
        // the dir itself holds a credential — it must not be world-readable either
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(runners.dirFor("feishu-bot").toPath())))
    }

    @Test
    fun state_never_exposes_env_values_only_key_names() = runBlocking {
        val spec = BridgeRunnerSpec(
            scriptPath = script("pass"),
            env = mapOf("FEISHU_APP_SECRET" to "s3cret-value", "FEISHU_APP_ID" to "cli_x"),
        )
        runners.attach("feishu-bot", spec, cred())
        val state = assertNotNull(runners.state("feishu-bot"))
        assertEquals(listOf("FEISHU_APP_ID", "FEISHU_APP_SECRET"), state.envKeys)
        assertFalse("s3cret-value" in state.toString(), "runner state leaked an env VALUE: $state")
    }

    @Test
    fun persisted_store_is_owner_only_because_it_holds_the_im_secret() = runBlocking {
        runners.attach("feishu-bot", BridgeRunnerSpec(scriptPath = script("pass"), env = mapOf("FEISHU_APP_SECRET" to "s3cret")), cred())
        assertTrue(store.isFile)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.toPath())))
        // and it survives a restart of the daemon
        val reloaded = BridgeRunners(rootDir = root, store = store)
        assertTrue(reloaded.isManaged("feishu-bot"))
    }

    @Test
    fun start_injects_the_credential_path_and_the_im_env_into_the_process() = runBlocking {
        val python = python ?: return@runBlocking // no interpreter on this box; the wiring is covered by the others
        val out = File(tmp, "seen.txt")
        val spec = BridgeRunnerSpec(
            scriptPath = script(
                """
                import os, json
                cred = json.load(open(os.environ["POCKET_CREDENTIAL"]))
                open(r"${out.path}", "w").write(json.dumps({
                    "app_id": os.environ.get("FEISHU_APP_ID"),
                    "secret": os.environ.get("FEISHU_APP_SECRET"),
                    "ticket": cred["ticket"],
                    "cwd": os.getcwd(),
                    "routes": os.environ.get("POCKET_ROUTES"),
                }))
                """.trimIndent(),
            ),
            env = mapOf("FEISHU_APP_ID" to "cli_abc", "FEISHU_APP_SECRET" to "s3cret"),
            interpreter = python,
        )
        runners.attach("feishu-bot", spec, cred())
        assertNull(runners.start("feishu-bot"))

        // the adapter is a real process; give it a moment to run and exit
        repeat(60) { if (out.isFile) return@repeat; Thread.sleep(50) }
        assertTrue(out.isFile, "adapter never ran — log tail: ${runners.state("feishu-bot")?.logTail}")
        val seen = out.readText()
        assertTrue("cli_abc" in seen && "s3cret" in seen, "IM env not injected: $seen")
        assertTrue("TICKET-SECRET" in seen, "adapter couldn't read its credential: $seen")
        assertTrue(runners.dirFor("feishu-bot").canonicalPath in seen, "cwd should be the runner's state dir: $seen")
    }

    @Test
    fun a_crashing_adapter_reports_its_exit_code_and_output_instead_of_failing_silently() = runBlocking {
        val python = python ?: return@runBlocking
        val spec = BridgeRunnerSpec(
            scriptPath = script("import sys; print('boom: bad app secret'); sys.exit(3)"),
            interpreter = python,
        )
        runners.attach("feishu-bot", spec, cred())
        assertNull(runners.start("feishu-bot"))
        repeat(60) { if (runners.state("feishu-bot")?.running == false) return@repeat; Thread.sleep(50) }

        val state = assertNotNull(runners.state("feishu-bot"))
        assertFalse(state.running)
        assertEquals(3, state.exitCode)
        assertTrue(state.lastError?.contains("3") == true, "owner must learn WHY: ${state.lastError}")
        assertTrue(state.logTail.any { "boom" in it }, "log tail should carry the adapter's own words: ${state.logTail}")
    }

    @Test
    fun start_refuses_a_missing_script_rather_than_spawning_nothing() = runBlocking {
        runners.attach("feishu-bot", BridgeRunnerSpec(scriptPath = "/nope/not/here.py"), cred())
        val err = runners.start("feishu-bot")
        assertNotNull(err)
        assertTrue("not found" in err, err)
        assertFalse(runners.state("feishu-bot")!!.running)
    }

    @Test
    fun detach_stops_the_process_and_destroys_the_credential() = runBlocking {
        val python = python ?: return@runBlocking
        val spec = BridgeRunnerSpec(scriptPath = script("import time; time.sleep(60)"), interpreter = python)
        runners.attach("feishu-bot", spec, cred())
        assertNull(runners.start("feishu-bot"))
        repeat(40) { if (runners.state("feishu-bot")?.running == true) return@repeat; Thread.sleep(50) }
        val dir = runners.dirFor("feishu-bot")
        assertTrue(File(dir, "bridge-credential.json").isFile)

        assertNull(runners.detach("feishu-bot"))
        assertFalse(runners.isManaged("feishu-bot"))
        assertNull(runners.state("feishu-bot"))
        // the credential must not outlive the runner that was the only thing entitled to use it
        assertFalse(File(dir, "bridge-credential.json").exists(), "credential survived detach")
    }

    @Test
    fun reconfigure_with_mergeEnv_overlays_typed_values_and_keeps_the_untyped_secret() = runBlocking {
        val spec = BridgeRunnerSpec(
            scriptPath = script("pass"),
            env = mapOf("FEISHU_APP_ID" to "cli_x", "FEISHU_APP_SECRET" to "s3cret"),
        )
        runners.attach("feishu-bot", spec, cred())

        // the edit path: only the admin id is typed; blank scriptPath means "keep"
        assertNull(runners.reconfigure(
            "feishu-bot",
            BridgeRunnerSpec(scriptPath = "", env = mapOf("FEISHU_ADMIN_OPEN_ID" to "ou_me")),
            mergeEnv = true,
        ))
        val state = assertNotNull(runners.state("feishu-bot"))
        assertEquals(listOf("FEISHU_ADMIN_OPEN_ID", "FEISHU_APP_ID", "FEISHU_APP_SECRET"), state.envKeys,
            "merge must ADD the admin id without dropping the untyped app credentials")
        assertEquals(spec.scriptPath, state.scriptPath, "blank scriptPath on merge must keep the stored one")

        // and it survives a daemon restart
        val reloaded = BridgeRunners(rootDir = root, store = store)
        assertTrue("FEISHU_ADMIN_OPEN_ID" in assertNotNull(reloaded.state("feishu-bot")).envKeys)

        // wholesale replace (mergeEnv=false) still means what it says
        assertNull(runners.reconfigure("feishu-bot", BridgeRunnerSpec(scriptPath = spec.scriptPath, env = mapOf("FEISHU_APP_ID" to "cli_y"))))
        assertEquals(listOf("FEISHU_APP_ID"), assertNotNull(runners.state("feishu-bot")).envKeys)
    }

    @Test
    fun reconfigure_refuses_a_bridge_that_was_never_managed() = runBlocking {
        val err = runners.reconfigure("never-managed", BridgeRunnerSpec(scriptPath = script("pass")))
        assertNotNull(err)
        // the message must explain the real constraint, not just say "no"
        assertTrue("revoke" in err, err)
    }

    @Test
    fun an_unwritable_store_rejects_reconfigure_and_keeps_old_memory_and_disk_truth() = runBlocking {
        val old = BridgeRunnerSpec(
            scriptPath = script("pass"),
            env = mapOf("FEISHU_NO_APPROVAL" to "true", "FEISHU_APP_SECRET" to "old-secret"),
        )
        runners.attach("feishu-bot", old, cred())
        val durableBefore = store.readText()
        assertTrue(assertNotNull(runners.state("feishu-bot")).noApproval)

        // Atomic persistence needs to create its sibling temp file in this directory. Removing the write
        // bit makes that fail before rename, deterministically leaving the old complete store untouched.
        Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val error = runners.reconfigure(
                "feishu-bot",
                old.copy(env = mapOf("FEISHU_NO_APPROVAL" to "false", "FEISHU_APP_SECRET" to "new-secret")),
            )
            assertNotNull(error, "an authority edit that did not reach disk must not report success")
            assertTrue("persist" in error, error)
            assertTrue(assertNotNull(runners.state("feishu-bot")).noApproval, "memory must roll back to durable old truth")
            assertEquals(durableBefore, store.readText(), "atomic failure must preserve the old complete JSON")
        } finally {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        val reloaded = BridgeRunners(rootDir = root, store = store)
        assertTrue(assertNotNull(reloaded.state("feishu-bot")).noApproval, "restart must agree with live memory after failure")
        assertFalse(tmp.listFiles().orEmpty().any { it.name.startsWith(".${store.name}.") }, "failed atomic write left a temp secret")
    }

    @Test
    fun in_process_reconfigure_drains_late_handler_before_origin_close_and_rebuild() = runBlocking {
        val made = mutableListOf<GatedEngine>()
        runners.registerEngine("test-in-process") { _, _, env, _, _ ->
            GatedEngine(gated = made.isEmpty(), configEnv = env).also(made::add)
        }
        val runnerSpec = BridgeRunnerSpec(scriptPath = "", kind = "test-in-process")
        runners.attachInProcess(
            "feishu-bot",
            runnerSpec,
            BridgeSpec(name = "feishu-bot", workdirs = listOf("/p/alpha")),
        )
        assertNull(runners.start("feishu-bot"))
        val old = made.single()

        val edit = async(start = CoroutineStart.UNDISPATCHED) {
            runners.reconfigure(
                "feishu-bot",
                runnerSpec.copy(env = mapOf("FEISHU_OWNER_BYPASS" to "false")),
                mergeEnv = true,
                restartIfRunning = true,
            )
        }
        old.handlerEntered.await()

        // A concurrent owner START used to slip into the revoke→entry-replace gap and rebuild the OLD
        // config. It must wait on the same per-name lifecycle transaction.
        val racingStart = async(start = CoroutineStart.UNDISPATCHED) { runners.start("feishu-bot") }

        assertFalse(edit.isCompleted, "reconfigure must await already-admitted handlers")
        assertFalse(racingStart.isCompleted, "start must not observe the half-reconfigured runner")
        assertEquals(listOf("start", "quiesce"), old.events)
        assertEquals(1, made.size, "a replacement engine must not be built while old authority is live")

        old.allowHandler.complete(Unit)
        assertNull(edit.await())
        assertEquals(listOf("start", "quiesce", "handler-late-open", "close-origin", "release"), old.events)
        assertEquals(0, old.activeConversations, "the close sweep must run after the late handler open")
        assertFalse(old.running)

        assertNull(edit.await())
        assertNull(racingStart.await())
        assertEquals(2, made.size, "the next start must build from the replacement config")
        assertEquals("false", made.last().configEnv["FEISHU_OWNER_BYPASS"])
    }

    @Test
    fun detach_serializes_a_racing_start_and_cannot_orphan_a_recreated_engine() = runBlocking {
        val made = mutableListOf<GatedEngine>()
        runners.registerEngine("test-detach-race") { _, _, env, _, _ ->
            GatedEngine(gated = made.isEmpty(), configEnv = env).also(made::add)
        }
        val runnerSpec = BridgeRunnerSpec(scriptPath = "", kind = "test-detach-race")
        runners.attachInProcess(
            "feishu-bot",
            runnerSpec,
            BridgeSpec(name = "feishu-bot", workdirs = listOf("/p/alpha")),
        )
        assertNull(runners.start("feishu-bot"))
        val old = made.single()

        val detach = async(start = CoroutineStart.UNDISPATCHED) { runners.detach("feishu-bot") }
        old.handlerEntered.await()
        val racingStart = async(start = CoroutineStart.UNDISPATCHED) { runners.start("feishu-bot") }
        assertFalse(racingStart.isCompleted, "start must wait until detach's durable removal completes")
        assertEquals(1, made.size, "no replacement may be constructed while old authority is draining")

        old.allowHandler.complete(Unit)
        assertNull(detach.await())
        val startError = assertNotNull(racingStart.await())
        assertTrue("no managed adapter" in startError)
        assertEquals(1, made.size, "a detached runner must not leave an orphan replacement engine")
        assertFalse(runners.isManaged("feishu-bot"))
        assertNull(runners.state("feishu-bot"))
    }

    @Test
    fun a_failed_in_process_revoke_aborts_reconfigure_and_keeps_the_old_engine_mapped() = runBlocking {
        val made = mutableListOf<FailOnceEngine>()
        runners.registerEngine("test-failing-revoke") { _, _, _, _, _ ->
            FailOnceEngine().also(made::add)
        }
        val runnerSpec = BridgeRunnerSpec(scriptPath = "", kind = "test-failing-revoke")
        runners.attachInProcess(
            "feishu-bot",
            runnerSpec,
            BridgeSpec(name = "feishu-bot", workdirs = listOf("/p/alpha")),
        )
        assertNull(runners.start("feishu-bot"))

        val error = runners.reconfigure("feishu-bot", runnerSpec.copy(env = mapOf("changed" to "yes")), mergeEnv = true)
        assertNotNull(error)
        assertTrue("registry close failed" in error, error)
        assertEquals(1, made.size, "failure must not construct a replacement engine")
        assertTrue(runners.isManaged("feishu-bot"), "failed revoke must not discard the recoverable entry")
        assertNotNull(runners.state("feishu-bot"), "failed revoke must keep the old engine mapped")
    }

    @Test
    fun a_name_cannot_traverse_out_of_the_runners_directory() = runBlocking {
        val dir = runners.dirFor("../../etc/evil")
        assertTrue(dir.canonicalPath.startsWith(root.canonicalPath), "escaped the runners root: ${dir.canonicalPath}")
    }

    private class GatedEngine(
        gated: Boolean,
        val configEnv: Map<String, String> = emptyMap(),
    ) : InProcessBridgeEngine {
        val events = mutableListOf<String>()
        val handlerEntered = CompletableDeferred<Unit>()
        val allowHandler = CompletableDeferred<Unit>().also { if (!gated) it.complete(Unit) }
        var activeConversations = 0
        private var isRunning = false

        override val running: Boolean get() = isRunning
        override val lastError: String? = null
        override fun ownedConvoIds(): Set<String> = setOf("owned-convo")

        override fun start(): String? {
            isRunning = true
            events += "start"
            return null
        }

        override fun stop() { isRunning = false }

        override suspend fun revokeAndShutdown() {
            isRunning = false
            events += "quiesce"
            handlerEntered.complete(Unit)
            allowHandler.await()
            // Models a handler admitted just before quiesce reaching its non-cancellable router open late.
            activeConversations++
            events += "handler-late-open"
            activeConversations = 0
            events += "close-origin"
            events += "release"
        }
    }

    private class FailOnceEngine : InProcessBridgeEngine {
        private var isRunning = false
        private var revokeAttempts = 0

        override val running: Boolean get() = isRunning
        override val lastError: String? = null
        override fun ownedConvoIds(): Set<String> = setOf("owned-convo")
        override fun start(): String? { isRunning = true; return null }
        override fun stop() { isRunning = false }
        override suspend fun revokeAndShutdown() {
            isRunning = false
            if (revokeAttempts++ == 0) error("registry close failed")
        }
    }
}
