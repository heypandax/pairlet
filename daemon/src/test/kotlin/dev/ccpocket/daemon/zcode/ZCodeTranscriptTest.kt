package dev.ccpocket.daemon.zcode

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
