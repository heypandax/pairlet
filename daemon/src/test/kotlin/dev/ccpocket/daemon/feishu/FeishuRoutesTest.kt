package dev.ccpocket.daemon.feishu

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built-in Feishu bridge's chat-side logic — routing, /commands, admin gating. Pure, no Feishu. */
class FeishuRoutesTest {
    private val tmp: File = Files.createTempDirectory("ccp-feishu").toFile()
    private val routesFile = File(tmp, "routes.json")
    private val workdirs = listOf("/p/alpha", "/p/Beta", "/p/gamma/")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    // ── project name resolution (parity with the python adapter's resolve_project) ──

    @Test
    fun project_names_are_basenames_and_resolution_is_exact_then_unique_fold() {
        assertEquals("alpha", FeishuRoutes.projectName("/p/alpha"))
        assertEquals("gamma", FeishuRoutes.projectName("/p/gamma/"))
        assertEquals("/p/alpha", FeishuRoutes.resolveProject("alpha", workdirs))
        assertEquals("/p/Beta", FeishuRoutes.resolveProject("beta", workdirs))   // unique case-fold
        assertEquals("/p/alpha", FeishuRoutes.resolveProject("  alpha  ", workdirs))
        assertNull(FeishuRoutes.resolveProject("nope", workdirs))
        assertNull(FeishuRoutes.resolveProject("", workdirs))
        // ambiguous case-fold must FAIL, never pick for the user
        assertNull(FeishuRoutes.resolveProject("dup", listOf("/a/DUP", "/b/Dup")))
    }

    // ── the routes table ──

    @Test
    fun routes_persist_atomically_at_0600_and_survive_reload() {
        val r = FeishuRoutes(routesFile)
        r.bind("oc_1", "/p/alpha"); r.bind("oc_2", "/p/alpha"); r.bind("oc_3", "/p/Beta")
        assertEquals("/p/alpha", r.workdirFor("oc_1"))
        assertEquals(2, r.chatsFor("/p/alpha"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(routesFile.toPath())))

        val reloaded = FeishuRoutes(routesFile)
        assertEquals("/p/Beta", reloaded.workdirFor("oc_3"))
        assertTrue(reloaded.unbind("oc_3"))
        assertFalse(reloaded.unbind("oc_3"))
        assertNull(FeishuRoutes(routesFile).workdirFor("oc_3"))
    }

    @Test
    fun a_corrupt_routes_table_fails_engine_start_not_silently_empty() {
        routesFile.writeText("{ broken")
        val boom = runCatching { FeishuRoutes(routesFile) }
        assertTrue(boom.isFailure, "corrupt table must throw — an empty table would read as 'all chats unbound'")
    }

    // ── the command surface ──

    private fun commands(admin: String? = "ou_admin", r: FeishuRoutes = FeishuRoutes(routesFile)) =
        r to FeishuCommands(r, workdirs, admin)

    @Test
    fun a_plain_prompt_in_a_bound_chat_asks_and_in_an_unbound_chat_teaches() {
        val (r, c) = commands()
        // multi-project: no auto-bind possible — teach with the project list
        assertIs<ChatAction.Reply>(c.handle("帮我看下 git status", "oc_x", "ou_someone")).let {
            assertTrue("还没有绑定项目" in it.text)
            assertTrue("alpha" in it.text, "the teach text must list the actual projects: ${it.text}")
        }
        r.bind("oc_x", "/p/alpha")
        val ask = assertIs<ChatAction.Ask>(c.handle("帮我看下 git status", "oc_x", "ou_someone"))
        assertEquals("/p/alpha", ask.workdir)
        assertEquals("帮我看下 git status", ask.prompt)
        assertNull(ask.note, "an already-bound chat must not re-announce a bind")
    }

    @Test
    fun single_project_auto_binds_for_the_admin_and_only_the_admin() {
        val single = listOf("/p/alpha")
        // the admin's first message in an unbound chat: bind + proceed, with immediate feedback
        val r1 = FeishuRoutes(File(tmp, "r1.json"))
        val c1 = FeishuCommands(r1, single, "ou_admin")
        val ask = assertIs<ChatAction.Ask>(c1.handle("你好", "oc_1", "ou_admin"))
        assertEquals("/p/alpha", ask.workdir)
        assertTrue("自动" in (ask.note ?: ""), "auto-bind must announce itself: ${ask.note}")
        assertEquals("/p/alpha", r1.workdirFor("oc_1"), "the bind must persist")

        // anyone else: inert, but the hint is copy-paste ready
        val r2 = FeishuRoutes(File(tmp, "r2.json"))
        val c2 = FeishuCommands(r2, single, "ou_admin")
        val teach = assertIs<ChatAction.Reply>(c2.handle("你好", "oc_2", "ou_stranger"))
        assertTrue("/bind alpha" in teach.text, teach.text)
        assertNull(r2.workdirFor("oc_2"), "a stranger's message must not bind anything")

        // admin unset: inert too — nobody has proven ownership, auto-binding would hand it to whoever speaks first
        val r3 = FeishuRoutes(File(tmp, "r3.json"))
        val c3 = FeishuCommands(r3, single, null)
        assertIs<ChatAction.Reply>(c3.handle("你好", "oc_3", "ou_whoever"))
        assertNull(r3.workdirFor("oc_3"))
    }

    @Test
    fun bind_is_admin_only_and_bootstraps_by_echoing_the_caller_open_id_when_unset() {
        // admin unset: refuse AND hand back the sender's own open_id — the only way they can learn it
        val (r0, c0) = commands(admin = null)
        val boot = assertIs<ChatAction.Reply>(c0.handle("/bind alpha", "oc_1", "ou_caller_9"))
        assertTrue("ou_caller_9" in boot.text, boot.text)
        assertNull(r0.workdirFor("oc_1"), "a refused bind must not persist")

        // admin set: others refused, admin binds; case-insensitive resolution; rebind switches
        val (r, c) = commands()
        assertTrue("只有管理员" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_other")).text)
        assertTrue("已绑定" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_admin")).text)
        assertEquals("/p/alpha", r.workdirFor("oc_1"))
        c.handle("/bind BETA", "oc_1", "ou_admin")
        assertEquals("/p/Beta", r.workdirFor("oc_1"))
        // unknown project: helpful, not bound
        val miss = assertIs<ChatAction.Reply>(c.handle("/bind nope", "oc_2", "ou_admin"))
        assertTrue("找不到项目" in miss.text && "alpha" in miss.text)
        assertNull(r.workdirFor("oc_2"))
        // unbind
        assertTrue("已解绑" in assertIs<ChatAction.Reply>(c.handle("/unbind", "oc_1", "ou_admin")).text)
        assertNull(r.workdirFor("oc_1"))
    }

    @Test
    fun bind_falls_back_to_the_group_owner_when_no_admin_is_set() {
        // no admin configured, but the Feishu group owner is known → the owner (and only the owner) can bind
        val r = FeishuRoutes(routesFile)
        val c = FeishuCommands(r, workdirs, adminOpenId = null, chatOwnerOf = { if (it == "oc_1") "ou_owner" else null })
        // a non-owner is refused, and nothing persists
        assertTrue("只有管理员或群主" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_stranger")).text)
        assertNull(r.workdirFor("oc_1"))
        // the group owner binds
        assertTrue("已绑定" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_owner")).text)
        assertEquals("/p/alpha", r.workdirFor("oc_1"))
        // owner not resolved yet (cache miss) → "confirming, retry" + echo the caller id, not a silent refuse
        val pending = assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_2", "ou_someone")).text
        assertTrue("确认" in pending && "ou_someone" in pending, pending)
    }

    @Test
    fun an_explicit_admin_wins_over_the_group_owner() {
        // admin set AND a group owner known: the owner must NOT be able to bind — the designated admin wins
        val r = FeishuRoutes(routesFile)
        val c = FeishuCommands(r, workdirs, adminOpenId = "ou_admin", chatOwnerOf = { "ou_owner" })
        assertTrue("只有管理员或群主" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_owner")).text)
        assertNull(r.workdirFor("oc_1"))
        assertTrue("已绑定" in assertIs<ChatAction.Reply>(c.handle("/bind alpha", "oc_1", "ou_admin")).text)
    }

    @Test
    fun projects_lists_basenames_with_bind_counts_and_never_absolute_paths() {
        val (r, c) = commands()
        r.bind("oc_1", "/p/alpha")
        val out = assertIs<ChatAction.Reply>(c.handle("/projects", "oc_9", "ou_x")).text
        assertTrue("alpha" in out && "Beta" in out && "gamma" in out, out)
        assertTrue("已绑 1 个群" in out, out)
        assertFalse("/p/alpha" in out, "chat-facing text must not leak machine paths: $out")
    }

    @Test
    fun help_answers_with_usage() {
        val (_, c) = commands()
        assertTrue("用法" in assertIs<ChatAction.Reply>(c.handle("/help", "oc_1", "ou_x")).text)
    }

    @Test
    fun a_non_bridge_slash_passes_through_to_the_bound_session_verbatim() {
        // the walled-garden fix: /clear /compact /model /skill-name are NOT ours — they run in the bound
        // session exactly as the app sends them, instead of reporting "unknown command"
        val (r, c) = commands()
        r.bind("oc_1", "/p/alpha")
        for (cmd in listOf("/clear", "/compact", "/model opus", "/deep-research something")) {
            val a = assertIs<ChatAction.Ask>(c.handle(cmd, "oc_1", "ou_x"), "$cmd should pass through")
            assertEquals("/p/alpha", a.workdir)
            assertEquals(cmd, a.prompt, "the slash command reaches the session verbatim")
        }
        // ...but an unbound chat can't run one — it teaches instead
        assertIs<ChatAction.Reply>(c.handle("/clear", "oc_unbound", "ou_x"))
    }

    @Test
    fun new_resets_the_chat_conversation() {
        val (r, c) = commands()
        r.bind("oc_1", "/p/alpha")
        assertIs<ChatAction.Reset>(c.handle("/new", "oc_1", "ou_x"))
        assertIs<ChatAction.Reset>(c.handle("/reset", "oc_1", "ou_x"))
        // still bound after a reset — /new clears context, not the binding
        assertEquals("/p/alpha", r.workdirFor("oc_1"))
        // unbound chat: /new has nothing to reset
        assertIs<ChatAction.Reply>(c.handle("/new", "oc_unbound", "ou_x"))
    }
}
