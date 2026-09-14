package dev.ccpocket.daemon.control

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.execution.ExecutionFingerprint
import dev.ccpocket.daemon.execution.ExecutionGrant
import dev.ccpocket.daemon.execution.ExecutionGrantState
import dev.ccpocket.daemon.execution.ExecutionGrantStore
import dev.ccpocket.daemon.execution.ExecutionRunPlane
import dev.ccpocket.daemon.execution.ExecutionWorkspaces
import dev.ccpocket.daemon.execution.RunJournal
import dev.ccpocket.daemon.execution.RunService
import dev.ccpocket.daemon.execution.WorkspaceAlias
import dev.ccpocket.daemon.execution.client.ExecutionClient
import dev.ccpocket.daemon.execution.client.ExecutionClientStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.review.PeerChannel
import dev.ccpocket.daemon.review.PeerKeys
import dev.ccpocket.daemon.review.PeerLink
import dev.ccpocket.daemon.review.PeerLinkSecret
import dev.ccpocket.daemon.review.PeerLinkStore
import dev.ccpocket.daemon.review.PeerSession
import dev.ccpocket.daemon.review.PeerTransport
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * #367 G1 — `/v1/local/execution/…`. Three classes of test, mirroring [LocalControlRoutesTest]:
 *  - the GATES: this surface must be exactly as unreachable from a web page as the review one;
 *  - REDACTION: a workspace ROOT, a ticket hash and a device id all exist on a grant row and none of them
 *    is the caller's business — only aliases and fingerprints cross this boundary;
 *  - the happy path from the LOCAL CALLER's side: run → status → result over the real client and the
 *    real run plane.
 *
 * The transport double below is NOT a security shortcut: G0's fixture already proves the Noise/ticket
 * layer end to end. What is under test here is everything ABOVE it — the client's request/reply
 * correlation, its mirror, and the HTTP surface an Agent actually calls.
 */
class ExecutionControlRoutesTest {

    private val token = "test-token-not-a-secret"

    /** Hands the source client's frames straight to a real [ExecutionRunPlane]. No relay, no Noise. */
    private class DirectPlaneTransport(
        private val deviceId: String,
        /** the static key the real transport would have PROVEN before calling the plane */
        private val linkPubB64: String,
        private val plane: () -> ExecutionRunPlane,
    ) : PeerTransport {
        override fun generateKeys(): PeerKeys {
            val kp = E2ECrypto.generateKeyPair()
            return PeerKeys(b64(kp.privateRaw), b64(kp.publicRaw))
        }
        override suspend fun redeem(relay: String, ticket: String, devicePubB64: String): PairCredential? = null
        override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
            val replies = ArrayList<Frame>()
            val channel = PeerChannel { frame: ToDaemon -> plane().handle(deviceId, linkPubB64, frame) { replies += it } }
            session.onOpen(channel)
            replies.forEach { session.onFrame(channel, it) }
        }
    }

    private class ScriptedBackend(private val stage: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        override fun processBuilder(spec: AgentSpec) =
            ProcessBuilder("sh", "-c", "read go; cat '${stage.absolutePathString()}'; sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: kotlinx.serialization.json.JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private class Fixture(val scope: CoroutineScope, val dir: File) {
        val clock = 1_800_000_000_000L
        val ws = File(dir, "ws/app").apply { mkdirs() }
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val linkPub = b64(E2ECrypto.generateKeyPair().publicRaw)
        val store = ExecutionGrantStore.load(File(dir, "execution-grants.json"), identity.e2ePubB64)
        val journal = RunJournal(File(dir, "execution-runs")) { clock }
        val stage = dir.toPath().resolve("stage.jsonl").apply {
            writeText(
                """{"type":"system","subtype":"init","session_id":"s-1","cwd":"/tmp","model":"m"}""" + "\n" +
                    """{"type":"result","subtype":"success","is_error":false,"result":"remote answer","usage":{"input_tokens":1,"output_tokens":1}}""" + "\n",
            )
        }
        val registry = SessionRegistry(scope, mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScriptedBackend(stage) }))
        val plane = RunService(store, journal, registry, scope) { clock }
        val links = PeerLinkStore.load(File(dir, "execution-links.json"), File(dir, "execution-link-secrets.json"))
        val client = ExecutionClient(
            DirectPlaneTransport(DEVICE, linkPub) { plane }, links, ExecutionClientStore.load(File(dir, "execution-client-runs.json")),
        ) { clock }

        val grant = ExecutionGrant(
            grantId = GRANT, revision = 1, state = ExecutionGrantState.ACTIVE,
            createdAt = clock - 1_000, expiresAt = clock + 3_600_000,
            targetAccountId = identity.accountId, targetDaemonPub = identity.e2ePubB64,
            targetDaemonFingerprint = ExecutionFingerprint.of(identity.e2ePubB64),
            sourceLabel = "Studio Mac", sourceDeviceId = DEVICE, sourceLinkPub = linkPub,
            sourceLinkFingerprint = ExecutionFingerprint.of(linkPub),
            workspaces = listOf(WorkspaceAlias("app", ExecutionWorkspaces.canonicalRoot(ws.path)!!, ExecutionWorkspaces.fileKeyOf(ws.path))),
            allowedAgents = listOf(AgentKind.CLAUDE), approvalCeiling = PermissionMode.DEFAULT,
            maxConcurrentRuns = 1, maxQueuedRuns = 4, runTimeoutMs = 600_000, perGrantRequestBudget = 20,
        )

        init {
            store.insert(grant)
            // the source's local address book: one link whose id IS the grant id (as ExecutionSource.join writes it)
            links.put(
                PeerLink(
                    id = GRANT, label = "Build Box", relay = "wss://relay.test",
                    peerAccountId = identity.accountId, peerDaemonPub = identity.e2ePubB64,
                    deviceId = DEVICE, fingerprint = ExecutionFingerprint.of(identity.e2ePubB64), joinedAt = clock,
                ),
                PeerLinkSecret(GRANT, "cred", "priv", linkPub, ticket = null),
            )
        }

        val deps = ExecutionControlDeps(target = { null }, grants = { store }, client = { client })
    }

    private fun <T> serving(block: suspend (Fixture, HttpClient, String) -> T): T = runBlocking {
        val dir = createTempDirectory("ccp-exec-routes").toFile()
        val scope = CoroutineScope(Dispatchers.Default)
        val f = Fixture(scope, dir)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing { installExecutionControl(f.deps, token) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(f, client, "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX")
        } finally {
            runCatching { f.registry.closeAll() }
            client.close()
            server.stop(0, 0)
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.authGet(url: String) = get(url) { header(LocalControlToken.HEADER, token) }
    private suspend fun HttpClient.authPost(url: String, body: String): HttpResponse = post(url) {
        header(LocalControlToken.HEADER, token)
        header("Content-Type", "application/json")
        setBody(body)
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    // ---------------------------------------------------------------- gates

    @Test
    fun `every execution route enforces the same three gates as the review routes`() = serving { _, http, base ->
        // no token
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/execution/targets").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/execution/grants").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            http.post("$base/execution/run") { header("Content-Type", "application/json"); setBody("{}") }.status,
        )
        // a browser Origin, even WITH the token
        val origin = http.get("$base/execution/targets") {
            header(LocalControlToken.HEADER, token)
            header("Origin", "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, origin.status)
        assertTrue(origin.bodyAsText().contains("forbidden_origin"))
        // a form-shaped POST (the CSRF-able one)
        val form = http.post("$base/execution/run") {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", "text/plain")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, form.status)
        // DELETE is not a hole either
        assertEquals(HttpStatusCode.Unauthorized, http.delete("$base/execution/grants/$GRANT").status)
    }

    // ---------------------------------------------------------------- owner plane

    @Test
    fun `the grant list shows scope and NOTHING that could impersonate the link`() = serving { f, http, base ->
        val res = http.authGet("$base/execution/grants")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        val parsed = PocketJson.decodeFromString(LocalExecGrantsRes.serializer(), body)
        val row = parsed.items.single()
        assertEquals(GRANT, row.grantId)
        assertEquals(listOf("app"), row.workspaces)
        assertEquals("default", row.approvalCeiling)
        assertEquals("active", row.state)
        // the ROOT never leaves the target machine — the source only ever names the alias
        assertFalse(body.contains(f.ws.canonicalPath), "a workspace root must not appear in a local API reply")
        assertFalse(body.contains(DEVICE), "the relay device id is not the caller's business")
        assertFalse(body.contains(f.linkPub), "no key material may appear")
    }

    @Test
    fun `approving needs the relay leg and says so instead of half-creating a grant`() = serving { f, http, base ->
        val res = http.authPost(
            "$base/execution/grants",
            """{"label":"Studio Mac","workspaces":{"app":"${f.ws.canonicalPath}"}}""",
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText().contains("relay_offline"))
        assertEquals(1, f.store.all().size, "a refused approval must not leave a row behind")
    }

    @Test
    fun `a bad ceiling or agent name is refused before anything is minted`() = serving { f, http, base ->
        val ceiling = http.authPost(
            "$base/execution/grants",
            """{"label":"x","workspaces":{"app":"${f.ws.canonicalPath}"},"approvalCeiling":"bypassPermissionsPlease"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, ceiling.status)
        assertTrue(ceiling.bodyAsText().contains("ceiling_invalid"))
        val agent = http.authPost(
            "$base/execution/grants",
            """{"label":"x","workspaces":{"app":"${f.ws.canonicalPath}"},"agents":["definitely-not-a-backend"]}""",
        )
        assertEquals(HttpStatusCode.BadRequest, agent.status)
        assertTrue(agent.bodyAsText().contains("agent_invalid"))
    }

    // ---------------------------------------------------------------- source plane

    @Test
    fun `targets lists only ACTIVE grants, by alias`() = serving { _, http, base ->
        val res = http.authGet("$base/execution/targets")
        assertEquals(HttpStatusCode.OK, res.status)
        val parsed = PocketJson.decodeFromString(LocalExecTargetsRes.serializer(), res.bodyAsText())
        val t = parsed.items.single()
        assertEquals(GRANT, t.grantId)
        assertEquals(listOf("app"), t.workspaces)
        assertEquals("Build Box", t.label)
    }

    @Test
    fun `run, status and result carry one task end to end`() = serving { f, http, base ->
        if (isWindows()) return@serving
        val run = http.authPost("$base/execution/run", """{"target":"$GRANT","workspace":"app","agent":"claude","prompt":"summarise the repo"}""")
        assertEquals(HttpStatusCode.OK, run.status)
        val accepted = PocketJson.decodeFromString(LocalExecRunRes.serializer(), run.bodyAsText())
        assertFalse(accepted.duplicate)
        assertTrue(accepted.runId.startsWith("xr_"))

        // poll status the way a caller would
        var state = ""
        repeat(400) {
            val s = PocketJson.decodeFromString(LocalExecStatusRes.serializer(), http.authGet("$base/execution/runs/${accepted.runId}").bodyAsText())
            state = s.state
            if (state == "completed" || state == "failed") return@repeat
            Thread.sleep(50)
        }
        assertEquals("completed", state)

        val result = PocketJson.decodeFromString(
            LocalExecResultRes.serializer(),
            http.authGet("$base/execution/runs/${accepted.runId}/result").bodyAsText(),
        )
        assertEquals("remote answer", result.text)
        assertTrue(result.done)

        // a retry with the SAME requestId must return the same run
        val retry = PocketJson.decodeFromString(
            LocalExecRunRes.serializer(),
            http.authPost(
                "$base/execution/run",
                """{"target":"$GRANT","workspace":"app","agent":"claude","prompt":"summarise the repo","requestId":"${accepted.requestId}"}""",
            ).bodyAsText(),
        )
        assertTrue(retry.duplicate)
        assertEquals(accepted.runId, retry.runId)
        assertEquals(1, f.journal.ofGrant(GRANT).size)
    }

    @Test
    fun `a workspace the grant does not name is refused with the target's own code`() = serving { _, http, base ->
        val res = http.authPost("$base/execution/run", """{"target":"$GRANT","workspace":"secrets","agent":"claude","prompt":"x"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue(res.bodyAsText().contains("workspace_not_allowed"))
    }

    @Test
    fun `an unknown run id is a clean 404, not a stack trace`() = serving { _, http, base ->
        val res = http.authGet("$base/execution/runs/xr_nope")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue(res.bodyAsText().contains("run_unknown"))
    }

    @Test
    fun `joining refuses an invite that is not an execution invite`() = serving { _, http, base ->
        val res = http.authPost("$base/execution/join", """{"invite":"ccpocket://collab#abc","targetFingerprint":"abcd-efgh"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("invite_invalid"))
    }

    private companion object {
        const val GRANT = "xg_ROUTESGRANT01"
        const val DEVICE = "dev_source_routes"
    }
}
