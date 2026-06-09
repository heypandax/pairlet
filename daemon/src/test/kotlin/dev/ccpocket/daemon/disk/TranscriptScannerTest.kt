package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TranscriptScannerTest {

    @Test
    fun counts_only_real_user_turns_and_reads_header() {
        val dir = Files.createTempDirectory("ccp-scan")
        val f = dir.resolve("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"mode","mode":"normal"}""",
                """{"type":"permission-mode","permissionMode":"default"}""",
                """{"type":"file-history-snapshot","messageId":"m"}""",
                """{"type":"user","message":{"role":"user","content":"first real prompt"},"cwd":"/repo","gitBranch":"main","version":"2.1.165"}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}""",
                """{"type":"user","toolUseResult":{"x":1},"message":{"role":"user","content":[{"type":"tool_result","content":"r"}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"r2"}]}}""",
                """{"type":"ai-title","aiTitle":"My Title"}""",
            ).joinToString("\n"),
        )

        val s = assertNotNull(TranscriptScanner.summarize(f))
        assertEquals("sess-1", s.sessionId)
        assertEquals(1, s.messageCount) // C5: tool-result user turns excluded
        assertEquals("My Title", s.title) // ai-title preferred over firstPrompt
        assertEquals("first real prompt", s.firstPrompt)
        assertEquals("/repo", s.cwd)
        assertEquals("main", s.gitBranch)
        assertEquals("2.1.165", s.version)
    }
}
