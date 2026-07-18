package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GUEST folder-share tool path guard (issue #115 §4): a file tool whose target escapes the shared
 * root is HARD-DENIED before any ask, under every mode. Bash is deliberately NOT guarded (its targets
 * aren't statically knowable) — the owner's boundary card says as much.
 */
class PermissionBridgeGuestPathTest {
    private data class Resp(val askId: String, val allow: Boolean, val deny: String?)

    private val root = createTempDirectory("ccp-guest-path").toFile().canonicalFile

    private fun bridge(
        mode: PermissionMode = PermissionMode.DEFAULT,
        emitted: MutableList<Frame>,
        responses: MutableList<Resp>,
        scope: CoroutineScope,
    ) = PermissionBridge(
        "c1", mode, scope, { emitted += it }, mutableSetOf(),
        respond = { id, allow, _, _, _, deny -> responses += Resp(id, allow, deny) },
        pathScope = listOf(root.path),
        workdir = root.path,
    )

    private fun req(tool: String, key: String, value: String) =
        AgentEvent.ControlRequest("r", tool, buildJsonObject { put(key, value) })

    @Test
    fun a_read_inside_the_shared_root_falls_through_to_an_ask() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Read", "file_path", File(root, "a/b.txt").path))
        assertTrue(emitted.single() is PermissionAsk) // the guest is asked normally
        assertTrue(responses.isEmpty())               // NOT auto-denied
        scope.cancel()
    }

    @Test
    fun a_read_or_write_or_edit_outside_the_root_is_denied_without_an_ask() = runBlocking {
        for ((tool, key) in listOf("Read" to "file_path", "Write" to "file_path", "Edit" to "file_path", "Glob" to "path")) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
            val b = bridge(emitted = emitted, responses = responses, scope = scope)
            b.onControlRequest(req(tool, key, "/etc/passwd"))
            assertTrue(emitted.isEmpty(), "$tool must not surface an ask for an out-of-scope target")
            val r = responses.single()
            assertFalse(r.allow, "$tool out of scope must be denied")
            assertTrue(r.deny!!.contains("outside the allowed directory"))
            scope.cancel()
        }
    }

    @Test
    fun a_bridge_read_outside_the_workdir_is_denied_even_without_a_pathScope() = runBlocking {
        // issue #91 (security review H1): a bridge has NO pathScope, but its structured file tools must
        // still not escape the bound workdir — else a Read of ~/.ssh/id_rsa exfiltrates it to the chat.
        for ((tool, key) in listOf("Read" to "file_path", "Write" to "file_path", "Grep" to "path")) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
            val b = PermissionBridge(
                "c1", PermissionMode.DEFAULT, scope, { emitted += it }, mutableSetOf(),
                respond = { id, allow, _, _, _, deny -> responses += Resp(id, allow, deny) },
                pathScope = null,          // a bridge has none
                workdir = root.path,
                bridgeSession = true,      // but the workdir becomes the implicit scope
            )
            b.onControlRequest(req(tool, key, System.getProperty("user.home") + "/.ssh/id_rsa"))
            assertTrue(emitted.isEmpty(), "$tool must not even ASK for an out-of-workdir read")
            assertFalse(responses.single().allow, "$tool outside a bridge's workdir must be denied")
            scope.cancel()
        }
        // and a read INSIDE the workdir still flows to a normal ask (not auto-denied)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = PermissionBridge(
            "c1", PermissionMode.DEFAULT, scope, { emitted += it }, mutableSetOf(),
            respond = { id, allow, _, _, _, deny -> responses += Resp(id, allow, deny) },
            pathScope = null, workdir = root.path, bridgeSession = true,
        )
        b.onControlRequest(req("Read", "file_path", File(root, "src/x.kt").path))
        assertTrue(emitted.single() is PermissionAsk)
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun a_glob_with_a_relative_dotdot_pattern_is_denied_not_just_absolute() = runBlocking {
        // review N6: a relative Glob pattern with `..` walks out of the workdir but has no `path` key —
        // the old absoluteGlobRoot returned null for it, escaping the scope guard.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = PermissionBridge(
            "c1", PermissionMode.DEFAULT, scope, { emitted += it }, mutableSetOf(),
            respond = { id, allow, _, _, _, deny -> responses += Resp(id, allow, deny) },
            pathScope = null, workdir = root.path, bridgeSession = true,
        )
        for (pattern in listOf("../../../../etc/ssh/*_key", "*/../../../etc/ssh/*")) { // .. leading AND past a wildcard
            val s = CoroutineScope(Dispatchers.Unconfined)
            val em = mutableListOf<Frame>(); val rs = mutableListOf<Resp>()
            val bb = PermissionBridge(
                "c1", PermissionMode.DEFAULT, s, { em += it }, mutableSetOf(),
                respond = { id, allow, _, _, _, deny -> rs += Resp(id, allow, deny) },
                pathScope = null, workdir = root.path, bridgeSession = true,
            )
            bb.onControlRequest(req("Glob", "pattern", pattern))
            assertTrue(em.isEmpty(), "a ../-escaping glob must not surface an ask: $pattern")
            assertFalse(rs.single().allow, "a ../-escaping glob must be denied: $pattern")
            s.cancel()
        }
        // an in-workdir glob is untouched
        val s2 = CoroutineScope(Dispatchers.Unconfined)
        val em2 = mutableListOf<Frame>(); val rs2 = mutableListOf<Resp>()
        val b2 = PermissionBridge(
            "c1", PermissionMode.DEFAULT, s2, { em2 += it }, mutableSetOf(),
            respond = { id, allow, _, _, _, deny -> rs2 += Resp(id, allow, deny) },
            pathScope = null, workdir = root.path, bridgeSession = true,
        )
        b2.onControlRequest(req("Glob", "pattern", "src/**/*.kt"))
        assertTrue(em2.single() is PermissionAsk, "an in-workdir glob flows to a normal ask")
        s2.cancel()
    }

    @Test
    fun a_dotdot_or_relative_escape_is_denied() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        // relative path resolved against the session workdir, then canonicalized — climbs out of the root
        b.onControlRequest(req("Read", "file_path", "../../../etc/passwd"))
        assertTrue(emitted.isEmpty())
        assertFalse(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun the_path_guard_beats_bypass_mode() = runBlocking {
        // even under bypassPermissions (which normally auto-allows) an out-of-scope file op is DENIED —
        // the guard runs before the auto-allow path
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(mode = PermissionMode.BYPASS_PERMISSIONS, emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Read", "file_path", "/etc/passwd"))
        assertFalse(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun bash_is_not_path_guarded_and_an_owner_session_has_no_guard() = runBlocking {
        // Bash: the guard cannot parse its targets, so it falls through to the normal ask (honest v1 boundary)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val guest = bridge(emitted = emitted, responses = responses, scope = scope)
        guest.onControlRequest(AgentEvent.ControlRequest("r", "Bash", buildJsonObject { put("command", "cat /etc/passwd") }))
        assertTrue(emitted.single() is PermissionAsk) // asked, not path-denied
        assertTrue(responses.isEmpty())

        // an OWNER conversation (no pathScope) never path-denies — reads anywhere fall through to an ask
        val emitted2 = mutableListOf<Frame>(); val responses2 = mutableListOf<Resp>()
        val owner = PermissionBridge("c2", PermissionMode.DEFAULT, scope, { emitted2 += it }, mutableSetOf(),
            respond = { id, allow, _, _, _, deny -> responses2 += Resp(id, allow, deny) })
        owner.onControlRequest(req("Read", "file_path", "/etc/passwd"))
        assertTrue(emitted2.single() is PermissionAsk)
        assertTrue(responses2.isEmpty())
        scope.cancel()
    }

    @Test
    fun a_file_tool_with_no_path_arg_is_not_denied() = runBlocking {
        // Glob without a `path` searches the cwd (in scope) — must not be falsely denied
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(AgentEvent.ControlRequest("r", "Glob", buildJsonObject { put("pattern", "**/*.kt") }))
        assertTrue(emitted.single() is PermissionAsk)
        assertNull(responses.firstOrNull()?.deny)
        scope.cancel()
    }

    @Test
    fun every_guarded_file_tool_denies_an_out_of_scope_target() = runBlocking {
        // pin the FULL guarded set (ToolMetadata.PATH_TOOLS), not just Read/Write/Edit/Glob — a new file
        // tool added to the set without a test would otherwise silently escape coverage. NotebookEdit is the
        // only carrier of the `notebook_path` key; MultiEdit/Grep exercise the other two key names.
        for ((tool, key) in listOf("MultiEdit" to "file_path", "NotebookEdit" to "notebook_path", "Grep" to "path")) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
            val b = bridge(emitted = emitted, responses = responses, scope = scope)
            b.onControlRequest(req(tool, key, "/etc/hosts"))
            assertTrue(emitted.isEmpty(), "$tool must not surface an ask for an out-of-scope target")
            val r = responses.single()
            assertFalse(r.allow, "$tool out of scope must be denied")
            assertTrue(r.deny!!.contains("outside the allowed directory"))
            scope.cancel()
        }
    }

    @Test
    fun an_unlisted_future_file_tool_carrying_a_path_is_still_confined() = runBlocking {
        // M1 (crypto review): confinement is by path-KEY, not a hard-coded tool-name whitelist, so a file
        // tool the CLI adds/renames later (here a hypothetical "ReadMany" carrying file_path) is denied when
        // out of scope instead of slipping the guard and falling through to an ask the guest self-approves.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("ReadMany", "file_path", "/etc/shadow"))
        assertTrue(emitted.isEmpty(), "an unlisted file tool must not surface an ask for an out-of-scope target")
        assertFalse(responses.single().allow)
        assertTrue(responses.single().deny!!.contains("outside the allowed directory"))
        scope.cancel()
    }

    @Test
    fun a_symlink_pointing_out_of_the_root_is_denied() = runBlocking {
        // the ../-escape test only proves lexical `..` collapse; this proves the canonicalFile symlink
        // FOLLOWING the whole guard rests on. A regression from canonicalFile to absoluteFile (which does
        // NOT resolve symlinks) would pass every other test but leak here — this is the one that catches it.
        val link = File(root, "out")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File("/etc").toPath())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Read", "file_path", File(link, "hosts").path)) // root/out/hosts → /etc/hosts
        assertTrue(emitted.isEmpty())
        assertFalse(responses.single().allow)
        assertTrue(responses.single().deny!!.contains("outside the allowed directory"))
        scope.cancel()
    }

    @Test
    fun a_prefix_sibling_directory_is_out_of_scope() = runBlocking {
        // path-SEGMENT containment: "<root>-secret" shares a string prefix with "<root>" but is NOT under it.
        // A raw startsWith(root) (no separator) would wrongly admit it — this pins the separator boundary.
        val sibling = File(root.path + "-secret").apply { mkdirs() }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Read", "file_path", File(sibling, "secrets.txt").path))
        assertTrue(emitted.isEmpty())
        assertFalse(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun an_absolute_glob_pattern_escaping_the_root_is_denied() = runBlocking {
        // followup-115-h1: Glob's reach can ride ENTIRELY in `pattern` (no `path` key). An ABSOLUTE glob
        // like `/etc/**` used to yield empty pathTargets → fall through to an ask the guest self-approves,
        // reading the owner's whole /etc. Now the pattern's literal root is mined and containment-checked.
        for (pattern in listOf("/etc/**", "/etc/pass*", "/var/log/**/*.log", "/**")) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
            val b = bridge(emitted = emitted, responses = responses, scope = scope)
            b.onControlRequest(req("Glob", "pattern", pattern))
            assertTrue(emitted.isEmpty(), "Glob pattern '$pattern' must not surface an ask (out of scope)")
            val r = responses.single()
            assertFalse(r.allow, "Glob '$pattern' out of scope must be denied")
            assertTrue(r.deny!!.contains("outside the allowed directory"))
            scope.cancel()
        }
    }

    @Test
    fun an_absolute_glob_pattern_inside_the_root_falls_through_to_an_ask() = runBlocking {
        // the in-scope counterpart: an absolute pattern rooted INSIDE the shared folder is legitimate and
        // must NOT be path-denied — it reaches an ordinary ask like any in-scope file op.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Glob", "pattern", File(root, "src/**/*.kt").path))
        assertTrue(emitted.single() is PermissionAsk)
        assertNull(responses.firstOrNull()?.deny)
        scope.cancel()
    }

    @Test
    fun a_relative_glob_pattern_is_not_treated_as_an_escape() = runBlocking {
        // a RELATIVE pattern resolves under the (in-scope) session cwd, so it must fall through to an ask,
        // NOT be mined as an absolute target. Guards against over-eager denial that would break Glob for guests.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Glob", "pattern", "**/*.kt"))
        assertTrue(emitted.single() is PermissionAsk)
        assertNull(responses.firstOrNull()?.deny)
        scope.cancel()
    }

    @Test
    fun a_dotdot_absolute_glob_pattern_is_canonicalized_and_denied() = runBlocking {
        // a `..` inside an absolute pattern climbs out after the prefix is backed off to a directory and
        // canonicalized — proves the pattern root rides the SAME canonical containment as keyed paths.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Glob", "pattern", File(root, "../*.txt").path)) // root/../*.txt → parent of root
        assertTrue(emitted.isEmpty())
        assertFalse(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun a_write_to_a_new_file_inside_the_root_is_allowed() = runBlocking {
        // a not-yet-existing leaf under the root (canonicalizes via the existing prefix) must NOT be
        // false-denied — a guest creating a file in the shared folder is the common case
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emitted = mutableListOf<Frame>(); val responses = mutableListOf<Resp>()
        val b = bridge(emitted = emitted, responses = responses, scope = scope)
        b.onControlRequest(req("Write", "file_path", File(root, "sub/brand-new.txt").path))
        assertTrue(emitted.single() is PermissionAsk) // falls through to a normal ask, not path-denied
        assertNull(responses.firstOrNull()?.deny)
        scope.cancel()
    }
}
