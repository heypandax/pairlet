package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.FetchUsage
import dev.ccpocket.protocol.Frame
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

    /**
     * The frames a DaemonInfo itself legitimately produces, which are not the user's agent-scoped requests under
     * test: the ClientCaps re-declaration, and the Codex catalog PREFETCH (Codex catalog cache) — a correlated
     * `FetchModels(agent=CODEX, requestId=…)` sent only because the daemon just advertised "codex". Everything
     * else that leaves the repository after the handshake is still subject to the guard, including a plain
     * FetchModels for an agent the daemon did not advertise.
     */
    private fun handshakeOwn(f: Frame) =
        f is dev.ccpocket.protocol.ClientCaps || (f is FetchModels && f.agent == AgentKind.CODEX && f.requestId != null)

    @Test
    fun every_agent_scoped_frame_is_blocked_after_daemon_omits_its_agent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val all = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { all += it; if (!handshakeOwn(it)) sent += it } }
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
            // the advertisement's own prefetch is the ONLY agent frame the handshake may produce, and it names the
            // advertised agent — it is not a leak of the guard
            assertEquals(1, all.count { it is FetchModels }, "exactly one prefetch, for the advertised Codex")
            assertTrue(all.filterIsInstance<FetchModels>().all { it.agent == AgentKind.CODEX && it.requestId != null })
            zcodeFrames.forEach { repo.sendForTest(it) }

            assertTrue(sent.isEmpty(), "no frame may let an unsupported agent fall back to Claude")
        } finally {
            scope.cancel()
        }
    }

    /** A daemon that does NOT advertise Codex gets no prefetch — the guard applies to the handshake's own frame too. */
    @Test
    fun the_codex_prefetch_only_follows_an_advertisement_that_names_codex() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { if (it !is dev.ccpocket.protocol.ClientCaps) sent += it } }
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude")))
            repo.receiveForTest(DaemonInfo()) // an older daemon that advertises nothing at all
            assertTrue(sent.isEmpty(), "no Codex, no prefetch: $sent")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun all_agent_usage_request_remains_unscoped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { if (!handshakeOwn(it)) sent += it } }
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
            repo.sendForTest(FetchUsage(agent = null))

            assertEquals(listOf<Frame>(FetchUsage(agent = null)), sent)
        } finally {
            scope.cancel()
        }
    }
}
