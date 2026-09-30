package dev.ccpocket.app.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VoiceMemoAudio
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memo state machine end to end over fake ports and virtual time: capture (consent, permission, stale
 * callbacks, the 3:00 limit, interruptions, save failures), processing (chunking, interrupted uploads, reconnect
 * queries, resume, unknown / refused / stale / regressed replies, results, degraded, edits racing results) and
 * result editing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoReducerTest {

    private var harness: MemoHarness? = null

    @AfterTest
    fun noActorFailures() {
        harness?.let { assertEquals(emptyList(), it.errors, "the actor threw") }
    }

    private fun TestScope.start(readiness: MemoReadiness = ready(), setup: MemoHarness.() -> Unit = {}): MemoHarness =
        MemoHarness(this, readiness).also { it.setup(); harness = it }.start()

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun MemoHarness.attemptOf(memoId: String) = checkNotNull(stored(memoId)?.processing).attemptId

    // ── capture ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun permissionDeniedNeverStartsRecording() = runTest {
        val h = start { recorder.startResult = MemoRecorderStart.PermissionDenied }
        h.act(MemoAction.NewMemo)
        assertEquals(MemoCapturePhase.PERMISSION_DENIED, h.state.capture.phase)
        advance(5_000)
        assertEquals(0, h.state.capture.elapsedMs)
        assertEquals(0, h.recorder.stops)
        h.act(MemoAction.OpenMicSettings)
        assertEquals(1, h.recorder.settingsOpened)
        assertTrue(h.files.files.isEmpty())
    }

    @Test
    fun consentComesBeforeTheMicrophone() = runTest {
        val h = start { prefs.accepted = false }
        h.act(MemoAction.NewMemo)
        assertEquals(MemoCapturePhase.CONSENT, h.state.capture.phase)
        assertEquals(0, h.recorder.starts)
        h.act(MemoAction.DeclineConsent)
        assertEquals(MemoCapturePhase.IDLE, h.state.capture.phase)
        h.act(MemoAction.NewMemo)
        h.act(MemoAction.AcceptConsent)
        assertTrue(h.prefs.accepted)
        assertEquals(1, h.recorder.starts)
        assertEquals(MemoCapturePhase.RECORDING, h.state.capture.phase)
        assertEquals(MemoScreen.RECORDING, h.state.screen)
    }

    @Test
    fun aComputerThatIsNotReadyBlocksANewRecording() = runTest {
        val h = start(ready().copy(block = MemoBlock.WHISPER_MISSING))
        h.act(MemoAction.NewMemo)
        assertEquals(MemoCapturePhase.IDLE, h.state.capture.phase)
        assertEquals(0, h.recorder.starts)
    }

    @Test
    fun aFullLibraryRefusesANewRecording() = runTest {
        val h = start {
            (1..VoiceMemoLimits.MAX_LOCAL_MEMOS).forEach { seed(seededDoc(uuid(1000 + it))) }
        }
        assertEquals(MEMO_PAGE, h.state.list.rows.size)
        assertTrue(h.state.list.hasMore)
        h.act(MemoAction.LoadMore)
        assertEquals(VoiceMemoLimits.MAX_LOCAL_MEMOS, h.state.list.rows.size)
        assertFalse(h.state.list.hasMore)
        h.act(MemoAction.NewMemo)
        assertEquals(MemoToast.LIBRARY_FULL, h.state.toast)
        assertEquals(0, h.recorder.starts)
        h.act(MemoAction.ToastShown)
        assertNull(h.state.toast)
    }

    @Test
    fun aStartThatReturnsAfterTheUserLeftDoesNotReviveTheCapture() = runTest {
        val h = start()
        val gate = CompletableDeferred<MemoRecorderStart>()
        h.recorder.startGate = gate
        h.act(MemoAction.NewMemo)
        assertEquals(MemoCapturePhase.PREPARING, h.state.capture.phase)
        h.act(MemoAction.DiscardRecording)
        assertEquals(1, h.recorder.cancels)
        gate.complete(MemoRecorderStart.Started)
        runCurrent()
        assertEquals(MemoCapturePhase.IDLE, h.state.capture.phase)
        assertEquals(MemoScreen.LIST, h.state.screen)
        assertEquals(2, h.recorder.cancels, "the late start releases the microphone again")
        advance(200_000)
        assertEquals(0, h.recorder.stops, "no limit timer from the stale start")
    }

    @Test
    fun theLimitStopsOnceAndCountsDownFrom245() = runTest {
        val h = start()
        h.act(MemoAction.NewMemo)
        advance(1_000)
        assertEquals(1_000, h.state.capture.elapsedMs)
        advance(163_000)
        assertNull(h.state.capture.secondsLeft)
        advance(1_000)
        assertEquals(165_000, h.state.capture.elapsedMs)
        assertEquals(15, h.state.capture.secondsLeft)
        advance(14_000)
        assertEquals(1, h.state.capture.secondsLeft)
        advance(1_000)
        assertEquals(1, h.recorder.stops)
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
        assertTrue(h.state.processing!!.reachedLimit)
        h.act(MemoAction.StopRecording)
        advance(10_000)
        assertEquals(1, h.recorder.stops, "the limit and a late manual stop never stop twice")
        assertEquals(1, h.link.frames<VoiceMemoStart>().size)
    }

    @Test
    fun anInterruptionDiscardsTheFragmentAndCreatesNoDocument() = runTest {
        val h = start()
        h.act(MemoAction.NewMemo)
        advance(3_000)
        h.recorder.interruptions.tryEmit(Unit)
        runCurrent()
        assertEquals(MemoCapturePhase.INTERRUPTED, h.state.capture.phase)
        assertEquals(1, h.recorder.cancels)
        assertEquals(0, h.recorder.stops)
        assertTrue(h.files.files.isEmpty())
        assertTrue(h.link.sent.isEmpty())
        advance(200_000)
        assertEquals(0, h.recorder.stops)
        h.act(MemoAction.BackToList)
        assertEquals(MemoScreen.LIST, h.state.screen)
        assertTrue(h.state.list.rows.isEmpty())
    }

    @Test
    fun goingToTheBackgroundWhileRecordingIsAnInterruption() = runTest {
        val h = start()
        h.act(MemoAction.NewMemo)
        h.act(MemoAction.Background)
        assertEquals(MemoCapturePhase.INTERRUPTED, h.state.capture.phase)
        assertEquals(1, h.recorder.cancels)
        assertTrue(h.files.files.isEmpty())
    }

    @Test
    fun leavingWhileRecordingAsksFirst() = runTest {
        val h = start()
        h.act(MemoAction.NewMemo)
        h.act(MemoAction.RequestLeaveRecording)
        assertTrue(h.state.capture.leavePrompt)
        h.act(MemoAction.ContinueRecording)
        assertFalse(h.state.capture.leavePrompt)
        assertEquals(MemoCapturePhase.RECORDING, h.state.capture.phase)
        h.act(MemoAction.BackToList)
        assertTrue(h.state.capture.leavePrompt)
        h.act(MemoAction.DiscardRecording)
        assertEquals(MemoScreen.LIST, h.state.screen)
        assertEquals(1, h.recorder.cancels)
        assertTrue(h.files.files.isEmpty())
    }

    @Test
    fun audioThatIsNotDurableIsNotSavedAndNotUploaded() = runTest {
        val h = start { store.audioResult = MemoWrite.Indeterminate }
        h.act(MemoAction.NewMemo)
        h.act(MemoAction.StopRecording)
        assertEquals(MemoCapturePhase.SAVE_FAILED, h.state.capture.phase)
        assertEquals(MemoScreen.RECORDING, h.state.screen)
        assertTrue(h.link.sent.isEmpty())
    }

    @Test
    fun aDocumentThatIsNotDurableIsNotSavedAndNotUploaded() = runTest {
        val h = start { store.commitResult = { MemoWrite.NotWritten() } }
        h.act(MemoAction.NewMemo)
        h.act(MemoAction.StopRecording)
        assertEquals(MemoCapturePhase.SAVE_FAILED, h.state.capture.phase)
        assertTrue(h.link.sent.isEmpty())
        assertNull(h.state.processing)
    }

    // ── upload ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theUploadIsAStartThenIndependentlyEncodedChunksInOrder() = runTest {
        val h = start()
        val bytes = ByteArray(300_000) { (it % 253).toByte() }
        val id = h.recordMemo(bytes, durationMs = 42_000)
        val start = h.link.frames<VoiceMemoStart>().single()
        val chunks = h.link.frames<VoiceMemoAudio>()
        assertEquals(h.link.sent.first().second, start)
        assertEquals(id, start.memoId)
        assertEquals("audio", start.inputKind)
        assertEquals(VoiceMemoLimits.AUDIO_MEDIA_TYPE, start.mediaType)
        assertEquals(300_000L, start.byteLength)
        assertEquals(42_000L, start.durationMs)
        assertEquals(3, start.chunkCount)
        assertEquals(VoiceMemoHash.sha256Hex(bytes), start.sha256)
        assertNull(start.transcript)
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals(listOf(131_072, 131_072, 37_856), chunks.map { Base64.Default.decode(it.base64).size })
        assertTrue(chunks.all { it.attemptId == start.attemptId })
        assertEquals(bytes.toList(), chunks.flatMap { Base64.Default.decode(it.base64).toList() })
        assertTrue(h.link.sent.all { it.first == 1 }, "every frame is bound to the connection the upload started on")
        assertEquals(MemoStepStatus.RUNNING, h.state.processing!!.steps[0].status)
        assertEquals(start.attemptId, h.attemptOf(id))
    }

    @Test
    fun aSendThatIsNotWrittenStopsTheUploadWithoutRetrying() = runTest {
        val h = start {
            link.result = { if (it is VoiceMemoAudio && it.index == 1) MemoLinkSend.NotWritten else MemoLinkSend.Written }
        }
        val id = h.recordMemo(ByteArray(300_000))
        assertEquals(listOf(0, 1), h.link.frames<VoiceMemoAudio>().map { it.index })
        assertEquals(MemoProcessingIssue.UPLOAD_INTERRUPTED, h.state.processing!!.issue)
        assertEquals(MemoStepStatus.FAILED, h.state.processing!!.steps[0].status)
        assertEquals(MemoLocalStage.INTERRUPTED, h.stored(id)!!.processing!!.stage)
        val before = h.link.sent.size
        advance(120_000)
        assertEquals(before, h.link.sent.size, "no automatic retry")
    }

    @Test
    fun aReconnectOnlyQueriesAndAReceivingAnswerOffersAWholeResend() = runTest {
        val h = start { link.result = { if (it is VoiceMemoAudio) MemoLinkSend.Indeterminate else MemoLinkSend.Written } }
        val id = h.recordMemo(ByteArray(200_000))
        val attempt = h.attemptOf(id)
        assertEquals(MemoProcessingIssue.UPLOAD_INTERRUPTED, h.state.processing!!.issue)
        h.link.result = { MemoLinkSend.Written }
        val before = h.link.sent.size

        h.link.readiness.value = ready(generation = 2)
        runCurrent()
        val after = h.link.sent.drop(before)
        assertEquals(listOf(2 to VoiceMemoGet(id, attempt)), after, "one query on the new connection, nothing resent")
        h.link.readiness.value = ready(generation = 2).copy(computerName = "renamed")
        runCurrent()
        assertEquals(before + 1, h.link.sent.size)

        h.push(memoState(id, attempt, 1, VoiceMemoStage.RECEIVING))
        assertEquals(MemoProcessingIssue.UPLOAD_INCOMPLETE, h.state.processing!!.issue)
        h.act(MemoAction.ResumeUpload)
        val resent = h.link.sent.drop(before + 1).map { it.second }
        assertEquals(VoiceMemoStart::class, resent.first()::class)
        assertEquals(attempt, (resent.first() as VoiceMemoStart).attemptId, "the same attempt, the whole audio again")
        assertEquals(listOf(0, 1), resent.filterIsInstance<VoiceMemoAudio>().map { it.index })
        assertNull(h.state.processing!!.issue)
    }

    @Test
    fun unknownIsTakenOnlyAsTheAnswerToAQueryAndNeverStartsAgain() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 1, VoiceMemoStage.QUEUED))
        h.push(memoState(id, attempt, 2, VoiceMemoStage.SUMMARIZING, transcript = "hello there"))
        assertEquals("hello there", h.stored(id)!!.content.transcript)

        // Unsolicited: ignored.
        h.push(memoState(id, attempt, 0, VoiceMemoStage.UNKNOWN, errorCode = VoiceMemoError.UNKNOWN_JOB))
        assertNull(h.state.processing!!.issue)

        h.link.readiness.value = ready(generation = 2)
        runCurrent()
        assertEquals(VoiceMemoGet(id, attempt), h.link.sent.last().second)
        val starts = h.link.frames<VoiceMemoStart>().size
        h.push(memoState(id, attempt, 0, VoiceMemoStage.UNKNOWN, errorCode = VoiceMemoError.UNKNOWN_JOB))
        assertEquals(MemoProcessingIssue.RECORD_UNAVAILABLE, h.state.processing!!.issue)
        assertEquals("hello there", h.stored(id)!!.content.transcript, "the stored transcript survives")
        advance(60_000)
        assertEquals(starts, h.link.frames<VoiceMemoStart>().size, "unknown never triggers a new start")

        // The user retries: a new attempt, organising the stored transcript.
        h.act(MemoAction.RetryProcessing)
        val retry = h.link.frames<VoiceMemoStart>().last()
        assertNotEquals(attempt, retry.attemptId)
        assertEquals(id, retry.memoId)
        assertEquals("transcript", retry.inputKind)
        assertEquals("hello there", retry.transcript)
        assertEquals(VoiceMemoHash.sha256Hex("hello there".encodeToByteArray()), retry.sha256)
        assertNull(retry.mediaType)
        assertEquals(retry.attemptId, h.attemptOf(id), "the new attempt id is stored")

        // The old attempt's late result is dropped; the new one's is taken.
        h.push(readyState(id, attempt, 9, "old"))
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
        h.push(readyState(id, retry.attemptId, 3, "new"))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        assertEquals(listOf("new"), h.state.document!!.todos.map { it.text })
    }

    @Test
    fun aRevisionZeroRefusalCountsOnlyBeforeAnyRealRevisionAndStopsTheUpload() = runTest {
        val h = start()
        val gate = CompletableDeferred<Unit>()
        h.link.hold = { if (it is VoiceMemoAudio && it.index == 1) gate else null }
        val id = h.recordMemo(ByteArray(300_000))
        val attempt = h.attemptOf(id)
        assertEquals(listOf(0), h.link.frames<VoiceMemoAudio>().map { it.index })

        h.push(memoState(id, attempt, 0, VoiceMemoStage.FAILED, errorCode = VoiceMemoError.BUSY))
        assertEquals(MemoProcessingIssue.COMPUTER_BUSY, h.state.processing!!.issue)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0), h.link.frames<VoiceMemoAudio>().map { it.index }, "the upload stopped")
        assertEquals(VoiceMemoStage.FAILED, h.stored(id)!!.processing!!.stage)

        // A memo that already heard revision 1 ignores a later revision-0 refusal.
        h.link.hold = null
        h.act(MemoAction.BackToList)
        val second = h.recordMemo()
        val secondAttempt = h.attemptOf(second)
        h.push(memoState(second, secondAttempt, 1, VoiceMemoStage.QUEUED))
        h.push(memoState(second, secondAttempt, 0, VoiceMemoStage.FAILED, errorCode = VoiceMemoError.NOT_READY))
        assertNull(h.state.processing!!.issue)
        assertEquals(VoiceMemoStage.QUEUED, h.stored(second)!!.processing!!.stage)
    }

    @Test
    fun notReadyRefusalIsARetryableUploadFailure() = runTest {
        val h = start()
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 0, VoiceMemoStage.FAILED, errorCode = VoiceMemoError.NOT_READY))
        assertEquals(MemoProcessingIssue.UPLOAD_FAILED, h.state.processing!!.issue)
        assertTrue(h.stored(id)!!.processing!!.retryable)
        assertEquals(MemoStepStatus.FAILED, h.state.processing!!.steps[0].status)
    }

    @Test
    fun aRevisionThatGoesBackwardsIsIgnored() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 3, VoiceMemoStage.TRANSCRIBING))
        h.push(memoState(id, attempt, 2, VoiceMemoStage.QUEUED))
        assertEquals(VoiceMemoStage.TRANSCRIBING, h.stored(id)!!.processing!!.stage)
        assertEquals(MemoStepStatus.RUNNING, h.state.processing!!.steps[1].status)
        h.push(memoState(id, attempt, 3, VoiceMemoStage.QUEUED))
        assertEquals(VoiceMemoStage.TRANSCRIBING, h.stored(id)!!.processing!!.stage, "an equal revision without a query is not new")
    }

    @Test
    fun anAttemptThatWasUploadingWhenTheAppDiedIsOnlyQueried() = runTest {
        val id = uuid(600)
        val attempt = uuid(601)
        val h = start {
            seed(
                MemoDocument(
                    scope = SCOPE, memoId = id, createdAtMs = 1, updatedAtMs = 1,
                    processing = MemoAttempt(attempt, "audio", "0".repeat(64), 0, stage = MemoLocalStage.UPLOADING),
                    content = MemoContent(audioDurationMs = 1_000, audio = AudioRef("audio.m4a", 3, "0".repeat(64), VoiceMemoLimits.AUDIO_MEDIA_TYPE)),
                ),
            )
        }
        h.act(MemoAction.OpenMemo(id))
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
        assertEquals(MemoProcessingIssue.UPLOAD_INTERRUPTED, h.state.processing!!.issue)
        assertNull(h.state.processing!!.elapsedMs, "not measurable across a restart")
        assertEquals(listOf(VoiceMemoGet(id, attempt)), h.link.sent.map { it.second })
        h.push(memoState(id, attempt, 2, VoiceMemoStage.TRANSCRIBING))
        assertNull(h.state.processing!!.issue)
        assertEquals(MemoStepStatus.RUNNING, h.state.processing!!.steps[1].status)
    }

    @Test
    fun aReplyFromAnotherComputerIsIgnored() = runTest {
        val h = start()
        val id = h.recordMemo()
        h.push(readyState(id, h.attemptOf(id), 1, "x"), bindingId = "someone-else")
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
    }

    @Test
    fun aResultIsStoredBeforeItIsShownAndThenTheAudioIsReleased() = runTest {
        val h = start()
        val id = h.recordMemo(ByteArray(1000))
        assertTrue(h.files.has(SCOPE, id, DefaultVoiceMemoStore.AUDIO))
        val attempt = h.attemptOf(id)
        advance(4_000)
        h.push(readyState(id, attempt, 2, "Fix the build", "Reply to Ann"))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        val stored = h.stored(id)!!
        assertEquals("Plan", stored.content.title)
        assertEquals(listOf("Fix the build", "Reply to Ann"), stored.todos.map { it.text })
        assertTrue(stored.todos.all { it.selected })
        assertTrue(stored.processing!!.accepted)
        assertEquals(4_000L, stored.processing.timings.totalMs)
        assertEquals(4_000L, stored.processing.timings.uploadMs, "first chunk → the first state past receiving")
        assertNull(stored.content.audio, "the reference is removed first")
        assertFalse(h.files.has(SCOPE, id, DefaultVoiceMemoStore.AUDIO), "then the file")
        assertEquals("the transcript", h.state.document!!.transcript)
        assertNull(h.state.processing)
        assertNotNull(h.state.document)
    }

    @Test
    fun aResultThatCannotBeStoredKeepsTheAudioAndSaysNotSaved() = runTest {
        val h = start()
        val id = h.recordMemo()
        h.store.commitResult = { if (it.content.summary != null) MemoWrite.NotWritten() else null }
        h.push(readyState(id, h.attemptOf(id), 2, "a"))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        assertTrue(h.state.document!!.saveFailed)
        assertEquals(MemoToast.SAVE_FAILED, h.state.toast)
        assertTrue(h.files.has(SCOPE, id, DefaultVoiceMemoStore.AUDIO))
        assertNull(h.stored(id)!!.content.summary)
    }

    @Test
    fun aRepeatedResultDoesNotRegenerateTheList() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(readyState(id, attempt, 5, "a", "b"))
        val ids = h.state.document!!.todos.map { it.todoId }
        h.push(readyState(id, attempt, 5, "a", "b"))
        h.push(readyState(id, attempt, 6, "c").copy(metrics = dev.ccpocket.protocol.VoiceMemoMetrics(summarizeMs = 777)))
        assertEquals(ids, h.state.document!!.todos.map { it.todoId })
        assertEquals(listOf("a", "b"), h.state.document!!.todos.map { it.text })
        assertEquals(777L, h.stored(id)!!.processing!!.timings.summarizeMs, "only the timings merge")
    }

    @Test
    fun anInvalidReplyKeepsEverythingAndSaysIncompatible() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 1, VoiceMemoStage.QUEUED))
        h.push(memoState(id, attempt, 2, VoiceMemoStage.READY, transcript = "kept words", result = null))
        assertEquals(MemoProcessingIssue.INCOMPATIBLE, h.state.processing!!.issue)
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
        val stored = h.stored(id)!!
        assertEquals(VoiceMemoStage.QUEUED, stored.processing!!.stage)
        assertEquals("kept words", stored.content.transcript, "a valid transcript is kept for a retry")
        assertNull(stored.content.summary)
        assertTrue(stored.todos.isEmpty())

        // A result with its fields missing decodes (all defaulted) and is refused; the stored transcript stays.
        h.push(memoState(id, attempt, 3, VoiceMemoStage.READY, transcript = "other words", result = dev.ccpocket.protocol.VoiceMemoResult()))
        assertEquals(MemoProcessingIssue.INCOMPATIBLE, h.state.processing!!.issue)
        assertEquals("kept words", h.stored(id)!!.content.transcript)
        h.push(memoState(id, attempt, 4, "brand-new-stage"))
        assertEquals(VoiceMemoStage.QUEUED, h.stored(id)!!.processing!!.stage)
        assertEquals(MemoScreen.PROCESSING, h.state.screen)
    }

    @Test
    fun aDegradedResultKeepsTheTranscriptAndOpensTheResultWithoutASummary() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 3, VoiceMemoStage.DEGRADED, transcript = "raw words", errorCode = VoiceMemoError.SUMMARY_TIMEOUT))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        val doc = h.state.document!!
        assertTrue(doc.degraded)
        assertNull(doc.summary)
        assertEquals("raw words", doc.transcript)
        assertTrue(doc.todos.isEmpty())
        assertEquals(MemoProcessingIssue.ORGANIZE_TIMEOUT, h.state.processing!!.issue)
        assertNull(doc.organizedBy, "a degraded result was organised by nobody")
        val stored = h.stored(id)!!
        assertNull(stored.content.organizedBy)
        assertEquals(VoiceMemoError.SUMMARY_TIMEOUT, stored.processing!!.errorCode)
        assertTrue(stored.processing.accepted)
        assertNull(stored.content.audio)

        // The user can add a to-do by hand, and re-organise.
        h.act(MemoAction.AddTodo)
        assertEquals(1, h.state.document!!.todos.size)
        h.act(MemoAction.Reorganize)
        val start = h.link.frames<VoiceMemoStart>().last()
        assertEquals("transcript", start.inputKind)
        assertTrue(h.state.document!!.reorganizing)
    }

    @Test
    fun aLateResultDoesNotOverwriteTheUsersEdits() = runTest {
        val id = uuid(500)
        val h = start { seed(seededDoc(id, "t1" to "keep me")) }
        h.act(MemoAction.OpenMemo(id))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        h.act(MemoAction.Reorganize)
        val attempt = h.link.frames<VoiceMemoStart>().last().attemptId
        h.act(MemoAction.EditTitle("Mine"))
        h.push(readyState(id, attempt, 2, "replacement").copy(result = result("replacement", title = "Theirs")))
        val doc = h.state.document!!
        assertEquals("Mine", doc.title)
        assertEquals(listOf("keep me"), doc.todos.map { it.text })
        assertFalse(doc.reorganizing)
        assertTrue(h.stored(id)!!.processing!!.accepted)
    }

    @Test
    fun reorganiseReplacesOnlyTheDraftsAndKeepsEveryRecord() = runTest {
        val id = uuid(501)
        val delivered = MemoDispatchRecord("b1", "t1", "p1", "sent", TARGET, "c", MemoTodoState.DELIVERED, 1)
        val h = start { seed(seededDoc(id, "t1" to "sent", "t2" to "draft", dispatches = listOf(delivered))) }
        h.act(MemoAction.OpenMemo(id))
        h.act(MemoAction.Reorganize)
        val attempt = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(readyState(id, attempt, 1, "n1", "n2"))
        val doc = h.stored(id)!!
        assertEquals(listOf("sent", "n1", "n2"), doc.todos.map { it.text })
        assertEquals(listOf(delivered), doc.dispatches)
    }

    @Test
    fun cancellingKeepsTheAudioAndTheTranscript() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 2, VoiceMemoStage.SUMMARIZING, transcript = "words"))
        h.act(MemoAction.CancelProcessing)
        assertEquals(VoiceMemoCancel(id, attempt), h.link.sent.last().second)
        assertEquals(MemoProcessingIssue.CANCELLED, h.state.processing!!.issue)
        assertTrue(h.state.processing!!.hasAudio)
        assertTrue(h.state.processing!!.hasTranscript)
        h.push(readyState(id, attempt, 3, "late"))
        assertEquals(MemoScreen.PROCESSING, h.state.screen, "a result after the user cancelled is not taken")
    }

    @Test
    fun retryTranscriptionUsesTheAudioEvenWithATranscript() = runTest {
        val h = start()
        val id = h.recordMemo(ByteArray(2000))
        val attempt = h.attemptOf(id)
        h.push(memoState(id, attempt, 2, VoiceMemoStage.FAILED, transcript = "partial", errorCode = VoiceMemoError.TRANSCRIPT_TOO_LONG))
        assertEquals(MemoProcessingIssue.TRANSCRIBE_FAILED, h.state.processing!!.issue)
        h.act(MemoAction.RetryTranscription)
        val start = h.link.frames<VoiceMemoStart>().last()
        assertEquals("audio", start.inputKind)
        assertNotEquals(attempt, start.attemptId)
        assertEquals(id, start.memoId)
    }

    @Test
    fun pollingIsAtMostEveryTenSecondsOneAtATimeAndOnlyOnTheProcessingPage() = runTest {
        val h = start()
        h.recordMemo()
        val gets = { h.link.sentAt.zip(h.link.sent).filter { it.second.second is VoiceMemoGet }.map { it.first } }
        advance(9_000)
        assertEquals(emptyList(), gets())
        advance(1_000)
        assertEquals(1, gets().size)
        advance(45_000)
        val times = gets()
        assertTrue(times.size in 2..6, "$times")
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 10_000 }, "$times")
        assertEquals(MemoProcessingIssue.WAITING_FOR_COMPUTER, h.state.processing!!.issue)
        assertTrue(h.state.processing!!.running)

        h.act(MemoAction.BackToList)
        val n = gets().size
        advance(60_000)
        assertEquals(n, gets().size, "no polling off the page")
        h.act(MemoAction.Background)
        h.act(MemoAction.Foreground)
        assertEquals(n + 1, gets().size, "the foreground asks once")
    }

    @Test
    fun deletingTombstonesCancelsAndIgnoresLateReplies() = runTest {
        val h = start()
        val id = h.recordMemo()
        val attempt = h.attemptOf(id)
        h.act(MemoAction.BackToList)
        h.act(MemoAction.DeleteMemo(id))
        assertEquals(MemoToast.DELETED, h.state.toast)
        assertTrue(h.link.frames<VoiceMemoCancel>().contains(VoiceMemoCancel(id, attempt)))
        assertTrue(h.state.list.rows.none { it.memoId == id })
        assertTrue(h.files.files.keys.none { it.contains(id) }, "purged")
        h.push(readyState(id, attempt, 4, "zombie"))
        assertNull(h.stored(id))
        assertTrue(h.files.files.keys.none { it.contains(id) }, "a late reply does not resurrect it")
    }

    @Test
    fun aResultForAMemoThatIsNotOpenIsStoredInTheBackground() = runTest {
        val h = start()
        val first = h.recordMemo()
        val attempt = h.attemptOf(first)
        h.act(MemoAction.BackToList)
        h.push(readyState(first, attempt, 2, "later"))
        assertEquals(listOf("later"), h.stored(first)!!.todos.map { it.text })
        assertEquals(MemoScreen.LIST, h.state.screen)
        assertNull(h.state.list.rows.first { it.memoId == first }.stage)
    }

    @Test
    fun anUnreadableLibraryIsShownAsAnErrorNotAsEmpty() = runTest {
        val h = start { files.failListDirs = true }
        assertTrue(h.state.list.loaded)
        assertTrue(h.state.list.unreadable)
    }

    @Test
    fun errorCodesMapToIssues() {
        val failed = VoiceMemoStage.FAILED
        val expected = mapOf(
            VoiceMemoError.BUSY to MemoProcessingIssue.COMPUTER_BUSY,
            VoiceMemoError.NOT_READY to MemoProcessingIssue.UPLOAD_FAILED,
            VoiceMemoError.INVALID_INPUT to MemoProcessingIssue.UPLOAD_FAILED,
            VoiceMemoError.INPUT_CONFLICT to MemoProcessingIssue.UPLOAD_FAILED,
            VoiceMemoError.UPLOAD_TIMEOUT to MemoProcessingIssue.UPLOAD_FAILED,
            VoiceMemoError.AUDIO_INVALID to MemoProcessingIssue.AUDIO_REJECTED,
            VoiceMemoError.AUDIO_TOO_LONG to MemoProcessingIssue.AUDIO_REJECTED,
            VoiceMemoError.TRANSCRIBE_FAILED to MemoProcessingIssue.TRANSCRIBE_FAILED,
            VoiceMemoError.TRANSCRIPT_TOO_LONG to MemoProcessingIssue.TRANSCRIBE_FAILED,
            VoiceMemoError.TRANSCRIBE_TIMEOUT to MemoProcessingIssue.TRANSCRIBE_TIMEOUT,
            VoiceMemoError.EMPTY_TRANSCRIPT to MemoProcessingIssue.EMPTY_TRANSCRIPT,
            VoiceMemoError.AGENT_UNAVAILABLE to MemoProcessingIssue.AGENT_UNAVAILABLE,
            VoiceMemoError.SUMMARY_FAILED to MemoProcessingIssue.ORGANIZE_FAILED,
            VoiceMemoError.INVALID_RESULT to MemoProcessingIssue.ORGANIZE_FAILED,
            VoiceMemoError.SUMMARY_TIMEOUT to MemoProcessingIssue.ORGANIZE_TIMEOUT,
            VoiceMemoError.CANCELLED to MemoProcessingIssue.CANCELLED,
            VoiceMemoError.UNKNOWN_JOB to MemoProcessingIssue.RECORD_UNAVAILABLE,
            VoiceMemoError.UNSUPPORTED to MemoProcessingIssue.INCOMPATIBLE,
            "a_code_from_the_future" to MemoProcessingIssue.INCOMPATIBLE,
        )
        expected.forEach { (code, issue) -> assertEquals(issue, memoProcessingIssue(failed, code), code) }
        assertEquals(MemoProcessingIssue.ORGANIZE_FAILED, memoProcessingIssue(VoiceMemoStage.DEGRADED, VoiceMemoError.SUMMARY_FAILED))
        assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, memoProcessingIssue(VoiceMemoStage.DEGRADED, VoiceMemoError.AGENT_UNAVAILABLE))
        assertEquals(MemoProcessingIssue.CANCELLED, memoProcessingIssue(VoiceMemoStage.CANCELLED, null))
        assertEquals(MemoProcessingIssue.RECORD_UNAVAILABLE, memoProcessingIssue(VoiceMemoStage.UNKNOWN, null))
        assertEquals(MemoProcessingIssue.UPLOAD_INTERRUPTED, memoProcessingIssue(MemoLocalStage.INTERRUPTED, null))
        // TRANSCRIBED alone is a result; only an organise-only attempt that ended there is an issue.
        assertNull(memoProcessingIssue(VoiceMemoStage.TRANSCRIBED, null))
        assertNull(memoProcessingIssue(VoiceMemoStage.TRANSCRIBED, VoiceMemoError.AGENT_UNAVAILABLE))
        val transcribed = MemoAttempt(uuid(1), "audio", "0".repeat(64), 0, stage = VoiceMemoStage.TRANSCRIBED, accepted = true)
        assertNull(issueFromDisk(transcribed))
        assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, issueFromDisk(transcribed.copy(inputKind = "transcript")))
        listOf(VoiceMemoStage.RECEIVING, VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.SUMMARIZING, VoiceMemoStage.READY)
            .forEach { assertNull(memoProcessingIssue(it, null), it) }
    }

    // ── editing ───────────────────────────────────────────────────────────────────────────────────────

    private fun TestScope.openSeeded(vararg todos: Pair<String, String>, dispatches: List<MemoDispatchRecord> = emptyList()): MemoHarness {
        val id = uuid(700)
        val h = start {
            seed(seededDoc(id, *todos, dispatches = dispatches))
            gateway.rows = listOf(TARGET_ROW)
        }
        h.act(MemoAction.OpenMemo(id))
        h.act(MemoAction.SelectTarget(TARGET))
        return h
    }

    @Test
    fun blankItemsAreNeverPartOfTheSelection() = runTest {
        val h = openSeeded("t1" to "real")
        h.act(MemoAction.AddTodo)
        val blank = h.state.document!!.todos.last()
        assertEquals("", blank.text)
        assertTrue(blank.selected)
        assertEquals(listOf("t1"), h.state.selection.items.map { it.todoId })
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
        h.act(MemoAction.ToggleTodo("t1"))
        assertEquals(MemoDispatchBlock.ONLY_BLANK, h.state.selection.block)
        h.act(MemoAction.ToggleTodo(blank.todoId))
        assertEquals(MemoDispatchBlock.NO_SELECTION, h.state.selection.block)
        h.act(MemoAction.EditTodo(blank.todoId, "  "))
        h.act(MemoAction.ToggleTodo(blank.todoId))
        assertEquals(MemoDispatchBlock.ONLY_BLANK, h.state.selection.block)
    }

    @Test
    fun editsBumpEditRevisionAndAreStored() = runTest {
        val h = openSeeded("t1" to "a", "t2" to "b")
        h.act(MemoAction.EditTodo("t1", "a!"))
        h.act(MemoAction.DeleteTodo("t2"))
        h.act(MemoAction.EditTitle("A title"))
        val stored = h.stored(uuid(700))!!
        assertEquals(3, stored.editRevision)
        assertEquals(listOf("a!"), stored.todos.map { it.text })
        assertEquals("A title", stored.content.title)
        assertFalse(h.state.document!!.saving)
    }

    @Test
    fun titlesAndItemsAreBoundedInCodePoints() = runTest {
        val h = openSeeded("t1" to "a")
        h.act(MemoAction.EditTitle("   "))
        assertEquals("Seeded", h.state.document!!.title, "a blank title is refused")
        h.act(MemoAction.EditTitle("😀".repeat(90)))
        assertEquals(80, dev.ccpocket.protocol.VoiceMemoValidation.codePoints(h.state.document!!.title))
        h.act(MemoAction.EditTodo("t1", "x".repeat(1_200)))
        assertEquals(1_000, h.state.document!!.todos.single().text.length)
    }

    @Test
    fun twentyItemsIsTheLimit() = runTest {
        val h = openSeeded(*(1..20).map { "t$it" to "item $it" }.toTypedArray())
        assertTrue(h.state.document!!.atTodoLimit)
        h.act(MemoAction.AddTodo)
        assertEquals(MemoToast.TODO_LIMIT, h.state.toast)
        h.act(MemoAction.ToastShown)
        h.act(MemoAction.CopyTodo("t1"))
        assertEquals(MemoToast.TODO_LIMIT, h.state.toast)
        assertEquals(20, h.state.document!!.todos.size)
    }

    @Test
    fun aFailedSaveIsVisibleAndAnnounced() = runTest {
        val h = openSeeded("t1" to "a")
        h.store.commitResult = { MemoWrite.NotWritten() }
        h.act(MemoAction.EditTitle("Changed"))
        assertTrue(h.state.document!!.saveFailed)
        assertEquals("Changed", h.state.document!!.title)
        assertEquals(MemoToast.SAVE_FAILED, h.state.toast)
        assertEquals("Seeded", h.stored(uuid(700))!!.content.title)
        h.store.commitResult = null
        h.act(MemoAction.EditTitle("Changed again"))
        assertFalse(h.state.document!!.saveFailed)
        assertEquals("Changed again", h.stored(uuid(700))!!.content.title)
    }

    @Test
    fun onlyDraftsCanBeChangedAndACopyOfAnUnknownItemStartsUnticked() = runTest {
        val unknown = MemoDispatchRecord("b1", "t1", "p1", "a", TARGET, "c", MemoTodoState.UNKNOWN, 1)
        val h = openSeeded("t1" to "a", "t2" to "b", dispatches = listOf(unknown))
        h.act(MemoAction.EditTodo("t1", "changed"))
        h.act(MemoAction.DeleteTodo("t1"))
        h.act(MemoAction.ToggleTodo("t1"))
        val row = h.state.document!!.todos.first()
        assertEquals("a", row.text)
        assertEquals(MemoTodoState.UNKNOWN, row.state)
        assertFalse(row.editable)

        h.act(MemoAction.CopyTodo("t1"))
        assertEquals(MemoToast.COPIED, h.state.toast)
        val todos = h.state.document!!.todos
        assertEquals(listOf("t1", todos[1].todoId, "t2"), todos.map { it.todoId })
        val copy = todos[1]
        assertEquals("a", copy.text)
        assertFalse(copy.selected)
        assertEquals(1, copy.copiedFromIndex)
        assertTrue(copy.copyOfUnknown)
        assertEquals(MemoTodoState.DRAFT, copy.state)
        assertEquals(listOf(unknown), h.stored(uuid(700))!!.dispatches, "the original record is untouched")

        h.act(MemoAction.CopyTodo("t2"))
        assertTrue(h.state.document!!.todos.first { it.copiedFrom() == "t2" }.selected)
    }

    private fun MemoTodoRow.copiedFrom(): String? = if (copiedFromIndex == 3) "t2" else null

    @Test
    fun retryingAFailedItemMakesItASelectedDraftAgain() = runTest {
        val failed = MemoDispatchRecord("b1", "t1", "p1", "a", TARGET, "c", MemoTodoState.FAILED, 1, errorCode = "not_submitted")
        val h = openSeeded("t1" to "a", dispatches = listOf(failed))
        h.act(MemoAction.ToggleTodo("t1"))
        assertEquals(MemoTodoState.FAILED, h.state.document!!.todos.single().state)
        assertEquals("not_submitted", h.state.document!!.todos.single().errorCode)
        h.act(MemoAction.RetryFailedTodo("t1"))
        val row = h.state.document!!.todos.single()
        assertEquals(MemoTodoState.DRAFT, row.state)
        assertTrue(row.selected)
        assertEquals(listOf("t1"), h.state.selection.items.map { it.todoId })
        val record = h.stored(uuid(700))!!.dispatches.single()
        assertEquals(MemoTodoState.DRAFT, record.state)
        assertEquals("not_submitted", record.errorCode, "the failure stays as history")
    }

    @Test
    fun targetsRefreshAndOnlySelectableOnesCanBeChosen() = runTest {
        val h = openSeeded("t1" to "a")
        assertEquals(TARGET, h.state.selection.target!!.target)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
        val archived = MemoTargetRow(TARGET.copy(sessionId = "s2"), MemoTargetStatus.ARCHIVED)
        h.gateway.rows = listOf(TARGET_ROW.copy(status = MemoTargetStatus.NEEDS_TAKEOVER), archived)
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoDispatchBlock.TARGET_UNAVAILABLE, h.state.selection.block)
        h.act(MemoAction.SelectTarget(archived.target))
        assertEquals(TARGET, h.state.selection.target!!.target, "an unselectable row is refused")
        h.gateway.rows = emptyList()
        h.act(MemoAction.RefreshTargets)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block, "not shown in any loaded row: kept, the host re-checks on entry")
        h.link.readiness.value = ready(online = false)
        runCurrent()
        assertEquals(MemoDispatchBlock.OFFLINE, h.state.selection.block)
    }

    // ── security review follow-ups ────────────────────────────────────────────────────────────────────

    @Test
    fun aNewAttemptBlocksDispatchRightAfterSaving() {
        val doc = seededDoc(uuid(1), "t1" to "a")
        val base = MemoModel(
            readiness = ready(online = false), scope = SCOPE, screen = MemoScreen.DETAIL, currentId = uuid(1),
            slots = mapOf(uuid(1) to Slot(doc, diskRevision = 1)), catalog = MemoTargetCatalog(recent = listOf(TARGET_ROW)), target = TARGET_ROW,
        )
        assertEquals(MemoDispatchBlock.OFFLINE, base.selection().block)
        val busyRuns = listOf(
            Run(reorganizing = true),
            Run(hashing = Hashing(1, reorganize = false)),
            Run(starting = true),
            Run(upload = Upload(1, uuid(2), 1)),
        )
        for (run in busyRuns) {
            val m = base.copy(slots = mapOf(uuid(1) to Slot(doc, diskRevision = 1, run = run)))
            assertEquals(MemoDispatchBlock.BUSY, m.selection().block, "$run, even offline")
            val saving = m.copy(slots = mapOf(uuid(1) to Slot(doc, diskRevision = 1, run = run, inFlight = InFlight(1, 2))))
            assertEquals(MemoDispatchBlock.SAVING, saving.selection().block, "SAVING outranks it")
        }
    }

    /** A slot whose commit failed and whose revision is being re-read, while a batch waits on the next write. */
    private fun waitingOnReread(diskRevision: Long?): MemoModel {
        val id = uuid(1)
        val lease = MemoTargetLease(TARGET, "c", "b1", 1)
        val record = MemoDispatchRecord("b1", "t1", "p1", "a", TARGET, "c", MemoTodoState.SENDING, 1)
        val doc = seededDoc(id, "t1" to "a", dispatches = listOf(record))
        return MemoModel(
            readiness = ready(), scope = SCOPE, screen = MemoScreen.DETAIL, currentId = id,
            slots = mapOf(
                id to Slot(
                    doc, diskRevision = diskRevision, seq = 2, durableSeq = 0, failedSeq = 1, rereading = true, saveFailed = true,
                    waiters = listOf(Waiter(2, Purpose.SendingStored("b1", "p1"))),
                ),
            ),
            batch = Batch("b1", id, TARGET, listOf(BatchItem("t1", "p1", "a")), phase = MemoDispatchPhase.SENDING, lease = lease, step = BatchStep.STORING_SENDING),
        )
    }

    @Test
    fun aRereadThatCannotEstablishTheRevisionFailsItsWaiters() {
        val reducer = MemoReducer({ "x" }, { "p" })
        for ((disk, read) in listOf<Pair<Long?, MemoRead<MemoDocument>>>(1L to MemoRead.Unreadable("io"), null to MemoRead.Missing)) {
            val r = reducer.reduce(waitingOnReread(disk), MemoEvent.Reread(uuid(1), read), MemoNow(0, 0))
            val batch = r.model.batch!!
            assertEquals(MemoDispatchPhase.STOPPED, batch.phase, "$read")
            assertEquals(MemoDispatchStop.SAVE_FAILED, batch.stop)
            assertTrue(r.effects.none { it is MemoEffect.Submit }, "nothing is submitted on an unconfirmed ledger")
            val slot = r.model.slots.getValue(uuid(1))
            assertTrue(slot.waiters.isEmpty())
            assertFalse(slot.rereading)
            assertEquals(MemoTodoState.DRAFT, slot.doc.dispatches.single().state, "the item goes back to a draft")
        }
    }

    @Test
    fun malformedIdsAreDroppedBeforeAnyLookupOrRead() = runTest {
        val h = start()
        val reads = h.store.reads
        h.push(memoState("../../escape", uuid(2), 1, VoiceMemoStage.READY, transcript = "t", result = result("x")))
        h.push(memoState(uuid(3), "not-a-uuid", 1, VoiceMemoStage.QUEUED))
        h.push(memoState(uuid(0xabc).uppercase(), uuid(2), 1, VoiceMemoStage.QUEUED))
        assertEquals(reads, h.store.reads, "no store read for a malformed id")
        h.push(memoState(uuid(3), uuid(2), 1, VoiceMemoStage.QUEUED))
        assertEquals(reads + 1, h.store.reads, "a well-formed unknown memo is looked up (and then dropped)")
        assertNull(h.stored(uuid(3)), "a reply never creates a memo")
    }

    @Test
    fun anOrganiseOnlyAttemptKeepsItsLocalTranscript() = runTest {
        val id = uuid(502)
        val h = start { seed(seededDoc(id, "t1" to "a").copy(content = seededDoc(id).content.copy(transcript = "my words"))) }
        h.act(MemoAction.OpenMemo(id))
        h.act(MemoAction.Reorganize)
        val attempt = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(memoState(id, attempt, 1, VoiceMemoStage.SUMMARIZING, transcript = "an echo that differs"))
        assertEquals("my words", h.stored(id)!!.content.transcript)
        h.push(memoState(id, attempt, 2, VoiceMemoStage.READY, transcript = "yet another", result = result("n")))
        assertEquals("my words", h.stored(id)!!.content.transcript)
        assertEquals(listOf("n"), h.stored(id)!!.todos.map { it.text })
    }

    @Test
    fun invisibleCharactersAreStrippedFromResultsAndEdits() = runTest {
        val h = start()
        val id = h.recordMemo()
        val bidi = "‮rm -rf‬"
        val tag = String(Character.toChars(0xE0041))
        h.push(
            readyState(id, h.attemptOf(id), 2).copy(
                result = dev.ccpocket.protocol.VoiceMemoResult(
                    title = "Pl​an\u0007", summary = "Sum⁦mary\n\tok", language = "en",
                    todos = listOf(
                        dev.ccpocket.protocol.VoiceMemoTodoSuggestion("Fix $bidi build$tag"),
                        dev.ccpocket.protocol.VoiceMemoTodoSuggestion("​‍﻿"),
                    ),
                ),
            ),
        )
        val doc = h.state.document!!
        assertEquals("Plan", doc.title)
        assertEquals("Summary\n\tok", doc.summary)
        assertEquals(listOf("Fix rm -rf build"), doc.todos.map { it.text }, "an item that is only invisible characters is dropped")

        val todo = doc.todos.single().todoId
        h.act(MemoAction.EditTitle("​‎⁠"))
        assertEquals("Plan", h.state.document!!.title, "a title of only invisible characters is blank")
        h.act(MemoAction.EditTitle("New‮ title"))
        assertEquals("New title", h.state.document!!.title)
        h.act(MemoAction.EditTodo(todo, "a\u0000b\nc\td\r‏"))
        assertEquals("ab\nc\td", h.state.document!!.todos.single().text)
        h.act(MemoAction.EditTodo(todo, "​"))
        assertEquals(MemoDispatchBlock.ONLY_BLANK, h.state.selection.block, "invisible padding does not make an item non-blank")
        assertEquals("", h.stored(id)!!.todos.single().text)
    }

    @Test
    fun sanitizeKeepsOrdinaryTextAndSurrogatePairs() {
        assertEquals("中文 😀 a\nb\tc", sanitizeMemoText("中文 😀 a\nb\tc"))
        assertEquals("ab", sanitizeMemoText("a­b؜‌"))
        assertEquals("x", sanitizeMemoText("x" + String(Character.toChars(0xE007F)) + String(Character.toChars(0x1D173))))
    }

    @Test
    fun selectionBlocksFollowTheirPriority() {
        val doc = seededDoc(uuid(1), "t1" to "a")
        val base = MemoModel(
            readiness = ready(), scope = SCOPE, screen = MemoScreen.DETAIL, currentId = uuid(1),
            slots = mapOf(uuid(1) to Slot(doc, diskRevision = 1)), catalog = MemoTargetCatalog(recent = listOf(TARGET_ROW)),
        )
        assertEquals(MemoDispatchBlock.NO_TARGET, base.selection().block)
        val targeted = base.copy(target = TARGET_ROW)
        assertEquals(MemoDispatchBlock.NONE, targeted.selection().block)
        val saving = targeted.copy(slots = mapOf(uuid(1) to Slot(doc, 1, inFlight = InFlight(1, 2))), readiness = ready(online = false))
        assertEquals(MemoDispatchBlock.SAVING, saving.selection().block)
        assertTrue(saving.project().document!!.saving)
        assertEquals(MemoDispatchBlock.OFFLINE, targeted.copy(readiness = ready(online = false), batch = Batch("b", uuid(9), TARGET, emptyList())).selection().block)
        assertEquals(MemoDispatchBlock.BUSY, targeted.copy(batch = Batch("b", uuid(9), TARGET, emptyList())).selection().block)
        val stopped = Batch("b", uuid(9), TARGET, emptyList(), phase = MemoDispatchPhase.STOPPED)
        assertEquals(MemoDispatchBlock.NONE, targeted.copy(batch = stopped).selection().block)
    }

    // ── organisers: pluggable, or none (protocol v2) ──────────────────────────────────────────────────

    @Test
    fun aNewRecordingAsksForTheOrganiserReadinessNamesOrForNone() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        val start = h.link.frames<VoiceMemoStart>().single()
        assertEquals(VOICE_MEMO_AGENT_NONE, start.agent, "no organiser: transcribe only")
        assertEquals(VOICE_MEMO_AGENT_NONE, h.stored(id)!!.processing!!.agent)
        assertNull(h.state.processing!!.organizer)
        assertEquals(MemoStepStatus.SKIPPED, h.state.processing!!.steps[2].status, "nothing to organise, from the start")
        h.push(memoState(id, h.attemptOf(id), 1, VoiceMemoStage.TRANSCRIBING))
        assertEquals(listOf(MemoStepStatus.DONE, MemoStepStatus.RUNNING, MemoStepStatus.SKIPPED), h.state.processing!!.steps.map { it.status })

        // The computer now offers Codex: the next memo asks for it.
        h.act(MemoAction.BackToList)
        h.link.readiness.value = ready(organizer = VOICE_MEMO_AGENT_CODEX)
        runCurrent()
        val second = h.recordMemo()
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.link.frames<VoiceMemoStart>().last().agent)
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.stored(second)!!.processing!!.agent)
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.state.processing!!.organizer)
        assertEquals(MemoStepStatus.WAITING, h.state.processing!!.steps[2].status)
    }

    @Test
    fun aResumedUploadAsksForTheOrganiserItsAttemptRecorded() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CODEX)) {
            link.result = { if (it is VoiceMemoAudio) MemoLinkSend.Indeterminate else MemoLinkSend.Written }
        }
        val id = h.recordMemo(ByteArray(200_000))
        val attempt = h.attemptOf(id)
        h.link.result = { MemoLinkSend.Written }
        h.link.readiness.value = ready(generation = 2, organizer = null)
        runCurrent()
        h.push(memoState(id, attempt, 1, VoiceMemoStage.RECEIVING))
        h.act(MemoAction.ResumeUpload)
        assertEquals(listOf(VOICE_MEMO_AGENT_CODEX, VOICE_MEMO_AGENT_CODEX), h.link.frames<VoiceMemoStart>().map { it.agent })
    }

    @Test
    fun aRetriedTranscriptionAsksForTheOrganiserOfNow() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CODEX))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.FAILED, errorCode = VoiceMemoError.TRANSCRIBE_FAILED))
        h.link.readiness.value = ready(organizer = null)
        runCurrent()
        h.act(MemoAction.RetryTranscription)
        val retry = h.link.frames<VoiceMemoStart>().last()
        assertEquals("audio", retry.inputKind)
        assertEquals(VOICE_MEMO_AGENT_NONE, retry.agent)
        assertEquals(VOICE_MEMO_AGENT_NONE, h.stored(id)!!.processing!!.agent)
    }

    @Test
    fun aTranscribeOnlyMemoOpensItsResultWithTheTranscriptAndNothingElse() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        advance(3_000)
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "Buy milk. Then call Ann"))
        assertEquals(MemoScreen.DETAIL, h.state.screen, "the result page, not an issue")
        val doc = h.state.document!!
        assertEquals("", doc.title)
        assertEquals("Buy milk", doc.titleFallback)
        assertNull(doc.summary)
        assertFalse(doc.summaryUnstructured)
        assertNull(doc.organizedBy)
        assertNull(doc.organizerAvailable)
        assertFalse(doc.degraded, "transcribe-only is not a failed organisation")
        assertTrue(doc.todos.isEmpty())
        assertEquals("Buy milk. Then call Ann", doc.transcript)
        assertTrue(doc.wholeTranscriptFits)
        assertFalse(doc.organizerLost, "no organiser was asked for, none was lost")
        assertNull(h.state.processing, "nothing is running and nothing went wrong")

        val stored = h.stored(id)!!
        val att = stored.processing!!
        assertTrue(att.accepted)
        assertEquals(VoiceMemoStage.TRANSCRIBED, att.stage)
        assertNull(att.errorCode)
        assertEquals(3_000L, att.timings.totalMs)
        assertEquals("Buy milk. Then call Ann", stored.content.transcript)
        assertNull(stored.content.audio, "the audio is released like after any stored result")
        assertFalse(h.files.has(SCOPE, id, DefaultVoiceMemoStore.AUDIO))
        // organise step: skipped, and the organiser was not "lost" (none was asked for)
        assertEquals(listOf(MemoStepStatus.DONE, MemoStepStatus.DONE, MemoStepStatus.SKIPPED), memoSteps(att, Run()).map { it.status })
        assertFalse(organizerLost(att))

        h.act(MemoAction.BackToList)
        val row = h.state.list.rows.single { it.memoId == id }
        assertEquals("Buy milk", row.title, "the list shows the first sentence of an untitled memo")
        assertNull(row.stage)
    }

    @Test
    fun anOrganiserThatVanishedLeavesATranscribedResultAndSaysSo() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CODEX))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 3, VoiceMemoStage.TRANSCRIBED, transcript = "words", errorCode = VoiceMemoError.AGENT_UNAVAILABLE))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        assertTrue(h.state.document!!.organizerLost, "the result page's note")
        assertNull(h.state.processing, "a finished result: no processing state lingers on the result page")

        // The processing page's projection of the same attempt (rule: organise step skipped, organiser lost).
        val onProcessingPage = MemoModel(
            readiness = ready(organizer = VOICE_MEMO_AGENT_CODEX), scope = SCOPE, screen = MemoScreen.PROCESSING, currentId = id,
            slots = mapOf(id to Slot(h.stored(id)!!, diskRevision = h.stored(id)!!.revision)),
        ).project().processing!!
        assertTrue(onProcessingPage.organizerLost)
        assertEquals(VOICE_MEMO_AGENT_CODEX, onProcessingPage.organizer)
        assertNull(onProcessingPage.issue, "a result, not an issue")
        assertEquals(listOf(MemoStepStatus.DONE, MemoStepStatus.DONE, MemoStepStatus.SKIPPED), onProcessingPage.steps.map { it.status })
        val stored = h.stored(id)!!
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, stored.processing!!.errorCode, "kept as reported")
        assertTrue(stored.processing.accepted)
        assertNull(stored.content.organizedBy)
        assertFalse(h.state.document!!.degraded)
        assertEquals("words", h.state.document!!.transcript)
    }

    @Test
    fun anOrganiseAttemptThatOnlyTranscribedIsAnIssueAndChangesNothing() = runTest {
        for (code in listOf(null, VoiceMemoError.AGENT_UNAVAILABLE)) {
            val id = uuid(510)
            val h = start { seed(seededDoc(id, "t1" to "keep me")) }
            h.act(MemoAction.OpenMemo(id))
            h.act(MemoAction.Reorganize)
            val start = h.link.frames<VoiceMemoStart>().last()
            assertEquals("transcript", start.inputKind)
            assertEquals(VOICE_MEMO_AGENT_CLAUDE, start.agent)
            val before = h.stored(id)!!
            h.push(memoState(id, start.attemptId, 1, VoiceMemoStage.TRANSCRIBED, transcript = "an echo", errorCode = code).copy(retryable = true))
            val after = h.stored(id)!!
            assertEquals(before.content, after.content, "$code")
            assertEquals(before.todos, after.todos)
            assertEquals(VoiceMemoStage.TRANSCRIBED, after.processing!!.stage)
            assertEquals(code, after.processing.errorCode)
            assertTrue(after.processing.accepted)
            assertTrue(after.processing.retryable)
            assertEquals(MemoScreen.DETAIL, h.state.screen)
            assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, h.state.processing!!.issue, "$code")
            assertFalse(h.state.processing!!.running)
            assertFalse(h.state.document!!.reorganizing)
            assertEquals(listOf("keep me"), h.state.document!!.todos.map { it.text })
        }
    }

    @Test
    fun aResultRecordsTheOrganiserThatWasAskedFor() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CODEX))
        val id = h.recordMemo()
        h.push(readyState(id, h.attemptOf(id), 2, "a"))
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.stored(id)!!.content.organizedBy)
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.state.document!!.organizedBy)
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.state.document!!.organizerAvailable)
        assertFalse(h.state.document!!.degraded)
        assertNull(h.state.processing)
    }

    @Test
    fun withoutAnOrganiserNothingIsOrganisedAndAFirstOrganisationComesLater() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "Plan the release"))
        val starts = h.link.frames<VoiceMemoStart>().size
        h.act(MemoAction.Reorganize)
        h.act(MemoAction.RetryProcessing)
        assertEquals(starts, h.link.frames<VoiceMemoStart>().size, "no organiser: ignored, no attempt")
        assertFalse(h.state.document!!.reorganizing)
        assertEquals(VoiceMemoStage.TRANSCRIBED, h.stored(id)!!.processing!!.stage)
        assertNull(h.state.toast)

        // The computer now offers Claude: a first organisation, which replaces the hand-written drafts.
        h.link.readiness.value = ready(organizer = VOICE_MEMO_AGENT_CLAUDE)
        runCurrent()
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, h.state.document!!.organizerAvailable)
        h.act(MemoAction.AddTodo)
        h.act(MemoAction.EditTodo(h.state.document!!.todos.single().todoId, "by hand"))
        h.act(MemoAction.Reorganize)
        val start = h.link.frames<VoiceMemoStart>().last()
        assertEquals("transcript", start.inputKind)
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, start.agent)
        assertEquals("Plan the release", start.transcript)
        assertEquals(MemoScreen.DETAIL, h.state.screen, "still the result page while organising")
        assertTrue(h.state.document!!.reorganizing)
        h.push(readyState(id, start.attemptId, 1, "n1", "n2"))
        val doc = h.state.document!!
        assertEquals(listOf("n1", "n2"), doc.todos.map { it.text })
        assertEquals("Plan", doc.title)
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, doc.organizedBy)
        assertFalse(doc.degraded)
    }

    @Test
    fun anOrganiserThatDisappearsBeforeTheAttemptIsMadeStartsNothing() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CLAUDE))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "words"))
        val starts = h.link.frames<VoiceMemoStart>().size
        h.repo.accept(MemoAction.Reorganize)
        h.link.readiness.value = ready(organizer = null)
        runCurrent()
        assertEquals(starts, h.link.frames<VoiceMemoStart>().size)
        assertFalse(h.state.document!!.reorganizing)
        assertEquals(VoiceMemoStage.TRANSCRIBED, h.stored(id)!!.processing!!.stage)
    }

    @Test
    fun theWholeTranscriptCanBecomeOneTodoThatKnowsWhereItCameFrom() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "Call Ann\u200B about the build"))
        assertTrue(h.state.document!!.wholeTranscriptFits)
        h.act(MemoAction.AddWholeTranscriptTodo)
        val row = h.state.document!!.todos.single()
        assertEquals("Call Ann about the build", row.text, "sanitized like any to-do")
        assertTrue(row.selected)
        assertTrue(row.wholeTranscript)
        assertEquals(MemoTodoState.DRAFT, row.state)
        assertTrue(h.stored(id)!!.todos.single().wholeTranscript)
        assertEquals(listOf(row.todoId), h.state.selection.items.map { it.todoId })

        h.act(MemoAction.CopyTodo(row.todoId))
        val copy = h.state.document!!.todos[1]
        assertTrue(copy.wholeTranscript, "a copy keeps the mark")

        h.act(MemoAction.EditTodo(row.todoId, row.text))
        assertTrue(h.state.document!!.todos[0].wholeTranscript, "the same text is not an edit")
        h.act(MemoAction.EditTodo(row.todoId, "Call Ann"))
        assertFalse(h.state.document!!.todos[0].wholeTranscript, "an edit clears it")
        assertFalse(h.stored(id)!!.todos[0].wholeTranscript)
        assertTrue(h.state.document!!.todos[1].wholeTranscript)
    }

    @Test
    fun aTranscriptThatDoesNotFitOneTodoCannotBecomeOne() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "字".repeat(VoiceMemoLimits.MAX_TODO_CODE_POINTS + 1)))
        assertFalse(h.state.document!!.wholeTranscriptFits)
        h.act(MemoAction.AddWholeTranscriptTodo)
        assertTrue(h.state.document!!.todos.isEmpty())
        assertNull(h.state.toast)

        assertEquals("字".repeat(1_000), wholeTranscriptText("字".repeat(1_000) + "\u200B\u2066"), "measured after sanitizing")
        assertNull(wholeTranscriptText("😀".repeat(1_001)), "code points, not UTF-16 units")
        assertEquals(2_000, wholeTranscriptText("😀".repeat(1_000))!!.length)
        assertNull(wholeTranscriptText("\u200B \u200D"))
        assertNull(wholeTranscriptText(null))
    }

    @Test
    fun theWholeTranscriptIsNotAddedToAFullList() = runTest {
        val h = openSeeded(*(1..VoiceMemoLimits.MAX_TODOS).map { "t$it" to "item $it" }.toTypedArray())
        assertTrue(h.state.document!!.wholeTranscriptFits)
        h.act(MemoAction.AddWholeTranscriptTodo)
        assertEquals(VoiceMemoLimits.MAX_TODOS, h.state.document!!.todos.size)
        assertNull(h.state.toast, "ignored, not announced")
    }

    @Test
    fun theFallbackTitleIsTheTranscriptsFirstSentence() {
        assertEquals("x".repeat(80), memoTitleFallback("x".repeat(200)), "no punctuation: cut at 80 code points")
        assertEquals("😀".repeat(80), memoTitleFallback("😀".repeat(81)))
        assertEquals("先修构建", memoTitleFallback("  先修构建。再回复 Ann！"))
        assertEquals("Ship it", memoTitleFallback("Ship it! Then rest."))
        assertEquals("Why", memoTitleFallback("Why? Because"))
        assertEquals("First line", memoTitleFallback("First line\nsecond line"))
        assertEquals("After the dots", memoTitleFallback("... After the dots"), "a leading empty piece is skipped")
        assertEquals("Hidden", memoTitleFallback("Hid\u200Bden。"))
        assertEquals("", memoTitleFallback(null))
        assertEquals("", memoTitleFallback("。！？"))
    }

    @Test
    fun theResultPageSaysTheOrganiserWasLostOnlyWhileTheMemoIsUnorganised() = runTest {
        val h = start(ready(organizer = VOICE_MEMO_AGENT_CODEX))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "words", errorCode = VoiceMemoError.AGENT_UNAVAILABLE))
        assertTrue(h.state.document!!.organizerLost)

        // A new organise attempt runs: the note goes, the attempt's own state speaks.
        h.act(MemoAction.Reorganize)
        assertFalse(h.state.document!!.organizerLost)
        assertTrue(h.state.document!!.reorganizing)
        val second = h.link.frames<VoiceMemoStart>().last().attemptId

        // It too only transcribes because the organiser vanished again: still unorganised, the note is back.
        h.push(memoState(id, second, 1, VoiceMemoStage.TRANSCRIBED, transcript = "words", errorCode = VoiceMemoError.AGENT_UNAVAILABLE))
        assertTrue(h.state.document!!.organizerLost)
        assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, h.state.processing!!.issue)

        // Organised by a ready result: no note.
        h.act(MemoAction.Reorganize)
        val third = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(readyState(id, third, 1, "n1"))
        assertFalse(h.state.document!!.organizerLost)
        assertEquals(VOICE_MEMO_AGENT_CODEX, h.state.document!!.organizedBy)

        // An organised memo whose re-organise lost its organiser keeps its result, and gets no "unorganised" note.
        h.act(MemoAction.Reorganize)
        val fourth = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(memoState(id, fourth, 1, VoiceMemoStage.TRANSCRIBED, transcript = "words", errorCode = VoiceMemoError.AGENT_UNAVAILABLE))
        assertFalse(h.state.document!!.organizerLost)
        assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, h.state.processing!!.issue)
        assertEquals(listOf("n1"), h.state.document!!.todos.map { it.text })
    }

    @Test
    fun aDegradedMemoStaysDegradedThroughAFailedReorganiseUntilAReadyResultReplacesIt() = runTest {
        val h = start()
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.DEGRADED, transcript = "raw words", errorCode = VoiceMemoError.SUMMARY_FAILED))
        assertTrue(h.state.document!!.degraded)
        assertTrue(h.stored(id)!!.content.degraded, "persisted")

        h.act(MemoAction.Reorganize)
        assertTrue(h.state.document!!.degraded, "while the new attempt runs")
        val second = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(memoState(id, second, 1, VoiceMemoStage.FAILED, errorCode = VoiceMemoError.AGENT_UNAVAILABLE))
        assertEquals(MemoProcessingIssue.AGENT_UNAVAILABLE, h.state.processing!!.issue)
        assertTrue(h.state.document!!.degraded, "and after it failed")
        assertTrue(h.stored(id)!!.content.degraded)

        // Read again from the store: the same.
        h.act(MemoAction.BackToList)
        h.act(MemoAction.OpenMemo(id))
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        assertTrue(h.state.document!!.degraded)

        // A ready result replaces the content: no longer degraded.
        h.act(MemoAction.Reorganize)
        val third = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(readyState(id, third, 1, "n1"))
        assertFalse(h.state.document!!.degraded)
        assertFalse(h.stored(id)!!.content.degraded)
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, h.stored(id)!!.content.organizedBy)
        assertEquals(listOf("n1"), h.state.document!!.todos.map { it.text })
    }

    @Test
    fun aTranscribeOnlyMemoWhoseFirstOrganisingFailsBecomesDegradedAndAnOrganisedOneDoesNot() = runTest {
        val h = start(ready(organizer = null))
        val id = h.recordMemo()
        h.push(memoState(id, h.attemptOf(id), 2, VoiceMemoStage.TRANSCRIBED, transcript = "Plan the release"))
        assertFalse(h.state.document!!.degraded)
        h.link.readiness.value = ready(organizer = VOICE_MEMO_AGENT_CLAUDE)
        runCurrent()
        h.act(MemoAction.Reorganize)
        val organising = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(memoState(id, organising, 1, VoiceMemoStage.DEGRADED, transcript = "Plan the release", errorCode = VoiceMemoError.INVALID_RESULT))
        assertTrue(h.state.document!!.degraded)
        assertTrue(h.stored(id)!!.content.degraded)
        assertFalse(h.state.document!!.organizerLost)

        // An organised memo whose re-organise degrades keeps its result and is not marked.
        val organised = uuid(520)
        val h2 = start { seed(seededDoc(organised, "t1" to "keep")) }
        h2.act(MemoAction.OpenMemo(organised))
        h2.act(MemoAction.Reorganize)
        val attempt = h2.link.frames<VoiceMemoStart>().last().attemptId
        h2.push(memoState(organised, attempt, 1, VoiceMemoStage.DEGRADED, transcript = "t", errorCode = VoiceMemoError.SUMMARY_FAILED))
        assertFalse(h2.state.document!!.degraded)
        assertFalse(h2.stored(organised)!!.content.degraded)
        assertEquals(MemoProcessingIssue.ORGANIZE_FAILED, h2.state.processing!!.issue)
    }

    @Test
    fun olderDocumentsAreReadAsDegradedByTheirShape() {
        val att = MemoAttempt(uuid(1), "audio", "0".repeat(64), 0, stage = VoiceMemoStage.DEGRADED, accepted = true)
        val old = MemoDocument(scope = SCOPE, memoId = uuid(2), createdAtMs = 1, updatedAtMs = 1, processing = att, content = MemoContent(transcript = "raw"))
        assertFalse(old.content.degraded, "a document from before the field")
        assertTrue(isDegraded(old), "nobody's summary + accepted degraded attempt")
        assertTrue(isDegraded(old.copy(content = old.content.copy(summary = "s", summaryUnstructured = true))))
        assertFalse(isDegraded(old.copy(content = old.content.copy(summary = "an earlier organiser's"))))
        assertFalse(isDegraded(old.copy(processing = att.copy(stage = VoiceMemoStage.TRANSCRIBED))))
        assertTrue(isDegraded(old.copy(processing = att.copy(stage = VoiceMemoStage.FAILED, accepted = false), content = old.content.copy(degraded = true))))
    }
}
