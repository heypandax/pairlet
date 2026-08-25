package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.RewindDone
import dev.ccpocket.protocol.RewindMode
import dev.ccpocket.protocol.RewindPreview
import dev.ccpocket.protocol.RewindRefusal
import dev.ccpocket.protocol.RewindSession
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #282: [SessionRegistry.rewind]'s admission gate and its execute path.
 *
 * The gate is the whole safety story of the feature, so every refusal is asserted BY REASON and — where
 * it matters — together with the fact that nothing happened: the original conversation is still the live
 * one and the transcript on disk is untouched. A half-applied rewind (an original closed, no branch, or
 * a lineage edge pointing at a session that does not exist) is the failure this file exists to prevent.
 */
class SessionRegistryRewindTest {

    private val workdir = "/tmp/ccp-rewind-reg"

    /** Records what the daemon answered THIS client. */
    private class Recorder : OutboundSink {
        val frames: MutableList<Frame> = Collections.synchronizedList(mutableListOf())
        override suspend fun emit(f: Frame) { frames += f }
        inline fun <reified T : Frame> last(): T? = frames.filterIsInstance<T>().lastOrNull()
    }

    /** A backend that never writes stdout: the process (if any) stays silent, so `sessionId` remains null
     *  and the conversation is identified by its resume anchor — the same shape a cold open has. */
    private class SilentBackend(override val kind: AgentKind = AgentKind.CLAUDE) : AgentBackend {
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", "sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
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

    private fun registry(root: Path, scope: CoroutineScope, kind: AgentKind = AgentKind.CLAUDE) =
        SessionRegistry(
            scope,
            backends = mapOf(kind to AgentBackendFactory { SilentBackend(kind) }),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT },
            // Pin the Codex probe too: the default shells out to lsof, and on a loaded dev machine with a
            // real codex running a timeout reads as UNKNOWN → "assume held" → the open under test silently
            // becomes a read-only observe instead of the owned conversation these cases exercise.
            codexProcessProbe = { _, _ -> LiveProcesses.ExternalClaude.ABSENT },
            projectsRoot = root,
        )

    /** Two turns under [workdir]'s dirKey. mtime pushed forward for the same reason the rename tests do
     *  it: a coarse filesystem clock can stamp a write BEFORE the registry's `startedAt` and trip the
     *  restart-amnesia gate, flipping the external-writer verdict on CI. */
    private fun seedTranscript(root: Path, sid: String): Path {
        val dir = Files.createDirectories(root.resolve(ProjectPaths.dirKey(workdir)))
        val f = dir.resolve("$sid.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"u0","parentUuid":null,"cwd":"$workdir","message":{"role":"user","content":"hello"}}""",
                """{"type":"assistant","uuid":"a0","parentUuid":"u0","message":{"role":"assistant","content":[{"type":"text","text":"hi"}]}}""",
                """{"type":"user","uuid":"u1","parentUuid":"a0","cwd":"$workdir","message":{"role":"user","content":"go on"}}""",
                """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            ).joinToString("\n") + "\n",
        )
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 50))
        return f
    }

    private fun rewind(convoId: String, uuid: String = "u1", seq: Long = 3, dry: Boolean = false, mode: String = RewindMode.REWIND) =
        RewindSession(convoId, anchorSeq = seq, anchorUuid = uuid, mode = mode, dryRun = dry)

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun an_unknown_convo_is_refused_not_ignored() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        try {
            reg.rewind(rewind("no-such-convo"), sink)
            assertEquals(RewindRefusal.NO_CONVO, sink.last<RewindDone>()?.reason)
            assertEquals(false, sink.last<RewindDone>()?.ok)
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun an_unknown_mode_is_refused_before_anything_is_touched() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        seedTranscript(root, "sid-mode")
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-mode"), sink)
            reg.rewind(rewind(convo, mode = "rewind-files"), sink)
            assertEquals(RewindRefusal.BAD_MODE, sink.last<RewindDone>()?.reason)
            // the conversation is still the live one — a bad mode must not close anything
            assertTrue(reg.liveCountOf(listOf(convo)) == 1)
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun a_non_claude_session_is_refused_as_unsupported() = runBlocking {
        // no other backend has a truncated-resume primitive; doing something ELSE (a plain resume, a new
        // session) would be worse than saying no
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope, kind = AgentKind.CODEX)
        val sink = Recorder()
        seedTranscript(root, "sid-codex")
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-codex", agent = AgentKind.CODEX), sink)
            reg.rewind(rewind(convo, dry = true), sink)
            assertEquals(RewindRefusal.UNSUPPORTED, sink.last<RewindPreview>()?.reason)
            assertEquals(false, sink.last<RewindPreview>()?.ok)
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun a_session_with_a_queued_prompt_is_not_idle() = runBlocking {
        // A queued prompt is invisible to isBusy() before its turn starts. Cutting under it would either
        // strand it in a conversation about to be closed, or hand it to the branch as a first turn nobody
        // typed there — which is why the gate reads the #122 ledger as well.
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking // sh-based stub
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        val f = seedTranscript(root, "sid-busy")
        val before = Files.readString(f)
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-busy", takeOver = true), sink)
            assertTrue(reg.sendPrompt(SendPrompt(convo, "do a thing", promptId = "p1")))

            reg.rewind(rewind(convo), sink)

            assertEquals(RewindRefusal.NOT_IDLE, sink.last<RewindDone>()?.reason)
            assertEquals(1, reg.liveCountOf(listOf(convo)), "the refused rewind must leave the convo alive")
            assertEquals(before, Files.readString(f), "and the transcript untouched")
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun a_stale_anchor_is_refused_and_changes_nothing() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        val f = seedTranscript(root, "sid-stale")
        val before = Files.readString(f)
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-stale"), sink)
            // the uuid exists but the client's line number is out of date
            reg.rewind(rewind(convo, uuid = "u1", seq = 99), sink)

            assertEquals(RewindRefusal.STALE, sink.last<RewindDone>()?.reason)
            assertNull(sink.last<RewindDone>()?.newConvoId)
            assertEquals(1, reg.liveCountOf(listOf(convo)))
            assertEquals(before, Files.readString(f))
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun the_first_message_is_refused_so_the_client_can_disable_that_row() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        seedTranscript(root, "sid-first")
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-first"), sink)
            reg.rewind(rewind(convo, uuid = "u0", seq = 1, dry = true), sink)
            assertEquals(RewindRefusal.FIRST_MESSAGE, sink.last<RewindPreview>()?.reason)
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun an_external_writer_blocks_the_cut() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { SilentBackend() }),
            processProbe = { _, _ -> LiveProcesses.ExternalClaude.PRESENT },
            projectsRoot = root,
        )
        val sink = Recorder()
        seedTranscript(root, "sid-ext")
        try {
            // a plain open on a live-foreign transcript becomes an OBSERVE view, which is not a
            // conversation at all — so the rewind is refused a step earlier, and either refusal is a
            // correct "not while someone else is writing this"
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-ext"), sink)
            reg.rewind(rewind(convo), sink)
            val reason = sink.last<RewindDone>()?.reason
            assertTrue(
                reason == RewindRefusal.EXTERNAL_WRITER || reason == RewindRefusal.NO_CONVO,
                "expected an external-writer refusal, got $reason",
            )
        } finally { reg.closeAll(); scope.cancel() }
    }

    // ── dry run + execute ────────────────────────────────────────────────────────────────────────

    @Test
    fun the_dry_run_counts_the_cut_and_changes_nothing() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        val f = seedTranscript(root, "sid-dry")
        val before = Files.readString(f)
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-dry"), sink)
            reg.rewind(rewind(convo, dry = true), sink)

            val preview = assertNotNull(sink.last<RewindPreview>())
            assertTrue(preview.ok)
            assertEquals(1, preview.dropTurns)
            assertEquals(1, preview.dropToolCalls)
            assertNull(preview.reason)
            // a dry run is a question, not an action
            assertNull(sink.last<RewindDone>())
            assertEquals(1, reg.liveCountOf(listOf(convo)))
            assertEquals(before, Files.readString(f))
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun executing_swaps_the_conversation_for_a_branch_and_leaves_the_original_transcript_alone() = runBlocking {
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        val f = seedTranscript(root, "sid-go")
        val before = Files.readString(f)
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-go"), sink)
            reg.rewind(rewind(convo), sink)

            val done = assertNotNull(sink.last<RewindDone>())
            assertTrue(done.ok, done.reason ?: "")
            assertEquals(convo, done.convoId, "the answer names the conversation that was asked about")
            val branch = assertNotNull(done.newConvoId)
            assertFalse(branch == convo)
            // the branch is lazy, so the CLI has not minted a session id yet — and that is a SUCCESS shape
            assertNull(done.newSessionId)

            assertEquals(0, reg.liveCountOf(listOf(convo)), "the original conversation is closed")
            assertEquals(1, reg.liveCountOf(listOf(branch)), "the branch took its place")
            assertEquals(before, Files.readString(f), "--fork-session never touches the original transcript")
            // nothing is journalled until the fork actually exists: back out here and no ledger entry
            // points at a session that was never created
            assertTrue(dev.ccpocket.daemon.disk.RewindLineage.entries(root.resolve("no-ledger.tsv").toFile()).isEmpty())
        } finally { reg.closeAll(); scope.cancel() }
    }

    @Test
    fun fork_mode_takes_the_same_path_as_rewind() = runBlocking {
        // the launch is identical by design; only how the ORIGINAL is later filed differs, and that is
        // the ledger's business, not this call's
        val root = Files.createTempDirectory("ccp-rw")
        val scope = CoroutineScope(Dispatchers.Default)
        val reg = registry(root, scope)
        val sink = Recorder()
        seedTranscript(root, "sid-fork")
        try {
            val convo = reg.open(OpenSession(workdir = workdir, resumeId = "sid-fork"), sink)
            reg.rewind(rewind(convo, mode = RewindMode.FORK), sink)
            val done = assertNotNull(sink.last<RewindDone>())
            assertTrue(done.ok, done.reason ?: "")
            assertNotNull(done.newConvoId)
        } finally { reg.closeAll(); scope.cancel() }
    }
}
