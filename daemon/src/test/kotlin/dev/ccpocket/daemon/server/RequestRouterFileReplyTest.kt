package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Audit 2026-10-04 C: the file surfaces run in `scope.launch`, whose exceptions only reach the scope's
 * handler — before this, a service that threw left the phone waiting for its own timeout with no frame.
 */
class RequestRouterFileReplyTest {

    private fun router(scope: CoroutineScope, exports: FileExportService): RequestRouter {
        val root = Files.createTempDirectory("ccp-file-reply-root")
        val registry = SessionRegistry(
            scope,
            backends = emptyMap(),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT },
            projectsRoot = root,
        )
        val tmp = Files.createTempDirectory("ccp-file-reply").toFile()
        return RequestRouter(
            registry = registry,
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = exports,
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
    fun an_export_whose_service_throws_still_answers_one_refusal() = runBlocking {
        // the production handler only logs — swallow here so the expected rethrow stays quiet
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val exports = FileExportService(scope, { null }, isChanged = { _, _, _, _ -> throw IllegalStateException("disk vanished") })
        val emitted = mutableListOf<Frame>()

        router(scope, exports).handle(ExportFile("c1", "/w", "s1", "a.txt"), { synchronized(emitted) { emitted += it } })

        val reply = withTimeoutOrNull(5_000) {
            while (synchronized(emitted) { emitted.isEmpty() }) delay(10)
            synchronized(emitted) { emitted.single() }
        }
        assertNotNull(reply, "no reply: the phone would wait for its own timeout")
        val content = reply as FileContent
        assertFalse(content.ok)
        assertEquals("a.txt", content.path)
        assertEquals("s1", content.sessionId)
    }
}
