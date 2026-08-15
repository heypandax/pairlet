package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The REATTACH capability gate (SessionRegistry.open): re-opening a live conversation whose backend the
 * calling peer never declared must be REFUSED, not silently honoured.
 *
 * Why this door exists at all: the reattach match is by `resumeId` ALONE — `open.agent` is deliberately
 * not consulted, so a client that guessed the agent wrong still lands on the right conversation. That
 * makes this the one place a peer can end up attached to a backend it cannot decode. If it were allowed,
 * the daemon would answer with a `SessionLive` the peer drops whole (an unknown enum fails the ENTIRE
 * Envelope, and every ingress swallows decode failures), leaving a session that reports itself open while
 * every push vanishes into nothing — the worst possible failure shape, because it looks like a hang.
 *
 * Four agent generations have relied on this gate and none of them was ever pinned. This covers all four,
 * so the next post-baseline agent inherits real coverage instead of a comment.
 */
class SessionRegistryAgentCapsTest {

    /**
     * A backend that never launches a process. The lazy open (issue #61) starts no process before the
     * first prompt, and this test never sends one — so no agent CLI needs to exist on the machine.
     */
    private class StubBackend(override val kind: AgentKind) : AgentBackend {
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("true")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun withRegistry(body: suspend (SessionRegistry, Path, MutableList<Frame>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("ccp-agentcaps")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(
            scope,
            backends = AgentKind.entries.associateWith { kind -> AgentBackendFactory { StubBackend(kind) } },
        )
        try {
            body(registry, dir, frames)
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    /** Open a live conversation on [agent], then reattach to it as a peer declaring [declare]. */
    private suspend fun reattachAs(
        registry: SessionRegistry,
        dir: Path,
        frames: MutableList<Frame>,
        agent: AgentKind,
        declare: Boolean,
    ): String {
        val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
        val convoId = registry.open(OpenSession(workdir = dir.toString(), agent = agent), sink)
        assertTrue(convoId.isNotEmpty(), "precondition: the $agent conversation must open")
        synchronized(frames) { frames.clear() } // only the REATTACH's frames matter below
        return registry.open(
            OpenSession(workdir = dir.toString(), resumeId = convoId, agent = agent),
            sink,
            peerSupportsOpencode = declare,
            peerSupportsKimi = declare,
            peerSupportsZcode = declare,
            peerSupportsDsh = declare,
        )
    }

    private fun errorsIn(frames: MutableList<Frame>): List<PocketError> =
        synchronized(frames) { frames.filterIsInstance<PocketError>() }

    /** ISSUE #255's gate. DSH is the newest post-baseline agent and the reason this file exists. */
    @Test
    fun a_dsh_reattach_is_refused_for_a_peer_that_never_declared_dsh() = withRegistry { registry, dir, frames ->
        val result = reattachAs(registry, dir, frames, AgentKind.DSH, declare = false)

        assertEquals("", result, "a refused reattach returns no convoId — the caller must not think it attached")
        val err = errorsIn(frames).single()
        assertEquals("agent_unavailable", err.code)
        assertTrue("DSH" in err.message, "the message must name the backend so the user knows what to update: ${err.message}")
    }

    @Test
    fun a_dsh_reattach_succeeds_once_the_peer_declares_dsh() = withRegistry { registry, dir, frames ->
        val result = reattachAs(registry, dir, frames, AgentKind.DSH, declare = true)

        assertNotEquals("", result, "a declared peer must reattach normally")
        assertTrue(errorsIn(frames).isEmpty(), "no error may be emitted to a peer that declared the backend")
    }

    /**
     * Every post-baseline agent goes through the same door. Baseline CLAUDE/CODEX must NOT — they are the
     * vocabulary every shipped client already understands, and refusing them would lock out legacy
     * ingresses (bridges, `--local`) that declare nothing at all.
     */
    @Test
    fun the_gate_covers_every_post_baseline_agent_and_spares_the_baseline() = withRegistry { registry, dir, frames ->
        for (agent in listOf(AgentKind.OPENCODE, AgentKind.KIMI, AgentKind.ZCODE, AgentKind.DSH)) {
            val result = reattachAs(registry, dir, frames, agent, declare = false)
            assertEquals("", result, "$agent must be refused for an undeclared peer")
            assertEquals("agent_unavailable", errorsIn(frames).single().code, "$agent")
        }
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            val result = reattachAs(registry, dir, frames, agent, declare = false)
            assertNotEquals("", result, "$agent is baseline vocabulary and must reattach without any declaration")
            assertTrue(errorsIn(frames).isEmpty(), "$agent must not be gated")
        }
    }

    /**
     * The bits are INDEPENDENT. Declaring zcode must not smuggle a dsh session through — that would be
     * exactly the "one capability implies the next" drift the per-agent bits exist to prevent.
     */
    @Test
    fun declaring_one_post_baseline_agent_does_not_admit_another() = withRegistry { registry, dir, frames ->
        val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
        val convoId = registry.open(OpenSession(workdir = dir.toString(), agent = AgentKind.DSH), sink)
        synchronized(frames) { frames.clear() }

        val result = registry.open(
            OpenSession(workdir = dir.toString(), resumeId = convoId, agent = AgentKind.DSH),
            sink,
            peerSupportsOpencode = true,
            peerSupportsKimi = true,
            peerSupportsZcode = true,
            peerSupportsDsh = false, // the ONLY bit withheld
        )

        assertEquals("", result)
        assertEquals("agent_unavailable", errorsIn(frames).single().code)
    }
}
