package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.dsh.DshConfigOptions
import dev.ccpocket.daemon.dsh.DshModelService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.FetchModels
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #333 removed the #255 scope-out that answered every DSH `FetchModels` with a hard-coded
 * "not supported yet". The dispatch is asserted here rather than only inside [DshModelService] because
 * the failure mode of a MISSING branch is the worst kind: the `when` still compiles (AgentKind is
 * exhaustive only if every arm is present), and a stale arm looks exactly like a working one from the
 * phone's side — a picker that opens, answers instantly, and offers nothing.
 */
class RequestRouterFetchModelsDshTest {

    private fun router(scope: CoroutineScope, dshModels: DshModelService): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-router-dsh").toFile()
        return RequestRouter(
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
            dshModels = dshModels,
        )
    }

    private fun service(configOptions: String?) = DshModelService(
        liveOptions = {
            configOptions?.let { DshConfigOptions.parse(Json.parseToJsonElement(it) as JsonArray) }
        },
        transientRead = { null },
    )

    /** FetchModels is answered off-pump (`scope.launch(Dispatchers.IO)`) — await the single reply. */
    private suspend fun awaitReply(emitted: MutableList<Frame>): Frame = withTimeout(20_000) {
        while (synchronized(emitted) { emitted.isEmpty() }) delay(10)
        synchronized(emitted) { emitted.single() }
    }

    @Test
    fun a_dsh_fetch_is_answered_by_the_dsh_model_service_with_real_rows() = runBlocking {
        val emitted = mutableListOf<Frame>()
        val svc = service(
            """[{"id":"model","currentValue":"[\"deepseek-official\",\"deepseek-v4-pro\"]",
                 "options":[{"group":"deepseek-official","options":[
                   {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"Pro"}]}]},
                {"id":"reasoning_effort","currentValue":"high","options":[
                   {"value":"off"},{"value":"high"},{"value":"max"}]}]""",
        )
        router(CoroutineScope(Dispatchers.Default), svc)
            .handle(FetchModels(AgentKind.DSH), { synchronized(emitted) { emitted += it } })

        val list = awaitReply(emitted) as ModelsList
        assertEquals(AgentKind.DSH, list.agent)
        assertNull(list.error, "the #255 placeholder text must be gone")
        assertEquals(listOf("deepseek-v4-pro"), list.models)
        assertEquals(listOf("off", "high", "max"), list.supportedEfforts)
        assertEquals(listOf("off", "high", "max"), list.modelCapabilities.single().reasoningEfforts)
    }

    /** A dsh that cannot be reached still answers — silence is what hangs the picker. */
    @Test
    fun an_unreachable_dsh_still_answers_the_frame_with_a_reason() = runBlocking {
        val emitted = mutableListOf<Frame>()
        router(CoroutineScope(Dispatchers.Default), service(null))
            .handle(FetchModels(AgentKind.DSH), { synchronized(emitted) { emitted += it } })

        val list = awaitReply(emitted) as ModelsList
        assertEquals(AgentKind.DSH, list.agent)
        assertTrue(list.error != null, "an empty list with no reason is a silently blank picker")
        assertTrue(list.models.isEmpty())
    }
}
