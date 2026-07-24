package dev.ccpocket.daemon.opencode

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeTranscriptScannerTest {
    // issue #172: OpenCode's task tool spawns sub-agent runs as CHILD sessions (parent_id → the
    // enclosing session). Those are internal sub-runs, not resumable top-level conversations, and
    // must be filtered from the phone's session list. Detection is by OpenCode's own parent/child
    // column ONLY — never by title text.

    @Test
    fun sub_agent_child_session_is_filtered_by_parent_id() {
        assertTrue(OpenCodeTranscriptScanner.isSubAgentSession("ses_7f3aParentEnclosing"))
    }

    @Test
    fun top_level_session_without_parent_is_kept() {
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession(null))
        // a blank column (defensive: OpenCode writes NULL, but treat empty as "no parent" too)
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession(""))
        assertFalse(OpenCodeTranscriptScanner.isSubAgentSession("   "))
    }

    @Test
    fun parses_session_model_json_to_provider_slash_model() {
        assertEquals(
            "opencode/deepseek-v4-flash-free",
            OpenCodeTranscriptScanner.parseModel("""{"id":"deepseek-v4-flash-free","providerID":"opencode","variant":"max"}"""),
        )
    }

    @Test
    fun leaves_already_qualified_model_ids_alone() {
        assertEquals("zhipuai/glm-4.5", OpenCodeTranscriptScanner.parseModel("zhipuai/glm-4.5"))
        assertEquals(
            "openai/gpt-5.1",
            OpenCodeTranscriptScanner.parseModel("""{"id":"openai/gpt-5.1","providerID":"openai"}"""),
        )
    }

    @Test
    fun rejects_unqualified_or_garbled_model_values() {
        assertNull(OpenCodeTranscriptScanner.parseModel("deepseek-chat"))
        assertNull(OpenCodeTranscriptScanner.parseModel("""{"id":"deepseek-v4-flash-free"}"""))
        assertNull(OpenCodeTranscriptScanner.parseModel("{not json"))
    }

    // ---- #172 子会话过滤 + #173 firstPrompt 新口径（喂 fixture db 走 scanConn；
    //      fixture schema 与真实 OpenCode 一致，session 表恒有 parent_id 列） ----

    @Test
    fun filters_subagent_child_rows_via_parent_id() {
        openFixtureDb().use { conn ->
            conn.putSession("top", "Top session", timeUpdated = 2_000)
            conn.putUserMessage("top", "m-top", "do the top thing", 1)
            // task 工具 spawn 的子 agent：同表 child 行，parent_id 指向发起会话
            conn.putSession("sub", "Explore serialization tests (@explore subagent)", timeUpdated = 2_500, parentId = "top")
            conn.putUserMessage("sub", "m-sub", "explore the tests", 1)
            val out = OpenCodeTranscriptScanner.scanConn(conn, "/w")
            assertEquals(listOf("top"), out.map { it.sessionId })
        }
    }

    @Test
    fun first_prompt_is_first_user_message_distinct_from_title() {
        openFixtureDb().use { conn ->
            conn.putSession("s", "Curated Title", timeUpdated = 2_000)
            conn.putUserMessage("s", "m1", "the actual first user prompt", 1)
            conn.putUserMessage("s", "m2", "a later prompt", 2)
            val row = OpenCodeTranscriptScanner.scanConn(conn, "/w").single()
            assertEquals("Curated Title", row.title)
            assertEquals("the actual first user prompt", row.firstPrompt)
        }
    }

    @Test
    fun first_prompt_blank_when_no_user_message() {
        openFixtureDb().use { conn ->
            conn.putSession("s", "Title only", timeUpdated = 2_000)
            val row = OpenCodeTranscriptScanner.scanConn(conn, "/w").single()
            assertEquals("Title only", row.title)
            assertEquals("", row.firstPrompt)
        }
    }

    private fun openFixtureDb(): Connection {
        val dir = createTempDirectory("opencode-scan-test")
        val db = dir.resolve("opencode.db")
        val conn = DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE session (id TEXT PRIMARY KEY, parent_id TEXT, title TEXT, directory TEXT, model TEXT, " +
                "cost REAL, tokens_input INTEGER, tokens_output INTEGER, time_created INTEGER, " +
                "time_updated INTEGER, time_archived INTEGER)"
            )
            st.executeUpdate("CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT, data TEXT, time_created INTEGER)")
            st.executeUpdate("CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT, message_id TEXT, data TEXT, time_created INTEGER)")
        }
        return conn
    }

    private fun Connection.putSession(
        id: String,
        title: String,
        dir: String = "/w",
        timeUpdated: Long = 1_000,
        parentId: String? = null,
    ) {
        prepareStatement("INSERT INTO session (id, parent_id, title, directory, time_created, time_updated) VALUES (?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, id); ps.setString(2, parentId); ps.setString(3, title)
            ps.setString(4, dir); ps.setLong(5, timeUpdated); ps.setLong(6, timeUpdated)
            ps.executeUpdate()
        }
    }

    private fun Connection.putUserMessage(sessionId: String, messageId: String, text: String, time: Long) {
        prepareStatement("INSERT INTO message (id, session_id, data, time_created) VALUES (?,?,?,?)").use {
            it.setString(1, messageId); it.setString(2, sessionId)
            it.setString(3, """{"role":"user"}"""); it.setLong(4, time); it.executeUpdate()
        }
        val partData = buildJsonObject { put("type", "text"); put("text", text) }.toString()
        prepareStatement("INSERT INTO part (id, session_id, message_id, data, time_created) VALUES (?,?,?,?,?)").use {
            it.setString(1, "$messageId-p"); it.setString(2, sessionId); it.setString(3, messageId)
            it.setString(4, partData); it.setLong(5, time); it.executeUpdate()
        }
    }
}
