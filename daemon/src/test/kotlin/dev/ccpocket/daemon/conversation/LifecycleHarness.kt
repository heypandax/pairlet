package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the lifecycle tests (design-conversation-lifecycle S0/S1/S3/S7): a scriptable fake
 * backend whose processes are real `sh -c` children, plus a [ProbeGate] for [Conversation.lifecycleProbe].
 *
 * The child speaks a tiny line protocol the fake [parse]s:
 *   `init` / `init:<sid>` / `initm:<model>`  → SessionInit (sid / model null unless given)
 *   `user:<text>`                            → top-level UserReplay
 *   `say:<text>`                             → AssistantText
 *   `result` / `result-err:<text>`           → TurnResult ok / error
 *   `ask:<id>`                               → ControlRequest for Bash
 *   `bg-start` / `bg-done`                   → a background Bash job starts / completes
 */
internal class LifecycleBackend(
    override val kind: AgentKind = AgentKind.CLAUDE,
    override val processMode: AgentProcessMode = AgentProcessMode.LONG_RUNNING,
    override val promptDelivery: AgentPromptDelivery = AgentPromptDelivery.STDIN_REPLAY,
    override val supportsNativeCompact: Boolean = false,
    private val relaunchOnSettings: Boolean = true,
    /** Throws from [attach] for these launch indexes (0-based). */
    private val attachThrowsAt: Set<Int> = emptySet(),
    /** Throws from [onProcessEnded] the first time it runs. */
    private val endThrowsOnce: Boolean = false,
    /** Writes this line to the child's stdin on [interrupt], so a script can react to ■. */
    private val interruptLine: String? = null,
    /** The shell script for launch #index. */
    private val script: (index: Int, spec: AgentSpec) -> String,
) : AgentBackend {
    val specs = CopyOnWriteArrayList<AgentSpec>()
    /** (launch index of the io it was written to, text) for every prompt written. */
    val sends = CopyOnWriteArrayList<Pair<Int, String>>()
    val ios = CopyOnWriteArrayList<AgentIo>()
    /** (askId, allow, launch index of the io that was CURRENT when the decision was written). */
    val responses = CopyOnWriteArrayList<Triple<String, Boolean, Int>>()
    @Volatile var io: AgentIo? = null
    /** Lines whose parse is held until the matching deferred completes; [parseHeld] fires when held. */
    val parseGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val parseHeld = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var endThrown = false

    fun gateParse(line: String): CompletableDeferred<Unit> {
        parseHeld[line] = CompletableDeferred()
        return CompletableDeferred<Unit>().also { parseGates[line] = it }
    }

    override fun processBuilder(spec: AgentSpec): ProcessBuilder {
        specs += spec
        return ProcessBuilder("sh", "-c", script(specs.size - 1, spec))
    }

    private val ioLaunch = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<AgentIo, Int>())

    /** The launch index [io] was attached for, or -1. */
    fun launchOf(io: AgentIo?): Int = io?.let { ioLaunch[it] } ?: -1

    override suspend fun attach(io: AgentIo, spec: AgentSpec) {
        val index = specs.size - 1 // processBuilder for this launch just ran
        if (index in attachThrowsAt) throw IllegalStateException("attach failed (test)")
        ioLaunch[io] = index
        ios += io
        this.io = io
    }

    override suspend fun parse(line: String): List<AgentEvent> {
        parseGates[line]?.let { gate ->
            parseHeld[line]?.complete(Unit)
            gate.await()
        }
        val cwd = specs.lastOrNull()?.workdir?.toString()
        return when {
            line == "init" -> listOf(AgentEvent.SessionInit(null, cwd, null))
            line.startsWith("init:") -> listOf(AgentEvent.SessionInit(line.removePrefix("init:"), cwd, null))
            line.startsWith("initm:") -> listOf(AgentEvent.SessionInit(null, cwd, line.removePrefix("initm:")))
            line.startsWith("user:") -> listOf(AgentEvent.UserReplay(line.removePrefix("user:")))
            line.startsWith("say:") -> listOf(AgentEvent.AssistantText(line.removePrefix("say:")))
            line == "result" -> listOf(AgentEvent.TurnResult("ok", null, false))
            line.startsWith("result-err:") -> listOf(AgentEvent.TurnResult(line.removePrefix("result-err:"), null, true))
            line.startsWith("ask:") -> listOf(
                AgentEvent.ControlRequest(line.removePrefix("ask:"), "Bash", buildJsonObject { put("command", JsonPrimitive("rm -rf build")) }),
            )
            line == "bg-start" -> listOf(
                AgentEvent.AssistantToolUse(
                    "bg1", "Bash",
                    buildJsonObject { put("command", JsonPrimitive("make build")); put("run_in_background", JsonPrimitive(true)) },
                ),
                AgentEvent.BackgroundTaskStarted("task1", "bg1", "make build", "local_bash"),
            )
            line == "bg-done" -> listOf(AgentEvent.BackgroundTaskUpdated("task1", "completed", "bg1"))
            else -> emptyList()
        }
    }

    override suspend fun sendPrompt(text: String, images: List<ImageData>) {
        val current = io
        sends += launchOf(current) to text
        current?.writeLine?.invoke(text.replace('\n', ' '))
    }

    override suspend fun interrupt() {
        interruptLine?.let { line -> io?.writeLine?.invoke(line) }
    }

    override suspend fun respondPermission(
        askId: String, allow: Boolean, remember: Boolean,
        originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
    ) {
        responses += Triple(askId, allow, launchOf(io))
    }

    override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = relaunchOnSettings
    override suspend fun compact(): Boolean = true

    override suspend fun onProcessEnded(sessionId: String?) {
        if (endThrowsOnce && !endThrown) {
            endThrown = true
            throw IllegalStateException("cleanup bug (test)")
        }
    }

    override fun transcriptDir(workdir: String): Path = Path.of(workdir)
    override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
    override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
    override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null

    companion object {
        /** Echo every stdin line as consumed and finish its turn; stay alive (a long-running CLI between turns). */
        const val ECHO_TURNS = "while IFS= read -r line; do printf 'user:%s\\nresult\\n' \"\$line\"; done; sleep 30"

        /** Swallow stdin and stay alive with no output (a CLI mid-turn / starting up). */
        const val SILENT = "exec sleep 30"
    }
}

/** Parks the [Conversation.lifecycleProbe] at [point] for its first [times] hits until [release]. */
internal class ProbeGate(private val point: Conversation.LifecyclePoint, private val times: Int = 1) {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val hits = AtomicInteger()
    val hitCount: Int get() = hits.get()

    suspend fun onProbe(p: Conversation.LifecyclePoint) {
        if (p != point) return
        if (hits.getAndIncrement() >= times) return
        reached.complete(Unit)
        release.await()
    }
}

/** One conversation over [backend] with a recording sink. */
internal class LifecycleHarness(
    val backend: LifecycleBackend,
    convoId: String = "cLife",
    mode: PermissionMode = PermissionMode.DEFAULT,
    origin: String? = null,
    pathScope: ((Path) -> List<String>)? = null,
    continuationGraceMs: Long = Conversation.CONTINUATION_GRACE_MS,
    val workdir: Path = Files.createTempDirectory("ccp-life"),
) {
    val frames = CopyOnWriteArrayList<Frame>()
    val scope = CoroutineScope(Dispatchers.Default)
    val approvals = ApprovalCoordinator(scope)
    val convo = Conversation(
        convoId, workdir, mode, { f -> frames += f }, scope, backend,
        continuationGraceMs = continuationGraceMs,
        origin = origin,
        pathScope = pathScope?.invoke(workdir),
        approvals = approvals,
    )

    inline fun <reified T : Frame> framesOf(): List<T> = frames.filterIsInstance<T>()

    suspend fun await(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; specs=${backend.specs.size} frames=${frames.toList()}", e)
        }
    }

    suspend fun close() {
        runCatching { convo.close() }
        scope.cancel()
    }

    companion object {
        fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
    }
}
