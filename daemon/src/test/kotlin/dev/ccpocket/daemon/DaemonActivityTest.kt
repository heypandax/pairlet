package dev.ccpocket.daemon

import dev.ccpocket.daemon.execution.ExecutionGrantStore
import dev.ccpocket.daemon.execution.ExecutionRunState
import dev.ccpocket.daemon.execution.RunJournal
import dev.ccpocket.daemon.execution.RunService
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.memo.FakeMemoClock
import dev.ccpocket.daemon.memo.FakeSummarizer
import dev.ccpocket.daemon.memo.FakeTranscriber
import dev.ccpocket.daemon.memo.Inbox
import dev.ccpocket.daemon.memo.MemoOwner
import dev.ccpocket.daemon.memo.MemoServiceLimits
import dev.ccpocket.daemon.memo.MemoSummaryResult
import dev.ccpocket.daemon.memo.VoiceMemoService
import dev.ccpocket.daemon.memo.transcriptStart
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.schedule.ScheduleExecutor
import dev.ccpocket.daemon.schedule.ScheduleStore
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.Transcript
import dev.ccpocket.protocol.VoiceMemoStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auto-update idle gate ([DaemonActivity]) beyond the session registry: remote-execution runs, scheduled
 * fires, chat dictation and voice memos are work a daemon exit destroys too. Each case: the gate holds the
 * update while that one kind of work is in progress, and releases it once the work ends — with every other
 * source idle, so the case can only pass on its own probe.
 */
class DaemonActivityTest {

    private val dir = createTempDirectory("ccp-activity").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_800_000_000_000L

    private val registry = SessionRegistry(scope, emptyMap())
    private val scheduleStore = ScheduleStore.load(File(dir, "schedules.json"))
    private val scheduler = SchedulerService(scheduleStore, ScheduleExecutor { null }, clock = { clock })
    private var dictation: suspend (String, String) -> Transcript = { convoId, captureId -> Transcript(convoId, captureId, text = "hi") }
    private val transcribe = TranscribeService(scope, { convoId, c, _ -> dictation(convoId, c.captureId) }) { dir.toPath() }
    private val summarizer = FakeSummarizer()
    private val voiceMemo = VoiceMemoService(scope, FakeTranscriber(), summarizer, FakeMemoClock(), MemoServiceLimits(sweepIntervalMs = 0))

    private val identity = Identity.loadOrCreate(File(dir, "identity.json"))
    private val journal = RunJournal(File(dir, "execution-runs")) { clock }
    private val runPlane = RunService(
        ExecutionGrantStore.load(File(dir, "execution-grants.json"), identity.e2ePubB64), journal, registry, scope,
    ) { clock }

    @AfterTest
    fun cleanup() {
        runBlocking { runCatching { voiceMemo.close() }; runCatching { registry.closeAll() } }
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun busy(withPlane: Boolean = true) =
        DaemonActivity.busy(registry, if (withPlane) runPlane else null, scheduler, transcribe, voiceMemo)

    @Test
    fun an_idle_daemon_is_not_busy() = runBlocking {
        assertFalse(busy())
        assertFalse(busy(withPlane = false))
    }

    @Test
    fun a_queued_remote_execution_run_holds_the_update_until_it_ends() = runBlocking {
        // ACCEPTED = queued behind the grant's concurrency ceiling: no session exists for it yet, and its
        // prompt lives only in memory, so an exit now loses the run for good (recovery marks it interrupted)
        val grantId = "xg_TESTGRANT0001"
        val accepted = assertIs<RunJournal.Accept.Ok>(
            journal.accept(
                grantId = grantId, requestId = "rq_1", revision = 1, workspaceAlias = "app",
                agent = AgentKind.CLAUDE, mode = PermissionMode.DEFAULT, model = null,
                payloadHash = RunJournal.payloadHash(grantId, "rq_1", "app", AgentKind.CLAUDE, PermissionMode.DEFAULT, null, "go"),
                budgetCeiling = 10,
            ),
        )
        assertTrue(busy(), "a run still in the execution journal must hold the update")

        journal.transition(accepted.run.runId, ExecutionRunState.CANCELLED)
        assertFalse(busy(), "a finished run must release it")
    }

    @Test
    fun an_unloaded_execution_plane_counts_as_idle_and_is_not_loaded_by_the_gate() = runBlocking {
        val core = DaemonCore(
            emptyMap(),
            scheduleStore = ScheduleStore.load(File(dir, "core-schedules.json")),
            projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
            managedSessionRoot = File(dir, "managed"),
            executionRunRoot = File(dir, "core-execution-runs"),
        )
        try {
            var installs = 0
            core.offerExecution(installer = { installs++ }, evidence = { null })
            assertFalse(core.hasActiveWork())
            assertEquals(0, installs, "the idle gate must never load the execution planes")
            assertNull(core.executionPlane)
            assertFalse(File(dir, "core-execution-runs").exists(), "nor open the run journal")
        } finally {
            core.shutdown()
            core.scope.cancel()
        }
    }

    @Test
    fun a_schedule_about_to_fire_holds_the_update_until_it_has_fired() = runBlocking {
        val state = scheduler.create(
            ScheduleCreate(workdir = dir.path, prompt = "standup prep", runAtMs = clock + 10_000),
            canonicalWorkdir = dir.path,
        )
        assertNull(state.error)
        assertTrue(busy(), "a fire due within moments must hold the update")

        clock += 10_000
        assertEquals(1, scheduler.checkDue())
        assertFalse(busy(), "once fired (the one-shot settled) the gate must release")
    }

    @Test
    fun a_schedule_far_in_the_future_does_not_hold_the_update() = runBlocking {
        scheduler.create(
            ScheduleCreate(workdir = dir.path, prompt = "nightly", runAtMs = clock + 60 * 60_000L),
            canonicalWorkdir = dir.path,
        )
        assertFalse(busy())
    }

    @Test
    fun a_running_dictation_transcription_holds_the_update_until_it_is_answered() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Transcript>()
        dictation = { convoId, captureId -> release.await(); Transcript(convoId, captureId, text = "hi") }
        val audio = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        transcribe.onChunk(AudioChunk("c-1", "cap-1", 0, last = true, mediaType = "audio/wav", base64 = audio)) {
            answered.complete(it as Transcript)
        }
        assertTrue(busy(), "a capture being transcribed must hold the update")

        release.complete(Unit)
        withTimeout(5_000) { answered.await() }
        withTimeout(5_000) { while (busy()) delay(10) }
        assertFalse(busy())
    }

    @Test
    fun a_voice_memo_being_organised_holds_the_update_until_it_is_ready() = runBlocking {
        val release = CompletableDeferred<Unit>()
        summarizer.behavior = { _, _ -> release.await(); MemoSummaryResult.Ok(FakeSummarizer.RESULT) }
        val inbox = Inbox()
        voiceMemo.handle(MemoOwner("device-a"), transcriptStart(), inbox)
        assertEquals(VoiceMemoStage.SUMMARIZING, inbox.states.first().stage)
        assertTrue(busy(), "a memo job in flight must hold the update")

        release.complete(Unit)
        inbox.awaitStage(VoiceMemoStage.READY)
        withTimeout(5_000) { while (busy()) delay(10) }
        assertFalse(busy())
    }
}
