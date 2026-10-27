package dev.ccpocket.daemon.dsh

import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DeepSeek Harness model catalogue (issue #333, re-sourced by the dsh 0.1.2 ACP switch).
 *
 * The payload is a VERBATIM copy of the `configOptions` dsh 0.1.2-rc.1 answered `session/new` with (see
 * `scripts/probe-dsh-acp.py`), so a shape drift on the next dsh upgrade fails here rather than showing up
 * as an empty picker on the phone.
 */
class DshModelServiceTest {

    private val configOptions = """
        [{"id":"model","name":"Model","category":"model","type":"select",
          "currentValue":"[\"deepseek-official\",\"deepseek-v4-flash\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"DeepSeek-V4-Flash"},
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]},
         {"id":"reasoning_effort","name":"Reasoning effort","category":"thought_level","type":"select",
          "currentValue":"high",
          "options":[{"value":"off","name":"Off"},{"value":"high","name":"High"},{"value":"max","name":"Max"}]}]
    """.trimIndent()

    private fun options(json: String = configOptions) =
        DshConfigOptions.parse(Json.parseToJsonElement(json) as JsonArray)

    @Test
    fun models_and_efforts_come_off_a_live_sessions_own_read_back() = runBlocking {
        val list = DshModelService(
            liveOptions = { options() },
            transientRead = { error("must not boot dsh while a session is live") },
        ).fetch()
        assertEquals(AgentKind.DSH, list.agent)
        assertNull(list.error)
        assertEquals(listOf("deepseek-official/deepseek-v4-flash", "deepseek-official/deepseek-v4-pro"), list.models)
        assertEquals(listOf("off", "high", "max"), list.supportedEfforts)
        val current = list.modelCapabilities.single()
        assertEquals("deepseek-official/deepseek-v4-flash", current.model)
        assertEquals("DeepSeek-V4-Flash", current.displayName)
        assertEquals("high", current.defaultReasoningEffort)
        // The ACP surface has no agent-preset axis — advertising one we cannot select would be a lie.
        assertTrue(list.agentPresets.isEmpty())
    }

    @Test
    fun no_live_session_means_a_throwaway_dsh_is_read() = runBlocking {
        var booted = 0
        val list = DshModelService(
            liveOptions = { null },
            transientRead = { booted++; options() },
        ).fetch()
        assertEquals(1, booted)
        assertEquals(2, list.models.size)
    }

    @Test
    fun an_unreachable_dsh_is_an_error_never_a_silent_empty_list() = runBlocking {
        val list = DshModelService(liveOptions = { null }, transientRead = { null }).fetch()
        assertNotNull(list.error)
        assertTrue(list.error!!.contains(DshLauncher.MIN_VERSION), list.error!!)
        assertTrue(list.models.isEmpty())
    }

    /** A boot that fails must not throw into the request router; it becomes the picker's notice. */
    @Test
    fun a_throwaway_boot_that_throws_becomes_an_error_row() = runBlocking {
        val list = DshModelService(
            liveOptions = { null },
            transientRead = { throw IllegalStateException("dsh executable not found") },
        ).fetch()
        assertNotNull(list.error)
        assertTrue(list.error!!.contains("dsh executable not found"), list.error!!)
    }

    @Test
    fun a_catalogue_with_no_models_reports_why() = runBlocking {
        val list = DshModelService(
            liveOptions = { null },
            transientRead = { options("""[{"id":"reasoning_effort","currentValue":"high","options":[]}]""") },
        ).fetch()
        assertNotNull(list.error)
        assertTrue(list.models.isEmpty())
    }

    @Test
    fun a_throwaway_read_is_cached_for_ten_minutes_and_then_refreshed() = runBlocking {
        var boots = 0
        var now = 0L
        val svc = DshModelService(
            liveOptions = { null },
            transientRead = { boots++; options() },
            nowMs = { now },
        )
        svc.fetch()
        assertEquals(1, boots)
        now = 9 * 60 * 1000L
        svc.fetch()
        assertEquals(1, boots, "a second fetch inside the TTL must not boot dsh again")
        now = 11 * 60 * 1000L
        svc.fetch()
        assertEquals(2, boots, "past the TTL a newly configured model must become visible")
    }

    /** The user pressed refresh (a plugin was just installed): the cached throwaway answer is not the answer. */
    @Test
    fun a_manual_refresh_bypasses_the_throwaway_cache() = runBlocking {
        var boots = 0
        val svc = DshModelService(liveOptions = { null }, transientRead = { boots++; options() }, nowMs = { 0L })
        svc.fetch()
        svc.fetch(forceRefresh = true)
        assertEquals(2, boots)
    }

    /**
     * A live session's answer describes THAT session and is read on every fetch: caching it served session A's
     * catalogue to a picker opened on session B for ten minutes, and kept a closed session's list alive.
     */
    @Test
    fun a_live_answer_is_never_served_from_cache() = runBlocking {
        var live: DshConfigOptions? = options()
        var boots = 0
        val svc = DshModelService(liveOptions = { live }, transientRead = { boots++; null }, nowMs = { 0L })
        assertNull(svc.fetch().error)
        live = options(configOptions.replace("deepseek-v4-pro", "deepseek-v4-max"))
        assertTrue("deepseek-official/deepseek-v4-max" in svc.fetch().models, "the live session's newest answer wins")
        assertEquals(0, boots)
    }

    @Test
    fun the_pickers_workdir_selects_the_live_session_it_describes() = runBlocking {
        val a = Any()
        val b = Any()
        try {
            DshCatalog.publish(a, options(), workdir = "/proj/a")
            DshCatalog.publish(b, options(configOptions.replace("deepseek-v4-pro", "deepseek-v4-max")), workdir = "/proj/b")
            val svc = DshModelService(transientRead = { error("must not boot dsh while a session is live") })
            assertTrue("deepseek-official/deepseek-v4-pro" in svc.fetch("/proj/a").models)
            assertTrue("deepseek-official/deepseek-v4-max" in svc.fetch("/proj/b").models)
            // no session in this workdir: the most recent report of any live session
            assertTrue("deepseek-official/deepseek-v4-max" in svc.fetch("/elsewhere").models)
            DshCatalog.publish(a, options(), workdir = "/proj/a") // a re-publish makes it the newest
            assertTrue("deepseek-official/deepseek-v4-pro" in svc.fetch(null).models)
        } finally {
            DshCatalog.clearForTest()
        }
    }

    /**
     * Two pickers opening at once (the phone's and the desktop's, or one impatient double tap) both miss
     * the empty cache. Without the gate each one BOOTS a dsh — two Node processes against the same
     * `$DSH_HOME`, both immediately discarded, and each one creating a scratch session in the store.
     */
    @Test
    fun concurrent_fetches_boot_exactly_one_throwaway_dsh() = runBlocking {
        val boots = java.util.concurrent.atomic.AtomicInteger()
        val svc = DshModelService(
            liveOptions = { null },
            transientRead = {
                boots.incrementAndGet()
                kotlinx.coroutines.delay(150) // hold the lock so a racing caller provably overlaps
                options()
            },
            nowMs = { 0L },
        )
        val results = List(8) { async(Dispatchers.Default) { svc.fetch() } }.awaitAll()
        assertEquals(1, boots.get(), "each concurrent fetch booted its own dsh")
        assertTrue(results.all { it.error == null && it.models.size == 2 }, "every caller must get the answer")
    }

    /** …and the gate must not turn a warm cache into a queue: a cached answer never takes the lock. */
    @Test
    fun a_warm_cache_answers_without_booting_anything() = runBlocking {
        val boots = java.util.concurrent.atomic.AtomicInteger()
        val svc = DshModelService(
            liveOptions = { null },
            transientRead = { boots.incrementAndGet(); options() },
            nowMs = { 0L },
        )
        repeat(5) { svc.fetch() }
        assertEquals(1, boots.get())
    }

    /** A dsh that was mid-upgrade for one fetch must not be remembered as broken for ten minutes. */
    @Test
    fun a_failure_is_never_cached() = runBlocking {
        var answer: DshConfigOptions? = null
        val svc = DshModelService(
            liveOptions = { answer },
            transientRead = { null },
            nowMs = { 0L },
        )
        assertNotNull(svc.fetch().error)
        answer = options()
        assertNull(svc.fetch().error, "the very next fetch must be allowed to succeed")
    }
}
