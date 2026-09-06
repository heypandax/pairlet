package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #347: a take-over of a Codex session the ChatGPT desktop app still holds forks a protective branch
 * that INHERITS the parent's name, leaving two indistinguishable rows in ChatGPT's sidebar. These tests
 * drive [CodexBackend] with synthetic app-server frames (no `codex` binary) and pin four things:
 * the branch is renamed exactly once, nothing else ever is, and a rename that fails or never answers
 * costs only the name.
 *
 * The `thread/name/set` shape asserted here (`{threadId, name}`, empty result, `thread/name/updated`
 * notification) is the one the local codex build publishes via `codex app-server generate-json-schema`.
 */
class CodexTakeoverNameTest {
    @BeforeTest fun reset() = CodexTakeoverLineage.clearForTest()

    private fun initResponse(id: Int) =
        """{"id":$id,"result":{"userAgent":"x","codexHome":"/h","platformFamily":"unix","platformOs":"macos"}}"""

    /** A thread/fork (or resume) response. [name] is what Codex copied from the parent. */
    private fun threadResponse(id: Int, threadId: String, name: String? = null, forkedFrom: String? = null) =
        """{"id":$id,"result":{"thread":{"id":"$threadId"""" +
            (name?.let { ""","name":"$it"""" } ?: ""","name":null""") +
            (forkedFrom?.let { ""","forkedFromId":"$it"""" } ?: "") +
            """},"model":"gpt-5.1-codex"}}"""

    private fun nameSets(w: List<String>) = w.filter { "\"method\":\"thread/name/set\"" in it }

    private fun param(line: String, key: String): String =
        Json.parseToJsonElement(line).jsonObject["params"]!!.jsonObject[key]!!.jsonPrimitive.content

    private fun requestId(line: String): Int =
        Json.parseToJsonElement(line).jsonObject["id"]!!.jsonPrimitive.content.toInt()

    /** attach as the phone's "Continue here" on a desktop-held session → thread/fork (id 2). */
    private suspend fun takeOver(
        w: MutableList<String>,
        titles: Map<String, String> = emptyMap(),
        forkSession: Boolean = true,
        takeOver: Boolean = true,
        onExit: () -> Unit = {},
    ): CodexBackend {
        val b = CodexBackend(null, threadTitles = { titles })
        b.attach(
            AgentIo(writeLine = { w += it }, emit = {}, requestProcessExit = onExit),
            AgentSpec(Path.of("/repo"), resumeId = "thr-desktop", forkSession = forkSession, takeOver = takeOver),
        )
        b.parse(initResponse(1))
        return b
    }

    @Test
    fun a_take_over_branch_with_a_new_id_is_named_once_after_its_fork() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)

        val ev = b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))

        assertEquals("thr-phone", assertIs<AgentEvent.SessionInit>(ev.single()).sessionId)
        val set = nameSets(w).single()
        assertEquals("thr-phone", param(set, "threadId"), "the BRANCH is renamed, never the parent")
        assertEquals("CC Pocket · Refactor login", param(set, "name"))
    }

    @Test
    fun the_branch_is_named_before_the_buffered_first_prompt_and_never_blocks_it() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)
        b.sendPrompt("continue here", emptyList()) // buffered — the thread isn't open yet

        b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))

        val nameAt = w.indexOfFirst { "thread/name/set" in it }
        val startAt = w.indexOfFirst { "\"method\":\"turn/start\"" in it }
        assertTrue(nameAt in 0 until startAt, w.joinToString("\n"))
        assertTrue("continue here" in w[startAt], w[startAt])
    }

    @Test
    fun a_repeated_thread_ready_for_the_same_branch_does_not_rename_twice() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)
        b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))

        // the backup path: thread/started can arrive for a thread whose response already landed
        b.parse("""{"method":"thread/started","params":{"thread":{"id":"thr-phone","name":"Refactor login"}}}""")

        assertEquals(1, nameSets(w).size, w.joinToString("\n"))
    }

    @Test
    fun a_fork_that_resolves_to_the_same_id_is_never_renamed() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)

        // no new row in the sidebar → nothing to tell apart, and renaming would rewrite the user's session
        b.parse(threadResponse(2, "thr-desktop", name = "Refactor login"))

        assertTrue(nameSets(w).isEmpty(), w.joinToString("\n"))
    }

    @Test
    fun an_in_place_take_over_resume_is_never_renamed() = runBlocking {
        val w = mutableListOf<String>()
        // the registry found no live external writer → resume in place, same id, one session
        val b = takeOver(w, forkSession = false)
        assertTrue(w.any { "\"method\":\"thread/resume\"" in it }, w.joinToString("\n"))

        b.parse(threadResponse(2, "thr-desktop", name = "Refactor login"))

        assertTrue(nameSets(w).isEmpty(), w.joinToString("\n"))
    }

    @Test
    fun a_fork_that_is_not_a_take_over_is_never_renamed() = runBlocking {
        val w = mutableListOf<String>()
        // e.g. a relaunch that forks off a foreign id — the user never asked to seize a desktop session
        val b = takeOver(w, takeOver = false)

        b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))

        assertTrue(nameSets(w).isEmpty(), w.joinToString("\n"))
    }

    @Test
    fun a_rejected_rename_still_opens_the_session_and_lets_the_first_prompt_through() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)
        val init = b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))
        assertIs<AgentEvent.SessionInit>(init.single())

        // an app-server without thread/name/set answers with a correlated error
        val ev = b.parse("""{"id":${requestId(nameSets(w).single())},"error":{"code":-32601,"message":"method not found"}}""")

        assertTrue(ev.isEmpty(), "a cosmetic rename failure must not reach the chat: $ev")
        b.sendPrompt("still works", emptyList())
        assertTrue(w.any { "\"method\":\"turn/start\"" in it && "still works" in it }, w.joinToString("\n"))
    }

    @Test
    fun a_rename_that_never_answers_does_not_hold_the_writer() = runBlocking {
        val w = mutableListOf<String>()
        var exits = 0
        val b = takeOver(w, onExit = { exits += 1 })
        b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))
        assertEquals(1, nameSets(w).size)

        b.sendPrompt("go", emptyList()) // → turn/start (id 4; the rename took id 3)
        b.parse("""{"method":"turn/started","params":{"threadId":"thr-phone","turn":{"id":"t1"}}}""")
        b.parse("""{"id":${requestId(w.last { "turn/start" in it })},"result":{}}""")
        b.parse("""{"method":"turn/completed","params":{"threadId":"thr-phone","turn":{"id":"t1","status":"completed"}}}""")

        // the rename response never came; the one-shot hand-back that frees the rollout for ChatGPT must
        // happen anyway — it is gated on turns and controls, never on a name
        assertEquals(1, exits, w.joinToString("\n"))
    }

    @Test
    fun the_name_updated_notification_is_tolerated() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)
        b.parse(threadResponse(2, "thr-phone", name = "Refactor login", forkedFrom = "thr-desktop"))

        val ev = b.parse(
            """{"method":"thread/name/updated","params":{"threadId":"thr-phone","threadName":"CC Pocket · Refactor login"}}""",
        )

        assertTrue(ev.isEmpty(), ev.toString())
    }

    @Test
    fun a_branch_of_an_already_prefixed_session_is_not_prefixed_twice() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)

        b.parse(threadResponse(2, "thr-phone", name = "CC Pocket · Refactor login", forkedFrom = "thr-desktop"))

        val name = param(nameSets(w).single(), "name")
        assertTrue(name.startsWith("CC Pocket · Refactor login"), name)
        assertTrue("CC Pocket · CC Pocket" !in name, name)
        // identical to its parent would be the same ambiguity again → the time marker separates them
        assertTrue(Regex("""^CC Pocket · Refactor login · \d{2}:\d{2}$""").matches(name), name)
    }

    @Test
    fun a_nameless_fork_falls_back_to_the_title_cc_pocket_already_knows() = runBlocking {
        val w = mutableListOf<String>()
        // Codex answered with no name (older build / never-named thread); session_index.jsonl has one
        val b = takeOver(w, titles = mapOf("thr-desktop" to "Ship the relay fix"))

        b.parse(threadResponse(2, "thr-phone", name = null, forkedFrom = "thr-desktop"))

        assertEquals("CC Pocket · Ship the relay fix", param(nameSets(w).single(), "name"))
    }

    @Test
    fun with_no_name_anywhere_the_branch_still_points_back_at_its_parent() = runBlocking {
        val w = mutableListOf<String>()
        val b = takeOver(w)

        b.parse(threadResponse(2, "thr-phone", name = null, forkedFrom = "thr-desktop"))

        // "CC Pocket ·" alone on every branch would be its own ambiguity; the parent's id prefix is traceable
        assertEquals("CC Pocket · thr-desk", param(nameSets(w).single(), "name"))
    }

    // ---- the naming rule itself (pure, so the collision + elision cases don't need a wire round trip) ----

    private val hk: ZoneId = ZoneId.of("Asia/Shanghai")
    private val at2333 = 1_757_000_000_000L // 2025-09-04 23:33:20 +08:00

    @Test
    fun a_second_branch_of_the_same_parent_is_separated_by_a_time_marker() {
        val first = CodexTakeoverLineage.nameFor("p1", "Refactor login", null, at2333, zone = hk)
        CodexTakeoverLineage.note("p1", "c1", first, at2333)

        val second = CodexTakeoverLineage.nameFor("p1", "Refactor login", null, at2333, zone = hk)

        assertEquals("CC Pocket · Refactor login", first)
        assertEquals("CC Pocket · Refactor login · 23:33", second)
    }

    @Test
    fun an_earlier_branch_recovered_from_the_persisted_index_still_disambiguates() {
        // lineage is in-memory; after a daemon restart the earlier branch is only visible as an index name
        val name = CodexTakeoverLineage.nameFor(
            parentId = "p1",
            parentName = "Refactor login",
            indexTitle = null,
            atMs = at2333,
            taken = listOf("CC Pocket · Refactor login", "unrelated"),
            zone = hk,
        )

        assertEquals("CC Pocket · Refactor login · 23:33", name)
    }

    @Test
    fun a_very_long_parent_name_is_elided_rather_than_pasted_whole() {
        val long = "x".repeat(120)

        val name = CodexTakeoverLineage.nameFor("p1", long, null, at2333, zone = hk)

        assertEquals("CC Pocket · " + "x".repeat(60) + "…", name)
    }

    @Test
    fun a_blank_parent_name_falls_through_to_the_index_title() {
        assertEquals(
            "CC Pocket · from the index",
            CodexTakeoverLineage.nameFor("p1", "   ", "from the index", at2333, zone = hk),
        )
    }

    @Test
    fun the_lineage_record_keeps_the_edge_without_replacing_the_parent() {
        CodexTakeoverLineage.note("p1", "c1", "CC Pocket · a", at2333)

        val edge = CodexTakeoverLineage.children("p1").single()

        assertEquals("c1", edge.childId)
        assertEquals("p1", edge.parentId)
        assertTrue(CodexTakeoverLineage.children("c1").isEmpty(), "the branch is a leaf, not a replacement")
    }
}
