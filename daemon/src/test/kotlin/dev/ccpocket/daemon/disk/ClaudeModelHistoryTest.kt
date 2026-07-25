package dev.ccpocket.daemon.disk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeModelHistoryTest {

    @BeforeTest
    fun setUp() = ClaudeModelHistory.clearCache()

    @AfterTest
    fun tearDown() = ClaudeModelHistory.clearCache()

    private fun withProjects(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccp-model-history")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun turn(timestamp: String, model: String): String =
        """{"type":"assistant","timestamp":"$timestamp","message":{"model":"$model","usage":{"input_tokens":1}}}"""

    @Test
    fun listsEveryDistinctModelAcrossProjectsByMostRecentUse() = withProjects { root ->
        val a = root.resolve("project-a").also { it.createDirectories() }
        val b = root.resolve("project-b").also { it.createDirectories() }
        a.resolve("old.jsonl").writeText(
            listOf(
                turn("2024-01-01T00:00:00Z", "old-only"),
                turn("2026-01-01T00:00:00Z", "shared"),
            ).joinToString("\n"),
        )
        b.resolve("new.jsonl").writeText(
            listOf(
                turn("2026-02-01T00:00:00Z", "newest"),
                turn("2026-03-01T00:00:00Z", "shared"),
            ).joinToString("\n"),
        )

        assertEquals(listOf("shared", "newest", "old-only"), ClaudeModelHistory.recent(root, nowMs = 1))
    }

    @Test
    fun malformedPseudoAndOversizedIdsAreIgnored() = withProjects { root ->
        val project = root.resolve("project").also { it.createDirectories() }
        val tooLong = "x".repeat(129)
        project.resolve("session.jsonl").writeText(
            listOf(
                "not json",
                """{"type":"user","message":{"model":"user-model"}}""",
                turn("2026-01-01T00:00:00Z", "<synthetic>"),
                turn("2026-01-02T00:00:00Z", tooLong),
                turn("2026-01-03T00:00:00Z", "valid/model"),
            ).joinToString("\n"),
        )

        assertEquals(listOf("valid/model"), ClaudeModelHistory.recent(root, nowMs = 1))
    }

    @Test
    fun resultIsCappedBeforeItCanReachAProtocolFrame() = withProjects { root ->
        val project = root.resolve("project").also { it.createDirectories() }
        project.resolve("session.jsonl").writeText(
            (0 until 250).joinToString("\n") { i ->
                turn("2026-01-01T00:${(i % 60).toString().padStart(2, '0')}:00Z", "model-$i")
            },
        )

        assertEquals(200, ClaudeModelHistory.recent(root, nowMs = 1).size)
    }

    @Test
    fun cacheAvoidsRescanningUntilTheShortTtlExpires() = withProjects { root ->
        val project = root.resolve("project").also { it.createDirectories() }
        val file = project.resolve("session.jsonl")
        file.writeText(turn("2026-01-01T00:00:00Z", "first"))
        assertEquals(listOf("first"), ClaudeModelHistory.recent(root, nowMs = 1))

        file.writeText(turn("2026-01-02T00:00:00Z", "second"))
        assertEquals(listOf("first"), ClaudeModelHistory.recent(root, nowMs = 2))
        assertEquals(listOf("second"), ClaudeModelHistory.recent(root, nowMs = 60_002))
    }

    @Test
    fun missingRootIsAnEmptyBestEffortAnswer() = withProjects { root ->
        val missing = root.resolve("missing")
        assertTrue(ClaudeModelHistory.recent(missing, nowMs = 1).isEmpty())
    }
}
