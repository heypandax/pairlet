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
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.disk.TranscriptScanner
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RenameSession
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * issue #158 end-to-end at the router: a RenameSession against an idle transcript answers with the
 * re-pushed Sessions carrying the NEW title (the group ops' refresh contract) + the renameSupported
 * capability stamp; a failed rename answers a PocketError instead.
 */
class RequestRouterRenameSessionTest {

    private val workdir = "/tmp/ccp-rename-router"

    /** Lists via the real scanner against the TEST projects root (never the user's ~/.claude). */
    private class ScanningBackend(val root: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> =
            TranscriptScanner.scan(ProjectPaths.dirForUnder(root, workdir)).map { it.copy(agent = AgentKind.CLAUDE) }

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

    private fun router(scope: CoroutineScope, root: Path): RequestRouter {
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScanningBackend(root) }),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT },
            projectsRoot = root,
        )
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

    private fun seedTranscript(root: Path, sessionId: String) {
        val dir = Files.createDirectories(root.resolve(ProjectPaths.dirKey(workdir)))
        dir.resolve("$sessionId.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"first prompt"},"cwd":"$workdir"}""" + "\n")
    }

    /** The rename branch runs off-pump (scope.launch) — await its single reply. */
    private suspend fun awaitReply(emitted: MutableList<Frame>): Frame =
        withTimeout(10_000) {
            while (synchronized(emitted) { emitted.isEmpty() }) delay(10)
            synchronized(emitted) { emitted.single() }
        }

    @Test
    fun rename_answers_with_the_repushed_sessions_carrying_the_new_title() = runBlocking {
        val root = Files.createTempDirectory("ccp-rename-root")
        seedTranscript(root, "sid-1")
        val emitted = mutableListOf<Frame>()

        router(CoroutineScope(Dispatchers.Default), root)
            .handle(RenameSession(workdir, "sid-1", "Named from the sidebar"), { synchronized(emitted) { emitted += it } })

        val reply = awaitReply(emitted)
        val sessions = reply as Sessions
        assertEquals(workdir, sessions.workdir, "the echo keeps the client's request key")
        assertEquals("Named from the sidebar", sessions.items.single().title)
        assertTrue(sessions.renameSupported, "an owner connection must carry the #158 capability stamp")
    }

    @Test
    fun rename_of_an_unknown_session_answers_a_pocket_error() = runBlocking {
        val root = Files.createTempDirectory("ccp-rename-root")
        val emitted = mutableListOf<Frame>()

        router(CoroutineScope(Dispatchers.Default), root)
            .handle(RenameSession(workdir, "sid-none", "x"), { synchronized(emitted) { emitted += it } })

        val reply = awaitReply(emitted)
        val err = reply as PocketError
        assertEquals("rename_failed", err.code)
        assertTrue("no transcript" in err.message, err.message)
    }
}
