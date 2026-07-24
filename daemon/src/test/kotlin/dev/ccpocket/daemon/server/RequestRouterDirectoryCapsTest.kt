package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #184 mechanism ②, wired end-to-end through [RequestRouter.handle]: the SAME `supportsOpencode`
 * caps bit that strips opencode sessions from the session list (emitSessions) must also gate project rows
 * ONLY opencode history sustains — otherwise an undeclared client sees a directory row whose session list
 * comes back empty, i.e. a dead row offering nothing but the "New session" CTA.
 */
class RequestRouterDirectoryCapsTest {

    private val projects = Files.createTempDirectory("ccp-projects") // empty → no claude rows in the way
    private val ocDir = Files.createTempDirectory("ccp-ocdir")

    @AfterTest
    fun cleanup() {
        projects.toFile().deleteRecursively()
        ocDir.toFile().deleteRecursively()
    }

    private fun router(scope: CoroutineScope): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-router").toFile()
        return RequestRouter(
            registry = SessionRegistry(scope, backends = emptyMap()),
            dirs = DirectoryService(
                projectsRoot = { projects },
                codexCwds = { emptyMap() },
                opencodeCwds = { mapOf(ocDir.toString() to 42L) }, // one opencode-ONLY project
            ),
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

    private fun listWith(caps: RequestRouter.ClientCapsHolder?): List<String> = runBlocking {
        val emitted = mutableListOf<Frame>()
        router(CoroutineScope(Dispatchers.Default)).handle(ListDirectories(null), { emitted += it }, caps = caps)
        (emitted.single() as Directories).entries.map { it.path }
    }

    @Test
    fun opencode_only_rows_reach_only_clients_that_declared_support() {
        assertEquals(emptyList(), listWith(null), "legacy ingress (null caps) must filter like an undeclared client")
        assertEquals(emptyList(), listWith(RequestRouter.ClientCapsHolder()), "an undeclared client gets no opencode-only row")
        assertEquals(
            listOf(ocDir.toString()),
            listWith(RequestRouter.ClientCapsHolder().apply { supportsOpencode = true }),
            "a declared client keeps the row",
        )
    }
}
