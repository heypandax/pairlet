package dev.ccpocket.daemon.acp

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One REAL ACP agent process (kimi / dsh) driven through its backend exactly the way Conversation drives it —
 * one ordered line channel parsed in arrival order — minus everything else in the daemon. For the LiveITs
 * only: it records every line in both directions with its arrival time, so a test can assert on the wire and
 * report latencies (`session/new` → first line, config write → its answer).
 *
 * Nothing here starts a daemon, binds a port or touches `~/.cc-pocket`.
 */
class AcpLiveHarness(
    val backend: AgentBackend,
    val spec: AgentSpec,
    private val label: String,
    /** The process to drive; defaults to the backend's own launch. A test may substitute one that misbehaves. */
    private val launch: () -> Process = { backend.processBuilder(spec).start() },
) : AutoCloseable {
    data class Line(val nanos: Long, val outbound: Boolean, val text: String) {
        val json: JsonObject? by lazy { runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() }
        val method: String? get() = (json?.get("method") as? JsonPrimitive)?.contentOrNull
        val id: Long? get() = (json?.get("id") as? JsonPrimitive)?.longOrNull
    }

    val lines = CopyOnWriteArrayList<Line>()
    val events = Channel<AgentEvent>(Channel.UNLIMITED)
    val seen = CopyOnWriteArrayList<AgentEvent>()
    private val stderr = StringBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pump = Channel<String>(Channel.UNLIMITED)
    lateinit var process: Process
    var sessionId: String? = null
    val startNanos = System.nanoTime()

    suspend fun start(): AcpLiveHarness {
        process = launch()
        val writer = process.outputStream.bufferedWriter()
        val io = AgentIo(
            writeLine = { line ->
                lines += Line(System.nanoTime(), true, line)
                synchronized(writer) { writer.write(line); writer.write("\n"); writer.flush() }
            },
            emit = {},
            inject = { line -> pump.send(line) },
        )
        scope.launch {
            for (line in pump) backend.parse(line).forEach {
                if (it is AgentEvent.SessionInit) sessionId = it.sessionId
                seen += it
                events.send(it)
            }
        }
        scope.launch {
            process.inputStream.bufferedReader().forEachLine {
                lines += Line(System.nanoTime(), false, it)
                runBlocking { pump.send(it) }
            }
        }
        scope.launch { process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        backend.attach(io, spec)
        return this
    }

    /** The next event of type [T], or null on timeout; every event passed over lands in [skipped]. */
    suspend inline fun <reified T : AgentEvent> await(
        timeoutMs: Long,
        skipped: MutableList<AgentEvent> = ArrayList(),
    ): T? = withTimeoutOrNull(timeoutMs) {
        for (e in events) {
            if (e is T) return@withTimeoutOrNull e
            skipped += e
        }
        null
    }

    data class Turn(val text: String, val result: AgentEvent.TurnResult?, val events: List<AgentEvent>)

    /**
     * Collect one turn up to its [AgentEvent.TurnResult]. Every permission ask is answered by [onAsk]
     * (default: allow once), so a tool call cannot park the turn.
     */
    suspend fun turn(
        timeoutMs: Long = 120_000,
        onAsk: suspend (AgentEvent.ControlRequest) -> Unit = { backend.respondPermission(it.requestId, true, false, it.input, null, null) },
    ): Turn {
        val text = StringBuilder()
        val got = ArrayList<AgentEvent>()
        val result = withTimeoutOrNull(timeoutMs) {
            for (e in events) {
                got += e
                when (e) {
                    is AgentEvent.AssistantText -> text.append(e.text)
                    is AgentEvent.ControlRequest -> onAsk(e)
                    is AgentEvent.TurnResult -> return@withTimeoutOrNull e
                    else -> {}
                }
            }
            null
        }
        return Turn(text.toString(), result, got)
    }

    /** Drain whatever arrives within [ms]. */
    suspend fun drain(ms: Long): List<AgentEvent> {
        val got = ArrayList<AgentEvent>()
        withTimeoutOrNull(ms) { for (e in events) got += e }
        return got
    }

    fun outbound(method: String): List<Line> = lines.filter { it.outbound && it.method == method }

    /** ms from the first outbound [method] to the first inbound line of any kind after it. */
    fun msToFirstLineAfter(method: String): Long? {
        val sent = outbound(method).firstOrNull() ?: return null
        val first = lines.firstOrNull { !it.outbound && it.nanos >= sent.nanos } ?: return null
        return (first.nanos - sent.nanos) / 1_000_000
    }

    /** ms from each outbound [method] request to ITS response (matched by id). */
    fun msToResponses(method: String): List<Pair<Line, Long?>> = outbound(method).map { req ->
        val resp = lines.firstOrNull { !it.outbound && it.method == null && it.id == req.id && it.nanos >= req.nanos }
        req to resp?.let { (it.nanos - req.nanos) / 1_000_000 }
    }

    /** Inbound `session/update` session ids, counted — the evidence for the foreign-session filter. */
    fun updateSessionIds(): Map<String?, Int> = lines.filter { !it.outbound && it.method == "session/update" }
        .groupingBy { ((it.json?.get("params") as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.contentOrNull }
        .eachCount()

    /** Write the recorded wire to `$CC_POCKET_ACP_LIVE_LOG/<label>-<name>.log` (when set). Secrets never reach the
     *  wire here — models are configured in the agent's own config — but `sk-…` is masked anyway. */
    fun dump(name: String) {
        val dir = System.getenv("CC_POCKET_ACP_LIVE_LOG")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs()
        val mask = Regex("sk-[A-Za-z0-9]{6,}")
        File(dir, "$label-$name.log").printWriter().use { w ->
            for (l in lines) {
                w.println("%9.3f %s %s".format((l.nanos - startNanos) / 1e9, if (l.outbound) ">>>" else "<<<", mask.replace(l.text, "sk-***")))
            }
            w.println("---- stderr ----")
            w.println(mask.replace(stderr.toString(), "sk-***"))
        }
    }

    fun stderrText(): String = stderr.toString()

    override fun close() {
        runBlocking { runCatching { backend.onProcessEnded(sessionId) } }
        runCatching { process.outputStream.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        scope.cancel()
        pump.close(); events.close()
    }
}
