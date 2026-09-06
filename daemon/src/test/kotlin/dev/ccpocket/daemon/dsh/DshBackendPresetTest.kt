package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File-scoped so it cannot collide with the same name in the sibling dsh tests. */
private const val SESSION = "session-preset-1"

/**
 * Issue #333 — the agent preset + live model switch, driven end to end against a real (fake) dsh host.
 *
 * ## The ordering these tests exist to pin
 *
 * dsh locks a session's agent preset the moment the session produces output (`agent-preset-locked`, a
 * probe-verified error code in the rc.6 bundle). So `agentPreset.select` has EXACTLY one window: after
 * `session.create`, before the first `session.prompt`. Both neighbours matter — selecting too early has
 * no session to name, selecting too late is refused and the session silently runs the wrong persona,
 * which is invisible to the user because the reply still arrives.
 *
 * The prompt gate is what makes this testable: [DshBackend.sendPrompt] buffers while `sessionId` is null,
 * and `sessionId` is only published after the preset and model RPCs have returned. A test that sends a
 * prompt BEFORE the boot line therefore proves the buffering, not just the happy path.
 */
class DshBackendPresetTest {

    private val hosts = CopyOnWriteArrayList<FakeDshHost>()
    private val workdir = Files.createTempDirectory("dsh-preset-test")

    @AfterTest
    fun tearDown() {
        hosts.forEach { it.stop() }
        DshHosts.clearForTest()
    }

    /** The handler set a healthy dsh answers with. Individual tests override single entries. */
    private fun handlers(
        create: String = FakeDshHost.ok("""{"sessionId":"$SESSION","agentPreset":"standard"}"""),
        select: String = FakeDshHost.ok("""{"agentPreset":"minimal"}"""),
        models: String = FakeDshHost.ok(
            """{"current":{"provider":"deepseek-official","model":"deepseek-v4-flash"},
                "routable":true,"groups":${FakeDshHost.GROUPS},"failures":[]}""",
        ),
        selectModel: String = FakeDshHost.ok(
            """{"selected":{"provider":"deepseek-official","model":"deepseek-v4-pro","reasoningEffort":"max"}}""",
        ),
        list: String = FakeDshHost.ok("""{"items":[{"sessionId":"$SESSION","agentPreset":"code"}]}"""),
    ): Map<String, (JsonObject) -> String> = mapOf(
        "session.create" to { create },
        "agentPreset.select" to { select },
        "session.models" to { models },
        "session.selectModel" to { selectModel },
        "session.list" to { list },
        "session.prompt" to { FakeDshHost.ok("{}") },
    )

    /** Boot a backend against a fake host and run [block] once the session has opened. */
    private fun opened(
        spec: AgentSpec,
        handlers: Map<String, (JsonObject) -> String> = handlers(),
        beforeBoot: suspend (DshBackend) -> Unit = {},
        block: suspend (DshBackend, FakeDshHost, List<AgentEvent>) -> Unit,
    ) = runBlocking {
        val host = FakeDshHost(handlers).start().also(hosts::add)
        val backend = DshBackend(null)
        val injected = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        backend.attach(
            AgentIo(writeLine = {}, emit = {}, inject = { line -> injected += line; events += backend.parse(line) }),
            spec,
        )
        beforeBoot(backend)
        backend.parse(host.bootLine())
        // The synthetic init is the last thing openSession injects before releasing the prompt gate, so
        // its arrival is the honest "the session is open" signal — polling `calls` would race it.
        withTimeout(20_000) {
            while (injected.none { it.contains("cc-pocket/dsh-init") }) kotlinx.coroutines.delay(25)
        }
        block(backend, host, events)
        backend.onProcessEnded(SESSION)
    }

    private fun spec(preset: String? = null, model: String? = null, effort: String? = null, resume: String? = null) =
        AgentSpec(
            workdir = workdir, resumeId = resume, model = model, mode = PermissionMode.DEFAULT,
            effort = effort, agentPreset = preset,
        )

    // ---- the ordering ------------------------------------------------------

    @Test
    fun the_preset_is_selected_after_create_and_before_the_first_prompt() =
        opened(spec(preset = "minimal"), beforeBoot = { it.sendPrompt("hello", emptyList()) }) { _, host, _ ->
            val create = host.calls.indexOf("session.create")
            val select = host.calls.indexOf("agentPreset.select")
            val prompt = host.calls.indexOf("session.prompt")
            assertTrue(create >= 0 && select >= 0 && prompt >= 0, "missing an RPC: ${host.calls}")
            assertTrue(create < select, "the select must name a session that exists: ${host.calls}")
            assertTrue(
                select < prompt,
                "a preset selected after the first prompt is refused as agent-preset-locked: ${host.calls}",
            )
            // …and it asked for the preset the client chose, verbatim.
            val payload = host.payloads[select]
            assertEquals("minimal", payload.str("agentPreset"))
            assertEquals(SESSION, payload.str("sessionId"))
        }

    /** No preset chosen = dsh's own default. Sending a select anyway would be a silent behaviour change. */
    @Test
    fun no_chosen_preset_means_no_select_rpc_at_all() = opened(spec()) { _, host, _ ->
        assertTrue("agentPreset.select" !in host.calls, host.calls.toString())
    }

    /**
     * `agent-preset-locked` is a preference that could not be honoured, not a broken session. The open must
     * survive it — and must then announce the preset that IS in force, never the one that was refused.
     */
    @Test
    fun a_locked_preset_is_tolerated_and_the_create_time_preset_is_announced() = opened(
        spec(preset = "minimal"),
        handlers(select = FakeDshHost.err("agent-preset-locked", "session already produced output")),
    ) { _, host, events ->
        assertTrue("agentPreset.select" in host.calls)
        assertTrue("session.prompt" !in host.calls) // nothing was sent, but the session did open
        val meta = events.filterIsInstance<AgentEvent.RuntimeMeta>().mapNotNull { it.agentPreset }
        assertEquals(listOf("standard"), meta, "a refused preset must never be announced as effective")
    }

    // ---- what gets announced ----------------------------------------------

    @Test
    fun the_effective_preset_and_the_current_model_are_announced_before_the_first_turn() =
        opened(spec(preset = "minimal")) { _, _, events ->
            val meta = events.filterIsInstance<AgentEvent.RuntimeMeta>().last()
            assertEquals("minimal", meta.agentPreset) // dsh's read-back, not our request
            // Pre-#333 the chip stayed blank until dsh happened to emit a `request/*` frame.
            assertEquals("deepseek-v4-flash", meta.model)
        }

    /** A resume adopts the stored preset: dsh will not move it, so the session index is the only truth. */
    @Test
    fun a_resumed_session_announces_the_preset_from_the_session_index() =
        opened(spec(preset = "minimal", resume = SESSION)) { _, host, events ->
            assertTrue("session.create" !in host.calls, "a resume must not create a session")
            assertTrue(
                "agentPreset.select" !in host.calls,
                "the preset is locked on a resumed session — asking is how you get a spurious error",
            )
            assertEquals("code", events.filterIsInstance<AgentEvent.RuntimeMeta>().last().agentPreset)
        }

    // ---- model + effort ----------------------------------------------------

    @Test
    fun a_launch_model_is_selected_with_its_provider_joined_from_dsh_own_listing() =
        opened(spec(model = "deepseek-v4-pro", effort = "max")) { _, host, events ->
            val at = host.calls.indexOf("session.selectModel")
            assertTrue(at >= 0, host.calls.toString())
            val payload = host.payloads[at]
            // provider is a SEPARATE argument; encoding it into the model id would leak into the chip
            assertEquals("deepseek-official", payload.str("provider"))
            assertEquals("deepseek-v4-pro", payload.str("model"))
            assertEquals("max", payload.str("reasoningEffort"))
            assertTrue(
                host.calls.indexOf("session.create") < at,
                "selectModel needs the session it names: ${host.calls}",
            )
            val meta = events.filterIsInstance<AgentEvent.RuntimeMeta>().last()
            assertEquals("deepseek-v4-pro", meta.model)
            assertEquals("max", meta.effort)
        }

    /**
     * cc-pocket's persisted effort ladder (low/medium/high/xhigh/max) is wider than the one a given dsh
     * model offers (off/high/max). A level this model does not have must degrade to the model's own
     * default, not fail the switch — otherwise a stale preference pins the session to the wrong model.
     */
    @Test
    fun an_effort_the_model_rejects_is_retried_without_it_and_the_model_switch_still_lands() {
        var seenEffort = 0
        opened(
            spec(model = "deepseek-v4-pro", effort = "medium"),
            handlers().toMutableMap().apply {
                this["session.selectModel"] = { payload ->
                    if (payload.str("reasoningEffort") != null) {
                        seenEffort++
                        FakeDshHost.err("bad-request", "unknown reasoningEffort")
                    } else {
                        FakeDshHost.ok("""{"selected":{"provider":"deepseek-official","model":"deepseek-v4-pro"}}""")
                    }
                }
            },
        ) { _, host, events ->
            assertEquals(1, seenEffort, "the level must be tried once before being dropped")
            assertEquals(2, host.calls.count { it == "session.selectModel" })
            val meta = events.filterIsInstance<AgentEvent.RuntimeMeta>().last()
            assertEquals("deepseek-v4-pro", meta.model)
            assertNull(meta.effort, "no level was accepted, so none may be announced")
        }
    }

    /**
     * A model change is a LIVE `session.selectModel`, never a relaunch: dsh accepts it at any point, and
     * a relaunch would drop the host and cost the user their live context for a preference change.
     */
    @Test
    fun applySettings_switches_the_model_live_without_asking_for_a_relaunch() =
        opened(spec()) { backend, host, events ->
            val before = host.calls.count { it == "session.selectModel" }
            val relaunch = backend.applySettings(mode = null, model = "deepseek-v4-pro", effort = "max")
            assertTrue(!relaunch, "a model switch must never force a relaunch on dsh")
            withTimeout(10_000) {
                while (host.calls.count { it == "session.selectModel" } == before) kotlinx.coroutines.delay(25)
            }
            withTimeout(10_000) {
                while (events.filterIsInstance<AgentEvent.RuntimeMeta>().none { it.model == "deepseek-v4-pro" }) {
                    kotlinx.coroutines.delay(25)
                }
            }
        }

    /** A MODE change still needs one: dsh reads its sandbox mode from the launch environment. */
    @Test
    fun applySettings_still_relaunches_for_a_permission_mode_change() = opened(spec()) { backend, _, _ ->
        assertTrue(backend.applySettings(PermissionMode.BYPASS_PERMISSIONS, model = null, effort = null))
    }

    /** The catalogue reuses a host we are already driving instead of booting a second dsh (issue #333). */
    @Test
    fun a_live_session_registers_its_host_for_the_catalogue_and_unregisters_on_teardown() =
        opened(spec()) { backend, _, _ ->
            val live = DshHosts.first()
            assertTrue(live != null, "a live dsh session must be reusable by the model picker")
            val listed = live!!.rpc("session.models", kotlinx.serialization.json.buildJsonObject { })
            assertEquals("true", listed?.get("ok")?.toString())
            backend.onProcessEnded(SESSION)
            assertNull(DshHosts.first(), "a dead host must not be handed to the picker")
        }
}
