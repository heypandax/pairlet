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
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
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
    private class ListingBackend(val listed: MutableList<String>) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> {
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

    private fun router(scope: CoroutineScope, listed: MutableList<String>): RequestRouter {
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ListingBackend(listed) }))
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
}
