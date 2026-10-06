package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ReplaySlice
import dev.ccpocket.daemon.media.ImagePreviews
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.FetchImage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageContent
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImageRefs
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lean history end to end on the daemon (SLOW-LINK-RESILIENCE §6): a connection is sent a preview, asks for
 * the picture behind it, and gets the full version — from memory, or read back off the transcript when memory
 * no longer has it. A request this daemon cannot honour is answered, never left hanging.
 */
class RequestRouterFetchImageTest {

    @BeforeTest fun reset() = ImagePreviews.clearForTest()

    private fun screenshot(seed: Int): ImageData {
        val img = BufferedImage(1024, 633, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 633) for (x in 0 until 1024) {
            val block = if ((x / 40 + y / 24 + seed) % 7 == 0) 0x202830 else 0
            img.setRGB(x, y, (((x * 255 / 1023) shl 16) or ((y * 255 / 632) shl 8) or (((x + y + seed) / 4) % 256)) xor block)
        }
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", bos)
        return ImageData("image/jpeg", Base64.getEncoder().encodeToString(bos.toByteArray()))
    }

    private val first = screenshot(1)
    private val second = screenshot(2)

    /** A transcript of three rows: text at line 4, then one reply at line 7 whose two tool calls each returned a picture. */
    private val transcript = listOf(
        HistoryMessage(ChatRole.USER, "look at these", seq = 4),
        HistoryMessage(ChatRole.TOOL, "a.png", tool = "Read", images = listOf(first), seq = 7),
        HistoryMessage(ChatRole.TOOL, "b.png", tool = "Read", images = listOf(second), seq = 7),
    )

    private inner class TranscriptBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun replaySlice(workdir: String, sessionId: String, sinceSeq: Long?) = ReplaySlice(transcript, firstSeq = 4, lastSeq = 9)
        override fun replayPage(workdir: String, sessionId: String, beforeSeq: Long, limit: Int): ReplaySlice {
            val older = transcript.filter { it.seq!! < beforeSeq }.takeLast(limit)
            return ReplaySlice(older, firstSeq = older.firstOrNull()?.seq)
        }
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = error("reading history must not start a process")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String) = emptyList<dev.ccpocket.daemon.agent.AgentEvent>()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun replayHistory(workdir: String, sessionId: String) = transcript
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun router(scope: CoroutineScope): RequestRouter {
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { TranscriptBackend() }),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT },
            projectsRoot = Files.createTempDirectory("ccp-fetch-image-projects"),
        )
        val tmp = Files.createTempDirectory("ccp-fetch-image").toFile()
        return RequestRouter(
            registry = registry,
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
        )
    }

    private suspend fun <T : Frame> await(emitted: List<Frame>, type: Class<T>, count: Int = 1): List<T> = withTimeout(5_000) {
        while (emitted.count { type.isInstance(it) } < count) delay(20)
        emitted.filter { type.isInstance(it) }.map { type.cast(it) }
    }

    /** What the sealer does toward a connection that declared previews: the frame as that client receives it. */
    private fun asSeenByALeanClient(frame: Frame): Frame = PocketJson.decodeFromString<Envelope>(
        FrameFitter.encodeWithin(Envelope("1", 0L, body = frame), WIRE_MAX_FRAME_BYTES, FrameFitter.Lean(imagePreviews = true)).decodeToString(),
    ).body

    @Test
    fun a_preview_is_exchanged_for_the_full_picture_from_memory_and_from_the_transcript() = runBlocking {
        val job = SupervisorJob()
        try {
            val router = router(CoroutineScope(Dispatchers.Default + job))
            val emitted = CopyOnWriteArrayList<Frame>()
            val sink = dev.ccpocket.daemon.conversation.OutboundSink { emitted += it }
            router.handle(OpenSession(Files.createTempDirectory("ccp-fetch-image-wd").toString(), resumeId = "sid"), sink)
            val convoId = await(emitted, SessionLive::class.java).first().convoId
            val history = asSeenByALeanClient(await(emitted, ConvoHistory::class.java).first()) as ConvoHistory
            val previews = history.messages.flatMap { it.images }
            assertEquals(2, previews.size)
            assertTrue(previews.all { it.ref != null && it.base64.length * 3 < first.base64.length })
            val secondRef = previews[1].ref!!
            assertEquals(ImageRefs.of(Base64.getDecoder().decode(second.base64)), secondRef)

            // 1) still in memory: answered without touching the transcript, and no cursor is needed
            router.handle(FetchImage(convoId, secondRef, requestId = "r1"), sink)
            val fromMemory = await(emitted, ImageContent::class.java).single()
            assertEquals(second, fromMemory.image)
            assertNull(fromMemory.image!!.ref) // the full picture is not a preview
            assertEquals("r1", fromMemory.requestId)
            assertNull(fromMemory.error)

            // 2) the daemon restarted (memory is empty): read back at the row's cursor, matched by content —
            //    the two rows share line 7, and position alone could not tell their pictures apart
            ImagePreviews.clearForTest()
            router.handle(FetchImage(convoId, secondRef, seq = 7, index = 0, requestId = "r2"), sink)
            val fromTranscript = await(emitted, ImageContent::class.java, count = 2).last()
            assertEquals(second, fromTranscript.image)
            assertEquals("r2", fromTranscript.requestId)
            // …and the read-back refilled memory for the sibling picture too
            assertNotNull(ImagePreviews.full(convoId, previews[0].ref!!))

            // 3) nothing in memory and no cursor to read from: an answer, not silence
            ImagePreviews.clearForTest()
            router.handle(FetchImage(convoId, secondRef, requestId = "r3"), sink)
            val gone = await(emitted, ImageContent::class.java, count = 3).last()
            assertNull(gone.image)
            assertEquals(ImageContent.ERROR_UNAVAILABLE, gone.error)
            assertEquals("r3", gone.requestId)
        } finally { job.cancel() }
    }

    @Test
    fun a_request_this_daemon_cannot_honour_is_refused_with_a_code() = runBlocking {
        val job = SupervisorJob()
        try {
            val router = router(CoroutineScope(Dispatchers.Default + job))
            val emitted = CopyOnWriteArrayList<Frame>()
            val sink = dev.ccpocket.daemon.conversation.OutboundSink { emitted += it }
            val ref = ImageRefs.of(byteArrayOf(1, 2, 3))
            // a picture remembered for SOME conversation is not served to a request naming one that is not open
            val shaped = ImagePreviews.shape("elsewhere", listOf(first), ImagePreviews.TOOL_EDGE).single()
            router.handle(FetchImage("not-an-open-conversation", shaped.ref!!), sink)
            router.handle(FetchImage("not-an-open-conversation", "../../../etc/passwd"), sink)
            router.handle(FetchImage("not-an-open-conversation", ref, seq = 1), sink)
            val replies = await(emitted, ImageContent::class.java, count = 3)
            assertTrue(replies.all { it.image == null })
            assertEquals(
                setOf(ImageContent.ERROR_NO_CONVERSATION, ImageContent.ERROR_BAD_REF),
                replies.map { it.error }.toSet(),
            )
            assertEquals(1, replies.count { it.error == ImageContent.ERROR_BAD_REF })
        } finally { job.cancel() }
    }
}
