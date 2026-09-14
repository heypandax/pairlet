package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #363: a new-session click owns its model/backend even while dispatch is queued or retried. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionDefaultsSnapshotTest {
    private fun checkSnapshot(initial: String?, picked: String? = null, retry: Boolean = false, switchAgent: Boolean = false) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultModelFor(AgentKind.CLAUDE, initial)
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.1-codex")
            repo.setDefaultMode(PermissionMode.BYPASS_PERMISSIONS)
            repo.onSendForTest = { sent += it }
            assertTrue(repo.openSession("/tmp/snapshot", startModel = picked))
            if (retry) {
                scheduler.runCurrent()
                repo.receiveForTest(PocketError("open_failed", "fixture failure"))
                sent.clear()
            }
            repo.setDefaultModelFor(AgentKind.CLAUDE, "claude-fable-5")
            if (switchAgent) repo.setDefaultAgent(AgentKind.CODEX)
            repo.setDefaultMode(PermissionMode.DEFAULT)
            if (retry) assertTrue(repo.retryOpen())
            scheduler.runCurrent()
            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals(AgentKind.CLAUDE, open.agent)
            assertEquals(picked ?: initial, open.model)
            assertEquals(PermissionMode.BYPASS_PERMISSIONS, open.mode)
            assertEquals("claude-fable-5", repo.defaultModelFor(AgentKind.CLAUDE), "snapshot must not rewrite settings")
            assertEquals(PermissionMode.DEFAULT, repo.defaultMode.value)
        } finally {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultMode(PermissionMode.DEFAULT)
            repo.setDefaultModelFor(AgentKind.CLAUDE, null)
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    /**
     * #363: the tuple the new-session sheet / popover hands over (mode, agent, nativeMode, model) must reach
     * the wire unchanged — including Claude's native Auto — and a Settings change made while the open is
     * still queued (or before Retry) must not leak into it.
     */
    private fun checkPickedTuple(mode: PermissionMode, agent: AgentKind, native: String?, model: String?, retry: Boolean) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.setDefaultAgent(AgentKind.CODEX)
            repo.setDefaultMode(PermissionMode.PLAN)
            repo.onSendForTest = { sent += it }
            assertTrue(repo.openSession("/tmp/picked", startMode = mode, agent = agent, startPermissionMode = native, startModel = model))
            if (retry) {
                scheduler.runCurrent()
                repo.receiveForTest(PocketError("open_failed", "fixture failure"))
                sent.clear()
            }
            repo.setDefaultAutoMode() // Settings flips to Auto mid-flight
            repo.setDefaultAgent(AgentKind.KIMI)
            if (retry) assertTrue(repo.retryOpen())
            scheduler.runCurrent()
            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals(agent, open.agent)
            assertEquals(mode, open.mode)
            assertEquals(native, open.permissionMode)
            assertEquals(model, open.model)
        } finally {
            repo.setDefaultAgent(AgentKind.CLAUDE)
            repo.setDefaultMode(PermissionMode.DEFAULT)
            scope.cancel()
        }
    }

    @Test fun pickedFullAccessReachesTheWire() =
        checkPickedTuple(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CLAUDE, null, "sonnet", retry = false)
    @Test fun pickedNativeAutoReachesTheWire() =
        checkPickedTuple(PermissionMode.DEFAULT, AgentKind.CLAUDE, dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO, "sonnet", retry = false)
    @Test fun pickedNativeAutoSurvivesRetry() =
        checkPickedTuple(PermissionMode.DEFAULT, AgentKind.CLAUDE, dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO, null, retry = true)
    @Test fun pickedCodexFullAccessSurvivesRetry() =
        checkPickedTuple(PermissionMode.BYPASS_PERMISSIONS, AgentKind.CODEX, null, null, retry = true)

    @Test fun queuedNewSessionKeepsDefaultAgent() = checkSnapshot("sonnet", switchAgent = true)
    @Test fun retryKeepsDefaultAgent() = checkSnapshot("sonnet", retry = true, switchAgent = true)
    @Test fun queuedNewSessionKeepsDefaultModelAndAgent() = checkSnapshot("sonnet")
    @Test fun queuedNewSessionKeepsNullDefaultAndAgent() = checkSnapshot(null)
    @Test fun queuedNewSessionKeepsExplicitModelAndAgent() = checkSnapshot("sonnet", picked = "haiku")
    @Test fun retryKeepsDefaultModelAndAgent() = checkSnapshot("sonnet", retry = true)
    @Test fun retryKeepsNullDefaultAndAgent() = checkSnapshot(null, retry = true)
    @Test fun retryKeepsExplicitModelAndAgent() = checkSnapshot("sonnet", picked = "haiku", retry = true)
}
