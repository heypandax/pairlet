package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.ScheduleCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentFrameGuardTest {
    private val zcodeFrames = listOf<Frame>(
        OpenSession("/tmp/project", agent = AgentKind.ZCODE),
        ScheduleCreate("/tmp/project", "continue", 1L, agent = AgentKind.ZCODE),
        FetchModels(AgentKind.ZCODE),
        ListSessionFiles("/tmp/project", "session", AgentKind.ZCODE),
        ReadFile("/tmp/project", "session", "README.md", AgentKind.ZCODE),
        ExportFile("convo", "/tmp/project", "session", "report.pdf", AgentKind.ZCODE),
        ReadFileDiff("/tmp/project", "session", "README.md", AgentKind.ZCODE),
        CreateHandoff(
            workdir = "/tmp/project",
            sessionId = "session",
            brief = HandoffBrief(request = "review"),
            agent = AgentKind.ZCODE,
        ),
        FetchUsage(agent = AgentKind.ZCODE),
    )

    @Test
    fun every_agent_scoped_frame_passes_before_daemon_info() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            zcodeFrames.forEach { repo.sendForTest(it) }

            assertEquals(zcodeFrames, sent, "the reconnect window must not guess that an agent is unsupported")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun every_agent_scoped_frame_is_blocked_after_daemon_omits_its_agent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
            zcodeFrames.forEach { repo.sendForTest(it) }

            assertTrue(sent.isEmpty(), "no frame may let an unsupported agent fall back to Claude")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun all_agent_usage_request_remains_unscoped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
            repo.sendForTest(FetchUsage(agent = null))

            assertEquals(listOf<Frame>(FetchUsage(agent = null)), sent)
        } finally {
            scope.cancel()
        }
    }
}
