package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.execution.ExecutionCaps
import dev.ccpocket.daemon.memo.FakeSummarizer
import dev.ccpocket.daemon.memo.FakeTranscriber
import dev.ccpocket.daemon.memo.MemoServiceLimits
import dev.ccpocket.daemon.memo.MemoUpload
import dev.ccpocket.daemon.memo.VoiceMemoService
import dev.ccpocket.daemon.memo.transcriptStart
import dev.ccpocket.daemon.memo.withVoiceMemo
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.VoiceMemoAudio
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoIds
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The router's voice-memo door: a recording and its transcript are served to the OWNER's authenticated device on a
 * connection that declared it can read them — and to nobody else, in silence. The job is filed under the transport's
 * device id, snapshots ride back on the asking connection only, and every restricted credential class refuses the
 * frames before they get here.
 */
class RequestRouterVoiceMemoTest {

    private val tmp = Files.createTempDirectory("ccp-router-memo").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transcriber = FakeTranscriber()
    private val summarizer = FakeSummarizer()
    private val service = VoiceMemoService(scope, transcriber, summarizer, limits = MemoServiceLimits(sweepIntervalMs = 0))

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun router(memo: VoiceMemoService? = service) = RequestRouter(
        registry = SessionRegistry(scope, backends = emptyMap()),
        dirs = DirectoryService(),
        transcribe = TranscribeService(scope) { null },
        inbox = FileInboxService { null },
        shell = ShellService(scope),
        exports = FileExportService(scope, { null }),
        scope = scope,
        auth = AuthService(scope, { emptyList() }, { 0 }),
        prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
        presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
        scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
            dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
            executor = { null },
        ),
        archiveFile = tmp.resolve("session-archive.json"),
        voiceMemo = memo,
    )

    /** One connection: its sink drops what its own declared caps do not allow, like both transports do. */
    private class Conn(val caps: RequestRouter.ClientCapsHolder? = RequestRouter.ClientCapsHolder()) : OutboundSink {
        val frames = mutableListOf<Frame>()
        override suspend fun emit(frame: Frame) {
            if (!RequestRouter.allowedForCaps(frame, caps)) return
            synchronized(frames) { frames += frame }
        }
        fun states() = synchronized(frames) { frames.filterIsInstance<VoiceMemoState>() }
        suspend fun awaitStage(stage: String): VoiceMemoState = withTimeout(5_000) {
            while (true) {
                states().firstOrNull { it.stage == stage }?.let { return@withTimeout it }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private suspend fun declared(router: RequestRouter, deviceId: String? = "dev-a"): Conn {
        val conn = Conn()
        router.handle(ClientCaps(supportsVoiceMemo = true), conn, caps = conn.caps, deviceId = deviceId)
        return conn
    }

    private suspend fun RequestRouter.upload(upload: MemoUpload, conn: Conn, deviceId: String? = "dev-a") {
        handle(upload.start(), conn, caps = conn.caps, deviceId = deviceId)
        upload.chunks().forEach { handle(it, conn, caps = conn.caps, deviceId = deviceId) }
    }

    @Test
    fun an_owner_upload_is_transcribed_organised_and_answered_on_the_asking_connection() = runBlocking {
        val router = router()
        val conn = declared(router)
        val other = declared(router, deviceId = "dev-b")
        val upload = MemoUpload.random()
        router.upload(upload, conn)

        val ready = conn.awaitStage(VoiceMemoStage.READY)
        assertEquals(upload.memoId, ready.memoId)
        assertEquals(FakeTranscriber.TRANSCRIPT, ready.transcript)
        assertEquals(FakeSummarizer.RESULT, ready.result)
        assertTrue(conn.states().any { it.stage == VoiceMemoStage.SUMMARIZING && it.transcript != null }, "the transcript is pushed as soon as it exists")
        assertTrue(other.states().isEmpty(), "a sibling device sees nothing of it")
    }

    @Test
    fun another_device_cannot_read_a_job_by_knowing_its_ids() = runBlocking {
        val router = router()
        val owner = declared(router)
        val upload = MemoUpload.random()
        router.upload(upload, owner)
        owner.awaitStage(VoiceMemoStage.READY)

        val thief = declared(router, deviceId = "dev-b")
        router.handle(VoiceMemoGet(upload.memoId, upload.attemptId), thief, caps = thief.caps, deviceId = "dev-b")
        val answer = thief.states().single()
        assertEquals(VoiceMemoStage.UNKNOWN, answer.stage, "the key is device + ids — the ids alone open nothing")
        assertEquals(null, answer.transcript)
        assertEquals(null, answer.result)
    }

    @Test
    fun a_restricted_credential_is_dropped_in_silence() = runBlocking {
        val router = router()
        val upload = MemoUpload.random()

        val viaBridge = declared(router)
        router.handle(upload.start(), viaBridge, origin = "feishu-bot", caps = viaBridge.caps, deviceId = "dev-a")

        for (conn in listOf(viaBridge)) assertTrue(conn.frames.isEmpty(), "no job, no refusal frame")
        assertEquals(0, transcriber.calls.get())
    }

    @Test
    fun a_connection_without_an_authenticated_device_is_dropped_in_silence() = runBlocking {
        val router = router()
        val upload = MemoUpload.random()
        for (device in listOf(null, "", RequestRouter.LOCAL_DEVICE_ID)) {
            val conn = Conn()
            router.handle(ClientCaps(supportsVoiceMemo = true), conn, caps = conn.caps, deviceId = device)
            router.upload(upload, conn, deviceId = device)
            router.handle(VoiceMemoGet(upload.memoId, upload.attemptId), conn, caps = conn.caps, deviceId = device)
            assertTrue(conn.frames.isEmpty(), "device=$device")
        }
        assertEquals(0, transcriber.calls.get(), "a recording is never filed under the plaintext local identity")
    }

    @Test
    fun an_undeclared_connection_gets_no_memo_frame_not_even_a_refusal() = runBlocking {
        val router = router()
        val upload = MemoUpload.random()
        val undeclared = Conn()
        router.upload(upload, undeclared)
        val legacy = Conn(caps = null)
        router.upload(upload, legacy)
        assertTrue(undeclared.frames.isEmpty())
        assertTrue(legacy.frames.isEmpty())
        assertEquals(0, transcriber.calls.get())

        // …and the egress gate alone refuses the frame type for such a connection
        val state = VoiceMemoState(upload.memoId, upload.attemptId, 1, VoiceMemoStage.QUEUED)
        assertFalse(RequestRouter.allowedForCaps(state, RequestRouter.ClientCapsHolder()))
        assertFalse(RequestRouter.allowedForCaps(state, null))
    }

    @Test
    fun a_router_without_the_service_advertises_nothing_and_serves_nothing() = runBlocking {
        val bare = router(memo = null)
        val cap = bare.voiceMemoCapability()
        assertEquals(0, cap.version)
        assertTrue(cap.agents.isEmpty())
        assertEquals(VoiceMemoStatus.UNKNOWN, cap.status)
        val conn = declared(bare)
        bare.upload(MemoUpload.random(), conn)
        assertTrue(conn.frames.isEmpty())
    }

    @Test
    fun the_announcement_reports_local_prerequisites() = runBlocking {
        val router = router()
        val ready = DaemonInfo(hostname = "mac").withVoiceMemo(router.voiceMemoCapability())
        assertEquals(VoiceMemoLimits.VERSION, ready.voiceMemoVersion)
        assertEquals(listOf("claude"), ready.voiceMemoAgents)
        assertEquals(VoiceMemoStatus.READY, ready.voiceMemoStatus)

        transcriber.status = VoiceMemoStatus.MODEL_MISSING
        assertEquals(VoiceMemoStatus.MODEL_MISSING, router.voiceMemoCapability().status)
        transcriber.status = VoiceMemoStatus.READY
        summarizer.available = false
        val noAgent = router.voiceMemoCapability()
        assertEquals(VoiceMemoStatus.READY, noAgent.status, "the status describes transcription only: no organiser is not a fault")
        assertTrue(noAgent.agents.isEmpty(), "an adapter that cannot launch is not advertised")
    }

    @Test
    fun a_start_is_refused_while_the_prerequisites_are_missing_but_a_query_still_answers() = runBlocking {
        val router = router()
        val conn = declared(router)
        val done = MemoUpload.random()
        router.upload(done, conn)
        conn.awaitStage(VoiceMemoStage.READY)

        transcriber.status = VoiceMemoStatus.WHISPER_MISSING
        val refused = MemoUpload.random(seed = 9)
        router.handle(refused.start(), conn, caps = conn.caps, deviceId = "dev-a")
        val refusal = conn.states().last()
        assertEquals(refused.attemptId, refusal.attemptId)
        assertEquals(VoiceMemoStage.FAILED, refusal.stage)
        assertEquals(0, refusal.revision, "a refusal answers the request; it is not a job")

        val before = conn.states().size
        router.handle(VoiceMemoGet(done.memoId, done.attemptId), conn, caps = conn.caps, deviceId = "dev-a")
        assertEquals(VoiceMemoStage.READY, conn.states().drop(before).single().stage)
    }

    @Test
    fun a_transcript_start_skips_transcription() = runBlocking {
        val router = router()
        val conn = declared(router)
        val start = transcriptStart()
        router.handle(start, conn, caps = conn.caps, deviceId = "dev-a")
        conn.awaitStage(VoiceMemoStage.READY)
        assertEquals(0, transcriber.calls.get())
        assertEquals(1, summarizer.calls.get())
    }

    @Test
    fun a_revoked_device_loses_its_cached_result() = runBlocking {
        val router = router()
        val conn = declared(router)
        val upload = MemoUpload.random()
        router.upload(upload, conn)
        conn.awaitStage(VoiceMemoStage.READY)

        router.revokeVoiceMemoDevice("dev-a")
        val before = conn.states().size
        router.handle(VoiceMemoGet(upload.memoId, upload.attemptId), conn, caps = conn.caps, deviceId = "dev-a")
        val after = conn.states().drop(before).single()
        assertEquals(VoiceMemoStage.UNKNOWN, after.stage)
        assertEquals(null, after.transcript)
    }

    @Test
    fun every_restricted_credential_class_refuses_all_five_frame_types() {
        val memoId = VoiceMemoIds.newId()
        val attemptId = VoiceMemoIds.newId()
        val requests: List<Frame> = listOf(
            MemoUpload.random().start(),
            VoiceMemoAudio(memoId, attemptId, 0, "AAAA"),
            VoiceMemoGet(memoId, attemptId),
            VoiceMemoCancel(memoId, attemptId),
        )
        val state = VoiceMemoState(memoId, attemptId, 1, VoiceMemoStage.READY)
        for (request in requests) {
            assertFalse(BridgeCaps.ingressAllowed(request), "bridge ${request::class.simpleName}")
            assertFalse(ExecutionCaps.ingressAllowed(request), "execution ${request::class.simpleName}")
        }
        assertFalse(BridgeCaps.egressAllowed(state))
        assertFalse(ExecutionCaps.egressAllowed(state))
    }
}
