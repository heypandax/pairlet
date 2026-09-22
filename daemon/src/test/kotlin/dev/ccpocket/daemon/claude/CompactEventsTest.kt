package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.daemon.disk.TranscriptScanner
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

class CompactEventsTest {
    private val summary = "This session is being continued from a previous conversation that ran out of context. Summary: fixture"

    @Test fun boundaryUsesOnlyPostTokensAndNeverInitializesAnotherSession() {
        for ((metadata, expected) in listOf(
            "\"pre_tokens\":191000,\"post_tokens\":12000" to 12000L,
            "\"pre_tokens\":191000" to null,
            "\"post_tokens\":-1" to null,
            "\"post_tokens\":0" to 0L,
        )) {
            assertEquals(listOf(AgentEvent.CompactBoundary(expected)), StreamParser.parse(
                """{"type":"system","subtype":"compact_boundary","session_id":"s","compact_metadata":{$metadata}}"""))
        }
        assertIs<AgentEvent.Ignored>(StreamParser.parse(
            """{"type":"system","subtype":"compact_boundary","parent_tool_use_id":"child","compact_metadata":{"post_tokens":12}}"""
        ).single())
    }

    @Test fun summaryNeedsHarnessProvenanceAndCannotConsumeAPrompt() {
        assertIs<AgentEvent.UserReplay>(StreamParser.parse(
            """{"type":"user","message":{"content":"$summary"}}"""
        ).single())
        assertEquals(listOf(AgentEvent.CompactSummary(summary)), StreamParser.parse(
            """{"type":"user","isSynthetic":true,"message":{"content":[{"type":"text","text":"$summary"}]}}"""))
        assertTrue(StreamParser.parse(
            """{"type":"user","isSynthetic":true,"message":{"content":"other harness message"}}"""
        ).isEmpty())
        assertIs<AgentEvent.Ignored>(StreamParser.parse(
            """{"type":"user","isSynthetic":true,"parent_tool_use_id":"child","message":{"content":"$summary"}}"""
        ).single())
    }

    @Test fun historyRetainsSummaryWithoutUserRewindCoordinates() {
        val file = Files.createTempFile("compact-history", ".jsonl")
        try {
            file.writeText(listOf(
                """{"type":"user","isCompactSummary":true,"isMeta":true,"uuid":"summary","message":{"content":"$summary"}}""",
                """{"type":"user","uuid":"quote","message":{"content":"$summary"}}""",
            ).joinToString("\n"))
            val rows = TranscriptReplay.read(file)
            assertEquals(2, rows.size)
            assertTrue(rows[0].compactSummary)
            assertEquals(summary, rows[0].text)
            assertNull(rows[0].seq)
            assertNull(rows[0].uuid)
            assertFalse(rows[1].compactSummary)
            assertEquals("quote", rows[1].uuid)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun bothResumeReadersInvalidatePreCompactUsageAndRecover() {
        val before = """{"type":"assistant","message":{"model":"claude-fixture","usage":{"input_tokens":191000}}}"""
        for ((meta, expected) in listOf("\"preTokens\":191000" to null,
            "\"preTokens\":191000,\"postTokens\":12000" to 12000L)) {
            val boundary = """{"type":"system","subtype":"compact_boundary","compactMetadata":{$meta}}"""
            val after = """{"type":"assistant","message":{"model":"claude-fixture","usage":{"input_tokens":13000,"output_tokens":7}}}"""
            for ((tail, want) in listOf("" to expected, "\n$after" to 13007L)) {
                val file = Files.createTempFile("compact-usage", ".jsonl")
                try {
                    file.writeText("$before\n$boundary$tail\n")
                    assertEquals(want, TranscriptScanner.lastContextTokens(file))
                    assertEquals(want, TranscriptScanner.resumeSeed(file)?.contextTokens)
                } finally { Files.deleteIfExists(file) }
            }
        }
    }
}
