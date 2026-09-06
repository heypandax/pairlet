package dev.ccpocket.daemon.dsh

import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #333 — the DeepSeek Harness model/preset catalogue.
 *
 * Every payload below is a VERBATIM copy of what `dsh 0.1.0-rc.6` answered on 2026-09-07 (see
 * `scripts/probe-dsh-api.py --probe-catalogue`), so a shape drift on the next dsh upgrade fails here
 * rather than showing up as an empty picker on the phone.
 */
class DshModelServiceTest {

    private fun rpc(answers: Map<String, String>, seen: MutableList<String> = mutableListOf()) =
        DshRpc { method, _ ->
            seen += method
            answers[method]?.let { DshTranscript.json.parseToJsonElement(it) as JsonObject }
        }

    private fun service(
        answers: Map<String, String>,
        seen: MutableList<String> = mutableListOf(),
        now: () -> Long = { 0L },
    ) = DshModelService(
        liveRpc = { rpc(answers, seen) },
        transientHost = { error("must not boot a host while one is live") },
        nowMs = now,
    )

    private val catalogue = mapOf(
        "llm.models" to FakeDshHost.ok("""{"groups":${FakeDshHost.GROUPS},"failures":[]}"""),
        "agentPreset.list" to FakeDshHost.ok(FakeDshHost.PRESETS),
    )

    @Test
    fun models_efforts_and_presets_all_come_off_dsh_own_rpcs() = runBlocking {
        val list = service(catalogue).fetch()
        assertEquals(AgentKind.DSH, list.agent)
        assertNull(list.error)
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), list.models)

        val flash = list.modelCapabilities.first { it.model == "deepseek-v4-flash" }
        assertEquals(listOf("off", "high", "max"), flash.reasoningEfforts)
        assertEquals("high", flash.defaultReasoningEffort)

        assertEquals(listOf("standard", "code", "minimal", "mine"), list.agentPresets.map { it.id })
        val standard = list.agentPresets.first()
        // Labels are dsh's own copy, verbatim — translating a localized name here would be inventing text.
        assertEquals("标准模式", standard.label)
        assertEquals("功能完整的编码 Agent。", standard.detail)
        assertTrue(standard.recommended) // isDefault
        assertTrue(!standard.custom)     // trust: "system"
    }

    /** `trust:"user"` is the ONLY marker separating a preset the human authored from a shipped one. */
    @Test
    fun a_user_authored_preset_is_marked_custom_and_not_recommended() = runBlocking {
        val mine = service(catalogue).fetch().agentPresets.first { it.id == "mine" }
        assertTrue(mine.custom)
        assertTrue(!mine.recommended)
    }

    /**
     * The two RPCs degrade INDEPENDENTLY: a dsh build old enough to lack `agentPreset.list` still has a
     * model picker, and an empty preset list is the App's own signal to show no preset row at all.
     */
    @Test
    fun a_missing_agentPreset_list_still_yields_models() = runBlocking {
        val list = service(mapOf("llm.models" to catalogue.getValue("llm.models"))).fetch()
        assertNull(list.error)
        assertEquals(2, list.models.size)
        assertTrue(list.agentPresets.isEmpty())
    }

    /** An empty catalogue with no reason is how the picker ends up silently blank (the #255 state). */
    @Test
    fun an_empty_catalogue_reports_dsh_own_failure_text() = runBlocking {
        val list = service(
            mapOf(
                "llm.models" to FakeDshHost.ok(
                    """{"groups":[],"failures":[{"provider":"deepseek-official","message":"no api key"}]}""",
                ),
            ),
        ).fetch()
        assertNotNull(list.error)
        assertTrue(list.error!!.contains("no api key"), list.error!!)
        assertTrue(list.models.isEmpty())
    }

    @Test
    fun an_unreachable_host_is_an_error_never_a_silent_empty_list() = runBlocking {
        val list = DshModelService(
            liveRpc = { DshRpc { _, _ -> null } },
            transientHost = { null },
        ).fetch()
        assertNotNull(list.error)
        assertTrue(list.models.isEmpty())
        assertTrue(list.agentPresets.isEmpty())
    }

    @Test
    fun no_live_host_means_a_throwaway_one_is_booted_and_then_closed() = runBlocking {
        var closed = false
        val list = DshModelService(
            liveRpc = { null },
            transientHost = {
                DshModelService.TransientHost(rpc(catalogue), close = { closed = true })
            },
        ).fetch()
        assertEquals(2, list.models.size)
        assertTrue(closed, "the throwaway dsh must be killed — a leaked host keeps writing to \$DSH_HOME")
    }

    /** A boot that fails must not throw into the request router; it becomes the picker's notice. */
    @Test
    fun a_throwaway_boot_that_throws_becomes_an_error_row() = runBlocking {
        val list = DshModelService(
            liveRpc = { null },
            transientHost = { throw IllegalStateException("dsh executable not found") },
        ).fetch()
        assertNotNull(list.error)
        assertTrue(list.error!!.contains("dsh executable not found"), list.error!!)
    }

    @Test
    fun a_successful_read_is_cached_for_ten_minutes_and_then_refreshed() = runBlocking {
        val seen = mutableListOf<String>()
        var now = 0L
        val svc = service(catalogue, seen, now = { now })
        svc.fetch()
        val afterFirst = seen.size
        now = 9 * 60 * 1000L
        svc.fetch()
        assertEquals(afterFirst, seen.size, "a second fetch inside the TTL must not re-ask dsh")
        now = 11 * 60 * 1000L
        svc.fetch()
        assertTrue(seen.size > afterFirst, "past the TTL a newly authored preset must become visible")
    }

    /**
     * Issue #333 review: two pickers opening at once (the phone's and the desktop's, or one impatient
     * double tap) both miss the empty cache. Without a gate each one BOOTS A dsh HOST — two Node
     * processes against the same `$DSH_HOME`, both immediately discarded. The loser must instead wait and
     * read the cache the winner just filled.
     */
    @Test
    fun concurrent_fetches_boot_exactly_one_throwaway_host() = runBlocking {
        val boots = java.util.concurrent.atomic.AtomicInteger()
        val svc = DshModelService(
            liveRpc = { null },
            transientHost = {
                boots.incrementAndGet()
                // Hold the lock long enough that a racing caller provably overlaps this one.
                kotlinx.coroutines.delay(150)
                DshModelService.TransientHost(rpc(catalogue), close = {})
            },
            nowMs = { 0L },
        )
        val results = List(8) { async(Dispatchers.Default) { svc.fetch() } }.awaitAll()
        assertEquals(1, boots.get(), "each concurrent fetch booted its own dsh host")
        assertTrue(results.all { it.error == null && it.models.size == 2 }, "every caller must get the answer")
    }

    /** …and the gate must not turn a warm cache into a queue: a cached answer never takes the lock. */
    @Test
    fun a_warm_cache_answers_without_booting_anything() = runBlocking {
        val boots = java.util.concurrent.atomic.AtomicInteger()
        val svc = DshModelService(
            liveRpc = { null },
            transientHost = {
                boots.incrementAndGet()
                DshModelService.TransientHost(rpc(catalogue), close = {})
            },
            nowMs = { 0L },
        )
        repeat(5) { svc.fetch() }
        assertEquals(1, boots.get())
    }

    /**
     * Issue #333 review: `trust` is dsh vouching for a preset as its OWN. An ABSENT field is not that
     * vouching, so the row is custom. Defaulting the other way would pass a preset the user wrote off as
     * shipped — and "yours" is the tag that tells them which rows they can edit.
     */
    @Test
    fun a_preset_with_no_trust_field_counts_as_custom() = runBlocking {
        val list = service(
            mapOf(
                "llm.models" to catalogue.getValue("llm.models"),
                "agentPreset.list" to FakeDshHost.ok(
                    """{"presets":[{"id":"mystery","name":"Mystery","isDefault":false}],"authorable":true}""",
                ),
            ),
        ).fetch()
        assertTrue(list.agentPresets.single().custom, "no `trust` is not dsh vouching for the preset")
    }

    /** A dsh that was mid-upgrade for one fetch must not be remembered as broken for ten minutes. */
    @Test
    fun a_failure_is_never_cached() = runBlocking {
        val seen = mutableListOf<String>()
        var answers = emptyMap<String, String>()
        val svc = DshModelService(
            liveRpc = { DshRpc { m, _ -> seen += m; answers[m]?.let { DshTranscript.json.parseToJsonElement(it) as JsonObject } } },
            transientHost = { null },
            nowMs = { 0L },
        )
        assertNotNull(svc.fetch().error)
        answers = catalogue
        assertNull(svc.fetch().error, "the very next fetch must be allowed to succeed")
    }
}
