package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CommandList
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SlashCommand
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The persistent inbound collector must survive one frame whose handling throws. It used to be a bare
 * `collect { handle(it) }`: the first exception ended the collector, `inboundJob` stayed non-null so
 * launchTransport never rebuilt it, and every later frame was silently ignored while the link showed Ready.
 */
class InboundIsolationTest {

    @Test
    fun aThrowingFrameDoesNotStopTheFramesBehindIt() = runBlocking {
        val r = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
        }
        // a UI hook blowing up stands in for any handler failure (merge, side panes, memo host, …)
        r.onTurnFinished = { _, _, _ -> error("notification hook failed") }

        r.collectInboundForTest(
            flowOf(
                SessionLive("c1", "/w", "sid-1", executing = true),
                AssistantChunk("c1", 1L,StreamPiece.Text("hello")),
                TurnDone("c1"), // throws inside handle() via onTurnFinished
                CommandList("c1", listOf(SlashCommand("compact"))),
            ),
        )

        assertEquals(listOf("compact"), r.slashCommands.map { it.name }, "the frame after the failure is still applied")
    }
}
