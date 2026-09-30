package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoIds
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoJobRegistryTest {

    private val clock = FakeMemoClock()
    private val spawned = ArrayList<Triple<MemoJobKey, Long, Job>>()

    private fun registry(limits: MemoRegistryLimits = MemoRegistryLimits()) =
        MemoJobRegistry(clock, limits) { key, gen, _ -> Job().also { spawned += Triple(key, gen, it) } }

    private fun MemoOutcome.state() = pushes.last().state

    private fun key(device: String = "dev-a", memo: String = VoiceMemoIds.newId(), attempt: String = VoiceMemoIds.newId()) =
        MemoJobKey(device, memo, attempt)

    @Test
    fun revision_rises_only_on_real_change() = runBlocking {
        val r = registry()
        val k = key()
        val inbox = Inbox()
        val start = transcriptStart(memoId = k.memoId, attemptId = k.attemptId)
        r.start(k, start, inbox)
        assertEquals(1, r.peek(k)!!.revision)
        val (_, gen, _) = spawned.single()
        // a no-op edit is not a change
        assertTrue(r.update(k, gen) { stage = VoiceMemoStage.SUMMARIZING }!!.isEmpty())
        assertEquals(1, r.peek(k)!!.revision)
        // reads never bump
        repeat(3) { r.get(k, inbox) }
        assertEquals(1, r.peek(k)!!.revision)
        val pushed = r.update(k, gen) { stage = VoiceMemoStage.READY; result = FakeSummarizer.RESULT }!!
        assertEquals(2, pushed.single().state.revision)
        assertEquals(clock.wall + VoiceMemoLimits.RESULT_TTL_MS, pushed.single().state.expiresAtMs)
    }

    @Test
    fun stale_generation_and_terminal_jobs_reject_updates() = runBlocking {
        val r = registry()
        val k = key()
        r.start(k, transcriptStart(memoId = k.memoId, attemptId = k.attemptId), Inbox())
        val (_, gen, handle) = spawned.single()
        r.cancel(k, Inbox())
        assertTrue(handle.isCancelled)
        assertNull(r.update(k, gen) { stage = VoiceMemoStage.READY })
        assertEquals(VoiceMemoStage.CANCELLED, r.peek(k)!!.stage)
    }

    @Test
    fun cancel_markers_are_bounded() = runBlocking {
        val r = registry(MemoRegistryLimits(maxCachedJobs = 2))
        val keys = List(3) { key() }
        keys.forEach { r.cancel(it, Inbox()) }
        // the oldest marker fell out: its start runs normally; the newest is born cancelled
        val first = r.start(keys[0], transcriptStart(memoId = keys[0].memoId, attemptId = keys[0].attemptId), Inbox()).state()
        assertEquals(VoiceMemoStage.SUMMARIZING, first.stage)
        val last = r.start(keys[2], transcriptStart(memoId = keys[2].memoId, attemptId = keys[2].attemptId), Inbox()).state()
        assertEquals(VoiceMemoStage.CANCELLED, last.stage)
        assertEquals(1, last.revision)
    }

    @Test
    fun oldest_terminal_is_evicted_first_and_active_jobs_never() = runBlocking {
        val r = registry(MemoRegistryLimits(maxCachedJobs = 2, maxActivePerDevice = 5, maxActiveTotal = 5))
        val active = key()
        r.start(active, MemoUpload.random().let { it.start().copy(memoId = active.memoId, attemptId = active.attemptId) }, Inbox())
        val done = List(3) { key() }
        for (k in done) {
            r.start(k, transcriptStart(memoId = k.memoId, attemptId = k.attemptId), Inbox())
            val gen = spawned.last().second
            clock.advance(1)
            r.update(k, gen) { stage = VoiceMemoStage.FAILED; errorCode = VoiceMemoError.SUMMARY_FAILED }
        }
        assertNull(r.peek(done[0]))
        assertNotNull(r.peek(done[1]))
        assertNotNull(r.peek(done[2]))
        assertEquals(VoiceMemoStage.RECEIVING, r.peek(active)!!.stage)
        assertEquals(2, r.cachedTerminalJobs())
    }

    @Test
    fun ttl_expires_results_and_markers() = runBlocking {
        val r = registry()
        val k = key()
        r.start(k, transcriptStart(memoId = k.memoId, attemptId = k.attemptId), Inbox())
        r.update(k, spawned.last().second) { stage = VoiceMemoStage.READY; result = FakeSummarizer.RESULT }
        val marked = key()
        r.cancel(marked, Inbox())
        clock.advance(VoiceMemoLimits.RESULT_TTL_MS - 1)
        assertNotNull(r.peek(k))
        clock.advance(1)
        assertEquals(VoiceMemoStage.UNKNOWN, r.get(k, Inbox()).state().stage)
        val restarted = r.start(marked, transcriptStart(memoId = marked.memoId, attemptId = marked.attemptId), Inbox()).state()
        assertEquals(VoiceMemoStage.SUMMARIZING, restarted.stage)
    }

    @Test
    fun idle_upload_times_out_and_releases_audio() = runBlocking {
        val r = registry()
        val k = key()
        val up = MemoUpload.random()
        val inbox = Inbox()
        r.start(k, up.start().copy(memoId = k.memoId, attemptId = k.attemptId), inbox)
        r.audio(k, 0, up.chunk(0).base64, inbox)
        clock.advance(VoiceMemoLimits.UPLOAD_IDLE_MS - 1)
        assertTrue(r.sweep().isEmpty())
        clock.advance(1)
        val pushed = r.sweep().single().state
        assertEquals(VoiceMemoStage.FAILED, pushed.stage)
        assertEquals(VoiceMemoError.UPLOAD_TIMEOUT, pushed.errorCode)
        assertTrue(pushed.retryable)
        assertEquals(0, r.activeJobs())
        // the rest of the file arriving late never restarts it
        assertEquals(VoiceMemoStage.FAILED, r.audio(k, 1, up.chunk(1).base64, inbox).state().stage)
        assertTrue(spawned.isEmpty())
    }
}
