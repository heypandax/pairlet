package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.ApprovalTimeout
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ApprovalPrefs
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SetApprovalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #201 at the router: the "wait for my decision" preference follows the push toggle's contract —
 * a null field queries, a set field persists, and EITHER way the single reply is the daemon's own truth.
 * The extra beat this one has over pocket/push.prefs.* is the mirror into [ApprovalTimeout.noAutoDeny],
 * which is what individual asks actually read: without it a flip would persist but never take effect.
 */
class RequestRouterApprovalPrefsTest {

    // ApprovalTimeout.noAutoDeny is process-global — leaving it set would silently change how every
    // other daemon test's asks behave in this JVM.
    @AfterTest
    fun reset() { ApprovalTimeout.noAutoDeny = false }

    private fun router(scope: CoroutineScope, prefsFile: File): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-approval-prefs").toFile()
        return RequestRouter(
            registry = SessionRegistry(scope, backends = emptyMap<AgentKind, AgentBackendFactory>()),
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(prefsFile),
            presets = dev.ccpocket.daemon.presets.PresetService(
                dev.ccpocket.daemon.presets.PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 },
            ),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
        )
    }

    private fun prefsFile() = Files.createTempDirectory("ccp-approval-prefs").resolve("prefs.json").toFile()

    @Test
    fun setting_it_persists_replies_with_the_truth_and_arms_the_per_ask_read() = runBlocking {
        val file = prefsFile()
        val emitted = mutableListOf<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)

        router(scope, file).handle(SetApprovalPrefs(noAutoDeny = true), { emitted += it })

        assertTrue((emitted.single() as ApprovalPrefs).noAutoDeny, "the reply is the daemon's truth")
        assertTrue(ApprovalTimeout.noAutoDeny, "the mirror is what the NEXT ask reads — without it nothing changes")
        // survives a restart: a fresh load of the same file sees it
        assertTrue(DaemonPrefs.load(file).askNoAutoDeny)
    }

    @Test
    fun a_null_field_only_queries() = runBlocking {
        val file = prefsFile()
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, file)
        r.handle(SetApprovalPrefs(noAutoDeny = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(SetApprovalPrefs(), { emitted += it }) // query only

        assertTrue((emitted.single() as ApprovalPrefs).noAutoDeny, "a query reports, it does not reset")
    }

    @Test
    fun it_defaults_off_so_an_untouched_daemon_behaves_exactly_as_before() = runBlocking {
        val emitted = mutableListOf<Frame>()
        router(CoroutineScope(Dispatchers.Default), prefsFile()).handle(SetApprovalPrefs(), { emitted += it })

        assertFalse((emitted.single() as ApprovalPrefs).noAutoDeny)
        assertFalse(ApprovalTimeout.noAutoDeny)
    }

    @Test
    fun turning_it_back_off_persists_and_disarms() = runBlocking {
        val file = prefsFile()
        val scope = CoroutineScope(Dispatchers.Default)
        val r = router(scope, file)
        r.handle(SetApprovalPrefs(noAutoDeny = true), { })

        val emitted = mutableListOf<Frame>()
        r.handle(SetApprovalPrefs(noAutoDeny = false), { emitted += it })

        assertFalse((emitted.single() as ApprovalPrefs).noAutoDeny)
        assertFalse(ApprovalTimeout.noAutoDeny)
        assertEquals(false, DaemonPrefs.load(file).askNoAutoDeny)
    }
}
