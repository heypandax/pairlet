package dev.ccpocket.daemon.approval

import dev.ccpocket.protocol.ApprovalHistoryItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalHistoryStoreTest {
    private val json = Json { encodeDefaults = true }

    private fun item(
        id: String = "e1",
        tool: String = "Bash",
        summary: String = "curl https://api.example.test?token=super-secret-value",
    ) = ApprovalHistoryItem(
        eventId = id,
        at = 1L,
        convoId = "c1",
        source = "AGENT",
        tool = tool,
        summary = summary,
        basis = "task-grant",
        decision = "auto",
        taskId = "t1",
        grantId = "g1",
    )

    @Test
    fun bash_arguments_never_reach_disk_and_file_is_owner_only() {
        val file = Files.createTempDirectory("ccp-approval-history").resolve("history.jsonl").toFile()
        val store = ApprovalHistoryStore(file)
        store.append(item())

        val disk = file.readText()
        assertFalse("super-secret-value" in disk, disk)
        assertFalse("api.example.test" in disk, "Bash arguments, including URLs, must be discarded: $disk")
        assertEquals("Bash", store.recent(10).single().summary)

        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let { perms ->
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        }
    }

    @Test
    fun load_migrates_old_unredacted_rows_and_drops_corrupt_lines() {
        val file = Files.createTempDirectory("ccp-approval-history-old").resolve("history.jsonl").toFile()
        file.writeText(json.encodeToString(item(summary = "echo plaintext-password")) + "\nnot-json\n")

        val store = ApprovalHistoryStore.load(file)

        assertFalse("plaintext-password" in file.readText())
        assertEquals(listOf("Bash"), store.recent(10).map { it.summary })
        assertEquals(1, file.readLines().size, "corrupt legacy rows are not preserved during security migration")
    }

    @Test
    fun arbitrary_shell_assignment_secrets_are_never_persisted_or_displayed() {
        val file = Files.createTempDirectory("ccp-approval-history-env").resolve("history.jsonl").toFile()
        val store = ApprovalHistoryStore(file)
        store.append(item(summary = "DB_CREDENTIAL=correct-horse-battery-staple ./deploy"))

        val disk = file.readText()
        assertFalse("correct-horse-battery-staple" in disk, disk)
        assertFalse("DB_CREDENTIAL" in disk, disk)
        assertEquals("Bash", store.recent(10).single().summary)
    }

    @Test
    fun page_builder_obeys_encoded_byte_budget_not_only_row_count() {
        val file = Files.createTempDirectory("ccp-approval-history-page").resolve("history.jsonl").toFile()
        val store = ApprovalHistoryStore(file)
        repeat(100) { store.append(item(id = "event-$it", tool = "Edit", summary = "x".repeat(10_000))) }

        val budget = 1_600
        val page = store.recent(limit = 100, maxBytes = budget)
        val encodedItems = page.sumOf { json.encodeToString(it).encodeToByteArray().size + 1 }
        assertTrue(page.isNotEmpty())
        assertTrue(encodedItems + 1_024 <= budget, "encoded page exceeded byte budget")
        assertTrue(page.size < 100, "the byte budget, not the row cap, should stop this page")
        assertTrue(page.all { it.summary == "Edit" }, "non-Bash summaries minimize to their tool family")
    }
}
