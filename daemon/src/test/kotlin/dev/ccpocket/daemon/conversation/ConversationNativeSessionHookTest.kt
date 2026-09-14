package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.session.NativeSessionHook
import dev.ccpocket.daemon.session.NativeSessionReport
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #360: the Conversation's SessionInit choke point is where BOTH Claude and Codex report a trusted native id.
 * These pin what reaches the managed-list hook: a new session once, a branch with its parent, nothing for an
 * in-place resume, a repeated init, or a process that died before its id arrived — a report from a replaced process
 * generation that no longer claims to be current — and the owner fact the registry fixed at open (security M1).
 */
class ConversationNativeSessionHookTest {

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** Each launch plays the next script; `init:<id>` lines become SessionInit. Gated on the prompt (`read go`). */
    private class ScriptBackend(override val kind: AgentKind, private val scripts: List<Path>) : AgentBackend {
        private var launches = 0
        private var io: AgentIo? = null
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            val script = scripts[minOf(launches++, scripts.size - 1)]
            return ProcessBuilder("sh", "-c", "read go; cat '${script.absolutePathString()}'; if grep -q '^exit' '${script.absolutePathString()}'; then exit 0; fi; sleep 30")
        }
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = line.trim().let {
            if (it.startsWith("init:")) listOf(AgentEvent.SessionInit(it.removePrefix("init:"), "/tmp", model = null)) else listOf(AgentEvent.Ignored(line))
        }
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

    private fun script(vararg lines: String): Path =
        Files.createTempDirectory("ccp-hook-fx").resolve("stream.txt").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun harness(
        agent: AgentKind,
        scripts: List<Path>,
        resumeId: String? = null,
        owner: Boolean = true,
        body: suspend (Conversation, CopyOnWriteArrayList<NativeSessionReport>) -> Unit,
    ) = runBlocking {
        val reports = CopyOnWriteArrayList<NativeSessionReport>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cHook", initialWorkdir = Files.createTempDirectory("ccp-hook"),
            initialMode = PermissionMode.DEFAULT, initialSink = { }, parentScope = scope,
            backend = ScriptBackend(agent, scripts),
        )
        convo.nativeSessionHookProvider = { NativeSessionHook { reports += it } }
        convo.ownerCreated = owner
        try {
            convo.open(resumeId = resumeId, model = null)
            body(convo, reports)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) =
        withTimeout(10_000) { while (!cond()) delay(20) }.also { assertTrue(cond(), what) }

    @Test
    fun a_new_session_reports_once_with_no_parent_for_claude_and_codex() {
        if (isWindows()) return
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            harness(agent, listOf(script("init:sid-new", "init:sid-new", "tick"))) { convo, reports ->
                convo.sendPrompt("go")
                waitFor("$agent id") { convo.sessionId == "sid-new" }
                delay(300)
                assertEquals(1, reports.size, "$agent: ${reports.map { it.sessionId }}")
                val r = reports.single()
                assertEquals(agent, r.agent)
                assertEquals("sid-new", r.sessionId)
                assertNull(r.parentSessionId)
                assertTrue(r.isCurrent())
                assertTrue(r.ownerCreated)
            }
        }
    }

    @Test
    fun a_restricted_conversation_reports_its_owner_fact_as_false() {
        if (isWindows()) return
        harness(AgentKind.CLAUDE, listOf(script("init:guest-made")), owner = false) { convo, reports ->
            convo.sendPrompt("go")
            waitFor("report") { reports.isNotEmpty() }
            assertFalse(reports.single().ownerCreated, "the service drops it; the report never claims owner")
        }
    }

    @Test
    fun an_in_place_resume_reports_nothing_and_a_branch_reports_its_parent() {
        if (isWindows()) return
        harness(AgentKind.CODEX, listOf(script("init:resumed")), resumeId = "resumed") { convo, reports ->
            convo.sendPrompt("go")
            waitFor("resumed id") { convo.sessionId == "resumed" }
            delay(300)
            assertTrue(reports.isEmpty(), "same id as the resume anchor: ${reports.map { it.sessionId }}")
        }
        harness(AgentKind.CLAUDE, listOf(script("init:branch")), resumeId = "origin") { convo, reports ->
            convo.sendPrompt("go")
            waitFor("branch report") { reports.isNotEmpty() }
            assertEquals("branch", reports.single().sessionId)
            assertEquals("origin", reports.single().parentSessionId)
        }
    }

    @Test
    fun a_process_that_exits_before_its_id_arrives_reports_nothing() {
        if (isWindows()) return
        harness(AgentKind.CLAUDE, listOf(script("noise", "exit"))) { convo, reports ->
            convo.sendPrompt("go")
            delay(1_000)
            assertNull(convo.sessionId)
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun a_report_from_a_replaced_process_generation_is_no_longer_current() {
        if (isWindows()) return
        val dir2 = Files.createTempDirectory("ccp-hook-switch")
        harness(AgentKind.CODEX, listOf(script("init:first"), script("init:second"))) { convo, reports ->
            convo.sendPrompt("go")
            waitFor("first report") { reports.size == 1 }
            val first = reports.single()
            assertTrue(first.isCurrent())
            convo.switchDirectory(dir2)
            convo.sendPrompt("go")
            waitFor("second report") { reports.size == 2 }
            assertFalse(first.isCurrent(), "a late processing of the first init must not register")
            val second = reports[1]
            assertTrue(second.isCurrent())
            assertEquals("second", second.sessionId)
            assertNull(second.parentSessionId)
            assertEquals(dir2.toString(), second.workdir)
        }
    }

    /** Security review R3: the owner fact belongs to the open that CREATED the conversation. An owner that later
     *  hot-reattaches to a live session a restricted credential started must not raise it to true. */
    @Test
    fun an_owner_hot_reattaching_to_a_restricted_session_never_raises_its_owner_fact() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = SessionRegistry(
            scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScriptBackend(AgentKind.CLAUDE, listOf(script("noise"))) }),
        )
        try {
            val wd = Files.createTempDirectory("ccp-owner-reattach").toString()
            val resume = "0b9a0003-aaaa-bbbb-cccc-000000000003"
            val guestConvo = registry.open(
                OpenSession(wd, resumeId = resume, agent = AgentKind.CLAUDE), { }, origin = "guest", pathScope = listOf(wd),
            )
            assertTrue(guestConvo.isNotEmpty())
            assertEquals(false, registry.ownerCreatedOf(guestConvo))

            val ownerConvo = registry.open(OpenSession(wd, resumeId = resume, agent = AgentKind.CLAUDE), { })
            assertEquals(guestConvo, ownerConvo, "precondition: the owner hot-reattached to the SAME live conversation")
            assertEquals(false, registry.ownerCreatedOf(ownerConvo), "reattaching attaches a view; it never re-evaluates the owner fact")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_registry_fixes_the_three_way_owner_fact_at_open() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = SessionRegistry(
            scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScriptBackend(AgentKind.CLAUDE, listOf(script("noise"))) }),
        )
        try {
            val wd = Files.createTempDirectory("ccp-owner-fact").toString()
            suspend fun openedAs(origin: String? = null, pathScope: List<String>? = null, access: HandoffAccess? = null): Boolean? {
                val id = registry.open(OpenSession(wd, agent = AgentKind.CLAUDE), { }, origin = origin, pathScope = pathScope, handoffAccess = access)
                assertTrue(id.isNotEmpty())
                return registry.ownerCreatedOf(id)
            }
            assertEquals(true, openedAs(), "owner")
            assertEquals(false, openedAs(origin = "feishu-bot"), "bridge")
            assertEquals(false, openedAs(origin = "guest", pathScope = listOf(wd)), "guest")
            assertEquals(false, openedAs(pathScope = listOf(wd)), "guest scope alone")
            assertEquals(false, openedAs(pathScope = listOf(wd), access = HandoffAccess.REVIEW_READ_ONLY), "collaborator")
            assertEquals(false, openedAs(access = HandoffAccess.REVIEW_READ_ONLY), "collaborator grant alone")
        } finally {
            scope.cancel()
        }
    }
}
