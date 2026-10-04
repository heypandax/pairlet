package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ListSessions workdir must go through the same tilde/realpath resolution as OpenSession before it
 * reaches the backends' transcript scans. The desktop new-session popover ships `~` paths raw (only the
 * daemon knows this machine's home), and claude keys its transcript dirs by the REAL cwd — an unexpanded
 * `~/…` scanned a dir that doesn't exist and answered EMPTY, blanking the project's session list the
 * moment ⌘N confirmed (07-12 report). The Sessions ECHO keeps the client's raw string: list-keyed UI
 * state matches the request it made.
 */
class RequestRouterListSessionsTest {

    /** Records the workdir each listSessions call receives; every live-process member is unreachable here. */
    private class ListingBackend(val listed: MutableList<String>, val onList: (String) -> Unit = {}) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> {
            onList(workdir) // a test's stand-in for a slow transcript scan
            listed += workdir
            return emptyList()
        }

        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun router(scope: CoroutineScope, listed: MutableList<String>, onList: (String) -> Unit = {}): RequestRouter {
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ListingBackend(listed, onList) }))
        val tmp = Files.createTempDirectory("ccp-router").toFile()
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

    @Test
    fun tilde_workdir_is_expanded_for_the_scan_but_echoed_raw() = runBlocking {
        val listed = mutableListOf<String>()
        val emitted = mutableListOf<Frame>()
        router(CoroutineScope(Dispatchers.Default), listed).handle(ListSessions("~"), { emitted += it })

        val home = Path.of(System.getProperty("user.home")).toRealPath().toString()
        assertEquals(listOf(home), listed, "the backend scan must see the expanded real path")
        assertEquals("~", (emitted.single() as Sessions).workdir, "the echo keeps the client's raw request key")
    }

    @Test
    fun unresolvable_workdir_falls_back_to_the_raw_string() = runBlocking {
        val listed = mutableListOf<String>()
        val emitted = mutableListOf<Frame>()
        router(CoroutineScope(Dispatchers.Default), listed).handle(ListSessions("/no/such/dir-ccp"), { emitted += it })

        assertEquals(listOf("/no/such/dir-ccp"), listed, "an unresolvable path keeps the old raw-string behavior")
        assertEquals("/no/such/dir-ccp", (emitted.single() as Sessions).workdir)
    }

    // ── listing lanes: the relay ingress asks for session-list replies off its single reader ──────────

    private fun workdirs(frames: List<Frame>) = frames.map { (it as Sessions).workdir }

    private suspend fun awaitSize(frames: List<Frame>, n: Int) = withTimeout(10_000) { while (frames.size < n) delay(10) }

    @Test
    fun a_lane_produces_replies_off_the_callers_loop_and_in_request_order() = runBlocking {
        val scan = CountDownLatch(1) // holds the "/slow" scan the way a cold transcript read would
        val listed = Collections.synchronizedList(mutableListOf<String>())
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        val scope = CoroutineScope(Dispatchers.Default)
        val router = router(scope, listed) { if (it == "/slow") scan.await(10, TimeUnit.SECONDS) }
        val sink = OutboundSink { emitted += it }
        try {
            router.handle(ListSessions("/slow"), sink, listingLane = "dev-1") // returns with the scan still held
            router.handle(ListSessions("/fast"), sink, listingLane = "dev-1")
            assertEquals(emptyList(), emitted.toList(), "neither reply is produced on the caller's loop")

            // …which is therefore free: a frame handled inline answers while the lane is still busy
            router.handle(ListSessions("/inline"), sink)
            assertEquals(listOf("/inline"), workdirs(emitted.toList()))

            scan.countDown()
            awaitSize(emitted, 3)
            assertEquals(
                listOf("/inline", "/slow", "/fast"), workdirs(emitted.toList()),
                "a later listing never overtakes the one asked for before it",
            )
        } finally {
            scan.countDown(); scope.cancel()
        }
    }

    @Test
    fun one_lanes_slow_listing_does_not_hold_another_lanes() = runBlocking {
        val scan = CountDownLatch(1)
        val listed = Collections.synchronizedList(mutableListOf<String>())
        val first = Collections.synchronizedList(mutableListOf<Frame>())
        val second = Collections.synchronizedList(mutableListOf<Frame>())
        val scope = CoroutineScope(Dispatchers.Default)
        val router = router(scope, listed) { if (it == "/slow") scan.await(10, TimeUnit.SECONDS) }
        try {
            router.handle(ListSessions("/slow"), { first += it }, listingLane = "dev-1")
            router.handle(ListSessions("/fast"), { second += it }, listingLane = "dev-2")
            awaitSize(second, 1)
            assertEquals(emptyList(), first.toList(), "the other device answered while this one was still scanning")

            scan.countDown()
            awaitSize(first, 1)
            assertEquals(listOf("/slow"), workdirs(first.toList()))
        } finally {
            scan.countDown(); scope.cancel()
        }
    }

    @Test
    fun a_reply_that_fails_on_its_lane_answers_with_an_error_and_the_lane_keeps_working() = runBlocking {
        val listed = Collections.synchronizedList(mutableListOf<String>())
        val emitted = Collections.synchronizedList(mutableListOf<Frame>())
        val failOnce = AtomicBoolean(true)
        val scope = CoroutineScope(Dispatchers.Default)
        val router = router(scope, listed)
        val sink = OutboundSink { frame ->
            if (frame is Sessions && failOnce.compareAndSet(true, false)) error("send failed")
            emitted += frame
        }
        try {
            router.handle(ListSessions("/a"), sink, listingLane = "dev-1")
            router.handle(ListSessions("/b"), sink, listingLane = "dev-1")
            awaitSize(emitted, 2)
            // inline, the transport's catch would have answered the failure; on a lane the router has to
            assertEquals("internal", (emitted[0] as PocketError).code)
            assertEquals("/b", (emitted[1] as Sessions).workdir, "the lane outlives a failed reply")
        } finally {
            scope.cancel()
        }
    }
}
