package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #380 — the "collapse tool process" switch is per device × computer × agent × session. */
class ToolProcessPrefsTest {

    private class MemoryStore : ToolProcessPrefStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun scope(acct: String? = "mac", agent: AgentKind = AgentKind.CLAUDE, session: String? = "s1", convo: String? = "c1") =
        ToolProcessScope(acct, agent, session, convo)

    @Test
    fun offByDefaultAndPersistedPerSession() {
        val store = MemoryStore()
        val prefs = ToolProcessPrefs(store)
        assertFalse(prefs.isCollapsed(scope()), "default keeps today's expanded stream")
        prefs.setCollapsed(scope(), true)
        assertTrue(prefs.isCollapsed(scope()))
        assertTrue(ToolProcessPrefs(store).isCollapsed(scope(convo = "c-after-reconnect")), "a reconnect (new convoId) keeps it")
        prefs.setCollapsed(scope(), false)
        assertTrue(store.map.isEmpty(), "turning it back off leaves nothing stored")
    }

    @Test
    fun neverSharedAcrossComputersAgentsOrSessions() {
        val prefs = ToolProcessPrefs(MemoryStore())
        prefs.setCollapsed(scope(), true)
        assertFalse(prefs.isCollapsed(scope(acct = "other-mac")))
        assertFalse(prefs.isCollapsed(scope(agent = AgentKind.CODEX)))
        assertFalse(prefs.isCollapsed(scope(session = "s2", convo = "c2")))
        assertFalse(prefs.isCollapsed(null))
    }

    @Test
    fun aNewSessionUsesItsConvoIdUntilTheSessionIdLandsThenMigrates() {
        val store = MemoryStore()
        val prefs = ToolProcessPrefs(store)
        val fresh = scope(session = null, convo = "c9")
        prefs.setCollapsed(fresh, true)
        assertTrue(prefs.isCollapsed(fresh))
        assertTrue(store.map.isEmpty(), "no durable key without a stable session id")
        assertFalse(prefs.isCollapsed(scope(session = null, convo = "c9", agent = AgentKind.CODEX)), "temp state is agent-scoped too")
        val landed = scope(session = "s9", convo = "c9")
        assertTrue(prefs.isCollapsed(landed), "visible the moment the id lands, before migration runs")
        prefs.migrate(landed)
        assertTrue(ToolProcessPrefs(store).isCollapsed(scope(session = "s9", convo = "later")), "migrated to the durable key")
        assertFalse(prefs.isCollapsed(scope(session = "s-other", convo = "c9")), "the temp entry is consumed, not reused")
    }

    @Test
    fun setWithNoIdentityIsANoOp() {
        val store = MemoryStore()
        val prefs = ToolProcessPrefs(store)
        prefs.setCollapsed(scope(session = null, convo = null), true)
        assertFalse(prefs.isCollapsed(scope(session = null, convo = null)))
        assertEquals(emptyMap(), store.map)
    }
}
