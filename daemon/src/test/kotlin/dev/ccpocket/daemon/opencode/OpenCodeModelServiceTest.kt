package dev.ccpocket.daemon.opencode

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeModelServiceTest {
    @Test
    fun sortModels_prefers_free_opencode_models_then_other_providers() {
        val sorted = OpenCodeModelService.sortModels(
            listOf("zhipuai/glm-5", "opencode/hy3-free", "opencode/big-pickle", "zhipuai/glm-4.5", "opencode/hy3-free"),
        )
        assertEquals(listOf("opencode/big-pickle", "opencode/hy3-free", "zhipuai/glm-4.5", "zhipuai/glm-5"), sorted)
    }

    @Test
    fun fetch_returns_sorted_models_from_explicit_binary() = runBlocking {
        val result = serviceReturning(
            OpenCodeModelService.CommandResult(
                exitCode = 0,
                stdout = "zhipuai/glm-5\nopencode/hy3-free\nopencode/big-pickle",
                stderr = "",
                timedOut = false,
            ),
        ).fetch(timeoutMs = 1_000)
        assertEquals(listOf("opencode/big-pickle", "opencode/hy3-free", "zhipuai/glm-5"), result.models)
        assertEquals(null, result.error)
    }

    @Test
    fun fetch_reports_nonzero_exit() = runBlocking {
        val result = serviceReturning(
            OpenCodeModelService.CommandResult(exitCode = 42, stdout = "", stderr = "broken", timedOut = false),
        ).fetch(timeoutMs = 1_000)
        assertTrue(result.models.isEmpty())
        assertNotNull(result.error)
        assertTrue("42" in result.error!!, result.error)
    }

    @Test
    fun fetch_reports_timeout() = runBlocking {
        val result = serviceReturning(
            OpenCodeModelService.CommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true),
        ).fetch(timeoutMs = 100)
        assertTrue(result.models.isEmpty())
        assertNotNull(result.error)
        assertTrue("timed out" in result.error!!, result.error)
    }

    private fun serviceReturning(result: OpenCodeModelService.CommandResult) =
        OpenCodeModelService("/bin/sh") { _: Path, _: Long -> result }
}
