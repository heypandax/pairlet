package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two automatic re-opens — reconnect restore and SessionGone recovery — must carry the session's
 * launch parameters. If the daemon lost the conversation they become a COLD resume, which launches with
 * whatever the request names; a bare request relaunched on CLI defaults, and the following SessionLive
 * then wrote those defaults back over the user's choice.
 */
class ResumeOpenParamsTest {

    private fun repo(sent: MutableList<Frame>) =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
        }

    @Test
    fun reconnectRestoreCarriesModelEffortAndPermissionMode() = runBlocking {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(sent)
        r.receiveForTest(
            SessionLive("c1", "/w", "sid-claude", agent = AgentKind.CLAUDE, model = "opus", effort = "high", permissionMode = "auto"),
        )
        r.restoreAfterReconnectForTest()
        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals("sid-claude", open.resumeId)
        assertEquals("opus", open.model)
        assertEquals("high", open.effort)
        assertEquals("auto", open.permissionMode)
        assertEquals(AgentKind.CLAUDE, open.agent)
    }

    @Test
    fun reconnectRestoreCarriesCodexServiceTier() = runBlocking {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(sent)
        r.receiveForTest(
            SessionLive("c1", "/w", "sid-codex", agent = AgentKind.CODEX, model = "gpt-5.1-codex", effort = "xhigh", serviceTier = "priority"),
        )
        r.restoreAfterReconnectForTest()
        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals("gpt-5.1-codex", open.model)
        assertEquals("xhigh", open.effort)
        assertEquals("priority", open.serviceTier)
        assertEquals(AgentKind.CODEX, open.agent)
    }

    @Test
    fun sessionGoneRecoveryCarriesTheSameParameters() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = repo(sent)
        r.receiveForTest(
            SessionLive("c1", "/w", "sid-gone", agent = AgentKind.CLAUDE, model = "opus", effort = "high", permissionMode = "auto"),
        )
        assertTrue(r.sendPrompt("keep going"))
        r.receiveForTest(SessionGone("c1"))
        val open = sent.filterIsInstance<OpenSession>().single()
        assertEquals("sid-gone", open.resumeId)
        assertEquals("opus", open.model)
        assertEquals("high", open.effort)
        assertEquals("auto", open.permissionMode)
    }
}
