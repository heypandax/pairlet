package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #290: tooling that drives an agent CLI programmatically (the reported case: a dsh plugin
 * spawning one OpenCode session per image under `%LOCALAPPDATA%\Temp\modlens-work-*`) leaves dozens of
 * one-shot cwds in the transcript stores, and each used to become a project row. These pin the noise
 * filter: temp-dir rows are hidden by default, and each "still relevant" signal (live conversation,
 * user-opened recent) independently keeps the row.
 */
class DirectoryServiceTempNoiseTest {

    private val projects = Files.createTempDirectory("ccp-projects")
    private val tempRoot = Files.createTempDirectory("ccp-sys-temp") // plays the machine's temp dir
    private val work = Files.createDirectory(tempRoot.resolve("modlens-work-eDE9K6"))
    private val realWork = Files.createTempDirectory("ccp-real-project") // NOT under the injected root
    private val now = System.currentTimeMillis()

    @AfterTest
    fun cleanup() {
        projects.toFile().deleteRecursively()
        tempRoot.toFile().deleteRecursively()
        realWork.toFile().deleteRecursively()
    }

    private fun service() = DirectoryService(
        projectsRoot = { projects },
        codexCwds = { emptyMap() },
        opencodeCwds = { mapOf(work.toString() to now, realWork.toString() to now) },
        kimiCwds = { emptyMap() },
        zcodeCwds = { emptyMap() },
        dshCwds = { emptyMap() },
        liveClaudeCwds = { emptySet() },
        tempNoiseRoots = listOf(tempRoot.toString()),
    )

    @Test
    fun one_shot_temp_cwds_are_hidden_but_real_projects_stay() {
        val rows = service().listDirectories(null)
        assertEquals(
            listOf(realWork.toString()), rows.map { it.path },
            "the temp-dir row is noise; the real project must be the only row",
        )
    }

    @Test
    fun a_live_conversation_keeps_a_temp_row_visible() {
        val rows = service().listDirectories(
            null,
            liveByCwd = mapOf(work.toString() to listOf(ActiveSession("session-x", "t", executing = true))),
        )
        assertTrue(
            rows.any { it.path == work.toString() },
            "a temp cwd with a live conversation is actively in use — it must not vanish mid-run",
        )
    }

    @Test
    fun a_user_opened_recent_temp_dir_stays_visible() {
        val svc = service()
        svc.noteRecent(work.toString())
        assertTrue(
            svc.listDirectories(null).any { it.path == work.toString() },
            "the user opened this dir on purpose — recency overrides the noise filter",
        )
    }
}
