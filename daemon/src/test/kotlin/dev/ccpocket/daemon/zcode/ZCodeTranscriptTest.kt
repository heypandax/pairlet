package dev.ccpocket.daemon.zcode

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZCodeTranscriptTest {
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { st ->
            st.execute("CREATE TABLE session(id TEXT, directory TEXT, title TEXT, version TEXT, time_updated INTEGER, parent_id TEXT, task_type TEXT, time_archived INTEGER)")
            st.execute("CREATE TABLE message(id TEXT, session_id TEXT, time_created INTEGER, time_updated INTEGER, data TEXT, sequence INTEGER)")
            st.execute("CREATE TABLE part(id TEXT, message_id TEXT, session_id TEXT, time_created INTEGER, time_updated INTEGER, data TEXT, sequence INTEGER)")
            st.execute("INSERT INTO session VALUES('s1','/repo','A session','0.16.3',1000,NULL,'interactive',NULL)")
            st.execute("INSERT INTO message VALUES('m1','s1',1,1,'{\"role\":\"user\",\"model\":{\"providerID\":\"zai\",\"modelID\":\"glm-5\"}}',1)")
            st.execute("INSERT INTO part VALUES('p1','m1','s1',1,1,'{\"type\":\"text\",\"text\":\"hello\"}',1)")
            st.execute("INSERT INTO message VALUES('m2','s1',2,2,'{\"role\":\"assistant\",\"providerID\":\"zai\",\"modelID\":\"glm-5\"}',2)")
            st.execute("INSERT INTO part VALUES('p2','m2','s1',2,2,'{\"type\":\"text\",\"text\":\"hi\"}',1)")
            st.execute("INSERT INTO part VALUES('p3','m2','s1',3,3,'{\"type\":\"tool\",\"tool\":\"Bash\",\"callID\":\"t1\",\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"pwd\"},\"output\":\"/repo\"}}',2)")
        }
    }

    @Test
    fun `scanner filters by cwd and exposes zcode model`() {
        val rows = ZCodeTranscriptScanner.scanFrom(database(), "/repo")
        assertEquals(1, rows.size)
        assertEquals(AgentKind.ZCODE, rows.single().agent)
        assertEquals("hello", rows.single().firstPrompt)
        assertEquals("zai/glm-5", rows.single().model)
        assertFalse(rows.single().live)
    }

    /** A session whose first user part is a harness `<system-reminder>` injection (issue #253). */
    private fun noisyDatabase(): Connection = database().also { db ->
        db.createStatement().use { st ->
            st.execute("INSERT INTO session VALUES('s2','/noisy','A noisy session','0.16.3',1000,NULL,'interactive',NULL)")
            st.execute("INSERT INTO message VALUES('n1','s2',1,1,'{\"role\":\"user\"}',1)")
            st.execute("INSERT INTO part VALUES('q1','n1','s2',1,1,'{\"type\":\"text\",\"text\":\"<system-reminder>\\nYour todo list has changed.\\n</system-reminder>\"}',1)")
            st.execute("INSERT INTO message VALUES('n2','s2',2,2,'{\"role\":\"user\"}',2)")
            st.execute("INSERT INTO part VALUES('q2','n2','s2',2,2,'{\"type\":\"text\",\"text\":\"真正的问题\"}',1)")
            st.execute("INSERT INTO message VALUES('n3','s2',3,3,'{\"role\":\"user\"}',3)")
            st.execute("INSERT INTO part VALUES('q3','n3','s2',3,3,'{\"type\":\"text\",\"text\":\"<system-reminder>\\nnudge\\n</system-reminder>\\n还有一件事\"}',1)")
            st.execute("INSERT INTO message VALUES('n4','s2',4,4,'{\"role\":\"assistant\"}',4)")
            st.execute("INSERT INTO part VALUES('q4','n4','s2',4,4,'{\"type\":\"text\",\"text\":\"<system-reminder>assistant text is untouched</system-reminder>\"}',1)")
        }
    }

    @Test
    fun `replay drops pure harness injections from user rows`() {
        val history = ZCodeTranscriptReplay.readFrom(noisyDatabase(), "s2")

        assertEquals(listOf(ChatRole.USER, ChatRole.USER, ChatRole.ASSISTANT), history.map { it.role })
        assertEquals("真正的问题", history[0].text)
        assertTrue(history[1].text.contains("还有一件事")) // reminder prepended to real input keeps the turn
        assertTrue(history[2].text.contains("assistant text is untouched")) // only user rows are screened
        assertFalse(history.any { it.role == ChatRole.USER && it.text.contains("todo list has changed") })
    }

    @Test
    fun `list preview skips the injected first turn`() {
        val rows = ZCodeTranscriptScanner.scanFrom(noisyDatabase(), "/noisy")

        assertEquals(1, rows.size)
        assertEquals("真正的问题", rows.single().firstPrompt) // not the system-reminder
    }

    @Test
    fun `replay maps text and tool rows`() {
        val history = ZCodeTranscriptReplay.readFrom(database(), "s1")
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL), history.map { it.role })
        assertEquals("hello", history[0].text)
        assertEquals("hi", history[1].text)
        assertEquals("Bash", history[2].tool)
        assertEquals(true, history[2].ok)
        assertEquals("/repo", history[2].output)
    }
}
