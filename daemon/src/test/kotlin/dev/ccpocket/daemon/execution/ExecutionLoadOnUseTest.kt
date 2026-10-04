package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeEntry
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.BridgeStore
import dev.ccpocket.daemon.bridge.ExecutionCredentialStore
import dev.ccpocket.daemon.control.LOCAL_CONTROL_PREFIX
import dev.ccpocket.daemon.control.LocalControlToken
import dev.ccpocket.daemon.control.executionControlDepsOf
import dev.ccpocket.daemon.control.installExecutionControl
import dev.ccpocket.daemon.execution.client.ExecutionClient
import dev.ccpocket.daemon.execution.client.ExecutionClientStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.RelayPeerTransport
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.protocol.e2e.E2ECrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * #367 isolation: the execution planes (grant store, owner plane, run plane, source client, the 15-second
 * maintenance ticker) load at relay attach ONLY on a machine with evidence of use, and otherwise on first
 * use — once per process, never unloaded.
 */
class ExecutionLoadOnUseTest {

    private val dir = createTempDirectory("ccp-exec-load").toFile()
    private val grantsFile = File(dir, "execution-grants.json")
    private val runRoot = File(dir, "execution-runs")
    private val linksFile = File(dir, "execution-links.json")
    private val secretsFile = File(dir, "execution-link-secrets.json")
    private val clientRunsFile = File(dir, "execution-client-runs.json")
    private val usage = ExecutionUsage(grantsFile, runRoot, linksFile, secretsFile, clientRunsFile)

    private val core = DaemonCore(
        emptyMap(),
        projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
        managedSessionRoot = File(dir, "managed"),
        executionRunRoot = runRoot,
    )

    @AfterTest
    fun cleanup() {
        runBlocking { runCatching { core.shutdown() } }
        core.scope.cancel()
        dir.deleteRecursively()
    }

    private fun bridges() = BridgeRegistry(File(dir, "bridges.json"))

    /** The production installer's shape (RelayClient.installExecutionPlanes) over this test's temp files. */
    private fun realInstaller(): () -> Unit = {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val store = ExecutionGrantStore.load(grantsFile, identity.e2ePubB64)
        val target = ExecutionTarget(
            identity = identity,
            relayUrl = "wss://relay.test",
            store = store,
            bridges = bridges(),
            mintTicket = { null },
            armPsk = {},
            revokeRelayDevice = {},
            isKnownDevice = { false },
            refusals = core.executionRefusals,
        )
        val client = ExecutionClient(
            transport = RelayPeerTransport(),
            links = PeerLinkStore.load(linksFile, secretsFile),
            runs = ExecutionClientStore.load(clientRunsFile),
        )
        core.installExecution(store, target, client)
    }

    private fun assertUnloaded() {
        assertNull(core.executionPlane)
        assertNull(core.router.executionPlane)
        assertNull(core.router.executionGuard)
        assertNull(core.executionTarget)
        assertNull(core.executionGrants)
        assertNull(core.executionClient)
        assertNull(core.executionControl)
    }

    // ---------------------------------------------------------------- evidence

    @Test
    fun a_fresh_machine_has_no_evidence() {
        assertNull(usage.reason(bridges()))
    }

    @Test
    fun every_piece_of_evidence_counts_on_its_own() {
        fun check(code: String, make: () -> Unit, undo: () -> Unit) {
            make()
            assertEquals(code, usage.reason(bridges()), code)
            undo()
            assertNull(usage.reason(bridges()), "after removing $code")
        }
        check("grants", { grantsFile.writeText("{}") }, { grantsFile.delete() })
        val tomb = ExecutionGrantStore.tombstonePathFor(grantsFile)
        check("grant_tombstones", { tomb.writeText("") }, { tomb.delete() })
        check("run_journal", { runRoot.mkdirs() }, { runRoot.deleteRecursively() })
        check("client_links", { linksFile.writeText("{}") }, { linksFile.delete() })
        check("client_link_secrets", { secretsFile.writeText("{}") }, { secretsFile.delete() })
        check("client_runs", { clientRunsFile.writeText("{}") }, { clientRunsFile.delete() })
        val creds = File(dir, "execution-credentials.json")
        check(
            "execution_credential",
            {
                val pub = b64(E2ECrypto.generateKeyPair().publicRaw)
                ExecutionCredentialStore.save(mapOf("dev-exec-1" to BridgeEntry(pub, BridgeSpec.execution("Studio Mac", "xg_abcdefgh1234"), 0L)), creds)
            },
            { creds.delete() },
        )
    }

    @Test
    fun the_credential_file_alone_is_not_evidence() {
        // BridgeRegistry rewrites execution-credentials.json as `{}` on every bridge/guest/collaborator change
        ExecutionCredentialStore.save(emptyMap(), File(dir, "execution-credentials.json"))
        BridgeStore.save(emptyMap(), File(dir, "bridges.json"))
        assertNull(usage.reason(bridges()))
        // …and a grantId-less execution row is refused by the registry, so it is not a credential either
        val pub = b64(E2ECrypto.generateKeyPair().publicRaw)
        ExecutionCredentialStore.save(
            mapOf("dev-exec-2" to BridgeEntry(pub, BridgeSpec.execution("x", "xg_abcdefgh1234").copy(grantId = null), 0L)),
            File(dir, "execution-credentials.json"),
        )
        assertNull(usage.reason(bridges()))
    }

    @Test
    fun loading_the_planes_on_an_unused_machine_creates_no_evidence(): Unit = runBlocking {
        // what every machine did at attach before this change: build the planes and run the ticker
        core.offerExecution(realInstaller()) { null }
        assertTrue(core.ensureExecution())
        assertNotNull(core.executionPlane)
        delay(300) // the ticker's first pass runs immediately
        assertNull(usage.reason(bridges()), "an empty store / journal / link store must not write themselves")
        assertFalse(runRoot.exists())
    }

    // ---------------------------------------------------------------- core load

    @Test
    fun no_evidence_loads_nothing_and_starts_no_ticker() {
        var installs = 0
        core.offerExecution({ installs++; realInstaller()() }) { null }
        assertEquals(0, installs)
        assertUnloaded()
        assertFalse(runRoot.exists(), "the run journal is not even touched")
    }

    @Test
    fun evidence_loads_the_planes_at_attach() {
        var installs = 0
        core.offerExecution({ installs++; realInstaller()() }) { "grants" }
        assertEquals(1, installs)
        assertNotNull(core.executionPlane)
        assertNotNull(core.router.executionPlane)
        assertNotNull(core.router.executionGuard)
        assertNotNull(core.executionTarget)
        assertNotNull(core.executionGrants)
        assertNotNull(core.executionClient)
        assertNotNull(core.executionControl)
        // already loaded: a later first-use call is a no-op
        assertTrue(core.ensureExecution())
        assertEquals(1, installs)
    }

    @Test
    fun without_a_relay_leg_first_use_is_a_noop() {
        assertFalse(core.ensureExecution(), "nothing registered an installer (LAN-only serve)")
        assertUnloaded()
    }

    @Test
    fun concurrent_first_use_loads_exactly_once() {
        val installs = AtomicInteger()
        core.offerExecution({ installs.incrementAndGet(); Thread.sleep(50); realInstaller()() }) { null }
        val start = CountDownLatch(1)
        val threads = List(16) { Thread { start.await(); core.ensureExecution() }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(1, installs.get())
        assertNotNull(core.executionPlane)
    }

    @Test
    fun a_failed_install_is_retried_on_the_next_use() {
        var attempts = 0
        core.offerExecution({ attempts++; if (attempts == 1) error("disk hiccup") else realInstaller()() }) { null }
        assertTrue(runCatching { core.ensureExecution() }.isFailure)
        assertNull(core.executionPlane)
        assertTrue(core.ensureExecution())
        assertNotNull(core.executionPlane)
        assertEquals(2, attempts)
    }

    // ---------------------------------------------------------------- control API first use

    private val token = "test-token-not-a-secret"

    private fun <T> serving(block: suspend (HttpClient, String) -> T): T = runBlocking {
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing { installExecutionControl(executionControlDepsOf(core), token) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO)
        try {
            block(http, "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX")
        } finally {
            http.close()
            server.stop(0, 0)
        }
    }

    @Test
    fun the_first_authorised_control_call_loads_the_planes() = serving { http, base ->
        var installs = 0
        core.offerExecution({ installs++; realInstaller()() }) { null }
        assertUnloaded()
        // an UNAUTHENTICATED call is turned away before any provider runs — it must not load anything
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/execution/grants").status)
        assertEquals(0, installs)
        assertUnloaded()
        // the first authorised call loads the planes and is then served as if they had always been there
        val res = http.get("$base/execution/grants") { header(LocalControlToken.HEADER, token) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertTrue(res.bodyAsText().contains("\"items\":[]"), res.bodyAsText())
        assertEquals(1, installs)
        assertNotNull(core.executionPlane)
        assertNotNull(core.executionTarget)
        // later calls reuse them
        assertEquals(HttpStatusCode.OK, http.get("$base/execution/grants") { header(LocalControlToken.HEADER, token) }.status)
        assertEquals(1, installs)
    }

    @Test
    fun with_no_relay_leg_the_control_api_answers_as_before() = serving { http, base ->
        val res = http.get("$base/execution/grants") { header(LocalControlToken.HEADER, token) }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText().contains("execution_unavailable"), res.bodyAsText())
        assertUnloaded()
    }
}
