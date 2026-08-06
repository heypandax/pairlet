package dev.ccpocket.daemon.kimi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parses the global session_index.jsonl, tolerating field-spelling variants (issue #206, V2 unverified). */
class KimiSessionIndexTest {

    private fun idx(vararg lines: String) = Files.createTempFile("kimi_idx", ".jsonl").also {
        Files.write(it, lines.toList())
    }

    @Test
    fun `canonical field spellings parse`() {
        val f = idx(
            """{"sessionId":"s1","sessionDir":"/home/u/.kimi-code/sessions/wd_x/s1","workDir":"/proj/a"}""",
            """{"session_id":"s2","dir":"/d/s2","cwd":"/proj/b"}""",
        )
        val e = KimiSessionIndex.entries(f)
        assertEquals(2, e.size)
        assertEquals("s1", e[0].sessionId)
        assertEquals("/proj/a", e[0].workDir)
        assertEquals("s2", e[1].sessionId)
        assertEquals("/proj/b", e[1].workDir)
    }

    @Test
    fun `lines without a session id are skipped, garbage tolerated`() {
        val f = idx(
            "not json",
            """{"workDir":"/x"}""",
            "",
            """{"sessionId":"ok","workDir":"/y"}""",
        )
        val e = KimiSessionIndex.entries(f)
        assertEquals(1, e.size)
        assertEquals("ok", e[0].sessionId)
    }

    @Test
    fun `missing index yields empty`() {
        assertTrue(KimiSessionIndex.entries(Files.createTempDirectory("x").resolve("none.jsonl")).isEmpty())
    }

    @Test
    fun `absent optional fields degrade to null`() {
        val f = idx("""{"sessionId":"s3"}""")
        val e = KimiSessionIndex.entries(f).single()
        assertNull(e.workDir)
        assertNull(e.sessionDir)
    }
}
