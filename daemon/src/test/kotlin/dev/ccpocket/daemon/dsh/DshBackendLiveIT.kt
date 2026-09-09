package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LIVE end-to-end against the REAL `dsh` binary — the one test that proves the whole launch path
 * (argv, stdin wiring, handshake, session open, config write, a real turn) rather than a transcript of
 * it.
 *
 * OFF BY DEFAULT: it spawns Node, needs DeepSeek credentials and spends a (tiny) real inference. Run it
 * deliberately, and always after a dsh upgrade alongside `scripts/probe-dsh-acp.py`:
 *
 * ```
 *   CC_POCKET_DSH_LIVE=1 JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
 *     ./gradlew :daemon:test --tests 'dev.ccpocket.daemon.dsh.DshBackendLiveIT'
 * ```
 */
@EnabledIfEnvironmentVariable(named = "CC_POCKET_DSH_LIVE", matches = "1")
class DshBackendLiveIT {

    @Test
    fun a_real_dsh_session_opens_switches_model_and_answers_a_turn() = runBlocking {
        val workdir = createTempDirectory("cc-pocket-dsh-live")
        val backend = DshBackend(null)
        val spec = AgentSpec(
            workdir = workdir,
            mode = PermissionMode.DEFAULT,
            model = "deepseek-v4-pro", // exercises the opaque-value join before the first turn
            effort = "high",
        )
        val process = backend.processBuilder(spec).start()
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val lines = Channel<String>(Channel.UNLIMITED)

        val writer = process.outputStream.bufferedWriter()
        val io = AgentIo(
            writeLine = { line -> synchronized(writer) { writer.write(line); writer.write("\n"); writer.flush() } },
            emit = {},
            inject = { line -> lines.send(line) },
        )
        // The daemon's pump, in miniature: ONE ordered channel of lines, parsed in arrival order.
        val pump = launch(Dispatchers.IO) {
            for (line in lines) backend.parse(line).forEach { events.send(it) }
        }
        val reader = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { runBlocking { lines.send(it) } }
        }
        val stderr = launch(Dispatchers.IO) { process.errorStream.bufferedReader().forEachLine { } }

        try {
            backend.attach(io, spec)
            val init = awaitEvent<AgentEvent.SessionInit>(events, 120_000)
            assertTrue(init?.sessionId?.isNotBlank() == true, "no session id — is dsh >= 0.1.2-rc.1?")

            backend.sendPrompt("Reply with exactly: hello-from-cc-pocket. Do not use any tool.", emptyList())
            val text = StringBuilder()
            var settled = false
            while (!settled) {
                when (val e = withTimeoutOrNull(300_000) { events.receive() }) {
                    is AgentEvent.AssistantText -> text.append(e.text)
                    is AgentEvent.TurnResult -> {
                        assertTrue(!e.isError, "the turn failed: $text")
                        settled = true
                    }
                    null -> error("the turn never settled; text so far: $text")
                    else -> {}
                }
            }
            assertTrue(text.contains("hello-from-cc-pocket"), "unexpected reply: $text")
            // the model the user asked for is the model dsh reports back, not the one we requested
            assertEquals("deepseek-v4-pro", backend.liveModelForTest())
        } finally {
            reader.cancel(); pump.cancel(); stderr.cancel()
            lines.close(); events.close()
            runCatching { process.destroyForcibly() }
            runCatching { workdir.toFile().deleteRecursively() }
            DshCatalog.clearForTest()
        }
    }

    private suspend inline fun <reified T : AgentEvent> awaitEvent(
        events: Channel<AgentEvent>,
        timeoutMs: Long,
    ): T? = withTimeoutOrNull(timeoutMs) {
        for (e in events) if (e is T) return@withTimeoutOrNull e
        null
    }
}
