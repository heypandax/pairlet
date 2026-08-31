package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives [CodexBackend] with synthetic app-server JSON-RPC lines (no real `codex` binary) to lock down the
 * handshake sequencing, the buffered-first-turn race fix, delta streaming, the approval round-trip, and the
 * Claude-mode → Codex-policy mapping. Request ids are deterministic (idSeq starts at 1).
 */
class CodexBackendTest {
    private fun initResponse(id: Int) =
        """{"id":$id,"result":{"userAgent":"x","codexHome":"/h","platformFamily":"unix","platformOs":"macos"}}"""

    private fun threadStartResponse(id: Int, threadId: String) =
        """{"id":$id,"result":{"thread":{"id":"$threadId","sessionId":"sess-1"},"model":"gpt-5.1-codex"}}"""

    /** The `input` array of the last turn/start line the backend wrote. */
    private fun turnInput(w: List<String>): JsonArray =
        Json.parseToJsonElement(w.last { "turn/start" in it })
            .jsonObject["params"]!!.jsonObject["input"]!!.jsonArray

    private fun JsonArray.field(index: Int, key: String): String? =
        this[index].jsonObject[key]?.jsonPrimitive?.content

    private fun requestId(line: String): Int =
        Json.parseToJsonElement(line).jsonObject["id"]!!.jsonPrimitive.content.toInt()

    /** attach + handshake to a live thread "thr-1". Leaves `w` holding every line the backend wrote. */
    private suspend fun ready(
        w: MutableList<String>,
        mode: PermissionMode = PermissionMode.DEFAULT,
        onExit: () -> Unit = {},
    ): CodexBackend {
        val b = CodexBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}, requestProcessExit = onExit), AgentSpec(Path.of("/repo"), mode = mode))
        b.parse(initResponse(1))          // → initialized + thread/start (id 2)
        b.parse(threadStartResponse(2, "thr-1"))
        return b
    }

    @Test
    fun attach_sends_initialize_then_initialized_and_thread_start() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        assertTrue("\"method\":\"initialize\"" in w[0], w[0])
        assertTrue(w.any { "\"method\":\"initialized\"" in it })
        assertTrue(w.any { "\"method\":\"thread/start\"" in it })
        assertEquals(AgentProcessMode.ONE_SHOT_TURN, b.processMode)
    }

    @Test
    fun user_message_item_is_the_prompt_consumption_receipt() = runBlocking {
        val b = ready(mutableListOf())

        val ev = b.parse(
            """{"method":"item/started","params":{"item":{"type":"userMessage","id":"u1","content":[{"type":"text","text":"hello ledger"}]}}}""",
        )

        assertEquals("hello ledger", assertIs<AgentEvent.UserReplay>(ev.single()).text)
    }

    @Test
    fun take_over_uses_native_thread_fork_instead_of_a_second_writer_on_resume() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}),
            AgentSpec(Path.of("/repo"), resumeId = "thr-desktop", forkSession = true),
        )
        b.parse(initResponse(1))

        val open = w.last { "thread/" in it }
        assertTrue("\"method\":\"thread/fork\"" in open, open)
        assertTrue("\"threadId\":\"thr-desktop\"" in open, open)
        assertFalse("thread/resume" in open, open)

        val ev = b.parse(threadStartResponse(2, "thr-phone"))
        assertEquals("thr-phone", assertIs<AgentEvent.SessionInit>(ev.single()).sessionId)
    }

    @Test
    fun first_prompt_buffers_until_thread_ready_then_turn_start() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(AgentIo({ w += it }, {}), AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT))
        b.sendPrompt("hello world", emptyList())
        assertTrue(w.none { "turn/start" in it }, "turn must not start before the thread is ready")
        b.parse(initResponse(1))
        val ev = b.parse(threadStartResponse(2, "thr-1"))
        assertIs<AgentEvent.SessionInit>(ev.single())
        assertEquals("thr-1", (ev.single() as AgentEvent.SessionInit).sessionId)
        val turn = w.last { "turn/start" in it }
        assertTrue("hello world" in turn, turn)
        assertTrue("\"threadId\":\"thr-1\"" in turn, turn)
        // DEFAULT = the "Balanced" Codex preset → ask when needed, edits inside the workspace
        assertTrue("\"approvalPolicy\":\"on-request\"" in turn, turn)
        assertTrue("\"workspaceWrite\"" in turn, turn)
    }

    @Test
    fun prompt_sent_during_an_active_turn_uses_native_turn_steer() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse(
            """{"method":"turn/started","params":{"threadId":"thr-1","turn":{"id":"turn-live","status":"inProgress"}}}""",
        )

        b.sendPrompt("continue with the next batch", emptyList())

        val request = w.last()
        assertTrue("\"method\":\"turn/steer\"" in request, request)
        assertTrue("\"threadId\":\"thr-1\"" in request, request)
        assertTrue("\"expectedTurnId\":\"turn-live\"" in request, request)
        assertTrue("continue with the next batch" in request, request)
    }

    @Test
    fun subagent_thread_notifications_do_not_mutate_or_complete_the_parent_turn() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })
        b.parse(
            """{"method":"turn/started","params":{"threadId":"thr-1","turn":{"id":"turn-parent","status":"inProgress"}}}""",
        )

        // Codex multiplexes spawned sub-agent threads onto the parent's app-server connection. Their
        // lifecycle must not become the lifecycle of the thread cc-pocket explicitly resumed.
        assertTrue(
            b.parse(
                """{"method":"turn/started","params":{"threadId":"thr-child","turn":{"id":"turn-child","status":"inProgress"}}}""",
            ).isEmpty(),
        )
        b.interrupt()
        val interrupt = w.last()
        assertTrue("\"threadId\":\"thr-1\"" in interrupt, interrupt)
        assertTrue("\"turnId\":\"turn-parent\"" in interrupt, interrupt)

        val foreignEvents = buildList {
            addAll(
                b.parse(
                    """{"method":"item/agentMessage/delta","params":{"threadId":"thr-child","turnId":"turn-child","itemId":"m-child","delta":"child-only text"}}""",
                ),
            )
            addAll(
                b.parse(
                    """{"method":"item/started","params":{"threadId":"thr-child","turnId":"turn-child","item":{"type":"userMessage","id":"u-child","content":[{"type":"text","text":"child prompt"}]}}}""",
                ),
            )
            addAll(
                b.parse(
                    """{"method":"thread/tokenUsage/updated","params":{"threadId":"thr-child","turnId":"turn-child","tokenUsage":{"last":{"inputTokens":99,"outputTokens":5,"cachedInputTokens":0}}}}""",
                ),
            )
            addAll(
                b.parse(
                    """{"method":"error","params":{"threadId":"thr-child","turnId":"turn-child","willRetry":false,"error":{"message":"child failure"}}}""",
                ),
            )
            addAll(
                b.parse(
                    """{"method":"item/completed","params":{"threadId":"thr-child","turnId":"turn-child","item":{"type":"agentMessage","id":"m-child","text":"child final"}}}""",
                ),
            )
            addAll(
                b.parse(
                    """{"method":"turn/completed","params":{"threadId":"thr-child","turn":{"id":"turn-child","status":"completed"}}}""",
                ),
            )
        }
        assertTrue(foreignEvents.isEmpty(), foreignEvents.toString())
        assertEquals(0, exits, "a child turn boundary must not release the parent's app-server writer")
        b.interrupt()
        val interruptAfterChild = w.last()
        assertTrue("\"turnId\":\"turn-parent\"" in interruptAfterChild, interruptAfterChild)

        val parentEvents = b.parse(
            """{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"turn-parent","status":"completed"}}}""",
        )
        val result = assertIs<AgentEvent.TurnResult>(parentEvents.single())
        assertFalse(result.isError, "a child error must not poison the successful parent turn")
        assertEquals(null, result.finalText, "child-only text must not become the parent's final answer")
        assertEquals(1, exits, "the actual parent boundary still owns the one-shot handoff")
    }

    @Test
    fun second_prompt_before_turn_started_waits_for_the_next_boundary() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })

        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())

        assertEquals(1, w.count { "\"method\":\"turn/start\"" in it })
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t1"}}}""")
        b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t1","status":"completed"}}}""")
        assertEquals(2, w.count { "\"method\":\"turn/start\"" in it })
        assertTrue("second" in w.last(), w.last())
        assertEquals(0, exits, "queued work owns the next turn")
    }

    @Test
    fun completed_turn_requests_a_clean_process_exit_for_cross_app_handoff() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })
        b.sendPrompt("finish this", emptyList())
        val startId = requestId(w.last { "\"method\":\"turn/start\"" in it })
        b.parse("""{"method":"turn/started","params":{"threadId":"thr-1","turn":{"id":"t1"}}}""")

        b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        assertEquals(0, exits, "the turn/start response still owns the stdout pipe")
        b.parse("""{"id":$startId,"result":{"turn":{"id":"t1"}}}""")

        assertEquals(1, exits)
        assertTrue(w.none { "thread/unsubscribe" in it }, "unsubscribe is not a writer-release boundary on newer Codex")
        assertFalse(b.compact(), "a control op racing process exit must ask the caller to retry")
        assertFalse(b.review(), "a control op racing process exit must ask the caller to retry")
    }

    @Test
    fun re_injected_prompt_on_the_next_process_resumes_before_starting_a_turn() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")

        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), resumeId = "thr-1"))
        b.sendPrompt("continue after handoff", emptyList()) // Conversation's clean-exit ledger re-injection
        val initId = requestId(w.last { "\"method\":\"initialize\"" in it })
        b.parse(initResponse(initId))

        val resume = w.last()
        assertTrue("\"method\":\"thread/resume\"" in resume, resume)
        assertTrue("\"threadId\":\"thr-1\"" in resume, resume)

        b.parse(threadStartResponse(requestId(resume), "thr-1"))
        val turn = w.last()
        assertTrue("\"method\":\"turn/start\"" in turn, turn)
        assertTrue("continue after handoff" in turn, turn)
    }

    @Test
    fun prompt_racing_the_exit_is_not_written_into_the_dying_process() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })
        b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        val writesBefore = w.size

        b.sendPrompt("arrived during handoff", emptyList())

        assertEquals(1, exits)
        assertEquals(writesBefore, w.size, "the process-exit ledger, not this dying pipe, owns the prompt")
    }

    @Test
    fun images_are_forwarded_to_codex_as_data_urls() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt(
            "compare these",
            listOf(
                ImageData("image/jpeg", "/9j/AA=="),
                ImageData("image/png", "iVBORw=="),
            ),
        )

        val input = turnInput(w)
        assertEquals(3, input.size)
        assertEquals("text", input.field(0, "type"))
        assertEquals("compare these", input.field(0, "text"))
        assertEquals("image", input.field(1, "type"))
        assertEquals("data:image/jpeg;base64,/9j/AA==", input.field(1, "url"))
        assertEquals("image", input.field(2, "type"))
        assertEquals("data:image/png;base64,iVBORw==", input.field(2, "url"))
    }

    @Test
    fun buffered_first_prompt_keeps_its_image_until_thread_ready() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(AgentIo({ w += it }, {}), AgentSpec(Path.of("/repo")))
        b.sendPrompt("", listOf(ImageData("image/webp", "UklGRg==")))

        b.parse(initResponse(1))
        b.parse(threadStartResponse(2, "thr-1"))

        val input = turnInput(w)
        assertEquals("", input.field(0, "text"))
        assertEquals("data:image/webp;base64,UklGRg==", input.field(1, "url"))
    }

    @Test
    fun agent_message_delta_streams_as_text() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse("""{"method":"item/agentMessage/delta","params":{"threadId":"thr-1","turnId":"t1","itemId":"i1","delta":"Hi"}}""")
        assertEquals(AgentEvent.AssistantText("Hi"), ev.single())
    }

    @Test
    fun completed_agent_message_not_duplicated_after_deltas() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"item/agentMessage/delta","params":{"itemId":"i1","delta":"Hi"}}""")
        val ev = b.parse("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"i1","text":"Hi there"}}}""")
        assertTrue(ev.isEmpty(), "final must not re-emit once deltas streamed the message") // text was already streamed
    }

    @Test
    fun compact_uses_native_app_server_rpc_when_thread_is_ready() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })

        assertTrue(b.compact())

        val request = w.last()
        assertTrue("\"method\":\"thread/compact/start\"" in request, request)
        assertTrue("\"threadId\":\"thr-1\"" in request, request)
        b.parse("""{"id":${requestId(request)},"result":{}}""")
        assertEquals(1, exits, "compact has no turn/completed, so its response is the handoff boundary")
    }

    @Test
    fun compact_queued_during_handshake_runs_once_after_thread_opens() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(AgentIo({ w += it }, {}), AgentSpec(Path.of("/repo"), resumeId = "thr-old"))

        assertTrue(b.compact())
        assertTrue(w.none { "thread/compact/start" in it })
        b.parse(initResponse(1))
        b.parse(threadStartResponse(2, "thr-1"))

        assertEquals(1, w.count { "thread/compact/start" in it })
    }

    @Test
    fun review_uses_native_app_server_target() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })

        assertTrue(b.review())

        val request = w.last()
        assertTrue("\"method\":\"review/start\"" in request, request)
        assertTrue("\"type\":\"uncommittedChanges\"" in request, request)
        b.parse("""{"id":${requestId(request)},"result":{}}""")
        assertEquals(0, exits, "review success owns a real turn and must wait for turn/completed")
        assertTrue("\"delivery\":\"inline\"" in request, request)
    }

    @Test
    fun simplify_expands_to_a_codex_task_not_an_unknown_slash_command() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)

        // Conversation applies expandSlashPrompt at its prompt boundary (issue #301); this pins the
        // backend's rewrite AND that the expanded form is what actually reaches the wire.
        b.sendPrompt(b.expandSlashPrompt("/simplify keep the public API"), emptyList())

        val turn = w.last { "turn/start" in it }
        assertFalse("/simplify" in turn, turn)
        assertTrue("simplify the implementation" in turn, turn)
        assertTrue("keep the public API" in turn, turn)
    }

    @Test
    fun command_approval_becomes_control_request_and_decision_is_written() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse(
            """{"id":"req-7","method":"item/commandExecution/requestApproval","params":{"itemId":"i2","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"rm -rf build","cwd":"/repo"}}""",
        )
        val cr = ev.single()
        assertIs<AgentEvent.ControlRequest>(cr)
        assertEquals("req-7", cr.requestId)
        assertEquals("Bash", cr.toolName) // synthesized so ToolMetadata gives a "Run command" title + danger flag

        b.respondPermission("req-7", allow = true, remember = false, originalInput = null, updatedInput = null, denyMessage = null)
        val resp = w.last()
        assertTrue("\"id\":\"req-7\"" in resp, resp) // string id echoed verbatim
        assertTrue("\"decision\":\"accept\"" in resp, resp)
    }

    @Test
    fun remember_maps_to_accept_for_session() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"id":9,"method":"item/commandExecution/requestApproval","params":{"itemId":"i3","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"ls"}}""")
        b.respondPermission("9", allow = true, remember = true, originalInput = null, updatedInput = null, denyMessage = null)
        val resp = w.last()
        assertTrue("\"id\":9" in resp, resp) // integer id echoed as integer
        assertTrue("\"decision\":\"acceptForSession\"" in resp, resp)
    }

    @Test
    fun deny_writes_decline() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"id":"a1","method":"item/commandExecution/requestApproval","params":{"itemId":"i4","startedAtMs":1,"threadId":"thr-1","turnId":"t1","command":"curl evil"}}""")
        b.respondPermission("a1", allow = false, remember = false, originalInput = null, updatedInput = null, denyMessage = "no")
        assertTrue("\"decision\":\"decline\"" in w.last(), w.last())
    }

    @Test
    fun token_usage_then_turn_completed_emits_turn_result() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"thread/tokenUsage/updated","params":{"threadId":"thr-1","tokenUsage":{"total":{"inputTokens":10,"outputTokens":5,"cachedInputTokens":2}}}}""")
        val ev = b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        val tr = ev.single()
        assertIs<AgentEvent.TurnResult>(tr)
        assertEquals(10L, tr.usage?.inputTokens)
        assertEquals(5L, tr.usage?.outputTokens)
        assertEquals(2L, tr.usage?.cacheReadInputTokens)
        assertTrue(!tr.isError)
    }

    @Test
    fun token_usage_prefers_per_turn_last_over_cumulative_total() = runBlocking {
        // `total` is the session-cumulative sum — after a few turns it dwarfs real window occupancy;
        // `last` is the finished call's usage, which IS the occupancy. Prefer last, total is only a fallback.
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse(
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"thr-1","tokenUsage":{""" +
                """"total":{"inputTokens":900,"outputTokens":400,"cachedInputTokens":800},""" +
                """"last":{"inputTokens":120,"outputTokens":30,"cachedInputTokens":100}}}}""",
        )
        val ev = b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        val tr = ev.single()
        assertIs<AgentEvent.TurnResult>(tr)
        assertEquals(120L, tr.usage?.inputTokens)
        assertEquals(30L, tr.usage?.outputTokens)
        assertEquals(100L, tr.usage?.cacheReadInputTokens)
    }

    @Test
    fun turn_completed_without_any_token_usage_reports_no_usage() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse("""{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"completed"}}}""")
        val tr = ev.single()
        assertIs<AgentEvent.TurnResult>(tr)
        assertEquals(null, tr.usage) // zeros would read as "empty window" on the phone's statusline
    }

    @Test
    fun failed_turn_surfaces_codex_error_instead_of_generic_turn_failed() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse(
            """{"method":"turn/completed","params":{"threadId":"thr-1","turn":{"id":"t1","status":"failed","error":{"message":"Selected model is unavailable"}}}}""",
        )

        val tr = assertIs<AgentEvent.TurnResult>(ev.single())
        assertTrue(tr.isError)
        assertEquals("Selected model is unavailable", tr.finalText)
    }

    @Test
    fun command_execution_started_surfaces_tool_use() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val ev = b.parse("""{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"go build","cwd":"/repo","status":"inProgress"}}}""")
        val tu = ev.single()
        assertIs<AgentEvent.AssistantToolUse>(tu)
        assertEquals("Bash", tu.name)
    }

    @Test
    fun plan_mode_maps_to_read_only_and_bypass_to_never() = runBlocking {
        val wPlan = mutableListOf<String>()
        ready(wPlan, PermissionMode.PLAN).sendPrompt("x", emptyList())
        val planTurn = wPlan.last { "turn/start" in it }
        assertTrue("\"readOnly\"" in planTurn, planTurn)

        val wBypass = mutableListOf<String>()
        ready(wBypass, PermissionMode.BYPASS_PERMISSIONS).sendPrompt("x", emptyList())
        val bypassTurn = wBypass.last { "turn/start" in it }
        assertTrue("\"approvalPolicy\":\"never\"" in bypassTurn, bypassTurn)
        assertTrue("\"dangerFullAccess\"" in bypassTurn, bypassTurn)
    }

    @Test
    fun dynamic_ultra_and_priority_are_sent_only_when_the_model_advertises_them() = runBlocking {
        val dir = Files.createTempDirectory("codex-capabilities-test")
        val cache = dir.resolve("models_cache.json")
        val config = dir.resolve("config.toml")
        Files.writeString(config, "")
        Files.writeString(
            cache,
            """
            {
              "models": [
                {
                  "slug": "gpt-5.6-sol",
                  "visibility": "list",
                  "upgrade": null,
                  "supported_reasoning_levels": [{"effort":"max"},{"effort":"ultra"}],
                  "service_tiers": [{"id":"priority","name":"Fast"}]
                },
                {
                  "slug": "gpt-5.5",
                  "visibility": "list",
                  "upgrade": null,
                  "supported_reasoning_levels": [{"effort":"xhigh"}],
                  "service_tiers": []
                }
              ]
            }
            """.trimIndent(),
        )
        val modelService = CodexModelService(cache, config)

        val supportedWrites = mutableListOf<String>()
        val supported = CodexBackend(null, modelService)
        supported.attach(
            AgentIo({ supportedWrites += it }, {}),
            AgentSpec(
                Path.of("/repo"),
                model = "gpt-5.6-sol",
                effort = "ultra",
                serviceTier = "priority",
            ),
        )
        supported.parse(initResponse(1))
        val start = supportedWrites.last { "thread/start" in it }
        assertTrue("\"serviceTier\":\"priority\"" in start, start)
        supported.parse(threadStartResponse(2, "thr-sol"))
        supported.sendPrompt("go", emptyList())
        val turn = supportedWrites.last { "turn/start" in it }
        assertTrue("\"effort\":\"ultra\"" in turn, turn)
        assertTrue("\"serviceTier\":\"priority\"" in turn, turn)

        val rejectedWrites = mutableListOf<String>()
        val rejected = CodexBackend(null, modelService)
        rejected.attach(
            AgentIo({ rejectedWrites += it }, {}),
            AgentSpec(
                Path.of("/repo"),
                model = "gpt-5.5",
                effort = "ultra",
                serviceTier = "priority",
            ),
        )
        rejected.parse(initResponse(1))
        val rejectedStart = rejectedWrites.last { "thread/start" in it }
        assertFalse("serviceTier" in rejectedStart, rejectedStart)
        rejected.parse(threadStartResponse(2, "thr-55"))
        rejected.sendPrompt("go", emptyList())
        val rejectedTurn = rejectedWrites.last { "turn/start" in it }
        assertFalse("\"effort\":\"ultra\"" in rejectedTurn, rejectedTurn)
        assertFalse("serviceTier" in rejectedTurn, rejectedTurn)
    }

    @Test
    fun file_change_approval_carries_the_diff() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        // the fileChange item (with its diff) arrives before the approval request references it by itemId
        b.parse("""{"method":"item/started","params":{"item":{"type":"fileChange","id":"f1","status":"inProgress","changes":[{"path":"src/A.kt","diff":"-old line\n+new line"}]}}}""")
        val ev = b.parse("""{"id":"ap1","method":"item/fileChange/requestApproval","params":{"itemId":"f1","startedAtMs":1,"threadId":"thr-1","turnId":"t1"}}""")
        val cr = ev.single()
        assertIs<AgentEvent.ControlRequest>(cr)
        assertEquals("Edit", cr.toolName)
        assertTrue("+new line" in (cr.diff ?: ""), cr.diff ?: "<null>") // diff is a typed field, for the phone's diff view
        assertTrue("src/A.kt" in cr.input.toString(), cr.input.toString())
    }

    @Test
    fun subagent_file_change_stays_hidden_but_keeps_approval_context() = runBlocking {
        val b = ready(mutableListOf())

        val started = b.parse(
            """{"method":"item/started","params":{"threadId":"thr-child","turnId":"turn-child","item":{"type":"fileChange","id":"f-child","status":"inProgress","changes":[{"path":"src/Child.kt","diff":"-old child\n+new child"}]}}}""",
        )
        assertTrue(started.isEmpty(), "a child edit must not appear as a root-thread tool card")

        val ev = b.parse(
            """{"id":"ap-child","method":"item/fileChange/requestApproval","params":{"itemId":"f-child","startedAtMs":1,"threadId":"thr-child","turnId":"turn-child"}}""",
        )
        val cr = assertIs<AgentEvent.ControlRequest>(ev.single())
        assertEquals("Edit", cr.toolName)
        assertTrue("+new child" in (cr.diff ?: ""), cr.diff ?: "<null>")
        assertTrue("src/Child.kt" in cr.input.toString(), cr.input.toString())
    }

    // ── JSON-RPC error responses must not be swallowed (PR #296 review) ──

    @Test
    fun a_steer_rejected_for_a_stale_turn_is_redelivered_as_turn_start() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w) // ids 1 (initialize) + 2 (thread/start) consumed
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"turn-live","status":"inProgress"}}}""")
        b.sendPrompt("keep going with the batch", emptyList()) // → turn/steer (id 3)
        assertTrue(w.last { "turn/steer" in it }.contains("\"expectedTurnId\":\"turn-live\""))

        // the turn completed server-side while the steer was in flight: on the ordered pipe the completion
        // is parsed FIRST, then the stale-expectedTurnId rejection — that pairing is what "stale" means
        b.parse("""{"method":"turn/completed","params":{"turn":{"id":"turn-live","status":"completed"}}}""")
        val ev = b.parse("""{"id":3,"error":{"code":-32602,"message":"expectedTurnId does not match the active turn"}}""")

        assertTrue(ev.isEmpty(), "the retry is silent — no error surfaces for a recovered prompt")
        val start = w.last { "\"method\":\"turn/start\"" in it }
        assertTrue("keep going with the batch" in start, start) // the acked prompt is re-delivered, not lost
    }

    @Test
    fun a_rejected_turn_start_settles_the_turn_with_a_visible_error() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("do the thing", emptyList()) // → turn/start (id 3)

        val ev = b.parse("""{"id":3,"error":{"code":-32000,"message":"model overloaded"}}""")

        // Conversation marked this turn executing on ack; only a TurnResult clears that state again
        assertEquals("do the thing", ev.filterIsInstance<AgentEvent.UserReplay>().single().text)
        val r = ev.filterIsInstance<AgentEvent.TurnResult>().single()
        assertTrue(r.isError)
        assertTrue("model overloaded" in (r.finalText ?: ""), r.finalText ?: "<null>")
    }

    @Test
    fun a_thread_fork_rejected_by_an_old_app_server_surfaces_instead_of_hanging() = runBlocking {
        val w = mutableListOf<String>()
        val b = CodexBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), resumeId = "thr-x", forkSession = true))
        b.parse(initResponse(1)) // → thread/fork (id 2)

        val ev = b.parse("""{"id":2,"error":{"code":-32601,"message":"method not found: thread/fork"}}""")

        // pre-0.147 app-server: without this the thread never opens and pendingPrompt waits forever
        val r = ev.filterIsInstance<AgentEvent.TurnResult>().single()
        assertTrue(r.isError)
        assertTrue("fork" in (r.finalText ?: ""), r.finalText ?: "<null>")
    }

    @Test
    fun a_rejected_compact_reports_failure_instead_of_a_silent_noop() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })
        b.compact() // → thread/compact/start (id 3)

        val ev = b.parse("""{"id":3,"error":{"code":-32601,"message":"method not found"}}""")

        val t = ev.filterIsInstance<AgentEvent.AssistantText>().single()
        assertTrue("compact" in t.text && "method not found" in t.text, t.text)
        assertEquals(1, exits, "a rejected control has no later terminal notification")
    }

    @Test
    fun an_error_notification_between_turns_is_surfaced_immediately() = runBlocking {
        val b = ready(mutableListOf())

        // no turn is active → no turn/completed will ever come to carry a stashed error to the phone
        val ev = b.parse("""{"method":"error","params":{"error":{"message":"stream disconnected"}}}""")

        val t = ev.filterIsInstance<AgentEvent.AssistantText>().single()
        assertTrue("stream disconnected" in t.text, t.text)
    }

    @Test
    fun a_failed_turn_still_reports_the_error_stashed_mid_turn() = runBlocking {
        val b = ready(mutableListOf())
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t1"}}}""")
        b.parse("""{"method":"error","params":{"error":{"message":"boom"}}}""") // mid-turn → stashed, not emitted

        val ev = b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t1","status":"failed"}}}""")

        val r = ev.filterIsInstance<AgentEvent.TurnResult>().single()
        assertTrue(r.isError)
        assertEquals("boom", r.finalText)
    }

    @Test
    fun a_null_id_error_response_is_tolerated_without_an_exception() = runBlocking {
        val b = ready(mutableListOf())
        // JSON-RPC's mandated reply to an invalid request: "id":null. Must not NPE the pending maps
        // (which would be swallowed upstream and mislogged as a parse failure).
        val ev = b.parse("""{"id":null,"error":{"code":-32700,"message":"parse error"}}""")
        assertTrue(ev.isEmpty(), ev.toString())
    }

    @Test
    fun a_steer_rejected_before_the_sender_registers_it_is_still_retried() = runBlocking {
        // The pump can parse an instant rejection while the sender is still inside rpcRequest. Pin that
        // registration happens BEFORE the write by replaying the server's pipe — turn/completed, then the
        // rejection — synchronously from within writeLine itself: with register-after-write neither map
        // has the id yet and the acked prompt would fall to the log-and-drop path.
        val w = mutableListOf<String>()
        var fired = false
        lateinit var b: CodexBackend
        b = CodexBackend(null)
        b.attach(
            AgentIo(
                writeLine = { line ->
                    w += line
                    if ("turn/steer" in line && !fired) {
                        fired = true
                        val id = Json.parseToJsonElement(line).jsonObject["id"]!!.jsonPrimitive.content
                        b.parse("""{"method":"turn/completed","params":{"turn":{"id":"turn-live","status":"completed"}}}""")
                        b.parse("""{"id":$id,"error":{"code":-32602,"message":"expectedTurnId mismatch"}}""")
                    }
                },
                emit = {},
            ),
            AgentSpec(Path.of("/repo")),
        )
        b.parse(initResponse(1))
        b.parse(threadStartResponse(2, "thr-1"))
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"turn-live"}}}""")

        b.sendPrompt("recover me", emptyList())

        val start = w.lastOrNull { "\"method\":\"turn/start\"" in it && "recover me" in it }
        assertTrue(start != null, w.joinToString("\n"))
    }

    @Test
    fun a_steer_rejected_while_its_turn_still_runs_surfaces_instead_of_colliding() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"turn-live"}}}""")
        b.sendPrompt("mid-turn note", emptyList()) // → turn/steer (id 3)

        // rejection arrives while turn-live is STILL the current turn — not a staleness race
        val ev = b.parse("""{"id":3,"error":{"code":-32000,"message":"input rejected"}}""")

        val t = ev.filterIsInstance<AgentEvent.AssistantText>().single()
        assertTrue("input rejected" in t.text, t.text)
        assertTrue(w.none { "\"method\":\"turn/start\"" in it }, "no blind turn/start against a live turn")
    }

    @Test
    fun an_image_prompt_sent_mid_turn_waits_for_the_boundary_instead_of_losing_its_image() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = ready(w, onExit = { exits += 1 })
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t1"}}}""")

        b.sendPrompt("look at this", listOf(ImageData("image/png", "iVBORw==")))

        assertTrue(w.none { "turn/steer" in it }, "an image prompt must not ride the image-less steer")
        b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t1","status":"completed"}}}""")
        val start = w.last { "\"method\":\"turn/start\"" in it }
        assertTrue("look at this" in start && "iVBORw==" in start, start)
        assertEquals(0, exits, "queued work owns the next turn")
    }

    @Test
    fun a_failed_turn_with_partial_text_still_reports_why_it_failed() = runBlocking {
        val b = ready(mutableListOf())
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t1"}}}""")
        b.parse("""{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"partial answer…"}}""")
        b.parse("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"m1","text":"partial answer…"}}}""")
        b.parse("""{"method":"error","params":{"error":{"message":"usage limit reached"}}}""")

        val ev = b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t1","status":"failed"}}}""")

        val r = ev.filterIsInstance<AgentEvent.TurnResult>().single()
        assertTrue(r.isError)
        assertEquals("usage limit reached", r.finalText, "the reason, not the already-streamed partial text")
    }

    @Test
    fun a_stashed_error_is_never_billed_to_a_later_successful_turn() = runBlocking {
        val b = ready(mutableListOf())
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t1"}}}""")
        b.parse("""{"method":"error","params":{"error":{"message":"transient rate limit"}}}""")
        b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t1","status":"completed"}}}""")

        // a tool-only turn: completes fine with no agentMessage text
        b.parse("""{"method":"turn/started","params":{"turn":{"id":"t2"}}}""")
        val ev = b.parse("""{"method":"turn/completed","params":{"turn":{"id":"t2","status":"completed"}}}""")

        val r = ev.filterIsInstance<AgentEvent.TurnResult>().single()
        assertFalse(r.isError)
        assertEquals(null, r.finalText, "an old error must not masquerade as this turn's successful answer")
    }
}
