package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunOutput
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.executionAgentWire
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #367 G1 end-to-end on the TARGET side: an execution frame arrives on a proven link, a REAL
 * [SessionRegistry] opens a REAL conversation against a scripted backend process, and the answer comes
 * back by cursor. No relay, no Noise, no `claude` — the G0 fixture already proves the link, and this
 * proves what the link is allowed to cause.
 *
 * The refusal half deliberately needs no backend at all: every one of those frames must die before
 * anything is opened.
 */
class RunServiceTest {

    private val root = createTempDirectory("ccp-run-service").toFile()
    private val wsDir = File(root, "ws/app").apply { mkdirs() }
    private val scriptDir = File(root, "script").apply { mkdirs() }
    private var clock = 1_800_000_000_000L

    private val identity = Identity.loadOrCreate(File(root, "identity.json"))
    private val linkKeys = E2ECrypto.generateKeyPair()
    private val linkPub = b64(linkKeys.publicRaw)
    private val store = ExecutionGrantStore.load(File(root, "execution-grants.json"), identity.e2ePubB64)
    private val journal = RunJournal(File(root, "execution-runs")) { clock }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val approvals = ApprovalCoordinator(scope)

    /** Rewritten per test before the first submit; the factory reads it when a conversation is created. */
    private var stages: List<Path> = emptyList()
    private var thenExit: Boolean = false

    private val registry = SessionRegistry(
        scope,
        backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScriptedBackend(stages, thenExit) }),
        approvals = approvals,
    )
    private val service = RunService(store, journal, registry, scope) { clock }

    @AfterTest
    fun cleanup() {
        runBlocking { runCatching { registry.closeAll() } }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun skipOnWindows() = System.getProperty("os.name").lowercase().contains("win")

    // ---------------------------------------------------------------- fixture

    private val initLine = """{"type":"system","subtype":"init","session_id":"s-exec","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private fun resultLine(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","usage":{"input_tokens":1,"output_tokens":1}}"""
    private val askLine =
        """{"type":"control_request","request_id":"ask-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"make test"}}}"""

    /** Same shape as ConversationPushTest's: one script file per prompt, gated on a `read`. */
    private class ScriptedBackend(private val stages: List<Path>, private val thenExit: Boolean) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            val cats = stages.joinToString("; ") { "read go; cat '${it.absolutePathString()}'" }
            return ProcessBuilder("sh", "-c", if (thenExit) cats else "$cats; sleep 30")
        }
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun script(name: String, vararg lines: String): Path =
        scriptDir.toPath().resolve("$name.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun grantRow(
        grantId: String = GRANT,
        state: ExecutionGrantState = ExecutionGrantState.ACTIVE,
        revision: Long = 1,
        ceiling: PermissionMode = PermissionMode.DEFAULT,
        concurrent: Int = 1,
        queued: Int = 4,
        budget: Int = 20,
        expiresAt: Long = clock + 3_600_000,
        deviceId: String = DEVICE,
    ) = ExecutionGrant(
        grantId = grantId,
        revision = revision,
        state = state,
        createdAt = clock - 1_000,
        expiresAt = expiresAt,
        targetAccountId = identity.accountId,
        targetDaemonPub = identity.e2ePubB64,
        targetDaemonFingerprint = ExecutionFingerprint.of(identity.e2ePubB64),
        sourceLabel = "Studio Mac",
        sourceDeviceId = deviceId,
        sourceLinkPub = linkPub,
        sourceLinkFingerprint = ExecutionFingerprint.of(linkPub),
        workspaces = listOf(WorkspaceAlias("app", ExecutionWorkspaces.canonicalRoot(wsDir.path)!!, ExecutionWorkspaces.fileKeyOf(wsDir.path))),
        allowedAgents = listOf(AgentKind.CLAUDE),
        approvalCeiling = ceiling,
        maxConcurrentRuns = concurrent,
        maxQueuedRuns = queued,
        runTimeoutMs = 600_000,
        perGrantRequestBudget = budget,
    )

    private fun install(grant: ExecutionGrant = grantRow()): ExecutionGrant {
        assertIs<ExecutionGrantStore.Write.Ok>(store.insert(grant))
        return grant
    }

    /** [pin] is the static key the TRANSPORT proved for this device — the plane never looks it up itself. */
    private suspend fun send(frame: ToDaemon, deviceId: String = DEVICE, pin: String = linkPub): Frame {
        var out: Frame? = null
        service.handle(deviceId, pin, frame) { out = it }
        return assertNotNull(out, "the run plane must always answer")
    }

    private suspend fun submit(
        grant: ExecutionGrant,
        requestId: String = "rq_1",
        prompt: String = "do the thing",
        alias: String = "app",
        revision: Long = grant.revision,
        mode: PermissionMode? = null,
    ): Frame = send(
        ExecutionRunSubmit(
            requestId = requestId, grantId = grant.grantId, revision = revision,
            workspaceAlias = alias, agent = executionAgentWire(AgentKind.CLAUDE), prompt = prompt, mode = mode,
        ),
    )

    private suspend fun await(runId: String, vararg wanted: ExecutionRunState): ExecutionRun {
        val hit = withTimeoutOrNull(25_000) {
            while (true) {
                journal.byId(runId)?.takeIf { it.state in wanted }?.let { return@withTimeoutOrNull it }
                delay(40)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return assertNotNull(hit, "run $runId stayed at ${journal.byId(runId)?.state} instead of ${wanted.toList()}")
    }

    // ================================================================ happy path

    @Test
    fun `a submitted run opens a scoped session, completes, and its output comes back by cursor`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ok", initLine, resultLine("all done")))
        val grant = install()

        val accepted = assertIs<ExecutionRunAccepted>(submit(grant))
        assertFalse(accepted.duplicate)
        assertEquals("accepted", accepted.state)

        val done = await(accepted.runId, ExecutionRunState.COMPLETED)
        assertEquals("all done", done.output)
        assertNotNull(done.convoId)

        val page = assertIs<ExecutionRunOutput>(send(ExecutionRunResult("rq_r", grant.grantId, accepted.runId)))
        assertEquals("all done", page.text)
        assertTrue(page.done)
        assertNull(page.nextCursor)
        assertFalse(page.truncated)
        assertEquals("completed", page.state)
    }

    @Test
    fun `the session the run opens is NOT owner-created and is scoped to the alias root`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        // a turn that never finishes on its own, so the conversation is still open when we inspect it
        stages = listOf(script("hang", initLine))
        val grant = install()
        val accepted = assertIs<ExecutionRunAccepted>(submit(grant))
        val running = await(accepted.runId, ExecutionRunState.RUNNING)
        val convoId = assertNotNull(running.convoId)
        // origin + pathScope are what make it restricted; ownerCreated=false is what keeps #360 out of it
        assertEquals(false, registry.ownerCreatedOf(convoId))
        assertEquals(ExecutionWorkspaces.canonicalRoot(wsDir.path), registry.workdirOf(convoId)?.toString())
    }

    @Test
    fun `retrying the SAME requestId returns the original run and starts nothing new`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ok", initLine, resultLine("done")))
        val grant = install()
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_retry"))
        await(first.runId, ExecutionRunState.COMPLETED)
        val again = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_retry"))
        assertTrue(again.duplicate)
        assertEquals(first.runId, again.runId)
        assertEquals(1, journal.ofGrant(grant.grantId).size)
    }

    @Test
    fun `the same requestId with a different prompt is run_conflict`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ok", initLine, resultLine("done")))
        val grant = install()
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_x", prompt = "task A"))
        await(first.runId, ExecutionRunState.COMPLETED)
        assertEquals("run_conflict", assertIs<PocketError>(submit(grant, requestId = "rq_x", prompt = "task B")).code)
    }

    // ================================================================ approval

    @Test
    fun `a permission ask parks the run and only the TARGET owner's verdict releases it`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ask", initLine, askLine))
        val grant = install()
        val accepted = assertIs<ExecutionRunAccepted>(submit(grant))
        val parked = await(accepted.runId, ExecutionRunState.WAITING_APPROVAL)
        assertTrue(parked.approvalPending)

        val status = assertIs<dev.ccpocket.protocol.ExecutionRunState>(send(ExecutionRunStatus("rq_s", grant.grantId, accepted.runId)))
        assertEquals("waiting_approval", status.state)
        assertTrue(status.approvalPending, "the source must be able to SEE the block…")

        // …and there is no frame it could answer with: the verdict happens on the target owner's plane
        val convoId = assertNotNull(parked.convoId)
        val ask = assertNotNull(registry.pendingApprovals().firstOrNull { it.ask.convoId == convoId })
        assertTrue(approvals.onVerdict(PermissionVerdict(convoId, ask.ask.askId, Decision.ALLOW)))

        val after = assertIs<dev.ccpocket.protocol.ExecutionRunState>(send(ExecutionRunStatus("rq_s2", grant.grantId, accepted.runId)))
        assertEquals("running", after.state)
        assertFalse(after.approvalPending)
    }

    // ================================================================ cancel

    @Test
    fun `cancel stops a live run and says whether the process actually ended`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("hang", initLine)) // no result: the turn never finishes on its own
        val grant = install()
        val accepted = assertIs<ExecutionRunAccepted>(submit(grant))
        await(accepted.runId, ExecutionRunState.RUNNING)

        val res = assertIs<dev.ccpocket.protocol.ExecutionRunState>(send(ExecutionRunCancel("rq_c", grant.grantId, accepted.runId)))
        assertEquals("cancelled", res.state)
        assertEquals(RunService.PHASE_ENDED, res.phase)
        // repeating it is idempotent and still terminal
        val again = assertIs<dev.ccpocket.protocol.ExecutionRunState>(send(ExecutionRunCancel("rq_c2", grant.grantId, accepted.runId)))
        assertEquals("cancelled", again.state)
    }

    @Test
    fun `cancelling a QUEUED run reports that nothing ever started`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("hang", initLine))
        val grant = install(grantRow(concurrent = 1, queued = 2))
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_a"))
        await(first.runId, ExecutionRunState.RUNNING)
        val second = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_b"))
        assertEquals(ExecutionRunState.ACCEPTED, journal.byId(second.runId)!!.state)

        val res = assertIs<dev.ccpocket.protocol.ExecutionRunState>(send(ExecutionRunCancel("rq_c", grant.grantId, second.runId)))
        assertEquals("cancelled", res.state)
        assertEquals(RunService.PHASE_NOT_STARTED, res.phase)
    }

    // ================================================================ limits

    @Test
    fun `the concurrency ceiling queues the next run and the pump starts it when a slot frees`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("hang", initLine))
        val grant = install(grantRow(concurrent = 1, queued = 2))
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_a"))
        await(first.runId, ExecutionRunState.RUNNING)
        val second = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_b"))
        assertEquals(ExecutionRunState.ACCEPTED, journal.byId(second.runId)!!.state)

        send(ExecutionRunCancel("rq_c", grant.grantId, first.runId))
        await(second.runId, ExecutionRunState.RUNNING, ExecutionRunState.STARTING)
    }

    @Test
    fun `a full queue is refused rather than silently dropped`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("hang", initLine))
        val grant = install(grantRow(concurrent = 1, queued = 0))
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_a"))
        await(first.runId, ExecutionRunState.RUNNING)
        assertEquals("run_queue_full", assertIs<PocketError>(submit(grant, requestId = "rq_b")).code)
    }

    @Test
    fun `an exhausted request budget refuses new work`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ok", initLine, resultLine("done")))
        val grant = install(grantRow(budget = 1))
        val first = assertIs<ExecutionRunAccepted>(submit(grant, requestId = "rq_a"))
        await(first.runId, ExecutionRunState.COMPLETED)
        assertEquals("budget_exhausted", assertIs<PocketError>(submit(grant, requestId = "rq_b")).code)
    }

    // ================================================================ refusals (no backend involved)

    @Test
    fun `a revoked grant refuses every run frame`() = runBlocking {
        val grant = install()
        assertIs<ExecutionGrantStore.Write.Ok>(store.revoke(grant.grantId, "owner_revoked", clock))
        assertEquals("grant_revoked", assertIs<PocketError>(submit(grant)).code)
        assertEquals("grant_revoked", assertIs<PocketError>(send(ExecutionGrantQuery(grant.grantId))).code)
        assertEquals("run_not_found", assertIs<PocketError>(send(ExecutionRunStatus("rq", grant.grantId, "xr_whatever"))).code)
    }

    @Test
    fun `an expired grant refuses submit`() = runBlocking {
        val grant = install(grantRow(expiresAt = clock + 1_000))
        clock += 5_000
        assertEquals("grant_expired", assertIs<PocketError>(submit(grant)).code)
    }

    @Test
    fun `a stale revision refuses submit`() = runBlocking {
        val grant = install(grantRow(revision = 3))
        assertEquals("revision_changed", assertIs<PocketError>(submit(grant, revision = 2)).code)
    }

    @Test
    fun `an unknown workspace alias, a disallowed agent and a bad request id are all refused`() = runBlocking {
        val grant = install()
        assertEquals("workspace_not_allowed", assertIs<PocketError>(submit(grant, alias = "secrets")).code)
        assertEquals(
            "agent_not_allowed",
            assertIs<PocketError>(
                send(
                    ExecutionRunSubmit("rq_2", grant.grantId, grant.revision, "app", executionAgentWire(AgentKind.CODEX), "x"),
                ),
            ).code,
        )
        assertEquals(
            "request_id_invalid",
            assertIs<PocketError>(send(ExecutionRunSubmit("../nope", grant.grantId, grant.revision, "app", executionAgentWire(AgentKind.CLAUDE), "x"))).code,
        )
    }

    @Test
    fun `a device with no grant of its own gets nothing`() = runBlocking {
        val grant = install()
        val stranger = "dev_not_bound"
        assertEquals("grant_unknown", assertIs<PocketError>(send(ExecutionGrantQuery(grant.grantId), deviceId = stranger)).code)
        val submitted = send(
            ExecutionRunSubmit("rq_z", grant.grantId, grant.revision, "app", executionAgentWire(AgentKind.CLAUDE), "x"),
            deviceId = stranger,
        )
        assertEquals("grant_unknown", assertIs<PocketError>(submitted).code)
        assertTrue(journal.ofGrant(grant.grantId).isEmpty(), "a stranger's submit must not reach the journal at all")
    }

    @Test
    fun `a run of ANOTHER grant is not readable through this link`() = runBlocking {
        val mine = install()
        val theirs = install(grantRow(grantId = OTHER_GRANT, deviceId = "dev_other"))
        val row = assertIs<RunJournal.Accept.Ok>(
            journal.accept(
                theirs.grantId, "rq_theirs", 1, "app", AgentKind.CLAUDE, PermissionMode.DEFAULT, null,
                RunJournal.payloadHash(theirs.grantId, "rq_theirs", "app", AgentKind.CLAUDE, PermissionMode.DEFAULT, null, "x"), 10,
            ),
        ).run
        assertEquals("run_not_found", assertIs<PocketError>(send(ExecutionRunStatus("rq", mine.grantId, row.runId))).code)
    }

    @Test
    fun `the requested mode is clamped to the grant ceiling and BYPASS is never reachable`() = runBlocking {
        if (skipOnWindows()) return@runBlocking
        stages = listOf(script("ok", initLine, resultLine("done")))
        val grant = install(grantRow(ceiling = PermissionMode.PLAN))
        val accepted = assertIs<ExecutionRunAccepted>(submit(grant, mode = PermissionMode.BYPASS_PERMISSIONS))
        val run = await(accepted.runId, ExecutionRunState.RUNNING, ExecutionRunState.COMPLETED)
        assertEquals(PermissionMode.PLAN, run.mode)
    }

    @Test
    fun `an unlisted frame type is refused by the plane itself, not merely by the caps whitelist`() = runBlocking {
        install()
        val refused = assertIs<PocketError>(send(dev.ccpocket.protocol.CancelTurn("c1")))
        assertEquals("execution_forbidden", refused.code)
    }

    @Test
    fun `a grant query on an ACTIVE grant discloses scope, and only scope`() = runBlocking {
        val grant = install()
        val info = assertIs<ExecutionGrantInfo>(send(ExecutionGrantQuery(grant.grantId)))
        assertEquals(listOf("app"), info.workspaceAliases)
        assertEquals(listOf("claude"), info.agents)
        assertEquals("default", info.approvalCeiling)
        // nothing in the reply can name the root on disk
        assertFalse(dev.ccpocket.protocol.PocketJson.encodeToString(ExecutionGrantInfo.serializer(), info).contains(wsDir.path))
    }

    private companion object {
        const val GRANT = "xg_RUNSERVICE001"
        const val OTHER_GRANT = "xg_RUNSERVICE002"
        const val DEVICE = "dev_source_1"
    }
}
