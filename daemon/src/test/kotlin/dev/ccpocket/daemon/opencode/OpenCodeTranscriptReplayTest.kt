package dev.ccpocket.daemon.opencode

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.HistoryMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [OpenCodeTranscriptReplay.readFrom] over an in-memory opencode.db (the injected-Connection seam)
 * — verifying a tool part replays as its own structured `ChatRole.TOOL` card, interleaved with the
 * surrounding assistant text, instead of the old `[bash] …output…` line flattened into the bubble (#177),
 * and that a SETTLED call's card carries the live parser's output + ok/failed verdict.
 */
class OpenCodeTranscriptReplayTest {
    private lateinit var conn: Connection
    private var clock = 0L // monotonic time_created so inserts keep their order

    @BeforeTest
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE message (id TEXT, session_id TEXT, data TEXT, time_created INTEGER)")
            st.executeUpdate("CREATE TABLE part (id TEXT, session_id TEXT, message_id TEXT, data TEXT, time_created INTEGER)")
        }
    }

    @AfterTest
    fun tearDown() = conn.close()

    private fun message(sessionId: String, messageId: String, role: String, parts: List<JsonObject>) {
        conn.prepareStatement("INSERT INTO message(id, session_id, data, time_created) VALUES (?,?,?,?)").use { ps ->
            ps.setString(1, messageId)
            ps.setString(2, sessionId)
            ps.setString(3, buildJsonObject { put("role", role) }.toString())
            ps.setLong(4, clock++)
            ps.executeUpdate()
        }
        parts.forEachIndexed { i, p ->
            conn.prepareStatement("INSERT INTO part(id, session_id, message_id, data, time_created) VALUES (?,?,?,?,?)").use { ps ->
                ps.setString(1, "$messageId-p$i")
                ps.setString(2, sessionId)
                ps.setString(3, messageId)
                ps.setString(4, p.toString())
                ps.setLong(5, clock++)
                ps.executeUpdate()
            }
        }
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun toolPart(tool: String, status: String = "completed", input: JsonObject? = null, output: String? = null, exit: Long? = null) = buildJsonObject {
        put("type", "tool")
        put("tool", tool)
        putJsonObject("state") {
            put("status", status)
            if (input != null) put("input", input)
            if (output != null) put("output", output)
            if (exit != null) putJsonObject("metadata") { put("exit", exit) }
        }
    }

    private fun read(sessionId: String, maxMessages: Int = 100) =
        OpenCodeTranscriptReplay.readFrom(conn, sessionId, maxMessages, Long.MAX_VALUE)

    @Test
    fun interleaves_text_and_tool_rows_in_recorded_order() {
        message(
            "s1", "m1", "assistant",
            listOf(
                textPart("Let me list the files."),
                toolPart("bash", input = buildJsonObject { put("command", "ls -la"); put("description", "list") }, output = "file1\nfile2"),
                textPart("Two files."),
            ),
        )
        val rows = read("s1")
        assertEquals(3, rows.size)
        assertEquals(ChatRole.ASSISTANT, rows[0].role)
        assertEquals("Let me list the files.", rows[0].text)
        assertEquals(ChatRole.TOOL, rows[1].role)
        assertEquals("Bash", rows[1].tool)
        assertTrue(rows[1].text.contains("ls -la"), "tool card preview should reveal the command")
        assertEquals(true, rows[1].ok, "a completed call with output replays as ok")
        assertEquals("file1\nfile2", rows[1].output, "the card carries the output it produced live")
        assertEquals(ChatRole.ASSISTANT, rows[2].role)
        assertEquals("Two files.", rows[2].text)
        // regression: the tool OUTPUT must no longer be flattened into any assistant bubble …
        assertFalse(rows.any { it.role == ChatRole.ASSISTANT && it.text.contains("file1") }, "tool output must not land in an assistant bubble")
        // … and the tool row is a real preview, not the old "[bash] output" text
        assertFalse(rows[1].text.startsWith("[bash]"))
    }

    @Test
    fun maps_opencode_tool_names_to_claude_shaped_names() {
        message(
            "s1", "m1", "assistant",
            listOf(
                toolPart("bash", input = buildJsonObject { put("command", "echo hi") }),
                toolPart("read", input = buildJsonObject { put("file_path", "/tmp/a") }),
                toolPart("webfetch", input = buildJsonObject { put("url", "https://x") }),
                toolPart("mytool", input = buildJsonObject { put("k", "v") }),
            ),
        )
        val toolNames = read("s1").filter { it.role == ChatRole.TOOL }.map { it.tool }
        assertEquals(listOf("Bash", "Read", "WebFetch", "Mytool"), toolNames)
    }

    @Test
    fun long_text_replays_whole_while_tool_preview_and_output_cap() {
        message(
            "s1", "m1", "assistant",
            listOf(
                textPart("y".repeat(9000)),
                toolPart("bash", input = buildJsonObject { put("command", "x".repeat(5000)) }, output = "z".repeat(5000)),
            ),
        )
        val rows = read("s1")
        // issue #81 parity with the Claude/Codex replays: reply-body text is NOT per-row clipped —
        // a long answer replays intact and ReplayBudget bounds the frame total instead
        assertEquals(9000, rows.first { it.role == ChatRole.ASSISTANT }.text.length, "long reply text replays whole")
        assertTrue(rows.first { it.role == ChatRole.TOOL }.text.length <= 1000, "tool preview capped at MAX_TOOL_TEXT")
        assertEquals(4000, rows.first { it.role == ChatRole.TOOL }.output?.length, "tool output capped at MAX_TOOL_OUTPUT")
    }

    @Test
    fun running_and_error_and_inputless_tool_parts_do_not_throw() {
        message(
            "s1", "m1", "assistant",
            listOf(
                toolPart("bash", status = "running", input = buildJsonObject { put("command", "sleep 10") }, output = null),
                toolPart("bash", status = "error", input = buildJsonObject { put("command", "false") }, output = null),
                toolPart("grep", status = "completed"), // no state.input at all
            ),
        )
        val toolRows = read("s1").filter { it.role == ChatRole.TOOL }
        assertEquals(3, toolRows.size)
        assertTrue(toolRows[0].text.contains("sleep 10"))
        assertTrue(toolRows[1].text.contains("false"))
        assertEquals("", toolRows[2].text, "an input-less tool part is still a card, just with an empty preview")
        assertEquals("Grep", toolRows[2].tool)
        // only a SETTLED (status=completed) call carries a verdict — running/error-in-flight rows stay open
        assertNull(toolRows[0].ok, "a running call must not replay a stale verdict")
        assertNull(toolRows[0].output)
        assertNull(toolRows[1].ok, "an unsettled (non-completed) error state carries no verdict either")
        assertNull(toolRows[1].output)
    }

    @Test
    fun user_message_folds_into_a_single_user_bubble() {
        message("s1", "m1", "user", listOf(textPart("hello"), textPart("world")))
        val rows = read("s1")
        assertEquals(1, rows.size)
        assertEquals(ChatRole.USER, rows[0].role)
        assertEquals("hello\nworld", rows[0].text)
    }

    @Test
    fun maxMessages_keeps_the_newest_rows_after_a_turn_expands() {
        // one assistant turn expands into 3 rows (text · card · text); capping to 2 keeps the newest 2
        message(
            "s1", "m1", "assistant",
            listOf(
                textPart("one"),
                toolPart("bash", input = buildJsonObject { put("command", "cmd") }),
                textPart("three"),
            ),
        )
        val rows = read("s1", maxMessages = 2)
        assertEquals(2, rows.size)
        assertEquals(ChatRole.TOOL, rows[0].role)
        assertEquals("three", rows[1].text)
    }

    // ---- settled-call outcome: the card replays the same output + ok/failed verdict the live parser gave ----

    /** Inserts one assistant message holding a single tool part and returns its replayed TOOL row. */
    private fun oneTool(part: JsonObject): HistoryMessage {
        val sid = "one-${clock}"
        message(sid, "m", "assistant", listOf(part))
        return read(sid).single()
    }

    @Test
    fun completed_tool_with_output_is_ok_and_carries_the_output() {
        val t = oneTool(toolPart("read", input = buildJsonObject { put("file_path", "/f") }, output = "body"))
        assertEquals(ChatRole.TOOL, t.role)
        assertEquals(true, t.ok)
        assertEquals("body", t.output)
    }

    @Test
    fun errored_tool_no_output_nonzero_exit_is_not_ok() {
        // mirrors the live parser: a completed call that produced NO output AND had a non-zero exit is an error
        val t = oneTool(toolPart("bash", input = buildJsonObject { put("command", "false") }, exit = 1L))
        assertEquals(false, t.ok)
        assertNull(t.output)
    }

    @Test
    fun completed_tool_without_output_and_absent_exit_is_not_ok() {
        // live-parser parity: an absent exit code counts as non-zero, so completed + no output = failed
        val t = oneTool(toolPart("bash", input = buildJsonObject { put("command", "x") }))
        assertEquals(false, t.ok)
        assertNull(t.output)
    }

    @Test
    fun output_present_wins_even_with_nonzero_exit() {
        // an error needs NO output, so a non-zero exit that still printed something replays as ok (live parity)
        val t = oneTool(toolPart("bash", input = buildJsonObject { put("command", "grep x") }, output = "no match", exit = 1L))
        assertEquals(true, t.ok)
        assertEquals("no match", t.output)
    }

    @Test
    fun unsettled_tool_carries_no_outcome_nor_partial_output() {
        // a running part may already hold partial output in the db — neither it nor a verdict may leak
        val t = oneTool(toolPart("read", status = "running", input = buildJsonObject { put("file_path", "/f") }, output = "partial"))
        assertEquals(ChatRole.TOOL, t.role)
        assertEquals("Read", t.tool)
        assertNull(t.ok)
        assertNull(t.output)
    }

    @Test
    fun blank_only_text_parts_yield_no_row() {
        message("s1", "m1", "assistant", listOf(textPart("   "), textPart("")))
        assertTrue(read("s1").isEmpty(), "a message whose text run is all blank folds to nothing")
    }
}
