package dev.ccpocket.app.data

import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persisted per-session launch parameters keep the last 100 sessions. "Last" must mean most
 * recently USED: re-putting an existing key into the map does not move it, so a session the user
 * opens every day — first seen long ago — was the first to be evicted.
 */
class SessionParamsRecencyTest {

    @Test
    fun aSessionInUseSurvivesTheHundredSessionCap() {
        SecureStore.remove(PocketRepository.K_SESSION_PARAMS)
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seed = PocketRepository(seedScope)
        try {
            // the daily session, first seen before everything else
            seed.receiveForTest(SessionLive("c1", "/x", "sid-daily", agent = AgentKind.CLAUDE, model = "claude-daily"))
            // 99 other sessions browsed since (one convoId: each re-announce rebinds the view)
            for (i in 1..99) seed.receiveForTest(SessionLive("c1", "/x", "sid-$i", agent = AgentKind.CLAUDE, model = "claude-$i"))
            // the daily one is used again…
            seed.receiveForTest(SessionLive("c1", "/x", "sid-daily", agent = AgentKind.CLAUDE, model = "claude-daily"))
            // …and then one more new session pushes the map past 100
            seed.receiveForTest(SessionLive("c1", "/x", "sid-new", agent = AgentKind.CLAUDE, model = "claude-new"))
        } finally {
            seedScope.cancel()
        }

        // app restart: a fresh repository reads the persisted rows
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val r = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            assertTrue(r.openSession("/x", "sid-daily", agent = AgentKind.CLAUDE))
            assertEquals("claude-daily", sent.filterIsInstance<OpenSession>().single().model,
                "the recently used session keeps its model across the restart")
        } finally {
            scope.cancel()
            SecureStore.remove(PocketRepository.K_SESSION_PARAMS)
        }
    }
}
