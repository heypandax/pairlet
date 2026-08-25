package dev.ccpocket.daemon.feishu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The auto-routing seam: deterministic name matching + the router CLI's fail-soft output parsing. */
class FeishuProjectRouterTest {
    private val workdirs = listOf("/p/cc-pocket", "/p/ReleaseAdmin", "/p/app")

    // ── the zero-cost deterministic pass ──

    @Test
    fun a_message_naming_exactly_one_project_routes_without_a_model() {
        assertEquals("/p/cc-pocket", ProjectRoutePolicy.mentionedProject("cc-pocket：帮我查下 CI 为什么红", workdirs))
        assertEquals("/p/ReleaseAdmin", ProjectRoutePolicy.mentionedProject("看看 releaseadmin 的周报模块", workdirs))
    }

    @Test
    fun short_generic_names_and_ambiguous_hits_fall_through_to_the_model() {
        // "app" (len < 3 is the bar; "app" is 3 — but it hides inside other words is fine, substring hit
        // counts) — what must NOT happen is a pick when SEVERAL projects match
        assertNull(ProjectRoutePolicy.mentionedProject("帮我看下 cc-pocket 和 releaseadmin 哪个有问题", workdirs))
        // no project named at all
        assertNull(ProjectRoutePolicy.mentionedProject("帮我修个 bug", listOf("/p/alpha", "/p/beta")))
        // a 2-char basename never matches by name — too easy to hit inside unrelated text
        assertNull(ProjectRoutePolicy.mentionedProject("go 一下", listOf("/p/go", "/p/beta")))
    }

    @Test
    fun nested_project_names_do_not_pick_by_list_order() {
        // "app-server" contains "app": a message about app-server matches BOTH basenames → must fall through
        assertNull(ProjectRoutePolicy.mentionedProject("app-server 的启动脚本挂了", listOf("/p/app", "/p/app-server")))
    }

    // ── the CLI envelope parser: only schema-valid structured output counts, closed candidate set ──

    private val candidates = listOf("cc-pocket", "ReleaseAdmin")

    private fun envelope(structured: String) = """{"version":"1.0","structured_output":$structured}"""

    @Test
    fun a_valid_pick_parses_and_a_blank_project_reads_as_none() {
        val r = ClaudeFeishuProjectRouter.parseRouteOutput(
            envelope("""{"project":"cc-pocket","confidence":0.92,"reason":"关于 relay 的问题"}"""),
            candidates,
        )
        assertEquals("cc-pocket", r?.project)
        assertEquals(0.92, r?.confidence)
        val none = ClaudeFeishuProjectRouter.parseRouteOutput(
            envelope("""{"project":"","confidence":0.2,"reason":"太模糊"}"""),
            candidates,
        )
        assertNull(none?.project, "empty project means the model declined to pick")
        assertEquals(0.2, none?.confidence)
    }

    @Test
    fun a_project_outside_the_candidate_set_invalidates_the_whole_output() {
        // the model picks from a CLOSED set or not at all — an invented name must not survive parsing
        assertNull(
            ClaudeFeishuProjectRouter.parseRouteOutput(
                envelope("""{"project":"evil-project","confidence":0.99,"reason":"x"}"""),
                candidates,
            ),
        )
    }

    @Test
    fun drifted_output_shapes_all_fail_soft_to_null() {
        for (bad in listOf(
            "not json at all",
            """{"no_structured_output":true}""",
            envelope("""{"project":"cc-pocket","confidence":1.5,"reason":"x"}"""),      // out-of-range
            envelope("""{"project":"cc-pocket","confidence":"high","reason":"x"}"""),   // wrong type
            envelope("""{"project":"cc-pocket","confidence":0.9}"""),                    // missing field
            envelope("""{"project":42,"confidence":0.9,"reason":"x"}"""),                // wrong type
        )) {
            assertNull(ClaudeFeishuProjectRouter.parseRouteOutput(bad, candidates), "should reject: $bad")
        }
    }

    // ── the stdin payload: requester text rides ONLY under UNTRUSTED_DATA ──

    @Test
    fun payload_labels_the_prompt_untrusted_and_carries_candidates_and_continuation_anchor() {
        val p = ClaudeFeishuProjectRouter.payload(
            ProjectRouteInput(
                prompt = "帮我看下相机权限的崩溃",
                candidates = listOf(ProjectCandidate("cc-pocket", "手机驱动本机 Claude Code 的伴侣工具")),
                chatName = "研发效率群",
                currentProject = "cc-pocket",
            ),
        )
        assertTrue(""""UNTRUSTED_DATA":{"prompt":"帮我看下相机权限的崩溃"}""" in p, p)
        assertTrue(""""current_project":"cc-pocket"""" in p, p)
        assertTrue(""""chat_name":"研发效率群"""" in p, p)
        assertTrue(""""name":"cc-pocket"""" in p, p)
    }

    @Test
    fun router_argv_is_toolless_mcpless_and_schema_bound() {
        val argv = ClaudeFeishuProjectRouter.buildArgv("/bin/claude")
        assertTrue("--tools=" in argv)
        assertTrue("--strict-mcp-config" in argv)
        assertTrue("--safe-mode" in argv)
        assertTrue("--no-session-persistence" in argv)
        assertTrue(argv.contains("--json-schema"))
    }
}
