package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.ObserveSession
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Codex gets the same external-session UX as Claude: read-only tail first, no second writer on tap. */
class CodexObserveParityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("ccp-codex-observe")
    private val rollout = dir.resolve("rollout-test.jsonl")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun external_codex_opens_as_a_codex_read_only_observer_and_replays_rollout_history() = runBlocking {
        val registry = SessionRegistry(
            scope,
            backends = emptyMap(),
            codexProcessProbe = { _, _ -> LiveProcesses.ExternalClaude.PRESENT },
            transcriptResolver = { agent, _, _ -> if (agent == AgentKind.CODEX) rollout else null },
        )
        Files.writeString(
            rollout,
            """{"type":"session_meta","payload":{"id":"codex-live","cwd":"/repo"}}""" + "\n" +
                """{"type":"turn_context","payload":{"turn_id":"t1","model":"gpt-5.6-sol"}}""" + "\n" +
                """{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"total_tokens":12345},"model_context_window":258400}}}""" + "\n" +
                """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}}""" + "\n" +
                """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}}""" + "\n",
        )
        Files.setLastModifiedTime(rollout, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        val frames = java.util.Collections.synchronizedList(mutableListOf<Frame>())
        fun snapshot(): List<Frame> = synchronized(frames) { frames.toList() }

        val convo = registry.open(
            OpenSession("/repo", resumeId = "codex-live", agent = AgentKind.CODEX, lastEventSeq = 0),
            OutboundSink { frames += it },
        )

        assertTrue(registry.observing(convo))
        withTimeout(3_000) {
            while (snapshot().none { it is SessionLive } || snapshot().none { it is ConvoHistory }) delay(20)
        }
        val live = snapshot().filterIsInstance<SessionLive>().last()
        assertTrue(live.observing)
        assertEquals(AgentKind.CODEX, live.agent)
        assertEquals("gpt-5.6-sol", live.model)
        assertEquals(258_400L, live.contextWindow)
        assertEquals(12_345L, live.contextUsed)
        assertEquals("hello", live.title, "an external Codex observer must announce its transcript title")
        val history = snapshot().filterIsInstance<ConvoHistory>().last()
        assertEquals(listOf("hello", "hi"), history.messages.map { it.text })
    }

    @Test
    fun unverifiable_ownership_of_an_old_codex_rollout_still_opens_read_only() = runBlocking {
        val registry = SessionRegistry(
            scope,
            backends = emptyMap(),
            // UNKNOWN = external codex processes exist but the fd probe couldn't run (lsof timeout, Windows).
            // Codex has no session lock, so the safe verdict is observe/fork — not the freshness gate's false.
            codexProcessProbe = { _, _ -> LiveProcesses.ExternalClaude.UNKNOWN },
            transcriptResolver = { agent, _, _ -> if (agent == AgentKind.CODEX) rollout else null },
        )
        Files.writeString(
            rollout,
            """{"type":"session_meta","payload":{"id":"codex-maybe","cwd":"/repo"}}""" + "\n",
        )
        Files.setLastModifiedTime(
            rollout,
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000),
        )

        val convo = registry.open(
            OpenSession("/repo", resumeId = "codex-maybe", agent = AgentKind.CODEX, lastEventSeq = 0),
            OutboundSink { },
        )

        assertTrue(registry.observing(convo), "unverifiable Codex ownership must not resume in place")
    }

    @Test
    fun idle_external_codex_holding_an_old_rollout_still_opens_read_only() = runBlocking {
        val registry = SessionRegistry(
            scope,
            backends = emptyMap(),
            codexProcessProbe = { _, _ -> LiveProcesses.ExternalClaude.PRESENT },
            transcriptResolver = { agent, _, _ -> if (agent == AgentKind.CODEX) rollout else null },
        )
        Files.writeString(
            rollout,
            """{"type":"session_meta","payload":{"id":"codex-idle","cwd":"/repo"}}""" + "\n",
        )
        // A terminal Codex keeps the rollout open while idle, but may not write it for hours. Its exact
        // process/FD ownership must outrank the generic transcript freshness window.
        Files.setLastModifiedTime(
            rollout,
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000),
        )

        val convo = registry.open(
            OpenSession("/repo", resumeId = "codex-idle", agent = AgentKind.CODEX, lastEventSeq = 0),
            OutboundSink { },
        )

        assertTrue(registry.observing(convo))
    }

    @Test
    fun stale_claude_wire_default_is_corrected_from_codex_transcript_and_history_replays() = runBlocking {
        val registry = SessionRegistry(
            scope,
            backends = emptyMap(),
            codexProcessProbe = { _, _ -> LiveProcesses.ExternalClaude.PRESENT },
            transcriptResolver = { agent, _, _ -> if (agent == AgentKind.CODEX) rollout else null },
        )
        Files.writeString(
            rollout,
            """{"type":"session_meta","payload":{"id":"codex-live","cwd":"/repo"}}""" + "\n" +
                """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"from codex"}]}}""" + "\n" +
                """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"restored"}]}}""" + "\n",
        )
        Files.setLastModifiedTime(rollout, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        val frames = java.util.Collections.synchronizedList(mutableListOf<Frame>())

        // No agent field on the wire decodes as CLAUDE for compatibility with old Apps.
        val convo = registry.open(
            OpenSession("/repo", resumeId = "codex-live", lastEventSeq = 0),
            OutboundSink { frames += it },
        )

        assertTrue(registry.observing(convo))
        withTimeout(3_000) {
            while (frames.none { it is SessionLive } || frames.none { it is ConvoHistory }) delay(20)
        }
        assertEquals(AgentKind.CODEX, frames.filterIsInstance<SessionLive>().last().agent)
        assertEquals(listOf("from codex", "restored"), frames.filterIsInstance<ConvoHistory>().last().messages.map { it.text })
    }
}
