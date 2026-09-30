package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStatus
import dev.ccpocket.protocol.VoiceMemoValidation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceMemoServiceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeMemoClock()
    private val transcriber = FakeTranscriber()
    private val summarizer = FakeSummarizer()
    private val services = ArrayList<VoiceMemoService>()

    private val a = MemoOwner("device-a")
    private val b = MemoOwner("device-b")
    private val c = MemoOwner("device-c")

    private fun service(limits: MemoServiceLimits = MemoServiceLimits(sweepIntervalMs = 0)) =
        VoiceMemoService(scope, transcriber, summarizer, clock, limits).also { services += it }

    /** A daemon built with several organisers (or none). */
    private fun service(organisers: MemoSummarizers) =
        VoiceMemoService(scope, transcriber, organisers, clock, MemoServiceLimits(sweepIntervalMs = 0)).also { services += it }

    @AfterEach
    fun tearDown() = runBlocking {
        services.forEach { withTimeout(5_000) { it.close() } }
        scope.cancel()
    }

    private suspend fun VoiceMemoService.upload(owner: MemoOwner, up: MemoUpload, inbox: Inbox, agent: String = VOICE_MEMO_AGENT_CLAUDE) {
        handle(owner, up.start(agent = agent), inbox)
        up.chunks().forEach { handle(owner, it, inbox) }
    }

    private fun assertRevisionsIncrease(inbox: Inbox) {
        val revs = inbox.states.filter { it.revision > 0 }.map { it.revision }
        assertEquals(revs.sorted(), revs)
        assertEquals(revs.distinct(), revs)
    }

    // ── happy paths ─────────────────────────────────────────────────────

    @Test
    fun audio_runs_receiving_to_ready_with_transcript_and_metrics() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        assertEquals(VoiceMemoStage.RECEIVING, inbox.last().stage)
        assertEquals(1, inbox.last().revision)
        s.handle(a, up.chunk(0), inbox)
        s.handle(a, up.chunk(1), inbox)
        assertEquals(1, inbox.states.size) // accepted chunks are not state changes
        s.handle(a, up.chunk(2), inbox)
        val ready = inbox.awaitStage(VoiceMemoStage.READY)
        assertEquals(
            listOf(VoiceMemoStage.RECEIVING, VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.SUMMARIZING, VoiceMemoStage.READY),
            inbox.states.map { it.stage },
        )
        assertEquals(listOf(1L, 2, 3, 4, 5), inbox.states.map { it.revision })
        val summarizing = inbox.states.first { it.stage == VoiceMemoStage.SUMMARIZING }
        assertEquals(FakeTranscriber.TRANSCRIPT, summarizing.transcript)
        assertEquals(FakeTranscriber.TRANSCRIPT, ready.transcript)
        assertEquals(FakeSummarizer.RESULT, ready.result)
        assertNull(VoiceMemoValidation.validateState(ready))
        assertEquals(5_000, ready.metrics.audioDurationMs)
        assertNotNull(ready.metrics.queueMs)
        assertNotNull(ready.metrics.transcribeMs)
        assertNotNull(ready.metrics.summarizeMs)
        assertNull(ready.metrics.coldStart)
        assertEquals(clock.wall + VoiceMemoLimits.RESULT_TTL_MS, ready.expiresAtMs)
        assertFalse(ready.retryable)
        assertEquals(0, s.activeJobs())
    }

    @Test
    fun transcript_input_starts_at_summarizing_and_skips_whisper() = runBlocking {
        val s = service()
        val inbox = Inbox()
        var seenLocale: String? = null
        summarizer.behavior = { _, locale -> seenLocale = locale; MemoSummaryResult.Ok(FakeSummarizer.RESULT) }
        s.handle(a, transcriptStart(locale = "en"), inbox)
        assertEquals(VoiceMemoStage.SUMMARIZING, inbox.states.first().stage)
        assertEquals(FakeTranscriber.TRANSCRIPT, inbox.states.first().transcript)
        val ready = inbox.awaitStage(VoiceMemoStage.READY)
        assertEquals(2, ready.revision)
        assertNull(ready.metrics.transcribeMs)
        assertNull(ready.metrics.queueMs)
        assertEquals(0, transcriber.calls.get())
        assertEquals("en", seenLocale)
    }

    // ── input validation & gates ────────────────────────────────────────

    @Test
    fun malformed_ids_are_neither_recorded_nor_answered() = runBlocking {
        val s = service()
        val inbox = Inbox()
        s.handle(a, transcriptStart(memoId = "not-a-uuid"), inbox)
        s.handle(a, VoiceMemoGet("x", "y"), inbox)
        s.handle(a, VoiceMemoCancel("x", "y"), inbox)
        s.handle(a, MemoUpload.random(100).chunk(0).copy(attemptId = "../../etc"), inbox)
        assertTrue(inbox.states.isEmpty())
        assertEquals(0, s.activeJobs())
    }

    @Test
    fun invalid_start_shapes_are_refused_with_revision_zero_and_not_recorded() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        suspend fun refused(start: dev.ccpocket.protocol.VoiceMemoStart): String? {
            val inbox = Inbox()
            s.handle(a, start, inbox)
            val st = inbox.states.single()
            assertEquals(VoiceMemoStage.FAILED, st.stage)
            assertEquals(0, st.revision)
            val probe = Inbox()
            s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), probe)
            assertEquals(VoiceMemoStage.UNKNOWN, probe.last().stage)
            return st.errorCode
        }
        assertEquals(VoiceMemoError.INVALID_INPUT, refused(up.start().copy(model = "sonnet")))
        assertEquals(VoiceMemoError.AUDIO_TOO_LONG, refused(up.start(durationMs = VoiceMemoLimits.MAX_AUDIO_DURATION_MS + 1)))
        assertEquals(VoiceMemoError.INVALID_INPUT, refused(up.start().copy(chunkCount = 9)))
        assertEquals(VoiceMemoError.INVALID_INPUT, refused(transcriptStart(sha256 = "0".repeat(64))))
        assertEquals(VoiceMemoError.EMPTY_TRANSCRIPT, refused(transcriptStart(text = "   ")))
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, refused(up.start().copy(agent = "codex")))
    }

    @Test
    fun not_ready_blocks_start_and_audio_but_not_get_or_cancel() = runBlocking {
        val s = service()
        val inbox = Inbox()
        val up = MemoUpload.random()
        s.handle(a, up.start(), inbox)
        transcriber.status = VoiceMemoStatus.MODEL_MISSING
        assertEquals(VoiceMemoStatus.MODEL_MISSING, s.capability().status)
        s.handle(a, up.chunk(0), inbox)
        assertEquals(VoiceMemoError.NOT_READY, inbox.last().errorCode)
        assertEquals(0, inbox.last().revision)
        val other = transcriptStart()
        s.handle(a, other, inbox)
        assertEquals(VoiceMemoError.NOT_READY, inbox.last().errorCode)
        s.handle(a, VoiceMemoGet(up.memoId, up.attemptId), inbox)
        assertEquals(VoiceMemoStage.RECEIVING, inbox.last().stage)
        s.handle(a, VoiceMemoCancel(up.memoId, up.attemptId), inbox)
        assertEquals(VoiceMemoStage.CANCELLED, inbox.last().stage)
    }

    @Test
    fun capability_reports_local_prerequisites() {
        val s = service()
        assertEquals(MemoCapability(VoiceMemoLimits.VERSION, listOf("claude"), VoiceMemoStatus.READY), s.capability())
        // no organiser: transcription is still ready, it just has nobody to organise with
        summarizer.available = false
        assertEquals(MemoCapability(VoiceMemoLimits.VERSION, emptyList(), VoiceMemoStatus.READY), s.capability())
        transcriber.status = VoiceMemoStatus.WHISPER_MISSING
        assertEquals(VoiceMemoStatus.WHISPER_MISSING, s.capability().status)
        assertEquals(0, transcriber.calls.get() + summarizer.calls.get())
    }

    @Test
    fun agent_unavailable_refuses_start() = runBlocking {
        val s = service()
        summarizer.available = false
        val inbox = Inbox()
        s.handle(a, transcriptStart(), inbox)
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, inbox.last().errorCode)
        assertTrue(inbox.last().retryable)
    }

    @Test
    fun other_frames_are_ignored() = runBlocking {
        val s = service()
        val inbox = Inbox()
        s.handle(a, ClientCaps(), inbox)
        s.handle(MemoOwner(""), transcriptStart(), inbox)
        assertTrue(inbox.states.isEmpty())
    }

    // ── identity, idempotency, conflicts ────────────────────────────────

    @Test
    fun devices_are_isolated_even_with_identical_ids() = runBlocking {
        val s = service()
        val gate = CompletableDeferred<Unit>()
        summarizer.behavior = { _, _ -> gate.await(); MemoSummaryResult.Ok(FakeSummarizer.RESULT) }
        val start = transcriptStart()
        val ia = Inbox()
        s.handle(a, start, ia)
        val ib = Inbox()
        s.handle(b, VoiceMemoGet(start.memoId, start.attemptId), ib)
        assertEquals(VoiceMemoStage.UNKNOWN, ib.last().stage)
        s.handle(b, VoiceMemoCancel(start.memoId, start.attemptId), ib)
        gate.complete(Unit)
        ia.awaitStage(VoiceMemoStage.READY)
        assertTrue(ib.states.none { it.stage == VoiceMemoStage.READY })
        // b's own start of the same ids is its own job — and b's earlier cancel marker applies to b only
        s.handle(b, start, ib)
        assertEquals(VoiceMemoStage.CANCELLED, ib.last().stage)
        assertEquals(VoiceMemoStage.READY, s.peek(a, start.memoId, start.attemptId)!!.stage)
    }

    @Test
    fun repeated_start_returns_state_and_different_input_conflicts() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        s.handle(a, up.start(), inbox)
        assertEquals(inbox.states[0], inbox.states[1]) // same snapshot, no revision bump
        s.handle(a, up.start().copy(durationMs = 4_000), inbox)
        assertEquals(VoiceMemoError.INPUT_CONFLICT, inbox.last().errorCode)
        assertEquals(0, inbox.last().revision)
        assertEquals(VoiceMemoStage.RECEIVING, s.peek(a, up.memoId, up.attemptId)!!.stage)
    }

    @Test
    fun terminal_job_is_never_restarted_by_a_repeated_start() = runBlocking {
        val s = service()
        val start = transcriptStart()
        val inbox = Inbox()
        s.handle(a, start, inbox)
        val ready = inbox.awaitStage(VoiceMemoStage.READY)
        s.handle(a, start, inbox)
        assertEquals(ready, inbox.last())
        delay(100)
        assertEquals(1, summarizer.calls.get())
    }

    @Test
    fun get_never_bumps_the_revision() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        repeat(5) { s.handle(a, VoiceMemoGet(up.memoId, up.attemptId), inbox) }
        assertTrue(inbox.states.all { it.revision == 1L })
    }

    @Test
    fun conflicting_chunk_fails_the_upload() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        s.handle(a, up.chunk(0), inbox)
        val other = MemoUpload.random(seed = 99)
        s.handle(a, other.chunk(0).copy(memoId = up.memoId, attemptId = up.attemptId), inbox)
        assertEquals(VoiceMemoStage.FAILED, inbox.last().stage)
        assertEquals(VoiceMemoError.INPUT_CONFLICT, inbox.last().errorCode)
        assertFalse(inbox.last().retryable)
        assertEquals(0, transcriber.calls.get())
    }

    @Test
    fun duplicate_final_chunk_starts_exactly_one_worker() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        up.chunks().dropLast(1).forEach { s.handle(a, it, inbox) }
        val last = up.chunks().last()
        List(8) { launch(Dispatchers.Default) { s.handle(a, last, inbox) } }.joinAll()
        // a whole-file resend after completion as well
        up.chunks().forEach { s.handle(a, it, inbox) }
        inbox.awaitStage(VoiceMemoStage.READY)
        delay(100)
        assertEquals(1, transcriber.calls.get())
        assertEquals(1, summarizer.calls.get())
        // later duplicates only echo the current snapshot: exactly one queued TRANSITION happened
        assertEquals(1, inbox.states.filter { it.stage == VoiceMemoStage.QUEUED }.map { it.revision }.distinct().size)
        assertEquals(5L, inbox.states.maxOf { it.revision })
    }

    // ── cancel ──────────────────────────────────────────────────────────

    @Test
    fun cancel_before_start_makes_the_late_start_cancelled() = runBlocking {
        val s = service()
        val start = transcriptStart()
        val inbox = Inbox()
        s.handle(a, VoiceMemoCancel(start.memoId, start.attemptId), inbox)
        assertEquals(VoiceMemoStage.UNKNOWN, inbox.last().stage)
        assertEquals(VoiceMemoError.UNKNOWN_JOB, inbox.last().errorCode)
        s.handle(a, start, inbox)
        assertEquals(VoiceMemoStage.CANCELLED, inbox.last().stage)
        assertEquals(1, inbox.last().revision)
        s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), inbox)
        assertEquals(VoiceMemoStage.CANCELLED, inbox.last().stage)
        delay(100)
        assertEquals(0, summarizer.calls.get())
    }

    @Test
    fun cancel_during_transcription_stops_it_and_never_summarizes() = runBlocking {
        val s = service()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        transcriber.behavior = { _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit); throw e
            }
        }
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.upload(a, up, inbox)
        withTimeout(5_000) { entered.await() }
        s.handle(a, VoiceMemoCancel(up.memoId, up.attemptId), inbox)
        assertEquals(VoiceMemoStage.CANCELLED, inbox.last().stage)
        assertEquals(VoiceMemoError.CANCELLED, inbox.last().errorCode)
        withTimeout(5_000) { cancelled.await() }
        assertEquals(0, s.activeJobs())
        delay(100)
        assertEquals(0, summarizer.calls.get())
        // the slot is free again: the next job transcribes
        transcriber.behavior = { _, _ -> MemoTranscribeResult.Ok(FakeTranscriber.TRANSCRIPT, 1_000) }
        val next = Inbox()
        s.upload(a, MemoUpload.random(seed = 3), next)
        next.awaitStage(VoiceMemoStage.READY)
    }

    @Test
    fun late_completion_after_cancel_is_discarded() = runBlocking {
        val s = service()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        transcriber.behavior = { _, _ ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                finished.complete(Unit)
            }
            MemoTranscribeResult.Ok(FakeTranscriber.TRANSCRIPT, 1_000)
        }
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.upload(a, up, inbox)
        withTimeout(5_000) { entered.await() }
        s.handle(a, VoiceMemoCancel(up.memoId, up.attemptId), inbox)
        val cancelledRev = inbox.last().revision
        release.complete(Unit)
        withTimeout(5_000) { finished.await() }
        delay(200)
        val now = s.peek(a, up.memoId, up.attemptId)!!
        assertEquals(VoiceMemoStage.CANCELLED, now.stage)
        assertEquals(cancelledRev, now.revision)
        assertEquals(0, summarizer.calls.get())
        assertTrue(inbox.states.none { it.stage == VoiceMemoStage.SUMMARIZING })
    }

    @Test
    fun cancel_of_a_terminal_job_returns_it_unchanged() = runBlocking {
        val s = service()
        val start = transcriptStart()
        val inbox = Inbox()
        s.handle(a, start, inbox)
        val ready = inbox.awaitStage(VoiceMemoStage.READY)
        s.handle(a, VoiceMemoCancel(start.memoId, start.attemptId), inbox)
        assertEquals(ready, inbox.last())
        s.handle(a, VoiceMemoCancel(start.memoId, start.attemptId), inbox)
        assertEquals(ready, inbox.last())
    }

    // ── limits ──────────────────────────────────────────────────────────

    @Test
    fun one_active_job_per_device_and_two_per_daemon() = runBlocking {
        val s = service()
        val ia = Inbox()
        s.handle(a, MemoUpload.random().start(), ia)
        s.handle(a, MemoUpload.random(seed = 2).start(), ia)
        assertEquals(VoiceMemoError.BUSY, ia.last().errorCode)
        assertTrue(ia.last().retryable)
        val ib = Inbox()
        s.handle(b, MemoUpload.random(seed = 3).start(), ib)
        assertEquals(VoiceMemoStage.RECEIVING, ib.last().stage)
        val ic = Inbox()
        s.handle(c, transcriptStart(), ic)
        assertEquals(VoiceMemoError.BUSY, ic.last().errorCode)
        assertEquals(2, s.activeJobs())
    }

    @Test
    fun whisper_slot_wait_is_bounded_and_reports_busy() = runBlocking {
        val s = service(MemoServiceLimits(slotWaitMs = 200, sweepIntervalMs = 0))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transcriber.behavior = { _, _ ->
            entered.complete(Unit); release.await(); MemoTranscribeResult.Ok(FakeTranscriber.TRANSCRIPT, 1_000)
        }
        val ia = Inbox()
        s.upload(a, MemoUpload.random(), ia)
        withTimeout(5_000) { entered.await() }
        val ib = Inbox()
        s.upload(b, MemoUpload.random(seed = 5), ib)
        val busy = ib.awaitStage(VoiceMemoStage.FAILED)
        assertEquals(VoiceMemoError.BUSY, busy.errorCode)
        assertTrue(busy.retryable)
        assertNotNull(busy.metrics.queueMs)
        release.complete(Unit)
        ia.awaitStage(VoiceMemoStage.READY)
        assertEquals(1, transcriber.calls.get())
    }

    // ── expiry ──────────────────────────────────────────────────────────

    @Test
    fun results_expire_after_the_ttl_and_then_read_unknown() = runBlocking {
        val s = service()
        val start = transcriptStart()
        val inbox = Inbox()
        s.handle(a, start, inbox)
        inbox.awaitStage(VoiceMemoStage.READY)
        clock.advance(VoiceMemoLimits.RESULT_TTL_MS)
        s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), inbox)
        assertEquals(VoiceMemoStage.UNKNOWN, inbox.last().stage)
        assertEquals(0, inbox.last().revision)
    }

    @Test
    fun terminal_cache_is_bounded() = runBlocking {
        val s = service(MemoServiceLimits(registry = MemoRegistryLimits(maxCachedJobs = 3), sweepIntervalMs = 0))
        val starts = List(5) { transcriptStart() }
        for (st in starts) {
            val inbox = Inbox()
            s.handle(a, st, inbox)
            inbox.awaitStage(VoiceMemoStage.READY)
            clock.advance(1)
        }
        assertEquals(3, s.cachedTerminalJobs())
        assertNull(s.peek(a, starts[0].memoId, starts[0].attemptId))
        assertNull(s.peek(a, starts[1].memoId, starts[1].attemptId))
        assertNotNull(s.peek(a, starts[4].memoId, starts[4].attemptId))
    }

    @Test
    fun idle_upload_fails_with_upload_timeout() = runBlocking {
        val s = service()
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(), inbox)
        s.handle(a, up.chunk(0), inbox)
        clock.advance(VoiceMemoLimits.UPLOAD_IDLE_MS)
        s.sweepNow()
        val failed = inbox.last()
        assertEquals(VoiceMemoStage.FAILED, failed.stage)
        assertEquals(VoiceMemoError.UPLOAD_TIMEOUT, failed.errorCode)
        assertTrue(failed.retryable)
        up.chunks().forEach { s.handle(a, it, inbox) }
        delay(100)
        assertEquals(0, transcriber.calls.get())
        assertEquals(0, s.activeJobs())
    }

    @Test
    fun a_fresh_daemon_answers_unknown() = runBlocking {
        val start = transcriptStart()
        val first = service()
        val inbox = Inbox()
        first.handle(a, start, inbox)
        inbox.awaitStage(VoiceMemoStage.READY)
        first.close()
        val restarted = service()
        restarted.handle(a, VoiceMemoGet(start.memoId, start.attemptId), inbox)
        val st = inbox.last()
        assertEquals(VoiceMemoStage.UNKNOWN, st.stage)
        assertEquals(0, st.revision)
        assertEquals(VoiceMemoError.UNKNOWN_JOB, st.errorCode)
    }

    // ── delivery ────────────────────────────────────────────────────────

    @Test
    fun a_new_connection_get_takes_over_later_pushes() = runBlocking {
        val s = service()
        val gate = CompletableDeferred<Unit>()
        summarizer.behavior = { _, _ -> gate.await(); MemoSummaryResult.Ok(FakeSummarizer.RESULT) }
        val start = transcriptStart()
        val old = Inbox()
        s.handle(a, start, old)
        val fresh = Inbox()
        s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), fresh)
        gate.complete(Unit)
        fresh.awaitStage(VoiceMemoStage.READY)
        assertTrue(old.states.none { it.stage == VoiceMemoStage.READY })
    }

    @Test
    fun failed_delivery_never_cancels_the_job() = runBlocking {
        val s = service()
        val start = transcriptStart()
        s.handle(a, start, Inbox(accept = false))
        withTimeout(5_000) {
            while (s.peek(a, start.memoId, start.attemptId)?.stage != VoiceMemoStage.READY) delay(10)
        }
        val start2 = transcriptStart()
        s.handle(b, start2, Inbox(throwOnSend = true))
        withTimeout(5_000) {
            while (s.peek(b, start2.memoId, start2.attemptId)?.stage != VoiceMemoStage.READY) delay(10)
        }
        val later = Inbox()
        s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), later)
        assertEquals(FakeSummarizer.RESULT, later.last().result)
    }

    @Test
    fun revoke_cancels_active_jobs_and_drops_results() = runBlocking {
        val s = service()
        val done = transcriptStart()
        val inbox = Inbox()
        s.handle(a, done, inbox)
        inbox.awaitStage(VoiceMemoStage.READY)
        val cancelled = CompletableDeferred<Unit>()
        transcriber.behavior = { _, _ ->
            try { awaitCancellation() } catch (e: CancellationException) { cancelled.complete(Unit); throw e }
        }
        val up = MemoUpload.random()
        s.upload(a, up, inbox)
        inbox.awaitStage(VoiceMemoStage.TRANSCRIBING)
        s.revokeDevice(a.deviceId)
        withTimeout(5_000) { cancelled.await() }
        val probe = Inbox()
        s.handle(a, VoiceMemoGet(done.memoId, done.attemptId), probe)
        s.handle(a, VoiceMemoGet(up.memoId, up.attemptId), probe)
        assertTrue(probe.states.all { it.stage == VoiceMemoStage.UNKNOWN })
        assertEquals(0, s.activeJobs())
    }

    // ── failure mapping ─────────────────────────────────────────────────

    @Test
    fun summarizer_failures_degrade_to_the_transcript() = runBlocking {
        val cases = listOf(
            MemoSummaryResult.Invalid to (VoiceMemoError.INVALID_RESULT to false),
            MemoSummaryResult.Failed to (VoiceMemoError.SUMMARY_FAILED to true),
            MemoSummaryResult.TimedOut to (VoiceMemoError.SUMMARY_TIMEOUT to true),
            MemoSummaryResult.Unavailable to (VoiceMemoError.AGENT_UNAVAILABLE to true),
        )
        val s = service()
        for ((outcome, expected) in cases) {
            summarizer.behavior = { _, _ -> outcome }
            val inbox = Inbox()
            s.handle(a, transcriptStart(), inbox)
            val st = inbox.awaitStage(VoiceMemoStage.DEGRADED)
            assertEquals(expected.first, st.errorCode)
            assertEquals(expected.second, st.retryable)
            assertEquals(FakeTranscriber.TRANSCRIPT, st.transcript)
            assertNull(st.result)
            assertNull(VoiceMemoValidation.validateState(st))
        }
    }

    @Test
    fun transcriber_failures_fail_the_job_with_their_code() = runBlocking {
        val s = service()
        val cases = listOf(
            VoiceMemoError.TRANSCRIBE_FAILED to true,
            VoiceMemoError.TRANSCRIBE_TIMEOUT to true,
            VoiceMemoError.AUDIO_INVALID to false,
            VoiceMemoError.AUDIO_TOO_LONG to false,
            VoiceMemoError.TRANSCRIPT_TOO_LONG to false,
            VoiceMemoError.EMPTY_TRANSCRIPT to false,
        )
        cases.forEachIndexed { i, (code, retryable) ->
            transcriber.behavior = { _, _ -> MemoTranscribeResult.Failed(code, 2_000) }
            val inbox = Inbox()
            s.upload(a, MemoUpload.random(seed = 10 + i), inbox)
            val st = inbox.awaitStage(VoiceMemoStage.FAILED)
            assertEquals(code, st.errorCode)
            assertEquals(retryable, st.retryable)
            assertEquals(2_000, st.metrics.audioDurationMs)
        }
        assertEquals(0, summarizer.calls.get())
    }

    @Test
    fun unexpected_exceptions_become_fixed_codes() = runBlocking {
        val s = service()
        transcriber.behavior = { _, _ -> throw IllegalStateException("whisper exploded: secret path /Users/x") }
        val ia = Inbox()
        s.upload(a, MemoUpload.random(), ia)
        val failed = ia.awaitStage(VoiceMemoStage.FAILED)
        assertEquals(VoiceMemoError.TRANSCRIBE_FAILED, failed.errorCode)
        summarizer.behavior = { _, _ -> throw IllegalStateException("model said something private") }
        val ib = Inbox()
        s.handle(b, transcriptStart(), ib)
        val degraded = ib.awaitStage(VoiceMemoStage.DEGRADED)
        assertEquals(VoiceMemoError.SUMMARY_FAILED, degraded.errorCode)
        assertEquals(FakeTranscriber.TRANSCRIPT, degraded.transcript)
        assertFalse((ia.states + ib.states).any { it.toString().contains("secret") || it.toString().contains("private") })
    }

    @Test
    fun close_cancels_running_work() = runBlocking {
        val s = service()
        val cancelled = CompletableDeferred<Unit>()
        summarizer.behavior = { _, _ ->
            try { awaitCancellation() } catch (e: CancellationException) { cancelled.complete(Unit); throw e }
        }
        s.handle(a, transcriptStart(), Inbox())
        delay(100)
        withTimeout(5_000) { s.close() }
        withTimeout(5_000) { cancelled.await() }
    }

    // ── pluggable organisers ────────────────────────────────────────────

    @Test
    fun transcribe_only_ends_transcribed_and_runs_no_organiser() = runBlocking {
        val claude = FakeSummarizer()
        val codex = FakeSummarizer(agent = VOICE_MEMO_AGENT_CODEX)
        val s = service(MemoSummarizers(listOf(claude, codex)))
        val inbox = Inbox()
        s.upload(a, MemoUpload.random(), inbox, agent = VOICE_MEMO_AGENT_NONE)
        val done = inbox.awaitStage(VoiceMemoStage.TRANSCRIBED)
        assertEquals(
            listOf(VoiceMemoStage.RECEIVING, VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.TRANSCRIBED),
            inbox.states.map { it.stage },
        )
        assertEquals(FakeTranscriber.TRANSCRIPT, done.transcript)
        assertNull(done.result)
        assertNull(done.errorCode)
        assertFalse(done.retryable)
        assertEquals(5_000, done.metrics.audioDurationMs)
        assertNotNull(done.metrics.transcribeMs)
        assertNull(done.metrics.summarizeMs)
        assertNotNull(done.expiresAtMs)
        assertNull(VoiceMemoValidation.validateState(done))
        delay(100)
        assertEquals(0, claude.calls.get() + codex.calls.get())
        assertEquals(0, s.activeJobs())
    }

    @Test
    fun a_daemon_without_any_organiser_still_transcribes() = runBlocking {
        val s = service(MemoSummarizers.EMPTY)
        assertEquals(MemoCapability(VoiceMemoLimits.VERSION, emptyList(), VoiceMemoStatus.READY), s.capability())
        // asking for an organiser that is not advertised is refused up front…
        val refused = Inbox()
        s.handle(a, MemoUpload.random(seed = 1).start(agent = VOICE_MEMO_AGENT_CLAUDE), refused)
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, refused.last().errorCode)
        assertEquals(0, refused.last().revision)
        // …while transcribe-only works exactly as before
        val inbox = Inbox()
        s.upload(a, MemoUpload.random(seed = 2), inbox, agent = VOICE_MEMO_AGENT_NONE)
        assertEquals(FakeTranscriber.TRANSCRIPT, inbox.awaitStage(VoiceMemoStage.TRANSCRIBED).transcript)
    }

    @Test
    fun the_requested_organiser_runs_not_the_first_one() = runBlocking {
        val claude = FakeSummarizer()
        val codexResult = FakeSummarizer.RESULT.copy(title = "由 Codex 整理")
        val codex = FakeSummarizer(agent = VOICE_MEMO_AGENT_CODEX, behavior = { _, _ -> MemoSummaryResult.Ok(codexResult) })
        val s = service(MemoSummarizers(listOf(claude, codex)))
        assertEquals(listOf("claude", "codex"), s.capability().agents)
        val audio = Inbox()
        s.upload(a, MemoUpload.random(), audio, agent = VOICE_MEMO_AGENT_CODEX)
        assertEquals(codexResult, audio.awaitStage(VoiceMemoStage.READY).result)
        val text = Inbox()
        s.handle(b, transcriptStart(agent = VOICE_MEMO_AGENT_CODEX), text)
        assertEquals(codexResult, text.awaitStage(VoiceMemoStage.READY).result)
        assertEquals(2, codex.calls.get())
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun an_organiser_gone_by_transcription_end_leaves_a_retryable_transcribed_result() = runBlocking {
        val claude = FakeSummarizer()
        val codex = FakeSummarizer(agent = VOICE_MEMO_AGENT_CODEX)
        val s = service(MemoSummarizers(listOf(claude, codex)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transcriber.behavior = { _, _ ->
            entered.complete(Unit); release.await(); MemoTranscribeResult.Ok(FakeTranscriber.TRANSCRIPT, 3_000)
        }
        val inbox = Inbox()
        s.upload(a, MemoUpload.random(), inbox, agent = VOICE_MEMO_AGENT_CLAUDE)
        withTimeout(5_000) { entered.await() }
        claude.available = false // e.g. the CLI was uninstalled mid-way
        release.complete(Unit)
        val done = inbox.awaitStage(VoiceMemoStage.TRANSCRIBED)
        assertEquals(FakeTranscriber.TRANSCRIPT, done.transcript)
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, done.errorCode)
        assertTrue(done.retryable)
        assertEquals(3_000, done.metrics.audioDurationMs)
        assertNull(VoiceMemoValidation.validateState(done))
        assertTrue(inbox.states.none { it.stage == VoiceMemoStage.SUMMARIZING })
        delay(100)
        // never a silent switch to the organiser that IS still there
        assertEquals(0, claude.calls.get() + codex.calls.get())
    }

    @Test
    fun a_transcript_input_whose_organiser_vanished_also_ends_transcribed() = runBlocking {
        // launchable when the start frame is checked, gone when the worker asks
        summarizer.availability = { check -> check == 1 }
        val s = service()
        val inbox = Inbox()
        s.handle(a, transcriptStart(), inbox)
        assertEquals(VoiceMemoStage.SUMMARIZING, inbox.states.first().stage)
        val done = inbox.awaitStage(VoiceMemoStage.TRANSCRIBED)
        assertEquals(VoiceMemoError.AGENT_UNAVAILABLE, done.errorCode)
        assertEquals(FakeTranscriber.TRANSCRIPT, done.transcript)
        assertEquals(0, summarizer.calls.get())
    }

    @Test
    fun transcript_input_cannot_ask_for_no_organiser() = runBlocking {
        val s = service()
        val inbox = Inbox()
        val start = transcriptStart(agent = VOICE_MEMO_AGENT_NONE)
        s.handle(a, start, inbox)
        assertEquals(VoiceMemoStage.FAILED, inbox.last().stage)
        assertEquals(VoiceMemoError.INVALID_INPUT, inbox.last().errorCode)
        assertEquals(0, inbox.last().revision)
        val probe = Inbox()
        s.handle(a, VoiceMemoGet(start.memoId, start.attemptId), probe)
        assertEquals(VoiceMemoStage.UNKNOWN, probe.last().stage)
    }

    @Test
    fun transcribe_only_does_not_need_an_organiser_check() = runBlocking {
        // an organiser whose local check explodes must not block transcribe-only memos
        summarizer.availability = { error("stat failed") }
        val s = service()
        val inbox = Inbox()
        s.upload(a, MemoUpload.random(), inbox, agent = VOICE_MEMO_AGENT_NONE)
        assertNull(inbox.awaitStage(VoiceMemoStage.TRANSCRIBED).errorCode)
        assertTrue(s.capability().agents.isEmpty())
    }

    @Test
    fun a_repeated_start_with_another_organiser_is_an_input_conflict() = runBlocking {
        val s = service(MemoSummarizers(listOf(FakeSummarizer(), FakeSummarizer(agent = VOICE_MEMO_AGENT_CODEX))))
        val up = MemoUpload.random()
        val inbox = Inbox()
        s.handle(a, up.start(agent = VOICE_MEMO_AGENT_CLAUDE), inbox)
        s.handle(a, up.start(agent = VOICE_MEMO_AGENT_CODEX), inbox)
        assertEquals(VoiceMemoError.INPUT_CONFLICT, inbox.last().errorCode)
        s.handle(a, up.start(agent = VOICE_MEMO_AGENT_NONE), inbox)
        assertEquals(VoiceMemoError.INPUT_CONFLICT, inbox.last().errorCode)
        assertEquals(VoiceMemoStage.RECEIVING, s.peek(a, up.memoId, up.attemptId)!!.stage)
    }
}
