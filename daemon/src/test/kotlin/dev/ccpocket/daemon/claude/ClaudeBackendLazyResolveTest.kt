package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #130: the claude binary is resolved LAZILY on first launch — mirroring CodexBackend — so a
 * codex-only machine constructs the backend (and attaches / lists) without ever touching claude, and a
 * launch with claude missing throws the resolver's clear error (surfaced as a PocketError upstream)
 * instead of crashing the daemon at startup. The resolver is injected so the tests don't depend on
 * whether this machine actually has claude installed.
 */
class ClaudeBackendLazyResolveTest {

    @Test
    fun construction_and_attach_never_resolve_claude() = runBlocking {
        val calls = AtomicInteger(0)
        val b = ClaudeBackend(resolveExe = { calls.incrementAndGet(); error("claude executable not found.") })
        b.attach(AgentIo(writeLine = {}, emit = {}), AgentSpec(Path.of("/x")))
        b.sendPrompt("hello", emptyList()) // non-launch IO never needs the binary either
        assertEquals(0, calls.get())
    }

    @Test
    fun launch_with_missing_claude_throws_resolver_error() {
        val b = ClaudeBackend(resolveExe = { error("claude executable not found. Set CC_POCKET_CLAUDE_BIN or pass --claude-bin.") })
        val e = assertFailsWith<IllegalStateException> { b.processBuilder(AgentSpec(Path.of("/x"))) }
        assertTrue("claude executable not found" in (e.message ?: ""), e.message)
    }

    @Test
    fun resolution_happens_once_and_is_cached_across_launches() {
        val calls = AtomicInteger(0)
        val b = ClaudeBackend(resolveExe = { calls.incrementAndGet(); Path.of("/fake/claude") })
        val spec = AgentSpec(Path.of("/x"))
        val pb1 = b.processBuilder(spec)
        val pb2 = b.processBuilder(spec)
        assertEquals(1, calls.get())
        assertTrue(pb1.command().first().endsWith("claude"), pb1.command().toString())
        assertEquals(pb1.command().first(), pb2.command().first())
    }
}
