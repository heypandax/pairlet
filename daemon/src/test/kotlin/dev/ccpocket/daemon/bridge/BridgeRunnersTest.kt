package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeRunnerSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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

    @AfterTest fun cleanup() {
        runners.stopAll()
        tmp.deleteRecursively()
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
    fun attach_lands_the_credential_where_the_adapter_looks_and_at_0600() {
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
    fun state_never_exposes_env_values_only_key_names() {
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
    fun persisted_store_is_owner_only_because_it_holds_the_im_secret() {
        runners.attach("feishu-bot", BridgeRunnerSpec(scriptPath = script("pass"), env = mapOf("FEISHU_APP_SECRET" to "s3cret")), cred())
        assertTrue(store.isFile)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.toPath())))
        // and it survives a restart of the daemon
        val reloaded = BridgeRunners(rootDir = root, store = store)
        assertTrue(reloaded.isManaged("feishu-bot"))
    }

    @Test
    fun start_injects_the_credential_path_and_the_im_env_into_the_process() {
        val python = python ?: return // no interpreter on this box; the wiring is covered by the others
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
    fun a_crashing_adapter_reports_its_exit_code_and_output_instead_of_failing_silently() {
        val python = python ?: return
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
    fun start_refuses_a_missing_script_rather_than_spawning_nothing() {
        runners.attach("feishu-bot", BridgeRunnerSpec(scriptPath = "/nope/not/here.py"), cred())
        val err = runners.start("feishu-bot")
        assertNotNull(err)
        assertTrue("not found" in err, err)
        assertFalse(runners.state("feishu-bot")!!.running)
    }

    @Test
    fun detach_stops_the_process_and_destroys_the_credential() {
        val python = python ?: return
        val spec = BridgeRunnerSpec(scriptPath = script("import time; time.sleep(60)"), interpreter = python)
        runners.attach("feishu-bot", spec, cred())
        assertNull(runners.start("feishu-bot"))
        repeat(40) { if (runners.state("feishu-bot")?.running == true) return@repeat; Thread.sleep(50) }
        val dir = runners.dirFor("feishu-bot")
        assertTrue(File(dir, "bridge-credential.json").isFile)

        assertNull(runBlocking { runners.detach("feishu-bot") })
        assertFalse(runners.isManaged("feishu-bot"))
        assertNull(runners.state("feishu-bot"))
        // the credential must not outlive the runner that was the only thing entitled to use it
        assertFalse(File(dir, "bridge-credential.json").exists(), "credential survived detach")
    }

    @Test
    fun reconfigure_with_mergeEnv_overlays_typed_values_and_keeps_the_untyped_secret() {
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
    fun reconfigure_refuses_a_bridge_that_was_never_managed() {
        val err = runners.reconfigure("never-managed", BridgeRunnerSpec(scriptPath = script("pass")))
        assertNotNull(err)
        // the message must explain the real constraint, not just say "no"
        assertTrue("revoke" in err, err)
    }

    @Test
    fun a_name_cannot_traverse_out_of_the_runners_directory() {
        val dir = runners.dirFor("../../etc/evil")
        assertTrue(dir.canonicalPath.startsWith(root.canonicalPath), "escaped the runners root: ${dir.canonicalPath}")
    }
}
