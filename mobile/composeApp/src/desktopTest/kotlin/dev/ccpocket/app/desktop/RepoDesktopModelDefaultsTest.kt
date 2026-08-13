package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepoDesktopModelDefaultsTest {
    private val keys = listOf(
        PocketRepository.K_DEFAULT_AGENT,
        PocketRepository.K_DEFAULT_EFFORT,
        PocketRepository.K_DEFAULT_CODEX_EFFORT,
        PocketRepository.K_DEFAULT_OPENCODE_EFFORT,
        PocketRepository.K_DEFAULT_KIMI_EFFORT,
    )
    private var saved: Map<String, String?> = emptyMap()

    @BeforeTest
    fun snapshotStore() {
        saved = keys.associateWith(SecureStore::getString)
        keys.forEach(SecureStore::remove)
    }

    @AfterTest
    fun restoreStore() {
        keys.forEach(SecureStore::remove)
        saved.forEach { (key, value) -> value?.let { SecureStore.putString(key, it) } }
    }

    @Test
    fun adapterWritesSelectedAgentAndCodexNewSessionInheritsIt() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CLAUDE.name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        try {
            repo.setDefaultEffort("high")
            model.defaultAgent = AgentKind.CODEX
            model.defaultEffort = "ultra"

            assertEquals("ultra", model.defaultEffort)
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX))
            assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))

            repo.openSession("/tmp/desktop-codex-default", agent = AgentKind.CODEX)
            assertEquals("ultra", sent.filterIsInstance<OpenSession>().single().effort)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun absentScopedKeysCopyTheLegacySharedValueOnceWithoutChangingClaude() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CODEX.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "xhigh")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        listOf(AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.KIMI).forEach { agent ->
            assertEquals("xhigh", repo.defaultEffortFor(agent), "$agent used the old shared preference")
        }
        listOf(
            PocketRepository.K_DEFAULT_CODEX_EFFORT,
            PocketRepository.K_DEFAULT_OPENCODE_EFFORT,
            PocketRepository.K_DEFAULT_KIMI_EFFORT,
        ).forEach { key -> assertEquals("xhigh", SecureStore.getString(key)) }
        assertEquals("xhigh", SecureStore.getString(PocketRepository.K_DEFAULT_EFFORT))

        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "low")
        val reloaded = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        listOf(AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.KIMI).forEach { agent ->
            assertEquals("xhigh", reloaded.defaultEffortFor(agent), "$agent migration must run only once")
        }
        assertEquals("low", reloaded.defaultEffortFor(AgentKind.CLAUDE))
    }

    @Test
    fun absentCodexKeyCopiesAnExplicitLegacyDefaultMarker() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CODEX.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        assertNull(repo.defaultEffortFor(AgentKind.CODEX))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_CODEX_EFFORT))
        assertNull(repo.defaultEffortFor(AgentKind.OPENCODE))
        assertNull(repo.defaultEffortFor(AgentKind.KIMI))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_OPENCODE_EFFORT))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_KIMI_EFFORT))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_EFFORT))
    }

    @Test
    fun explicitEmptyCodexKeyDoesNotReimportLegacyValue() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CODEX.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "xhigh")
        SecureStore.putString(PocketRepository.K_DEFAULT_CODEX_EFFORT, "")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        assertNull(repo.defaultEffortFor(AgentKind.CODEX))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_CODEX_EFFORT))
        assertEquals("xhigh", repo.defaultEffortFor(AgentKind.OPENCODE))
        assertEquals("xhigh", repo.defaultEffortFor(AgentKind.KIMI))
        assertEquals("xhigh", repo.defaultEffortFor(AgentKind.CLAUDE))
    }

    @Test
    fun legacySharedValueSeedsEveryBackendRegardlessOfSelectedAgent() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CLAUDE.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "high")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        AgentKind.entries.forEach { agent -> assertEquals("high", repo.defaultEffortFor(agent), "$agent preserves old behavior") }
        listOf(
            PocketRepository.K_DEFAULT_CODEX_EFFORT,
            PocketRepository.K_DEFAULT_OPENCODE_EFFORT,
            PocketRepository.K_DEFAULT_KIMI_EFFORT,
        ).forEach { key -> assertEquals("high", SecureStore.getString(key)) }
        assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))
    }

    @Test
    fun switchingToCodexAfterConstructionCannotMigrateAClaudeValueOnRestart() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CLAUDE.name)
        val first = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_CODEX_EFFORT))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_OPENCODE_EFFORT))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_KIMI_EFFORT))
        // This value did not exist at migration time; it is unquestionably a post-split Claude choice.
        first.setDefaultEffort("high")
        first.setDefaultAgent(AgentKind.CODEX)
        // Mirrors clicking the already-selected "Default" row: state remains null, but the construction-time
        // marker must already make that choice durable.
        first.setDefaultEffortFor(AgentKind.CODEX, null)

        val reloaded = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        assertNull(reloaded.defaultEffortFor(AgentKind.CODEX))
        assertEquals("", SecureStore.getString(PocketRepository.K_DEFAULT_CODEX_EFFORT))
        assertEquals("high", reloaded.defaultEffortFor(AgentKind.CLAUDE))
    }

    @Test
    fun everyBackendKeepsAnIndependentEffortDefault() {
        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

        repo.setDefaultEffortFor(AgentKind.CLAUDE, "high")
        repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
        repo.setDefaultEffortFor(AgentKind.OPENCODE, "medium")
        repo.setDefaultEffortFor(AgentKind.KIMI, "low")

        assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))
        assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX))
        assertEquals("medium", repo.defaultEffortFor(AgentKind.OPENCODE))
        assertEquals("low", repo.defaultEffortFor(AgentKind.KIMI))

        val reloaded = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        assertEquals("high", reloaded.defaultEffortFor(AgentKind.CLAUDE))
        assertEquals("ultra", reloaded.defaultEffortFor(AgentKind.CODEX))
        assertEquals("medium", reloaded.defaultEffortFor(AgentKind.OPENCODE))
        assertEquals("low", reloaded.defaultEffortFor(AgentKind.KIMI))

        repo.setDefaultEffortFor(AgentKind.OPENCODE, null)
        assertNull(repo.defaultEffortFor(AgentKind.OPENCODE))
        assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE), "clearing OpenCode must not clear Claude")
        assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX), "clearing OpenCode must not clear Codex")
        assertEquals("low", repo.defaultEffortFor(AgentKind.KIMI), "clearing OpenCode must not clear Kimi")
    }

    @Test
    fun selectedOpenCodeAtUpgradeStillMigratesTheSharedEffortToEveryBackend() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.OPENCODE.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "xhigh")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        AgentKind.entries.forEach { agent -> assertEquals("xhigh", repo.defaultEffortFor(agent)) }

        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "low")
        val reloaded = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        assertEquals("xhigh", reloaded.defaultEffortFor(AgentKind.OPENCODE), "migration must not re-run")
        assertEquals("xhigh", reloaded.defaultEffortFor(AgentKind.CODEX), "migration must not re-run")
        assertEquals("xhigh", reloaded.defaultEffortFor(AgentKind.KIMI), "migration must not re-run")
        assertEquals("low", reloaded.defaultEffortFor(AgentKind.CLAUDE))
    }

    @Test
    fun selectedKimiAtUpgradeStillMigratesTheSharedEffortToEveryBackend() {
        SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.KIMI.name)
        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "medium")

        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        AgentKind.entries.forEach { agent -> assertEquals("medium", repo.defaultEffortFor(agent)) }

        SecureStore.putString(PocketRepository.K_DEFAULT_EFFORT, "low")
        val reloaded = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
        assertEquals("medium", reloaded.defaultEffortFor(AgentKind.KIMI), "migration must not re-run")
        assertEquals("medium", reloaded.defaultEffortFor(AgentKind.CODEX), "migration must not re-run")
        assertEquals("medium", reloaded.defaultEffortFor(AgentKind.OPENCODE), "migration must not re-run")
        assertEquals("low", reloaded.defaultEffortFor(AgentKind.CLAUDE))
    }
}
