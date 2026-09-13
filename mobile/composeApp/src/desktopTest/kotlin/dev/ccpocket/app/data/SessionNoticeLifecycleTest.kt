package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Independent reviewer reproduction of Conversation.replayReattach order: SessionLive then full history. */
class SessionNoticeLifecycleTest {
    private val notice = "This session appears under Ungrouped in DSH Web."
    private fun live() = SessionLive("review-convo", "/tmp/review-notice", "review-session",
        executing = false, agent = AgentKind.DSH, notice = notice)
    private fun history() = ConvoHistory("review-convo", listOf(
        HistoryMessage(ChatRole.USER, "existing question"),
        HistoryMessage(ChatRole.ASSISTANT, "existing answer")), lastSeq = 3, firstSeq = 2)

    @Test
    fun focused_live_notice_survives_following_full_history_without_an_extra_live() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope).apply {
                paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
                onSendForTest = {}
                convoId.value = "review-convo"
                workdir.value = "/tmp/review-notice"
            }
            repo.receiveForTest(live())
            assertEquals(1, repo.messages.count { it == ChatItem.Sys(notice, isError = false) })
            repo.receiveForTest(history())
            assertEquals(1, repo.messages.count { it == ChatItem.Sys(notice, isError = false) },
                "a full history immediately after SessionLive must retain the session notice")
        } finally { scope.cancel() }
    }

    @Test
    fun side_pane_live_notice_survives_following_full_history_without_an_extra_live() = runTest {
        val panes = SidePanes(backgroundScope, send = {}, newPromptId = { "review-prompt" })
        val pane = assertNotNull(panes.open("/tmp/review-notice", "review-session", "Fixture", AgentKind.DSH, PermissionMode.DEFAULT))
        panes.route(live())
        assertEquals(1, pane.messages.count { it == ChatItem.Sys(notice, isError = false) })
        panes.route(history())
        assertEquals(1, pane.messages.count { it == ChatItem.Sys(notice, isError = false) },
            "a full history immediately after SessionLive must retain the pane session notice")
    }

    private fun repo(scope: CoroutineScope) = PocketRepository(scope).apply {
        paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
        onSendForTest = {}
        convoId.value = "review-convo"
        workdir.value = "/tmp/review-notice"
    }

    @Test
    fun updates_null_empty_delta_and_clear_have_the_same_lifecycle_in_both_receivers() = runTest {
        val r = repo(backgroundScope)
        val panes = SidePanes(backgroundScope, send = {}, newPromptId = { "prompt" })
        val pane = assertNotNull(panes.open("/tmp/review-notice", "review-session", "Fixture", AgentKind.DSH, PermissionMode.DEFAULT))
        val receivers: List<Pair<(Frame) -> Unit, List<ChatItem>>> = listOf(
            ({ f: Frame -> r.receiveForTest(f) }) to r.messages,
            ({ f: Frame -> panes.route(f) }) to pane.messages,
        )
        receivers.forEach { (receive, rows) ->
            receive(live())
            receive(history())
            receive(live())
            assertEquals(1, rows.filterIsInstance<ChatItem.Sys>().size)
            receive(live().copy(notice = "Updated limitation"))
            assertEquals(listOf(ChatItem.Sys("Updated limitation", false)), rows.filterIsInstance<ChatItem.Sys>())
            receive(ConvoHistory("review-convo", listOf(HistoryMessage(ChatRole.ASSISTANT, "delta answer")), delta = true))
            receive(ConvoHistory("review-convo", emptyList(), delta = true))
            assertEquals(1, rows.filterIsInstance<ChatItem.Sys>().size)
            receive(live().copy(notice = null))
            receive(history())
            assertTrue(rows.none { it is ChatItem.Sys })
            receive(live())
            receive(live().copy(notice = "  "))
            assertTrue(rows.none { it is ChatItem.Sys })
            receive(live())
            receive(ConvoHistory("review-convo", emptyList())) // empty history is not metadata revocation
            assertEquals(listOf(ChatItem.Sys(notice, false)), rows.toList())
            receive(live().copy(notice = null)) // /clear's following authoritative SessionLive
            assertTrue(rows.isEmpty())
            receive(history())
            assertTrue(rows.none { it is ChatItem.Sys }, "clear must not resurrect metadata on the next merge")
            receive(live().copy(sessionId = "new-session", notice = null))
            assertTrue(rows.none { it is ChatItem.Sys })
        }
    }

    @Test
    fun notice_survives_older_page_and_explicit_repository_clear_forgets_it() = runTest {
        val r = repo(backgroundScope)
        r.receiveForTest(live())
        r.receiveForTest(history().copy(hasMore = true))
        r.loadOlderHistory()
        r.receiveForTest(ConvoHistoryPage("review-convo", listOf(HistoryMessage(ChatRole.USER, "oldest")), firstSeq = 1, hasMore = false))
        assertEquals(1, r.messages.count { it == ChatItem.Sys(notice, false) })
        assertTrue(r.messages.any { it is ChatItem.User && it.text == "oldest" })
        r.clearConversation()
        r.receiveForTest(history())
        assertTrue(r.messages.none { it is ChatItem.Sys })
    }

    @Test
    fun metadata_is_excluded_from_merge_receipts_and_does_not_keep_arbitrary_errors() {
        val transcript = ChatTranscript()
        transcript.messages.add(ChatItem.Sys("stale failure"))
        transcript.setSessionNotice(notice)
        transcript.messages.add(ChatItem.User(notice, pending = true, promptId = "pending"))
        transcript.mergeHistory(history()) { before, after ->
            assertTrue(before.none { it is ChatItem.Sys && !it.isError })
            assertTrue(after.none { it is ChatItem.Sys })
            assertTrue(after.filterIsInstance<ChatItem.User>().last().pending)
        }
        assertEquals(listOf(ChatItem.Sys(notice, false)), transcript.messages.filterIsInstance<ChatItem.Sys>())
        transcript.mergeHistory(ConvoHistory("review-convo", listOf(HistoryMessage(ChatRole.USER, notice)), delta = true))
        assertFalse(transcript.messages.filterIsInstance<ChatItem.User>().last().pending, "real history can still resolve a matching prompt")
        transcript.reset()
        transcript.mergeHistory(history())
        assertTrue(transcript.messages.none { it is ChatItem.Sys })
        transcript.setSessionNotice("new session limitation")
        transcript.mergeHistory(history())
        assertEquals(listOf(ChatItem.Sys("new session limitation", false)), transcript.messages.filterIsInstance<ChatItem.Sys>())
    }

    @Test
    fun notice_updates_do_not_split_model_chunks_or_hide_replay_echo() {
        val transcript = ChatTranscript()
        transcript.mergeHistory(history())
        transcript.setSessionNotice(notice)
        transcript.appendChunk(AssistantChunk("review-convo", 4, StreamPiece.Text("existing answer")))
        assertEquals(listOf(ChatItem.Assistant("existing answer")), transcript.messages.filterIsInstance<ChatItem.Assistant>())
        transcript.setSessionNotice("Updated")
        transcript.appendChunk(AssistantChunk("review-convo", 5, StreamPiece.Text(" continued")))
        assertEquals(listOf(ChatItem.Assistant("existing answer continued")), transcript.messages.filterIsInstance<ChatItem.Assistant>())
    }

    @Test
    fun promoting_a_pane_reopens_into_focus_and_replays_its_notice() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val r = repo(scope)
            r.receiveForTest(live().copy(notice = "outgoing notice"))
            val pane = assertNotNull(r.sidePanes.open("/tmp/other", "other-session", "Other", AgentKind.CLAUDE, PermissionMode.DEFAULT))
            val target = SessionLive("other-convo", "/tmp/other", "other-session", agent = AgentKind.CLAUDE, notice = "other notice")
            r.receiveForTest(target)
            assertEquals(listOf(ChatItem.Sys("other notice", false)), pane.messages.toList())
            assertTrue(r.openSession("/tmp/other", "other-session", agent = AgentKind.CLAUDE))
            assertTrue(r.sidePanes.panes.isEmpty())
            assertTrue(r.messages.none { it is ChatItem.Sys }, "open clears the outgoing transcript metadata")
            r.receiveForTest(target)
            r.receiveForTest(history().copy(convoId = "other-convo"))
            assertEquals(listOf(ChatItem.Sys("other notice", false)), r.messages.filterIsInstance<ChatItem.Sys>())
            r.backToBrowse()
            assertTrue(r.messages.isEmpty())
            r.transcript.mergeHistory(history())
            assertTrue(r.messages.none { it is ChatItem.Sys })
        } finally { scope.cancel() }
    }

}
