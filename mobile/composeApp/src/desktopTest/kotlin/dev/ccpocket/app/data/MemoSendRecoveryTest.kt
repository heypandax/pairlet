package dev.ccpocket.app.data

import dev.ccpocket.app.memo.ConfirmedMemoPrompt
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoDispatchStop
import dev.ccpocket.app.memo.MemoEnterResult
import dev.ccpocket.app.memo.MemoLinkSend
import dev.ccpocket.app.memo.MemoPromptReceipt
import dev.ccpocket.app.memo.MemoScope
import dev.ccpocket.app.memo.MemoSubmitResult
import dev.ccpocket.app.memo.MemoTarget
import dev.ccpocket.app.memo.MemoTargetLease
import dev.ccpocket.app.memo.memoPromptText
import dev.ccpocket.app.net.TransientDisposition
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoIds
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A voice memo's confirmed to-do rides the app's chat, but none of the chat's recovery may ever re-deliver it:
 * not the receipt watchdog, not a SessionGone reopen, not the resend cue, not a reconnect. It is sent once, on the
 * one connection that was live when the user confirmed, and what became of it is reported — never guessed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoSendRecoveryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var savedFeature: String? = null
    private var savedDefaultAgent: String? = null

    @BeforeTest
    fun setUp() {
        savedFeature = SecureStore.getString(PocketRepository.K_MEMO_FEATURE)
        savedDefaultAgent = SecureStore.getString(PocketRepository.K_DEFAULT_AGENT)
    }

    @AfterTest
    fun tearDown() {
        SecureStore.putString(PocketRepository.K_MEMO_FEATURE, savedFeature ?: "0")
        savedDefaultAgent?.let { SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, it) }
            ?: SecureStore.putString(PocketRepository.K_DEFAULT_AGENT, AgentKind.CLAUDE.name)
        scope.cancel()
    }

    /** A memo library that lives and dies with the test — never the machine's real one. */
    private class ScratchFiles : dev.ccpocket.app.memo.MemoFiles {
        private val files = HashMap<String, ByteArray>()
        override fun read(dir: String, name: String) = synchronized(files) {
            files["$dir/$name"]?.let { dev.ccpocket.app.memo.MemoRead.Found(it.copyOf()) } ?: dev.ccpocket.app.memo.MemoRead.Missing
        }
        override fun write(dir: String, name: String, bytes: ByteArray) = synchronized(files) {
            files["$dir/$name"] = bytes.copyOf()
            dev.ccpocket.app.memo.MemoWrite.Durable(Unit)
        }
        override fun delete(dir: String, name: String) = synchronized(files) {
            files.remove("$dir/$name")
            dev.ccpocket.app.memo.MemoWrite.Durable(Unit)
        }
        override fun listDirs(dir: String): List<String>? = synchronized(files) {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
        }
        override fun listFiles(dir: String): List<String>? = synchronized(files) {
            files.keys.filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }.map { it.removePrefix("$dir/") }
        }
        override fun deleteDir(dir: String) = synchronized(files) {
            files.keys.filter { it.startsWith("$dir/") }.forEach { files.remove(it) }
            dev.ccpocket.app.memo.MemoWrite.Durable(Unit)
        }
    }

    private fun binding(role: BindingRole = BindingRole.OWNER, id: String = "acct-memo") =
        PairedDaemon(relay = "wss://test", accountId = id, daemonPub = "pk", deviceId = "dev-memo", credential = "cred", role = role)

    private val ready = DaemonInfo(voiceMemoVersion = VoiceMemoLimits.VERSION, voiceMemoAgents = listOf(VOICE_MEMO_AGENT_CLAUDE), voiceMemoStatus = VoiceMemoStatus.READY)

    private inner class Harness(
        role: BindingRole = BindingRole.OWNER,
        featureOn: Boolean = true,
        var write: (Frame) -> TransientDisposition = { TransientDisposition.WRITTEN },
    ) {
        val sent = mutableListOf<Frame>()
        val written = mutableListOf<Frame>()
        val stops = mutableListOf<MemoDispatchStop>()
        var entered: MemoEnterResult? = null
        val receipts = mutableListOf<MemoPromptReceipt>()
        val repo = PocketRepository(scope).apply {
            memoStoreForTest = dev.ccpocket.app.memo.DefaultVoiceMemoStore(ScratchFiles())
            paired.value = binding(role)
            sessionsDir.value = "/w/proj"
            onSendForTest = { sent += it }
            memoWriterForTest = { f -> write(f).also { if (it != TransientDisposition.NOT_WRITTEN) written += f } }
            useRelay = true
            connected.value = true
            memoFeatureOn.value = featureOn
        }
        val host get() = repo.memoHost
        val target = MemoTarget("acct-memo", "sid-a", "/w/proj", AgentKind.CLAUDE, project = "proj", title = "构建排查")
        val memoScope = MemoScope("acct-memo", "dev-memo")

        init {
            scope.launch { host.stops.collect { stops += it } }
            scope.launch { host.receipts.collect { receipts += it } }
        }

        /** What the Compose runtime does every frame: tell snapshot observers what was written. */
        fun settle() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        fun announce(info: DaemonInfo = ready) { repo.receiveForTest(info); settle() }

        fun open(convo: String = "convo-a", sid: String = "sid-a", observing: Boolean = false) {
            assertTrue(repo.openSession("/w/proj", resumeId = sid))
            repo.receiveForTest(SessionLive(convo, "/w/proj", sid, observing = observing, executing = false, agent = AgentKind.CLAUDE))
        }

        fun lease(batch: String = "batch-1"): MemoTargetLease =
            assertIs<MemoEnterResult.Entered>(runBlocking { host.enter(target, batch) }).lease

        fun prompt(lease: MemoTargetLease, todo: String = "检查 mobile build", id: String = "memo-p1") =
            ConfirmedMemoPrompt(VoiceMemoIds.newId(), VoiceMemoIds.newId(), id, todo, lease)

        fun prompts() = sent.filterIsInstance<SendPrompt>()
        fun bubbles() = repo.messages.filterIsInstance<ChatItem.User>().filter { it.memoTodo != null }
    }

    // ── readiness ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_feature_is_off_until_the_switch_is_on_and_this_connection_announced_it() {
        val off = Harness(featureOn = false)
        off.announce()
        assertEquals(MemoBlock.FEATURE_OFF, off.host.readiness.value.block)

        val h = Harness()
        h.settle()
        assertEquals(MemoBlock.OFFLINE, h.host.readiness.value.block, "connected, but this link has not said what it serves")
        h.announce(DaemonInfo())
        assertEquals(MemoBlock.COMPUTER_OUTDATED, h.host.readiness.value.block, "a daemon that predates memos")
        h.announce()
        assertEquals(MemoBlock.NONE, h.host.readiness.value.block)
        assertTrue(h.host.readiness.value.canRecord)
        assertEquals(h.memoScope, h.host.readiness.value.scope)
    }

    @Test
    fun local_prerequisites_and_unknown_words_each_block_with_their_own_reason() {
        val h = Harness()
        val cases = mapOf(
            VoiceMemoStatus.WHISPER_MISSING to MemoBlock.WHISPER_MISSING,
            VoiceMemoStatus.MODEL_MISSING to MemoBlock.MODEL_MISSING,
            VoiceMemoStatus.CONVERTER_MISSING to MemoBlock.CONVERTER_MISSING,
            VoiceMemoStatus.UNSUPPORTED_PLATFORM to MemoBlock.UNSUPPORTED_PLATFORM,
            // a v1 daemon's "no organiser" word is a v1 refusal; a v2 daemon never says it, and this build
            // treats an unfamiliar word as unknown rather than as a reason of its own
            VoiceMemoStatus.AGENT_UNAVAILABLE to MemoBlock.UNKNOWN,
            "later_word" to MemoBlock.UNKNOWN,
        )
        for ((status, block) in cases) {
            h.announce(ready.copy(voiceMemoStatus = status))
            assertEquals(block, h.host.readiness.value.block, status)
        }
        h.announce(ready.copy(voiceMemoVersion = VoiceMemoLimits.VERSION + 1))
        assertEquals(MemoBlock.NONE, h.host.readiness.value.block, "a newer computer still serves this build's contract")
        h.announce(ready.copy(voiceMemoVersion = VoiceMemoLimits.VERSION - 1))
        assertEquals(MemoBlock.COMPUTER_OUTDATED, h.host.readiness.value.block, "a v1 daemon refuses 'none' and 'codex'")
    }

    @Test
    fun an_organiser_is_a_preference_not_a_prerequisite() {
        val h = Harness()
        h.announce(ready.copy(voiceMemoAgents = emptyList()))
        val none = h.host.readiness.value
        assertEquals(MemoBlock.NONE, none.block, "no organiser still records and transcribes")
        assertTrue(none.canRecord)
        assertNull(none.organizer)
        assertEquals(emptyList(), none.organizers)
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, none.defaultAgent)

        h.announce(ready.copy(voiceMemoAgents = listOf(VOICE_MEMO_AGENT_CODEX, VOICE_MEMO_AGENT_CLAUDE)))
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, h.host.readiness.value.organizer, "the app's default agent wins when the computer has it")
        assertEquals(listOf(VOICE_MEMO_AGENT_CODEX, VOICE_MEMO_AGENT_CLAUDE), h.host.readiness.value.organizers)

        h.repo.setDefaultAgent(AgentKind.CODEX)
        h.settle()
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.host.readiness.value.organizer, "changing the default agent re-derives the organiser")
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.host.readiness.value.defaultAgent)

        h.announce(ready.copy(voiceMemoAgents = listOf(VOICE_MEMO_AGENT_CLAUDE)))
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, h.host.readiness.value.organizer, "a default without an adapter falls back to the first one listed")
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.host.readiness.value.defaultAgent, "…and the screen can say so")

        h.announce(ready.copy(voiceMemoStatus = VoiceMemoStatus.WHISPER_MISSING))
        assertNull(h.host.readiness.value.organizer, "no organiser is offered while recording is blocked")
        assertEquals(emptyList(), h.host.readiness.value.organizers)
    }

    @Test
    fun a_non_owner_binding_a_plain_link_and_a_lost_link_cannot_record() {
        val nonOwner = Harness(role = BindingRole.COLLABORATOR)
        nonOwner.announce()
        assertEquals(MemoBlock.NOT_OWNER, nonOwner.host.readiness.value.block)
        assertNull(nonOwner.host.readiness.value.scope)

        val h = Harness()
        h.announce()
        h.repo.connected.value = false
        h.settle()
        assertEquals(MemoBlock.OFFLINE, h.host.readiness.value.block)
        h.repo.connected.value = true
        h.settle()
        assertEquals(MemoBlock.NONE, h.host.readiness.value.block, "the same computer, reached again — on whichever leg")

        h.repo.paired.value = binding(id = "acct-other")
        h.settle()
        assertEquals(MemoBlock.OFFLINE, h.host.readiness.value.block, "another computer has to announce itself; this one's word does not carry over")
    }

    @Test
    fun a_new_connection_is_a_new_generation() {
        val h = Harness()
        h.announce()
        val first = h.host.readiness.value.connectionGeneration
        h.repo.transportLaunches++ // what every real reconnect does
        h.settle()
        h.announce()
        assertTrue(h.host.readiness.value.connectionGeneration > first, "a frame built for the previous connection is refused")
    }

    @Test
    fun a_reannouncement_on_the_same_connection_keeps_the_generation() {
        val h = Harness()
        h.announce()
        val generation = h.host.readiness.value.connectionGeneration
        h.announce()
        assertEquals(generation, h.host.readiness.value.connectionGeneration, "an upload in flight is bound to the generation it started under")
    }

    // ── memo frames ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_memo_frame_goes_out_only_under_the_generation_and_scope_it_was_built_for() = runBlocking<Unit> {
        val h = Harness()
        h.announce()
        val generation = h.host.readiness.value.connectionGeneration
        val frame = VoiceMemoGet(VoiceMemoIds.newId(), VoiceMemoIds.newId())

        assertEquals(MemoLinkSend.Written, h.host.send(h.memoScope, generation, frame))
        assertEquals(MemoLinkSend.NotWritten, h.host.send(h.memoScope, generation - 1, frame), "a stale generation")
        assertEquals(MemoLinkSend.NotWritten, h.host.send(MemoScope("acct-other", "dev-memo"), generation, frame), "another computer")
        assertEquals(1, h.written.size)

        h.write = { TransientDisposition.INDETERMINATE }
        assertEquals(MemoLinkSend.Indeterminate, h.host.send(h.memoScope, generation, frame))
    }

    @Test
    fun switching_the_feature_off_stops_new_memo_frames_but_still_lets_a_cancel_out() = runBlocking<Unit> {
        val h = Harness()
        h.announce()
        val generation = h.host.readiness.value.connectionGeneration
        h.repo.setMemoFeature(false)
        val memoId = VoiceMemoIds.newId()
        val attemptId = VoiceMemoIds.newId()
        assertEquals(MemoLinkSend.NotWritten, h.host.send(h.memoScope, generation, VoiceMemoGet(memoId, attemptId)))
        assertEquals(MemoLinkSend.Written, h.host.send(h.memoScope, generation, VoiceMemoCancel(memoId, attemptId)))
    }

    @Test
    fun no_memo_frame_not_even_a_cancel_goes_to_a_computer_that_never_announced_the_contract() = runBlocking<Unit> {
        val h = Harness()
        h.announce(DaemonInfo())
        val generation = h.host.readiness.value.connectionGeneration
        val memoId = VoiceMemoIds.newId()
        val attemptId = VoiceMemoIds.newId()
        assertEquals(MemoLinkSend.NotWritten, h.host.send(h.memoScope, generation, VoiceMemoCancel(memoId, attemptId)))
        assertEquals(MemoLinkSend.NotWritten, h.host.send(h.memoScope, generation, VoiceMemoGet(memoId, attemptId)))
        assertTrue(h.written.isEmpty(), "an old daemon cannot decode the frame; it would be dropped unanswered")
    }

    @Test
    fun a_query_still_goes_out_while_the_computers_transcriber_is_missing() = runBlocking<Unit> {
        val h = Harness()
        h.announce(ready.copy(voiceMemoStatus = VoiceMemoStatus.MODEL_MISSING))
        val generation = h.host.readiness.value.connectionGeneration
        assertEquals(MemoLinkSend.Written, h.host.send(h.memoScope, generation, VoiceMemoGet(VoiceMemoIds.newId(), VoiceMemoIds.newId())))
    }

    // ── entering the target ───────────────────────────────────────────────────────────────────────

    @Test
    fun a_lease_is_given_only_for_the_exact_session_the_user_confirmed() = runBlocking<Unit> {
        val h = Harness()
        h.announce()
        h.open()
        val lease = h.lease()
        assertEquals("convo-a", lease.convoId)
        assertTrue(h.host.holds(lease))

        val other = h.target.copy(bindingId = "acct-other")
        assertIs<MemoEnterResult.Failed>(h.host.enter(other, "batch-2"))
        assertFalse(h.host.holds(lease), "a new enter retires the previous lease")
    }

    @Test
    fun a_read_only_view_of_the_target_is_not_the_target() = runBlocking<Unit> {
        val h = Harness()
        h.announce()
        h.open(observing = true)
        assertIs<MemoEnterResult.Failed>(h.host.enter(h.target, "batch-1"))
        assertTrue(h.prompts().isEmpty())
    }

    @Test
    fun nothing_is_entered_while_the_feature_is_blocked() = runBlocking<Unit> {
        val h = Harness()
        h.announce(ready.copy(voiceMemoStatus = VoiceMemoStatus.MODEL_MISSING))
        h.open()
        assertIs<MemoEnterResult.Failed>(h.host.enter(h.target, "batch-1"))
    }

    // ── sending ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_confirmed_todo_is_sent_once_with_its_stored_id_and_the_fixed_prefix() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        val lease = h.lease()
        assertEquals(MemoSubmitResult.AwaitingReceipt, h.host.submit(h.prompt(lease, todo = "/clear 然后检查构建")))

        val prompt = h.prompts().single()
        assertEquals("convo-a", prompt.convoId)
        assertEquals("memo-p1", prompt.promptId)
        assertEquals(memoPromptText("/clear 然后检查构建"), prompt.text)
        assertFalse(prompt.text.startsWith("/"), "a to-do that starts with a slash must not read as a control command")
        assertTrue(prompt.images.isEmpty(), "the composer's staged attachments never ride a memo prompt")

        val bubble = h.bubbles().single()
        assertEquals("/clear 然后检查构建", bubble.memoTodo)
        assertEquals(prompt.text, bubble.text, "the bubble keeps the wire text, so a replayed transcript row still matches it")
        assertTrue(bubble.pending)
    }

    @Test
    fun a_receipt_is_reported_by_id_and_marks_the_bubble_delivered() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.host.submit(h.prompt(h.lease()))
        h.repo.receiveForTest(PromptAck("convo-a", "memo-p1"))

        assertEquals(listOf(MemoPromptReceipt("acct-memo", "convo-a", "memo-p1")), h.receipts)
        assertTrue(h.bubbles().single().delivered)
        assertFalse(h.bubbles().single().pending)
    }

    @Test
    fun a_receipt_for_a_chat_that_is_no_longer_on_screen_is_still_reported() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.host.submit(h.prompt(h.lease()))
        h.repo.backToBrowse()
        h.repo.receiveForTest(PromptAck("convo-a", "memo-p1"))
        assertEquals(1, h.receipts.size, "the ledger is matched by id, not by the page that happens to be open")
    }

    @Test
    fun a_frame_proven_unsent_leaves_no_bubble_behind() = runBlocking<Unit> {
        val h = Harness(write = { TransientDisposition.NOT_WRITTEN })
        h.announce(); h.open()
        assertEquals(MemoSubmitResult.NotSubmitted, h.host.submit(h.prompt(h.lease())))
        assertTrue(h.bubbles().isEmpty(), "the chat never shows a message that was not sent")
    }

    @Test
    fun a_write_lost_midway_is_unknown_and_keeps_its_bubble() = runBlocking<Unit> {
        val h = Harness(write = { TransientDisposition.INDETERMINATE })
        h.announce(); h.open()
        assertEquals(MemoSubmitResult.Unknown, h.host.submit(h.prompt(h.lease())))
        assertTrue(h.bubbles().single().pending)
    }

    @Test
    fun the_chats_recovery_paths_never_resend_a_memo_prompt() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.host.submit(h.prompt(h.lease()))
        assertEquals(1, h.prompts().size)

        // the session died under it: an ordinary prompt would reopen and resend; a memo prompt must not
        h.repo.receiveForTest(SessionGone("convo-a"))
        h.repo.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-a", executing = false, agent = AgentKind.CLAUDE))
        // the resend cue and the link's own recovery
        h.repo.resendStalledPrompt()
        h.repo.receiveForTest(AssistantChunk("convo-b", 1, StreamPiece.Text("unrelated output")))

        assertEquals(1, h.prompts().size, "sent once")
        assertTrue(h.sent.filterIsInstance<OpenSession>().size <= 1, "no reopen was started on the memo prompt's behalf")
        assertFalse(h.repo.sendStalled.value)
        assertFalse(h.repo.turnStalled.value)
    }

    @Test
    fun output_in_the_chat_is_not_a_receipt() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.host.submit(h.prompt(h.lease()))
        h.repo.receiveForTest(AssistantChunk("convo-a", 1, StreamPiece.Text("still answering the previous turn")))
        assertTrue(h.receipts.isEmpty())
        assertTrue(h.bubbles().single().pending, "only the matching acknowledgement marks it delivered")
    }

    // ── stopping ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_message_of_the_users_own_stops_the_batch_and_retires_the_lease() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        val lease = h.lease()
        assertTrue(h.repo.sendPrompt("我自己发一条"))
        assertEquals(listOf(MemoDispatchStop.MANUAL_MESSAGE), h.stops)
        assertFalse(h.host.holds(lease))
        assertEquals(MemoSubmitResult.NotSubmitted, h.host.submit(h.prompt(lease, id = "memo-p2")))
        assertTrue(h.prompts().none { it.promptId == "memo-p2" })
    }

    @Test
    fun wiping_the_conversation_stops_the_batch() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        val lease = h.lease()
        h.repo.clearConversation()
        assertEquals(listOf(MemoDispatchStop.MANUAL_MESSAGE), h.stops)
        assertFalse(h.host.holds(lease), "nothing more goes into a conversation that is being cleared")
    }

    @Test
    fun the_same_conversation_announced_as_another_session_ends_the_lease() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        val lease = h.lease()
        assertTrue(h.host.holds(lease))
        h.repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-forked", executing = false, agent = AgentKind.CLAUDE))
        assertFalse(h.host.holds(lease), "the lease is for the session the user confirmed, not for a conversation id")
    }

    @Test
    fun a_fleet_satellite_never_opens_the_memo_library() {
        val satellite = PocketRepository(scope, pinnedTo = binding()).apply { memoFeatureOn.value = true }
        satellite.openMemos()
        assertFalse(satellite.memoOpen.value)
    }

    @Test
    fun leaving_the_chat_or_the_foreground_stops_the_batch() = runBlocking<Unit> {
        val left = Harness()
        left.announce(); left.open()
        val lease = left.lease()
        left.repo.backToBrowse()
        assertEquals(listOf(MemoDispatchStop.LEFT_CHAT), left.stops)
        assertFalse(left.host.holds(lease))

        val background = Harness()
        background.announce(); background.open()
        val held = background.lease()
        background.repo.onAppBackground()
        assertEquals(listOf(MemoDispatchStop.BACKGROUND), background.stops)
        assertFalse(background.host.holds(held))
    }

    @Test
    fun entering_the_target_chat_does_not_stop_the_batch() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.lease()
        assertTrue(h.stops.isEmpty(), "the planned navigation into the target is part of the dispatch")
    }

    @Test
    fun switching_the_feature_off_stops_the_batch() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        val lease = h.lease()
        h.repo.setMemoFeature(false)
        assertEquals(listOf(MemoDispatchStop.FEATURE_OFF), h.stops)
        assertFalse(h.host.holds(lease))
    }

    @Test
    fun leaving_a_chat_a_memo_just_fed_does_not_close_its_session() = runBlocking<Unit> {
        val h = Harness()
        h.announce(); h.open()
        h.host.submit(h.prompt(h.lease()))
        h.repo.backToBrowse()
        assertTrue(h.sent.none { it is CloseSession }, "the task may not have produced output yet; idle-looking is not idle")

        val plain = Harness()
        plain.announce(); plain.open()
        plain.repo.backToBrowse()
        assertNotNull(plain.sent.firstOrNull { it is CloseSession }, "an untouched idle chat is still reclaimed as before")
    }

    // ── target sessions ───────────────────────────────────────────────────────────────────────────

    private fun project(path: String, modified: Long) =
        dev.ccpocket.protocol.DirectoryEntry(path = path, name = path.substringAfterLast('/'), isDir = true, hasSessions = true, lastModified = modified)

    private fun summary(id: String, title: String, modified: Long, live: Boolean = false, rewindOf: String? = null) =
        dev.ccpocket.protocol.SessionSummary(id, title, title, 3, "/w", modified, live = live, agent = AgentKind.CLAUDE, rewindOf = rewindOf)

    @Test
    fun the_picker_shows_projects_then_one_projects_sessions() {
        val h = Harness()
        h.repo.sessionsDir.value = null
        h.announce()
        h.repo.receiveForTest(dev.ccpocket.protocol.Directories(listOf(
            project("/w/alpha", 200), project("/w/beta", 100),
            project("/w/scratch", 50).copy(hasSessions = false),
        )))
        h.sent.clear()

        val first = h.host.catalog(null)
        assertEquals(dev.ccpocket.app.memo.MemoListStatus.READY, first.status)
        assertEquals(listOf("alpha", "beta", "scratch"), first.projects.map { it.name }, "most recent first")
        assertEquals(listOf(null, null, 0), first.projects.map { it.sessionCount }, "not counted yet; a project without sessions says so")
        assertNull(first.project)
        assertEquals(listOf("/w/alpha", "/w/beta"), h.sent.filterIsInstance<dev.ccpocket.protocol.ListSessions>().map { it.workdir }, "only projects that have sessions are asked about")

        assertEquals(dev.ccpocket.app.memo.MemoListStatus.LOADING, h.host.catalog("/w/alpha").project?.status)
        h.repo.receiveForTest(dev.ccpocket.protocol.Sessions("/w/alpha", listOf(
            summary("s-old", "旧会话", 10), summary("s-new", "新会话", 30, live = true),
            summary("s-replaced", "被回退的原会话", 20), summary("s-rewound", "回退后的会话", 25, rewindOf = "s-replaced"),
        )))
        assertNull(h.repo.sessionsDir.value, "a list the picker asked for must not walk the phone into that project")

        val second = h.host.catalog("/w/alpha")
        val sessions = second.project!!
        assertEquals(dev.ccpocket.app.memo.MemoListStatus.READY, sessions.status)
        assertEquals(listOf("s-new", "s-rewound", "s-old"), sessions.sessions.map { it.target.sessionId }, "newest first, the replaced original left out")
        assertEquals(dev.ccpocket.app.memo.MemoTargetStatus.RUNNING, sessions.sessions.first().status)
        assertEquals("alpha", sessions.sessions.first().target.project)
        assertEquals(4, second.projects.first().sessionCount)
        assertEquals(2, h.sent.filterIsInstance<dev.ccpocket.protocol.ListSessions>().size, "inside the freshness window nothing is asked again")

        val scratch = h.host.catalog("/w/scratch")
        assertEquals(3, h.sent.filterIsInstance<dev.ccpocket.protocol.ListSessions>().size, "opening a project asks for its list")
        assertEquals(dev.ccpocket.app.memo.MemoListStatus.LOADING, scratch.project?.status)
    }

    @Test
    fun a_new_session_offers_the_agents_the_computer_can_launch_with_the_default_first() {
        val h = Harness()
        h.announce()
        val options = h.host.catalog(null).newSession
        assertEquals(h.repo.sessionDefaultAgent, options.agents.first())
        assertEquals(h.repo.availableAgents.toSet(), options.agents.toSet())
        assertEquals(h.repo.defaultMode.value.name, options.mode)

        h.repo.connected.value = false
        assertTrue(h.host.catalog(null).newSession.agents.isEmpty(), "nothing can be created while the computer is away")
    }

    @Test
    fun a_new_session_is_created_once_and_the_lease_names_the_session_that_exists() = runBlocking<Unit> {
        val h = Harness()
        h.repo.sessionsDir.value = null
        h.announce()
        val wanted = MemoTarget("acct-memo", "", "/w/proj", AgentKind.CLAUDE, project = "proj", newSession = true)
        val entering = scope.launch { h.entered = h.host.enter(wanted, "batch-new") }
        val open = h.sent.filterIsInstance<OpenSession>().single()
        assertNull(open.resumeId, "a new session, not a resume")
        assertEquals("/w/proj", open.workdir)
        h.repo.receiveForTest(SessionLive("convo-new", "/w/proj", null, executing = false, agent = AgentKind.CLAUDE))
        h.settle()
        entering.join()

        val lease = assertIs<MemoEnterResult.Entered>(h.entered).lease
        assertEquals("convo-new", lease.convoId)
        assertFalse(lease.target.newSession, "the lease names the session that exists now")
        assertEquals("", lease.target.sessionId, "the daemon has not named it yet")
        assertTrue(h.host.holds(lease))

        assertEquals(MemoSubmitResult.AwaitingReceipt, h.host.submit(h.prompt(lease, todo = "检查发布前清单")))
        assertEquals("convo-new", h.prompts().single().convoId)
        assertEquals("检查发布前清单", h.repo.chatTitle.value, "the first to-do names the new session")

        h.repo.receiveForTest(SessionLive("convo-new", "/w/proj", "sid-created", executing = true, agent = AgentKind.CLAUDE))
        val resolved = h.host.resolved(lease)
        assertEquals("sid-created", resolved?.sessionId)
        assertFalse(resolved!!.newSession)
        assertTrue(h.host.holds(lease), "learning the id does not end the lease")
        assertEquals(1, h.sent.filterIsInstance<OpenSession>().size, "created once")
    }

    @Test
    fun a_new_session_on_an_agent_the_computer_cannot_launch_is_refused_before_anything_opens() = runBlocking<Unit> {
        val h = Harness()
        h.announce()
        val missing = AgentKind.entries.firstOrNull { it !in h.repo.availableAgents } ?: return@runBlocking
        val result = h.host.enter(MemoTarget("acct-memo", "", "/w/proj", missing, newSession = true), "batch-x")
        assertEquals(dev.ccpocket.app.memo.MemoEnterFailure.AGENT_UNAVAILABLE, assertIs<MemoEnterResult.Failed>(result).reason)
        assertTrue(h.sent.none { it is OpenSession })
    }

    @Test
    fun a_session_list_nobody_asked_for_is_left_to_the_page_router() {
        val h = Harness()
        h.announce()
        assertFalse(h.host.onSessions(dev.ccpocket.protocol.Sessions("/w/other", emptyList())))
    }

    @Test
    fun the_chat_microphone_waits_while_a_memo_is_recording() {
        val h = Harness()
        h.announce(); h.open()
        assertFalse(h.host.holdsMicrophone)
    }
}

/**
 * 2026-09-29: after the daemon restarted, the phone's capability declaration was written by the previous
 * connection's writer under the dead session. The daemon's new holder never heard it and silently dropped every
 * memo frame — the memo sat on "uploading" for ever.
 */
class CapsRedeclarationTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun every_announcement_is_answered_with_the_capability_declaration() {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-caps", daemonPub = "pk", deviceId = "dev", credential = "cred")
            onSendForTest = { sent += it }
        }
        repo.receiveForTest(DaemonInfo(voiceMemoVersion = VoiceMemoLimits.VERSION))
        val declared = sent.filterIsInstance<dev.ccpocket.protocol.ClientCaps>().single()
        assertTrue(declared.supportsVoiceMemo)
        assertTrue(declared.supportsProjectPins && declared.supportsManagedSessions, "the same declaration a connect sends")

        repo.receiveForTest(DaemonInfo(voiceMemoVersion = VoiceMemoLimits.VERSION))
        assertEquals(2, sent.filterIsInstance<dev.ccpocket.protocol.ClientCaps>().size, "a new session's holder starts undeclared every time")
    }
}

/** The target's agent decides what a dispatch can promise about it. */
class MemoAgentAwarenessTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var savedFeature: String? = null

    @BeforeTest
    fun setUp() { savedFeature = SecureStore.getString(PocketRepository.K_MEMO_FEATURE) }

    @AfterTest
    fun tearDown() {
        SecureStore.putString(PocketRepository.K_MEMO_FEATURE, savedFeature ?: "0")
        scope.cancel()
    }

    private fun harness(): Pair<PocketRepository, MutableList<Frame>> {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply {
            memoStoreForTest = dev.ccpocket.app.memo.DefaultVoiceMemoStore(scratchFiles())
            paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-agent", daemonPub = "pk", deviceId = "dev-agent", credential = "cred")
            sessionsDir.value = null
            onSendForTest = { sent += it }
            memoWriterForTest = { TransientDisposition.WRITTEN }
            useRelay = true
            connected.value = true
            memoFeatureOn.value = true
        }
        repo.receiveForTest(DaemonInfo(voiceMemoVersion = VoiceMemoLimits.VERSION, voiceMemoAgents = listOf(VOICE_MEMO_AGENT_CLAUDE), voiceMemoStatus = VoiceMemoStatus.READY))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        return repo to sent
    }

    private fun scratchFiles() = object : dev.ccpocket.app.memo.MemoFiles {
        private val files = HashMap<String, ByteArray>()
        override fun read(dir: String, name: String) = files["$dir/$name"]?.let { dev.ccpocket.app.memo.MemoRead.Found(it) } ?: dev.ccpocket.app.memo.MemoRead.Missing
        override fun write(dir: String, name: String, bytes: ByteArray) = dev.ccpocket.app.memo.MemoWrite.Durable(Unit).also { files["$dir/$name"] = bytes }
        override fun delete(dir: String, name: String) = dev.ccpocket.app.memo.MemoWrite.Durable(Unit).also { files.remove("$dir/$name") }
        override fun listDirs(dir: String): List<String> = emptyList()
        override fun listFiles(dir: String): List<String> = emptyList()
        override fun deleteDir(dir: String) = dev.ccpocket.app.memo.MemoWrite.Durable(Unit)
    }

    @Test
    fun an_open_the_computer_refuses_fails_the_dispatch_at_once_with_the_reason() = runBlocking<Unit> {
        val (repo, sent) = harness()
        repo.firstPromptTimeoutMs = 60_000 // the deadline must not be what ends this
        val target = MemoTarget("acct-agent", "", "/w/proj", AgentKind.KIMI, project = "proj", newSession = true)
        var result: MemoEnterResult? = null
        val entering = scope.launch { result = repo.memoHost.enter(target, "batch-k") }
        assertEquals(1, sent.filterIsInstance<OpenSession>().size)
        // what the daemon answers when the agent's CLI is not installed on that computer
        repo.receiveForTest(dev.ccpocket.protocol.PocketError("agent_unavailable", "kimi CLI not found — is it installed?"))
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        entering.join()
        assertEquals(dev.ccpocket.app.memo.MemoEnterFailure.AGENT_UNAVAILABLE, assertIs<MemoEnterResult.Failed>(result).reason)
        assertNull(repo.convoId.value)
        assertFalse(repo.opening.value)
    }

    @Test
    fun the_backend_native_default_mode_is_promised_for_claude_only() {
        val (repo, _) = harness()
        repo.defaultPermissionMode.value = "auto" // Claude's `auto`, kept in Settings alongside the plain default
        val options = repo.memoHost.catalog(null).newSession
        assertEquals("auto", options.modeFor(AgentKind.CLAUDE))
        assertEquals(repo.defaultMode.value.name, options.modeFor(AgentKind.CODEX), "another agent gets the plain default, which is what the open will use")
    }
}
