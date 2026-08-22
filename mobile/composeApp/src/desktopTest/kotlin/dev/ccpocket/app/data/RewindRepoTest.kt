package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.RewindDone
import dev.ccpocket.protocol.RewindMode
import dev.ccpocket.protocol.RewindPreview
import dev.ccpocket.protocol.RewindRefusal
import dev.ccpocket.protocol.RewindSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #282, client side: the capability gate and the rewind state machine.
 *
 * The gate matters more than it looks. The app has no version handshake for this feature — it decides
 * whether to offer a rewind purely from whether the replayed row carried transcript coordinates. Get
 * that wrong and the app sends an anchor a daemon cannot resolve, which is exactly the "cut somewhere
 * the user did not point at" class of failure the design refuses to allow.
 */
class RewindRepoTest {

    private fun repo(scope: CoroutineScope, sent: MutableList<Frame>) =
        PocketRepository(scope).apply { onSendForTest = { sent += it } }

    /** Bring a Claude conversation live and replay two user turns WITH coordinates.
     *  [sid] is per test on purpose: composer drafts persist in a process-wide store keyed by session id,
     *  so a shared id would let one test's prefill leak into the next one's assertion. */
    private fun PocketRepository.liveClaudeChat(sid: String = "sid-1") {
        receiveForTest(SessionLive("c1", "/x", sessionId = sid, agent = AgentKind.CLAUDE))
        clearDraft(sid)
        receiveForTest(
            ConvoHistory(
                "c1",
                listOf(
                    HistoryMessage(ChatRole.USER, "first", seq = 1, uuid = "u0"),
                    HistoryMessage(ChatRole.ASSISTANT, "sure", seq = 2),
                    HistoryMessage(ChatRole.USER, "second", seq = 3, uuid = "u1"),
                ),
            ),
        )
    }

    private fun user(text: String, seq: Long? = null, uuid: String? = null) =
        ChatItem.User(text, seq = seq, uuid = uuid)

    @Test
    fun a_row_without_coordinates_offers_no_rewind() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat()
            // an older daemon's replay, or a bubble this device just composed: no anchor exists yet
            assertFalse(repo.canRewind(user("no coords")))
            assertFalse(repo.canRewind(user("seq only", seq = 3)))
            assertFalse(repo.canRewind(user("uuid only", uuid = "u1")))
            assertTrue(repo.canRewind(user("both", seq = 3, uuid = "u1")))
        } finally { scope.cancel() }
    }

    @Test
    fun a_non_claude_session_offers_no_rewind_even_with_coordinates() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.receiveForTest(SessionLive("c1", "/x", sessionId = "sid-1", agent = AgentKind.CODEX))
            assertFalse(repo.canRewind(user("both", seq = 3, uuid = "u1")))
        } finally { scope.cancel() }
    }

    @Test
    fun the_replay_carries_the_coordinates_onto_the_chat_rows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat()
            val turns = repo.messages.filterIsInstance<ChatItem.User>()
            assertEquals(listOf<Long?>(1, 3), turns.map { it.seq })
            assertEquals(listOf("u0", "u1"), turns.map { it.uuid })
        } finally { scope.cancel() }
    }

    @Test
    fun the_gesture_asks_for_a_dry_run_first_and_only_then_cuts() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.liveClaudeChat()
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)

            val dry = assertNotNull(sent.filterIsInstance<RewindSession>().lastOrNull())
            assertTrue(dry.dryRun, "the confirmation sheet cannot be skipped")
            assertEquals(3L, dry.anchorSeq)
            assertEquals("u1", dry.anchorUuid)
            // the sheet opens immediately, in a loading state — the gesture must feel answered before the
            // round trip lands
            assertNotNull(repo.rewindSheet.value)
            assertNull(repo.rewindSheet.value?.counts)

            repo.receiveForTest(RewindPreview("c1", dropTurns = 2, dropToolCalls = 5, ok = true))
            assertEquals(2, repo.rewindSheet.value?.counts?.turns)
            assertEquals(5, repo.rewindSheet.value?.counts?.toolCalls)

            repo.confirmRewind()
            val real = assertNotNull(sent.filterIsInstance<RewindSession>().lastOrNull())
            assertFalse(real.dryRun)
            assertEquals(dry.anchorUuid, real.anchorUuid, "the cut must use the anchor the numbers described")
        } finally { scope.cancel() }
    }

    @Test
    fun a_second_confirm_tap_cannot_send_a_second_cut() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.liveClaudeChat()
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()
            repo.confirmRewind()
            assertEquals(1, sent.filterIsInstance<RewindSession>().count { !it.dryRun })
        } finally { scope.cancel() }
    }

    @Test
    fun a_refused_dry_run_closes_the_sheet_and_surfaces_the_reason() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat()
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 0, 0, ok = false, reason = RewindRefusal.STALE))

            assertNull(repo.rewindSheet.value, "no sheet can be confirmed against a refused preview")
            assertEquals(RewindRefusal.STALE, repo.rewindError.value)
            repo.dismissRewindError()
            assertNull(repo.rewindError.value)
        } finally { scope.cancel() }
    }

    @Test
    fun a_successful_rewind_records_the_lineage_and_prefills_the_anchors_words() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat("sid-rewind")
            val epoch = repo.composerEpoch.value
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()
            repo.receiveForTest(RewindDone("c1", ok = true, newConvoId = "c2"))

            val lineage = assertNotNull(repo.sessionLineage.value)
            assertEquals("c2", lineage.convoId, "the banner is scoped to the branch, not to whatever is open")
            assertEquals(RewindMode.REWIND, lineage.mode)
            assertEquals("sid-rewind", lineage.fromSessionId)
            assertNull(repo.rewindSheet.value)
            assertNull(repo.rewindError.value)

            // "say it differently" — the anchor's own words come back, through the draft + epoch bump the
            // composers re-read (never poked into a live IME field)
            assertEquals("second", repo.draftFor(repo.composerKey()))
            assertTrue(repo.composerEpoch.value > epoch)
        } finally { scope.cancel() }
    }

    @Test
    fun a_fork_records_lineage_but_leaves_the_composer_empty() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat("sid-fork")
            val epoch = repo.composerEpoch.value
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.FORK)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()
            repo.receiveForTest(RewindDone("c1", ok = true, newConvoId = "c2"))

            assertEquals(RewindMode.FORK, repo.sessionLineage.value?.mode)
            // a fork EXPLORES from that point; it is not a retry of the message, so prefilling it would be
            // putting words in the user's mouth
            assertEquals("", repo.draftFor(repo.composerKey()))
            assertEquals(epoch, repo.composerEpoch.value)
        } finally { scope.cancel() }
    }

    @Test
    fun a_failed_execute_leaves_the_view_where_it_was() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat("sid-failed")
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()
            repo.receiveForTest(RewindDone("c1", ok = false, reason = RewindRefusal.NOT_IDLE))

            assertNull(repo.sessionLineage.value, "nothing branched, so nothing to label")
            assertEquals(RewindRefusal.NOT_IDLE, repo.rewindError.value)
            assertEquals("c1", repo.convoId.value)
            assertEquals("", repo.draftFor(repo.composerKey()), "a failed rewind must not rewrite the composer")
        } finally { scope.cancel() }
    }

    @Test
    fun the_answer_still_lands_when_the_branch_announces_itself_first() {
        // The daemon opens the branch and emits its SessionLive BEFORE answering the request, so the app's
        // convoId has already moved to the branch by the time RewindDone arrives. Matching the answer
        // against the LIVE conversation would therefore drop it exactly when it succeeded — the lineage
        // banner and the prefill would silently never appear.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat("sid-race")
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()

            // the branch announces itself first, still resuming this session's id
            repo.receiveForTest(SessionLive("c2", "/x", sessionId = "sid-race", agent = AgentKind.CLAUDE))
            assertEquals("c2", repo.convoId.value)

            repo.receiveForTest(RewindDone("c1", ok = true, newConvoId = "c2"))

            assertEquals("c2", repo.sessionLineage.value?.convoId)
            assertEquals("second", repo.draftFor(repo.composerKey()))
        } finally { scope.cancel() }
    }

    @Test
    fun dismissing_the_sheet_after_confirming_still_lands_the_answer() {
        // Nothing can recall a cut already on the wire, so a late dismiss must not orphan its result and
        // leave the user in a branch with no banner explaining where they are.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat("sid-late-cancel")
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("c1", 1, 0, ok = true))
            repo.confirmRewind()
            repo.cancelRewind()

            repo.receiveForTest(RewindDone("c1", ok = true, newConvoId = "c2"))
            assertEquals("c2", repo.sessionLineage.value?.convoId)
        } finally { scope.cancel() }
    }

    @Test
    fun an_answer_for_another_conversation_is_ignored() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.liveClaudeChat()
            repo.startRewind(user("second", seq = 3, uuid = "u1"), RewindMode.REWIND)
            repo.receiveForTest(RewindPreview("other-convo", 9, 9, ok = true))
            assertNull(repo.rewindSheet.value?.counts, "a stray preview must not repaint this sheet")
            repo.receiveForTest(RewindDone("other-convo", ok = true, newConvoId = "cX"))
            assertNull(repo.sessionLineage.value)
        } finally { scope.cancel() }
    }
}
