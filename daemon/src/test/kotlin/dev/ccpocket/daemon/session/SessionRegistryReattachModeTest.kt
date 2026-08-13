package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-opening a STILL-LIVE conversation must apply the caller's permission mode (issue #50's promise,
 * previously implemented only on the cold-resume path). The gap became user-visible with M5's Full
 * Control auto-expiry (approval design §17.5): a conversation alive past the TTL falls back to
 * DEFAULT, and a reattach that ignores OpenSession.mode then keeps that fallback forever — every
 * re-open of a long-lived session reads as "my Settings default (Full Auto) is ignored".
 *
 * The busy exception: peeking at a conversation mid-turn must NOT yank its mode (and with it the
 * grants + autonomy the running task is executing under) — same idle-only spirit as the reaper.
 */
class SessionRegistryReattachModeTest {

    /** Runs dispatched work inline. This makes a non-lazy launch beat the caller's following statement,
     *  deterministically exercising registration-before-start ordering rather than relying on timing. */
    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    /** Replays [script] on stdout through the REAL Conversation pump; `sleep` keeps the process alive. */
    private class ScriptedBackend(private val script: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'; sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private val init = """{"type":"system","subtype":"init","session_id":"s-remode","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val toolUse =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}"""
    private val result =
        """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}"""

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun withRegistry(
        backend: AgentBackend,
        body: suspend (SessionRegistry, dir: Path, frames: MutableList<Frame>) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("ccp-remode")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        try {
            body(registry, dir, frames)
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    private suspend fun awaitFrame(frames: MutableList<Frame>, match: (Frame) -> Boolean) = withTimeout(10_000) {
        while (synchronized(frames) { frames.none(match) }) delay(20)
    }

    @Test
    fun reattach_applies_the_callers_mode_to_an_idle_conversation() {
        if (isWindows()) return // stubs run via sh/cat
        // the M5 shape: the convo's live mode (DEFAULT, as after a Full Control expiry) differs from
        // the mode the re-open carries (the phone's persisted Settings default)
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse, result).joinToString("\n") + "\n") } // turn completes → idle
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            registry.sendPrompt(SendPrompt(convoId = convoId, text = "run"))
            awaitFrame(frames) { it is TurnDone }
            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode", mode = PermissionMode.BYPASS_PERMISSIONS),
                sink,
            )
            assertEquals(convoId, again, "a live session must reattach, not fork a second conversation")
            assertEquals(PermissionMode.BYPASS_PERMISSIONS, registry.modeOf(convoId), "an idle reattach must apply the caller's mode")
        }
    }

    @Test
    fun reattach_leaves_a_busy_conversations_mode_untouched() {
        if (isWindows()) return
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse).joinToString("\n") + "\n") } // NO result: mid-turn
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            registry.sendPrompt(SendPrompt(convoId = convoId, text = "run"))
            awaitFrame(frames) { it is ToolEvent }
            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode", mode = PermissionMode.BYPASS_PERMISSIONS),
                sink,
            )
            assertEquals(convoId, again)
            assertEquals(
                PermissionMode.DEFAULT, registry.modeOf(convoId),
                "peeking at a running task must not change the mode it executes under",
            )
        }
    }

    @Test
    fun an_owner_reopen_never_relaxes_a_conversation_built_for_someone_elses_grant() {
        if (isWindows()) return
        // The escalation #50's hot-path fix would otherwise open: the owner hands a session to a
        // collaborator under REVIEW_READ_ONLY (clamped to DEFAULT), then taps that session to watch it.
        // The owner's open carries their OWN Settings default — Full Control — and a collaborator convo
        // has origin == null, so switchMode's `origin != null && BYPASS` ceiling never fires. Applying
        // the caller's mode here would give the colleague unattended write + shell under the owner's
        // credentials. Only a reattacher whose grant shape and origin match may re-apply a mode.
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse, result).joinToString("\n") + "\n") } // turn completes → idle
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT),
                sink,
                handoffAccess = HandoffAccess.REVIEW_READ_ONLY,
            )
            registry.sendPrompt(SendPrompt(convoId = convoId, text = "run"))
            awaitFrame(frames) { it is TurnDone }
            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode", mode = PermissionMode.BYPASS_PERMISSIONS),
                sink,
            )
            assertEquals(convoId, again, "the owner still reattaches as a spectator — no fork")
            assertEquals(
                PermissionMode.DEFAULT, registry.modeOf(convoId),
                "an owner re-open must not hand its Settings default to a session the handoff grant clamped",
            )
        }
    }

    @Test
    fun reattach_applies_the_native_auto_mode_to_a_lazy_conversation() {
        if (isWindows()) return
        // no prompt → no process (the lazy open, issue #61): the pre-first-turn reattach path
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            val again = registry.open(
                OpenSession(
                    workdir = dir.toString(), resumeId = convoId,
                    mode = PermissionMode.DEFAULT, permissionMode = CLAUDE_PERMISSION_MODE_AUTO,
                ),
                sink,
            )
            assertEquals(convoId, again)
            // the first open's announce is async, so frame ORDER is racy — wait for the reattach's
            // announce by content instead (times out = the native mode was dropped)
            awaitFrame(frames) { it is SessionLive && it.permissionMode == CLAUDE_PERMISSION_MODE_AUTO }
        }
    }

    @Test
    fun zero_grace_close_is_registered_before_its_timer_can_run() = runBlocking {
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        val dir = Files.createTempDirectory("ccp-remode")
        val scope = CoroutineScope(SupervisorJob() + ImmediateDispatcher)
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { ScriptedBackend(script) }),
        )
        try {
            val sink = OutboundSink { }
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                sink,
            )

            registry.scheduleClose(convoId, sink, graceMs = 0)

            assertEquals(
                null,
                registry.modeOf(convoId),
                "a zero-delay timer must see its registered ownership and close the idle conversation",
            )
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun lan_reconnect_does_not_leave_the_disconnected_sink_attached_forever() {
        // A LAN disconnect schedules a grace-close for that exact connection's sink. Re-opening from a
        // new socket must preserve the warm Conversation, but the old sink still needs to be detached
        // when its grace expires. Otherwise leaving from the replacement socket sees the zombie sink as
        // another live viewer and refuses to close; while any unrelated LAN socket remains online, the
        // idle reaper also mistakes that zombie view for an occupant and can pin the session forever.
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, _ ->
            val first = OutboundSink { }
            val replacement = OutboundSink { }
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                first,
            )
            registry.scheduleClose(convoId, first, graceMs = 40)

            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                replacement,
            )
            assertEquals(convoId, again, "the reconnect must reuse the warm conversation")
            delay(120)

            assertTrue(
                registry.close(convoId, requester = replacement),
                "after the old socket's grace, the replacement must be the only attached client",
            )
        }
    }

    @Test
    fun overlapping_lan_disconnect_graces_are_tracked_per_connection() {
        // Two LAN clients can view the same conversation. Their disconnect timers are independent:
        // scheduling the second must not cancel the first, or the first dead sink survives after both
        // sockets are gone and keeps the warm session registered indefinitely.
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, _ ->
            val first = OutboundSink { }
            val second = OutboundSink { }
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                first,
            )
            assertEquals(
                convoId,
                registry.open(OpenSession(workdir = dir.toString(), resumeId = "s-remode"), second),
            )

            registry.scheduleClose(convoId, first, graceMs = 40)
            registry.scheduleClose(convoId, second, graceMs = 40)
            delay(120)

            assertFalse(
                registry.sendPrompt(SendPrompt(convoId, "must be gone")),
                "after both connection graces expire, no dead sink may keep the conversation registered",
            )
        }
    }

    @Test
    fun same_key_reconnect_replaces_the_sink_and_cancels_its_stale_cleanup() {
        // Relay/device-style keyed sinks deliberately represent a logical client across reconnects.
        // The per-sink timer change must retain the existing behaviour: a replacement with the same
        // key cancels the stale connection's cleanup instead of detaching the new delegate at expiry.
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, _ ->
            val old = KeyedSink("dev:stable", OutboundSink { })
            val replacement = KeyedSink("dev:stable", OutboundSink { })
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                old,
            )
            registry.scheduleClose(convoId, old, graceMs = 40)
            assertEquals(
                convoId,
                registry.open(OpenSession(workdir = dir.toString(), resumeId = "s-remode"), replacement),
            )
            delay(120)

            assertTrue(
                registry.sendPrompt(SendPrompt(convoId, "still live")),
                "the cancelled old timer must not detach the replacement sharing its logical key",
            )
        }
    }

    @Test
    fun superseded_timer_that_already_woke_must_not_run_cleanup() {
        // Cancellation alone is not sufficient once the old job has crossed its delay. Hold it at the
        // claim seam, replace it with a long timer for the same (convo, sink) identity, then release it:
        // the old job must notice it no longer owns the map slot and return before detach.
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, _ ->
            val sink = OutboundSink { }
            val convoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                sink,
            )
            val oldAwake = CompletableDeferred<Unit>()
            val letOldClaim = CompletableDeferred<Unit>()
            var first = true
            registry.beforePendingCloseClaim = {
                if (first) {
                    first = false
                    oldAwake.complete(Unit)
                    letOldClaim.await()
                }
            }

            registry.scheduleClose(convoId, sink, graceMs = 1)
            withTimeout(5_000) { oldAwake.await() }
            registry.scheduleClose(convoId, sink, graceMs = 5_000) // supersedes the already-awake job
            letOldClaim.complete(Unit)
            delay(80)

            assertTrue(
                registry.sendPrompt(SendPrompt(convoId, "old timer must be inert")),
                "a superseded, already-awake timer must return before detaching its sink",
            )
            registry.beforePendingCloseClaim = null
        }
    }

    @Test
    fun expiry_winning_before_the_atomic_reattach_claim_forces_a_cold_replacement() {
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, _ ->
            val oldSink = KeyedSink("dev:stable", OutboundSink { })
            val replacement = KeyedSink("dev:stable", OutboundSink { })
            val oldConvoId = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                oldSink,
            )
            val expiryAwake = CompletableDeferred<Unit>()
            val letExpiryClaim = CompletableDeferred<Unit>()
            registry.beforePendingCloseClaim = {
                expiryAwake.complete(Unit)
                letExpiryClaim.await()
            }
            registry.scheduleClose(oldConvoId, oldSink, graceMs = 1)
            withTimeout(5_000) { expiryAwake.await() }

            val reattachAtClaim = CompletableDeferred<Unit>()
            val letReattachClaim = CompletableDeferred<Unit>()
            registry.beforeLiveReattachClaim = {
                reattachAtClaim.complete(Unit)
                letReattachClaim.await()
            }
            coroutineScope {
                val reopened = async {
                    registry.open(
                        OpenSession(workdir = dir.toString(), resumeId = "s-remode"),
                        replacement,
                    )
                }
                withTimeout(5_000) { reattachAtClaim.await() }

                letExpiryClaim.complete(Unit)
                withTimeout(5_000) { while (registry.modeOf(oldConvoId) != null) delay(1) }
                letReattachClaim.complete(Unit)
                val newConvoId = withTimeout(5_000) { reopened.await() }

                assertTrue(newConvoId.isNotEmpty())
                assertTrue(newConvoId != oldConvoId, "an expired candidate must never be returned as live")
                assertEquals(PermissionMode.DEFAULT, registry.modeOf(newConvoId), "the cold replacement must be registered")
            }
        }
    }
}
