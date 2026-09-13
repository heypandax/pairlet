package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.opencode.OpenCodeStreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationOpenCodeOneShotTest {
    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** [shell] overrides the per-launch shell command (index, stream file) — lets a test emit the first
     *  line then stall (long-turn shape) or hang with zero output (startup-hang shape). Default: cat. */
    private class OneShotBackend(
        private val scripts: List<List<String>>,
        private val shell: ((Int, Path) -> String)? = null,
    ) : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        override val kind = AgentKind.OPENCODE
        override val processMode = AgentProcessMode.ONE_SHOT_TURN
        override val promptDelivery = AgentPromptDelivery.INITIAL_ARG_ONE_SHOT

        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            val idx = minOf(specs.size, scripts.size) - 1
            val lines = scripts[idx]
            val f = Files.createTempDirectory("ccp-opencode-one-shot").resolve("stream.jsonl")
                .apply { writeText(lines.joinToString("\n") + "\n") }
            val cmd = shell?.invoke(idx, f) ?: "cat '${f.absolutePathString()}'"
            return ProcessBuilder("sh", "-c", cmd)
        }

        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = OpenCodeStreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
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

    /** Codex-shaped one-shot: prompt travels over stdin, a user replay is its receipt, and the backend
     * asks Conversation to close the process after a stable result. Each child intentionally ignores any
     * second stdin line so the clean-exit ledger must re-inject it into the next launch. */
    private class StdinOneShotBackend(private val afterEof: String = "") : AgentBackend {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        val sends = CopyOnWriteArrayList<String>()
        @Volatile private var io: AgentIo? = null
        override val kind = AgentKind.CODEX
        override val processMode = AgentProcessMode.ONE_SHOT_TURN
        override val promptDelivery = AgentPromptDelivery.STDIN_REPLAY

        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs += spec
            val sid = spec.resumeId ?: "codex-stdin-1"
            val script = """
                IFS= read -r line
                printf 'session:%s\n' '$sid'
                printf 'user:%s\n' "${'$'}line"
                sleep 0.3
                printf 'result\n'
                while IFS= read -r ignored; do :; done
                $afterEof
            """.trimIndent()
            return ProcessBuilder("sh", "-c", script)
        }

        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = when {
            line.startsWith("session:") -> listOf(AgentEvent.SessionInit(line.removePrefix("session:"), specCwd(), model = null))
            line.startsWith("user:") -> listOf(AgentEvent.UserReplay(line.removePrefix("user:")))
            line == "result" -> {
                io?.requestProcessExit?.invoke()
                listOf(AgentEvent.TurnResult("ok", usage = null, isError = false))
            }
            else -> emptyList()
        }
        private fun specCwd() = specs.lastOrNull()?.workdir?.toString().orEmpty()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {
            sends += text
            io?.writeLine?.invoke(text)
        }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /** Real stdin children with parse gates: B is staged before A's late ask, and each ask is fully
     * resolved before the next replay. Only the first child's actual EOF/TERM exit varies. */
    private class GrantBoundaryOneShotBackend(private val afterEof: String) : AgentBackend by StdinOneShotBackend() {
        val specs = CopyOnWriteArrayList<AgentSpec>()
        val sends = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val releaseLateA = CompletableDeferred<Unit>()
        val releaseShutdown = CompletableDeferred<Unit>()
        val releaseReplayB = CompletableDeferred<Unit>()
        @Volatile private var io: AgentIo? = null

        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs += spec
            val events = if (specs.size == 1) {
                "printf '%s\\n' result-a late-a shutdown-a"
            } else {
                // A non-matching replay must not activate B's staged grant either.
                "printf '%s\\n' user:unrelated before-b; printf 'user:%s\\n' \"${'$'}line\"; printf '%s\\n' control-b"
            }
            val ending = if (specs.size == 1) afterEof else "exit 0"
            return ProcessBuilder("sh", "-c", """
                IFS= read -r line
                printf '%s\n' session:grant-session
                ${if (specs.size == 1) "printf 'user:%s\\n' \"${'$'}line\"" else ""}
                $events
                while IFS= read -r ignored; do :; done
                $ending
            """.trimIndent())
        }

        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = when {
            line == "session:grant-session" -> listOf(AgentEvent.SessionInit("grant-session", specs.last().workdir.toString(), null))
            line == "user:request B" -> {
                releaseReplayB.await()
                listOf(AgentEvent.UserReplay("request B"))
            }
            line.startsWith("user:") -> listOf(AgentEvent.UserReplay(line.removePrefix("user:")))
            line == "result-a" -> listOf(AgentEvent.TurnResult("done A", null, false))
            line == "late-a" -> {
                releaseLateA.await()
                listOf(AgentEvent.ControlRequest(line, "mcp__remote__do", buildJsonObject {}))
            }
            line == "shutdown-a" -> {
                releaseShutdown.await()
                io?.requestProcessExit?.invoke()
                emptyList()
            }
            line == "before-b" || line == "control-b" -> listOf(AgentEvent.ControlRequest(line, "mcp__remote__do", buildJsonObject {}))
            else -> emptyList()
        }
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {
            sends += text
            io?.writeLine?.invoke(text)
        }
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) { responses += askId to allow }
    }

    private fun okTurn(sessionId: String, text: String = "ok") = listOf(
        """{"type":"step_start","sessionID":"$sessionId","part":{"sessionID":"$sessionId","type":"step-start"}}""",
        """{"type":"text","sessionID":"$sessionId","part":{"type":"text","text":"$text"}}""",
        """{"type":"step_finish","sessionID":"$sessionId","part":{"reason":"stop","type":"step-finish","tokens":{"input":1,"output":1}}}""",
    )

    private fun startedOnly(sessionId: String) = listOf(
        """{"type":"step_start","sessionID":"$sessionId","part":{"sessionID":"$sessionId","type":"step-start"}}""",
    )

    private suspend fun await(cond: () -> Boolean) {
        withTimeout(8_000) { while (!cond()) delay(20) }
    }

    @Test
    fun clean_one_shot_exit_after_turn_result_is_not_process_exited() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is TurnDone } }
            delay(300)
            assertFalse(frames.any { it is PocketError && it.code == "process_exited" }, frames.toString())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p1" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun next_prompt_relaunches_with_real_opencode_session_id() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1"), okTurn("ses_open_1", "again")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.count { it is TurnDone } >= 1 }
            delay(300)
            convo.sendPrompt("second", promptId = "p2")
            await { frames.count { it is TurnDone } >= 2 }
            assertEquals(null, backend.specs[0].resumeId)
            assertEquals("ses_open_1", backend.specs[1].resumeId)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun completed_one_shot_prompt_id_retry_is_reacked_without_rerun() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is TurnDone } }
            delay(300)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.count { it is PromptAck && it.promptId == "p1" } >= 2 }
            assertEquals(1, backend.specs.size)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    /** Watchdog regression (review P0): a turn STREAMING past the startup window must never be killed —
     *  the guard is startup-only (zero stdout), not a turn-length cap. */
    @Test
    fun long_turn_streaming_past_watchdog_window_is_not_killed() = runBlocking {
        if (win()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        try {
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            // first line (step_start) immediately, the rest well after the 300ms watchdog window
            val backend = OneShotBackend(listOf(okTurn("ses_open_1"))) { _, f ->
                "head -n 1 '${f.absolutePathString()}'; sleep 1.2; tail -n +2 '${f.absolutePathString()}'"
            }
            val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
            try {
                convo.open(resumeId = null, model = null)
                convo.sendPrompt("hello", promptId = "p1")
                await { frames.any { it is TurnDone } }
                assertFalse(frames.any { it is PocketError && it.code == "opencode_startup_timeout" }, frames.toString())
            } finally {
                convo.close()
                scope.cancel()
            }
        } finally {
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    /** The hang the watchdog exists for: a process that is alive but never prints gets killed + reported. */
    @Test
    fun zero_stdout_hang_is_killed_by_watchdog() = runBlocking {
        if (win()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        try {
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            val backend = OneShotBackend(listOf(okTurn("ses_open_1"))) { _, _ -> "sleep 30" }
            val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
            try {
                convo.open(resumeId = null, model = null)
                convo.sendPrompt("hello", promptId = "p1")
                await { frames.any { it is PocketError && it.code == "opencode_startup_timeout" } }
                assertTrue(frames.any { it is PocketError && it.code == "opencode_startup_timeout" })
            } finally {
                convo.close()
                scope.cancel()
            }
        } finally {
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    @Test
    fun startup_watchdog_revokes_the_bridge_grant_lease() = runBlocking {
        if (win()) return@runBlocking
        System.setProperty(Conversation.OPENCODE_WATCHDOG_PROP, "300")
        try {
            val frames = CopyOnWriteArrayList<Frame>()
            val scope = CoroutineScope(Dispatchers.Default)
            val backend = OneShotBackend(listOf(okTurn("ses_open_1"), okTurn("ses_open_2"))) { idx, f ->
                if (idx == 0) "sleep 30" else "cat '${f.absolutePathString()}'"
            }
            val convo = Conversation(
                "cOpenBridge",
                Files.createTempDirectory("ccp-open"),
                PermissionMode.DEFAULT,
                { frames.add(it) },
                scope,
                backend,
                origin = "feishu-bot",
            )
            try {
                convo.open(resumeId = null, model = null)
                assertTrue(convo.sendTrustedBridgePrompt("first"))
                await { frames.any { it is PocketError && it.code == "opencode_startup_timeout" } }
                // The failed process's pending lease must not permanently block or later authorize a retry.
                assertTrue(convo.sendTrustedBridgePrompt("second"))
                await { frames.any { it is TurnDone } }
            } finally {
                convo.close()
                scope.cancel()
            }
        } finally {
            System.clearProperty(Conversation.OPENCODE_WATCHDOG_PROP)
        }
    }

    /** Cold-resume regression (review P0): tapping a DISK session (openedResumeId set, no live sessionId)
     *  must resume that opencode session — not silently fork a fresh one. */
    @Test
    fun cold_resume_anchors_on_opened_resume_id() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_disk_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = "ses_disk_1", model = null)
            convo.sendPrompt("hello again", promptId = "p1")
            await { frames.any { it is TurnDone } }
            assertEquals("ses_disk_1", backend.specs[0].resumeId)
            assertEquals(false, backend.specs[0].forkSession)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    /** Mid-turn sends queue (same receipt contract as the stdin mid-turn queue) and drain into the next
     *  one-shot spawn after the running turn completes — never bounced with an error. */
    @Test
    fun midturn_prompt_queues_and_runs_after_current_turn() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_open_1"), okTurn("ses_open_1", "second answer"))) { idx, f ->
            // first launch stalls mid-turn so the second prompt lands while it runs; later launches just cat
            if (idx == 0) "head -n 1 '${f.absolutePathString()}'; sleep 1.2; tail -n +2 '${f.absolutePathString()}'"
            else "cat '${f.absolutePathString()}'"
        }
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.any { it is PromptAck && it.promptId == "p1" } }
            convo.sendPrompt("second", promptId = "p2") // lands mid-turn (first launch is still sleeping)
            await { frames.count { it is TurnDone } >= 2 }
            assertFalse(frames.any { it is PocketError }, frames.toString())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p2" })
            assertEquals(2, backend.specs.size)
            assertEquals("second", backend.specs[1].initialPrompt)
            assertEquals("ses_open_1", backend.specs[1].resumeId) // queued turn continues the SAME session
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun stdin_one_shot_reinjects_a_prompt_that_raced_the_clean_exit() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = StdinOneShotBackend()
        val convo = Conversation("cCodex", Files.createTempDirectory("ccp-codex-one-shot"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.any { it is PromptAck && it.promptId == "p1" } }
            convo.sendPrompt("second", promptId = "p2") // old child receives but deliberately never acknowledges it

            await { frames.count { it is TurnDone } >= 2 }

            assertFalse(frames.any { it is PocketError }, frames.toString())
            assertEquals(2, backend.specs.size)
            assertEquals("codex-stdin-1", backend.specs[1].resumeId)
            assertEquals(listOf("first", "second", "second"), backend.sends.toList())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p2" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun one_shot_exit_without_turn_result_still_surfaces_error() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(startedOnly("ses_open_1")))
        val convo = Conversation("cOpen", Files.createTempDirectory("ccp-open"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is PocketError && it.code == "process_exited" } }
            assertTrue(frames.any { it is PocketError && it.code == "process_exited" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun stdin_one_shot_term_shutdown_relaunches_a_prompt_received_after_the_result() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        // EOF cannot finish this child: the real shutdown ladder must send SIGTERM after 3 seconds.
        val backend = StdinOneShotBackend(afterEof = "exec sleep 30")
        val convo = Conversation("cCodexTerm", Files.createTempDirectory("ccp-codex-term"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("first", promptId = "p1")
            await { frames.any { it is TurnDone } }
            convo.sendPrompt("second", promptId = "p2")
            await { frames.count { it is TurnDone } >= 2 || frames.any { it is PocketError } }

            assertFalse(frames.any { it is PocketError }, frames.toString())
            assertEquals(2, frames.count { it is TurnDone })
            assertEquals(2, backend.specs.size)
            assertEquals("codex-stdin-1", backend.specs[1].resumeId)
            assertEquals(listOf("first", "second", "second"), backend.sends.toList())
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "p2" })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun a_completed_turn_followed_by_unrequested_sigterm_is_still_an_error() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = OneShotBackend(listOf(okTurn("ses_external_term"))) { _, f ->
            "cat '${f.absolutePathString()}'; kill -TERM ${'$'}${'$'}"
        }
        val convo = Conversation("cExternalTerm", Files.createTempDirectory("ccp-external-term"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is PocketError && it.code == "process_exited" } }
            assertEquals(1, frames.count { it is TurnDone })
            assertTrue(frames.any { it is PocketError && "exit 143" in it.message }, frames.toString())
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun exit_143_during_requested_eof_without_daemon_sigterm_is_still_an_error() = runBlocking {
        if (win()) return@runBlocking
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        // The child returns 143 itself before the daemon's escalation. Intent plus code is insufficient.
        val backend = StdinOneShotBackend(afterEof = "exit 143")
        val convo = Conversation("cEof143", Files.createTempDirectory("ccp-eof-143"), PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("hello", promptId = "p1")
            await { frames.any { it is PocketError && it.code == "process_exited" } }
            assertEquals(1, frames.count { it is TurnDone })
            assertTrue(frames.any { it is PocketError && "exit 143" in it.message }, frames.toString())
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun term_turn_boundary_preserves_only_the_exact_pending_prompt_grant() = runBlocking {
        if (win()) return@runBlocking
        checkPendingGrantAcrossExit(afterEof = "exec sleep 30", cleanTerm = true)
    }

    @Test
    fun self_exit_143_revokes_the_pending_prompt_grant_before_restart() = runBlocking {
        if (win()) return@runBlocking
        checkPendingGrantAcrossExit(afterEof = "exit 143", cleanTerm = false)
    }

    private suspend fun checkPendingGrantAcrossExit(afterEof: String, cleanTerm: Boolean) {
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = GrantBoundaryOneShotBackend(afterEof)
        val approvals = ApprovalCoordinator(scope)
        val convoId = "cGrantExit"
        val convo = Conversation(
            convoId, Files.createTempDirectory("ccp-grant-exit"), PermissionMode.DEFAULT,
            { frames += it }, scope, backend, approvals = approvals, origin = "feishu-bot",
        )
        suspend fun denyAsk(id: String) {
            await { frames.any { it is PermissionAsk && it.askId == id } }
            assertFalse(backend.responses.any { it == id to true }, backend.responses.toString())
            assertTrue(approvals.onVerdict(PermissionVerdict(convoId, id, Decision.DENY)))
            await { backend.responses.any { it == id to false } }
        }
        try {
            convo.open(resumeId = null, model = null)
            assertTrue(convo.sendTrustedBridgePrompt("request A", promptId = "a"))
            await { frames.any { it is TurnDone } }
            assertTrue(convo.sendTrustedBridgePrompt("request B", promptId = "b"))
            backend.releaseLateA.complete(Unit)
            denyAsk("late-a")
            backend.releaseShutdown.complete(Unit)

            if (!cleanTerm) {
                await { frames.any { it is PocketError && it.code == "process_exited" } }
                assertTrue(frames.any { it is PocketError && "exit 143" in it.message }, frames.toString())
                assertEquals(1, backend.specs.size, "an abnormal exit must not automatically resume B")
                // Resume the existing ledger without giving B a fresh trusted grant.
                convo.sendPrompt("wake", promptId = "wake")
            }
            denyAsk("before-b")
            assertEquals(2, backend.specs.size)
            assertEquals("grant-session", backend.specs[1].resumeId)
            assertEquals(2, backend.sends.count { it == "request B" }, "B must be re-injected into the fresh process")
            assertEquals(PromptFate.PENDING, convo.promptFate("b"), "an unrelated replay cannot consume B")
            backend.releaseReplayB.complete(Unit)

            if (cleanTerm) {
                await { backend.responses.any { it == "control-b" to true } }
                assertFalse(frames.any { it is PermissionAsk && it.askId == "control-b" }, frames.toString())
                assertFalse(frames.any { it is PocketError }, frames.toString())
            } else {
                denyAsk("control-b")
            }
            assertEquals(PromptFate.CONSUMED, convo.promptFate("b"))
            assertEquals(1, frames.count { it is PromptAck && it.promptId == "b" })
            assertEquals(
                listOf("late-a" to false, "before-b" to false, "control-b" to cleanTerm),
                backend.responses.toList(),
            )
        } finally {
            backend.releaseLateA.complete(Unit)
            backend.releaseShutdown.complete(Unit)
            backend.releaseReplayB.complete(Unit)
            convo.close()
            scope.cancel()
        }
    }
}
