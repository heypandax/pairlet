package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.ObserveSession
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #107: a reconnecting client re-opens its observe view with a FRESH sink instance (the relay
 * mints one per inbound frame) under the SAME device key. The registry must reap that client's
 * previous observer of the same transcript — otherwise both keep tailing, and the phone ping-pongs
 * between two SessionLive/ConvoHistory streams. A DIFFERENT client's observer must survive.
 */
class SessionRegistryObserveReapTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sid = "obs-${UUID.randomUUID()}"
    private val workdir = "/tmp/cc-pocket-obs-${UUID.randomUUID()}"
    private val projectDir = ProjectPaths.projectsRoot().resolve(ProjectPaths.dirKey(workdir))

    private fun sink(key: String): OutboundSink = KeyedSink(key, OutboundSink { /* drop */ })

    @AfterTest
    fun tearDown() {
        scope.cancel()
        projectDir.toFile().deleteRecursively() // the test's synthetic ~/.claude/projects entry
    }

    @Test
    fun reopen_same_client_reaps_the_stale_observer_but_not_other_clients() = runBlocking {
        val registry = SessionRegistry(scope, backends = emptyMap(), processProbe = { _, _ -> LiveProcesses.ExternalClaude.PRESENT })
        // a fresh transcript written AFTER the registry booted → externallyActive gate passes (probe stubbed PRESENT)
        Files.createDirectories(projectDir)
        val transcript = Files.writeString(projectDir.resolve("$sid.jsonl"), "{}")
        // pin mtime explicitly: Linux inode timestamps come from the kernel's coarse clock, which can lag
        // System.currentTimeMillis() by a tick — the fresh write then looks older than the registry boot
        // and the restart-amnesia gate rejects it (flaked on CI; an explicit utimes bypasses the coarse clock)
        Files.setLastModifiedTime(transcript, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        val open = OpenSession(workdir, resumeId = sid)

        val phoneA1 = registry.open(open, sink("dev:phone"))
        val desktop = registry.open(open, sink("dev:desktop"))
        assertTrue(registry.observing(phoneA1) && registry.observing(desktop))

        // the phone reconnects: same key, fresh sink instance
        val phoneA2 = registry.open(open, sink("dev:phone"))

        assertNotEquals(phoneA1, phoneA2)
        assertFalse(registry.observing(phoneA1), "the same client's stale observer must be reaped")
        assertTrue(registry.observing(phoneA2), "the re-opened observer is live")
        assertTrue(registry.observing(desktop), "another client's observer must survive")
    }
}

/** The identity ObserveSession reaping rests on: key equality for relay sinks, instance equality on LAN. */
class ObserveSessionAttachIdentityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun observe(sink: OutboundSink) = ObserveSession(
        convoId = "c1", workdir = "/tmp/x", sessionId = "s1",
        file = java.nio.file.Path.of("/tmp/x/absent.jsonl"), sink = sink, parentScope = scope,
    ) // never start()ed — identity only

    @Test
    fun keyed_sinks_match_by_key_across_instances() {
        val obs = observe(KeyedSink("dev:phone", OutboundSink { }))
        assertTrue(obs.isAttachedTo(KeyedSink("dev:phone", OutboundSink { })), "fresh instance, same key")
        assertFalse(obs.isAttachedTo(KeyedSink("dev:other", OutboundSink { })))
    }

    @Test
    fun plain_sinks_keep_instance_identity() {
        val lan = OutboundSink { }
        val obs = observe(lan)
        assertTrue(obs.isAttachedTo(lan))
        assertFalse(obs.isAttachedTo(OutboundSink { }))
    }

    @Test
    fun session_id_is_readable_for_the_reap_filter() {
        assertEquals("s1", observe(OutboundSink { }).sessionId)
    }
}
