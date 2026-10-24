package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.*
import dev.ccpocket.daemon.execution.ExecutionCaps
import dev.ccpocket.daemon.presets.*
import dev.ccpocket.daemon.schedule.*
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.daemon.update.DaemonUpdateController
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class RequestRouterDaemonUpdateTest {
    @Test fun restricted_credentials_cannot_request_or_receive_daemon_updates() {
        val request = DaemonUpdateStart("attempt")
        val response = DaemonUpdateStatus("attempt", "checking")
        assertFalse(BridgeCaps.ingressAllowed(request)); assertFalse(BridgeCaps.egressAllowed(response))
        assertFalse(ExecutionCaps.ingressAllowed(request)); assertFalse(ExecutionCaps.egressAllowed(response))
    }

    @Test fun router_calls_updater_only_for_owner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = Files.createTempDirectory("daemon-update-router").toFile()
        try {
            var calls = 0
            val router = RequestRouter(
                registry = SessionRegistry(scope, backends = emptyMap()), dirs = DirectoryService(),
                transcribe = TranscribeService(scope) { null }, inbox = FileInboxService { null },
                shell = ShellService(scope), exports = FileExportService(scope, { null }), scope = scope,
                auth = AuthService(scope, { emptyList() }, { 0 }), prefs = DaemonPrefs.load(root.resolve("prefs.json")),
                presets = PresetService(PresetStore.load(root.resolve("presets.json")), { emptyList() }, { 0 }),
                scheduler = SchedulerService(ScheduleStore.load(root.resolve("schedules.json")), executor = { null }),
                daemonUpdater = DaemonUpdateController(mode = { calls++; "manual" }, isBusy = { false }),
            )
            val replies = mutableListOf<Frame>()
            for (origin in listOf("feishu:chat", "guest:device", "execution:device")) {
                router.handle(DaemonUpdateStart("denied"), { replies += it }, origin = origin)
            }
            assertEquals(0, calls); assertTrue(replies.isEmpty())
            router.handle(DaemonUpdateStart("owner"), { replies += it })
            assertEquals(1, calls)
            assertEquals(DaemonUpdateStatus("owner", "manual"), replies.single())
            replies.clear()
            val updateState = dev.ccpocket.daemon.update.UpdateState
            assertTrue(updateState.tryBeginApply())
            try {
                updateState.blockControls()
                router.handle(SendPrompt("chat", "must not execute", promptId = "prompt"), { replies += it })
                val refusal = assertIs<PocketError>(replies.single())
                assertEquals("daemon_updating", refusal.code)
                assertEquals("prompt", refusal.promptId)
                replies.clear()
                router.handle(DaemonUpdateGet("query"), { replies += it })
                assertTrue(assertIs<DaemonUpdateStatus>(replies.single()).controlsBlocked)
            } finally { updateState.finishApply() }

        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
