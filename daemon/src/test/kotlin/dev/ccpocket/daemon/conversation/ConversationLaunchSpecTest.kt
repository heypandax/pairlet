package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentProcessMode
import dev.ccpocket.daemon.agent.AgentPromptDelivery
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization of the AgentSpec every launch entry point produces (lifecycle design S1 / §4.1), captured
 * at the backend's `processBuilder` — i.e. AFTER launchProcess's single choke point (remote-execution mode
 * downgrade, clean room, bridge preamble). This pins TODAY's behaviour field by field so that collapsing the
 * nine hand-built AgentSpecs into one constructor is provably a no-op.
 *
 * The expectations deliberately include the inconsistencies the design flags for a ruling (§4.2) — they are
 * NOT endorsements:
 *  - the control-plane cold start (/compact) carries no `thinking` / `agentPreset`;
 *  - `switchDirectory` launches with `model = null`;
 *  - the one-shot drains never fork, even pre-first-turn on a session opened with a fork decision.
 *
 * Every scenario runs pre-first-turn (the fake never reports a session id) on a session opened as
 * `resume = r-open, fork = true` — the lineage state in which the fork rules differ — with every launch knob
 * set, for three conversation shapes: a local Claude session, a local Codex session (service tier only exists
 * there) and a remote-execution run under an acceptEdits ceiling (the #367 HIGH-1 downgrade).
 */
class ConversationLaunchSpecTest {

    private enum class Variant(val kind: AgentKind) { CLAUDE(AgentKind.CLAUDE), CODEX(AgentKind.CODEX), REMOTE(AgentKind.CLAUDE) }

    private val lockStderr = "Error: Session r-open is currently running as a background agent (bg). add --fork-session"

    private fun harness(
        variant: Variant,
        processMode: AgentProcessMode = AgentProcessMode.LONG_RUNNING,
        promptDelivery: AgentPromptDelivery = AgentPromptDelivery.STDIN_REPLAY,
        supportsNativeCompact: Boolean = false,
        script: (Int, AgentSpec) -> String,
    ): LifecycleHarness {
        val backend = LifecycleBackend(
            kind = variant.kind, processMode = processMode, promptDelivery = promptDelivery,
            supportsNativeCompact = supportsNativeCompact, script = script,
        )
        return if (variant == Variant.REMOTE) {
            LifecycleHarness(backend, "cSpec", mode = PermissionMode.ACCEPT_EDITS, origin = "execution:g1", pathScope = { listOf(it.toString()) })
        } else {
            LifecycleHarness(backend, "cSpec")
        }
    }

    private suspend fun LifecycleHarness.openKnobs(takeOver: Boolean = false) = convo.open(
        resumeId = "r-open", model = "m-user", effort = "high", fork = true, takeOver = takeOver,
        permissionMode = "auto", serviceTier = "priority", thinking = true, agentPreset = "preset-x",
    )

    /** Today's spec for [variant] with the shared knobs; each scenario `copy`s what its launch point sets. */
    private fun base(variant: Variant, workdir: Path) = AgentSpec(
        workdir = workdir,
        resumeId = "r-open",
        model = "m-user",
        mode = PermissionMode.DEFAULT, // REMOTE: the acceptEdits ceiling is launched as DEFAULT (#367 HIGH-1)
        effort = "high",
        thinking = true,
        agentPreset = "preset-x",
        permissionMode = if (variant == Variant.CLAUDE) "auto" else null, // Claude-only, never for a restricted origin
        serviceTier = if (variant == Variant.CODEX) "priority" else null, // Codex-only
        forkSession = true,
        cleanRoom = variant == Variant.REMOTE,
    )

    private fun eachVariant(body: suspend (Variant) -> Unit) = runBlocking {
        if (LifecycleHarness.isWindows()) return@runBlocking
        for (v in Variant.entries) body(v)
    }

    private suspend fun captured(h: LifecycleHarness, index: Int): AgentSpec {
        h.await(what = "launch #$index") { h.backend.specs.size > index }
        return h.backend.specs[index]
    }

    /** #1 open(takeOver = true) — the eager "Continue here" launch. */
    @Test
    fun takeover_open() = eachVariant { v ->
        val h = harness(v) { _, _ -> LifecycleBackend.SILENT }
        try {
            h.openKnobs(takeOver = true)
            assertEquals(base(v, h.workdir).copy(takeOver = true), captured(h, 0), "$v")
        } finally { h.close() }
    }

    /** #2 relaunch-then-send after a settings switch. */
    @Test
    fun settings_relaunch() = eachVariant { v ->
        System.setProperty(Conversation.RELAUNCH_GRACE_PROP, "0")
        val h = harness(v) { _, _ -> LifecycleBackend.ECHO_TURNS }
        try {
            h.openKnobs()
            h.convo.sendPrompt("p1", promptId = "p1")
            h.await(what = "first turn") { h.framesOf<TurnDone>().isNotEmpty() }
            h.convo.switchEffort("low")
            h.convo.sendPrompt("p2", promptId = "p2")
            assertEquals(base(v, h.workdir).copy(effort = "low", initialPrompt = "p2"), captured(h, 1), "$v")
        } finally {
            h.close()
            System.clearProperty(Conversation.RELAUNCH_GRACE_PROP)
        }
    }

    /** #3 argv one-shot (OpenCode-shaped) drain of a queued prompt. */
    @Test
    fun argv_one_shot_drain() = eachVariant { v ->
        val gateFile = Files.createTempDirectory("ccp-spec-gate").resolve("go")
        val h = harness(v, AgentProcessMode.ONE_SHOT_TURN, AgentPromptDelivery.INITIAL_ARG_ONE_SHOT) { index, _ ->
            if (index == 0) "while [ ! -f '$gateFile' ]; do sleep 0.02; done; printf 'init\\nresult\\n'; exit 0"
            else "printf 'init\\nresult\\n'; exit 0"
        }
        try {
            h.openKnobs()
            h.convo.sendPrompt("p1", promptId = "p1")
            h.convo.sendPrompt("p2", promptId = "p2") // queued behind the running argv turn
            Files.createFile(gateFile)
            assertEquals(base(v, h.workdir).copy(forkSession = false, initialPrompt = "p2"), captured(h, 1), "$v")
        } finally { h.close() }
    }

    /** #4 stdin one-shot (Codex-shaped) relaunch for an unconsumed prompt. */
    @Test
    fun stdin_one_shot_drain() = eachVariant { v ->
        val h = harness(v, AgentProcessMode.ONE_SHOT_TURN, AgentPromptDelivery.STDIN_REPLAY) { index, _ ->
            if (index == 0) "IFS= read -r l; printf 'user:%s\\n' \"\$l\"; IFS= read -r l2; printf 'result\\n'; exit 0"
            else "IFS= read -r l; printf 'user:%s\\nresult\\n' \"\$l\"; exit 0"
        }
        try {
            h.openKnobs()
            h.convo.sendPrompt("p1", promptId = "p1")
            h.convo.sendPrompt("p2", promptId = "p2") // written, never echoed: unconsumed at the clean exit
            assertEquals(base(v, h.workdir).copy(forkSession = false), captured(h, 1), "$v")
        } finally { h.close() }
    }

    /** #5 session-lock heal: the refused resume is retried as a fork. */
    @Test
    fun session_lock_heal() = eachVariant { v ->
        val h = harness(v) { index, _ -> if (index == 0) "echo '$lockStderr' 1>&2; exit 1" else LifecycleBackend.SILENT }
        try {
            h.openKnobs()
            h.convo.sendPrompt("p1", promptId = "p1")
            assertEquals(base(v, h.workdir), captured(h, 1), "$v")
        } finally { h.close() }
    }

    /** #6 lazy first-prompt launch. */
    @Test
    fun lazy_first_prompt() = eachVariant { v ->
        val h = harness(v) { _, _ -> LifecycleBackend.SILENT }
        try {
            h.openKnobs()
            h.convo.sendPrompt("p1", promptId = "p1")
            assertEquals(base(v, h.workdir).copy(initialPrompt = "p1"), captured(h, 0), "$v")
        } finally { h.close() }
    }

    /** #7 control-plane cold start (/compact on a backend with native compaction). */
    @Test
    fun control_op_cold_start() = eachVariant { v ->
        val h = harness(v, supportsNativeCompact = true) { _, _ -> LifecycleBackend.SILENT }
        try {
            h.openKnobs()
            h.convo.sendPrompt("/compact", promptId = "c")
            // today's spec has no thinking / agentPreset here (design §4.1 #7 — flagged, not fixed)
            assertEquals(base(v, h.workdir).copy(thinking = null, agentPreset = null), captured(h, 0), "$v")
        } finally { h.close() }
    }

    /** #8 /clear: a fresh session, no lineage. */
    @Test
    fun clear_command() = eachVariant { v ->
        val h = harness(v) { _, _ -> LifecycleBackend.SILENT }
        try {
            h.openKnobs()
            h.convo.sendPrompt("/clear", promptId = "c")
            assertEquals(base(v, h.workdir).copy(resumeId = null, forkSession = false), captured(h, 0), "$v")
        } finally { h.close() }
    }

    /** #9 switchDirectory: a fresh session in the new cwd. */
    @Test
    fun switch_directory() = eachVariant { v ->
        val h = harness(v) { _, _ -> LifecycleBackend.SILENT }
        val elsewhere = Files.createTempDirectory("ccp-spec-cd")
        try {
            h.openKnobs()
            h.convo.switchDirectory(elsewhere)
            // today's spec drops the model here (design §4.1 #9 / audit L2 — flagged, not fixed)
            assertEquals(
                base(v, h.workdir).copy(workdir = elsewhere, resumeId = null, model = null, forkSession = false),
                captured(h, 0), "$v",
            )
        } finally { h.close() }
    }
}
