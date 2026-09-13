package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationHistoryReadErrorTest {
    private class HistoryBackend : AgentBackend {
        override val kind = AgentKind.DSH
        var slice = ReplaySlice(listOf(HistoryMessage(ChatRole.USER, "existing history")), lastSeq = 10)
        val cursors = CopyOnWriteArrayList<Long?>()
        override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?): ReplaySlice {
            cursors += sinceSeq
            return slice
        }
        override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int) = slice
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = error("history reads must not start a process")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    // Include a bogus model-error row to prove the emission guard cannot accidentally send it as
    // authoritative history, even if a future reader supplies both messages and a read error.
    private fun unavailable() = ReplaySlice(
        listOf(HistoryMessage(ChatRole.ASSISTANT, "not a model reply", error = true)),
        quality = "unavailable", readError = "DSH history unavailable: unsupported session format v9",
    )

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun generation_cursor_survives_history_open_and_page_wire_roundtrips() {
        val cursor = 3L * (1L shl 32) + 10L
        fun roundtrip(frame: Frame): Frame = PocketJson.decodeFromString<Envelope>(
            PocketJson.encodeToString(Envelope(id = "cursor", ts = 0, body = frame)),
        ).body
        val history = roundtrip(ConvoHistory("c", emptyList(), firstSeq = cursor - 2, lastSeq = cursor)) as ConvoHistory
        assertEquals(cursor, history.lastSeq)
        val reopen = roundtrip(OpenSession("/work", resumeId = "sid", lastEventSeq = history.lastSeq)) as OpenSession
        assertEquals(cursor, reopen.lastEventSeq)
        val page = roundtrip(FetchHistoryPage("c", beforeSeq = history.firstSeq!!)) as FetchHistoryPage
        assertEquals(cursor - 2, page.beforeSeq)
    }

    @Test
    fun failed_initial_history_is_a_pocket_error_for_legacy_and_cursor_clients() = runBlocking {
        for (cursor in listOf(null, 3L * (1L shl 32) + 10L)) {
            val frames = CopyOnWriteArrayList<Frame>()
            val backend = HistoryBackend().apply { slice = unavailable() }
            val scope = CoroutineScope(Dispatchers.Default)
            val convo = Conversation("cHistory", Files.createTempDirectory("ccp-history-error"),
                PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
            try {
                convo.open(resumeId = "sid", model = null, sinceSeq = cursor)
                await { frames.any { it is PocketError } }
                assertTrue(frames.any { it is PocketError && it.code == "history_unavailable" && "format v9" in it.message })
                assertFalse(frames.any { it is ConvoHistory || it is TurnDone }, frames.toString())
                assertEquals(listOf(cursor), backend.cursors.toList())
            } finally {
                convo.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun failed_reattach_preserves_existing_history_and_failed_page_completes_empty() = runBlocking {
        val frames = CopyOnWriteArrayList<Frame>()
        val backend = HistoryBackend()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation("cHistory", Files.createTempDirectory("ccp-history-error"),
            PermissionMode.DEFAULT, { frames.add(it) }, scope, backend)
        try {
            convo.open(resumeId = "sid", model = null)
            await { frames.any { it is ConvoHistory } }
            backend.slice = unavailable()
            val reconnect = CopyOnWriteArrayList<Frame>()
            convo.replayReattach(OutboundSink { reconnect.add(it) }, sinceSeq = 10L)
            assertFalse(reconnect.any { it is ConvoHistory || it is TurnDone }, reconnect.toString())
            assertEquals(1, reconnect.count { it is PocketError && it.code == "history_unavailable" })

            val page = CopyOnWriteArrayList<Frame>()
            convo.fetchHistoryPage(10L, 50, OutboundSink { page.add(it) })
            val response = page.filterIsInstance<ConvoHistoryPage>().single()
            assertTrue(response.messages.isEmpty())
            assertTrue(response.hasMore, "a read failure must leave paging retryable")
            assertEquals(10L, response.firstSeq)
            assertEquals(1, page.count { it is PocketError && it.code == "history_unavailable" })
            assertFalse(page.any { it is ConvoHistory || it is TurnDone })
            backend.slice = ReplaySlice(listOf(HistoryMessage(ChatRole.USER, "restored older history")), firstSeq = 1)
            val retried = CopyOnWriteArrayList<Frame>()
            convo.fetchHistoryPage(response.firstSeq!!, 50, OutboundSink { retried.add(it) })
            assertEquals("restored older history", retried.filterIsInstance<ConvoHistoryPage>().single().messages.single().text)
            assertFalse(retried.any { it is PocketError })
        } finally {
            convo.close()
            scope.cancel()
        }
    }
}
