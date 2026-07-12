package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SwitchMode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-guest scope + tier + expiry + rate enforcement (issue #115). Mirrors BridgeGuardTest. */
class GuestGuardTest {

    private val root = createTempDirectory("ccp-guest-guard").toFile().canonicalFile
    private val sibling = createTempDirectory("ccp-guest-sibling").toFile().canonicalFile

    private fun guard(tier: AccessTier = AccessTier.COLLABORATE, expiresAt: Long = FUTURE, seed: Set<String> = emptySet()) =
        GuestGuard(BridgeSpec.guest("alex", root.path, tier, expiresAt), seedSessions = seed)

    private fun allow(v: BridgeVerdict) = assertIs<BridgeVerdict.Allow>(v)
    private fun deny(v: BridgeVerdict, code: BridgeDenyCode) {
        val d = assertIs<BridgeVerdict.Deny>(v); assertEquals(code, d.code)
    }

    @Test
    fun open_under_the_shared_root_is_allowed_canonicalized_and_tier_clamped() {
        val g = guard(tier = AccessTier.REVIEW) // ceiling DEFAULT
        val sub = File(root, "proj").apply { mkdirs() }
        val a = allow(g.vet(OpenSession(sub.path, mode = PermissionMode.BYPASS_PERMISSIONS, takeOver = true), now = 0)).frame as OpenSession
        assertEquals(sub.canonicalFile.path, a.workdir)     // canonicalized
        assertEquals(PermissionMode.DEFAULT, a.mode)        // clamped to the Review ceiling (never bypass)
        assertTrue(!a.takeOver)                             // take-over stripped
    }

    @Test
    fun open_outside_the_shared_root_or_escaping_via_dotdot_is_denied() {
        deny(guard().vet(OpenSession(sibling.path), now = 0), BridgeDenyCode.BAD_WORKDIR)
        val escape = File(root, "../ccp-guest-sibling").path // canonicalizes OUT of root
        deny(guard().vet(OpenSession(escape), now = 0), BridgeDenyCode.BAD_WORKDIR)
        // path-segment aware: "<root>x" is NOT under "<root>"
        deny(guard().vet(OpenSession(root.path + "x"), now = 0), BridgeDenyCode.BAD_WORKDIR)
    }

    @Test
    fun collaborate_tier_clamps_to_acceptEdits_not_bypass() {
        val g = guard(tier = AccessTier.COLLABORATE)
        val a = allow(g.vet(OpenSession(root.path, mode = PermissionMode.BYPASS_PERMISSIONS), now = 0)).frame as OpenSession
        assertEquals(PermissionMode.ACCEPT_EDITS, a.mode)
    }

    @Test
    fun a_guest_open_is_forced_to_the_claude_backend() {
        // Codex has its own MCP/config the clean-room does not strip in v1, so a guest can't drive it
        val g = guard()
        val a = allow(g.vet(OpenSession(root.path, agent = dev.ccpocket.protocol.AgentKind.CODEX), now = 0)).frame as OpenSession
        assertEquals(dev.ccpocket.protocol.AgentKind.CLAUDE, a.agent)
    }

    @Test
    fun resume_switchMode_prompt_verdict_are_own_session_only() {
        val g = guard()
        // resume a session the guest doesn't own → denied
        deny(g.vet(OpenSession(root.path, resumeId = "owners-session"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        // frames for a convo the guest never opened → denied (verdict is the key one — a guest must not
        // approve another session's ask)
        deny(g.vet(PermissionVerdict("foreign", "a", Decision.ALLOW), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(SendPrompt("foreign", "hi"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(SwitchMode("foreign", PermissionMode.PLAN), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(ClearAllowRule("foreign"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(CancelTurn("foreign"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        // its OWN convo: allowed; switchMode is tier-clamped; close.force forced false
        g.noteOpened("mine"); g.noteSession("mine", "sid-own")
        allow(g.vet(PermissionVerdict("mine", "a", Decision.ALLOW), now = 0))
        allow(g.vet(SendPrompt("mine", "hi"), now = 0))
        val sm = allow(g.vet(SwitchMode("mine", PermissionMode.BYPASS_PERMISSIONS), now = 0)).frame as SwitchMode
        assertEquals(PermissionMode.ACCEPT_EDITS, sm.mode) // clamped under Collaborate
        val c = allow(g.vet(CloseSession("mine", force = true), now = 0)).frame as CloseSession
        assertTrue(!c.force)
        // resume of a NOW-owned session id is allowed
        allow(g.vet(OpenSession(root.path, resumeId = "sid-own"), now = 0))
    }

    @Test
    fun session_reads_require_the_root_and_an_owned_session() {
        val g = guard()
        g.noteOpened("mine"); g.noteSession("mine", "sid-own")
        // workdir outside the scope → denied even for an owned session
        deny(g.vet(ReadFile(sibling.path, "sid-own", "x"), now = 0), BridgeDenyCode.BAD_WORKDIR)
        // in-scope workdir but a session the guest doesn't own → denied (can't read owner-session files)
        deny(g.vet(ReadFile(root.path, "owners-session", "x"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(ListSessionFiles(root.path, "owners-session"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        // in-scope workdir + owned session → allowed
        allow(g.vet(ReadFile(root.path, "sid-own", "x"), now = 0))
        allow(g.vet(ListSessionFiles(root.path, "sid-own"), now = 0))
    }

    @Test
    fun listSessions_requires_scope_and_listDirectories_is_always_allowed() {
        val g = guard()
        deny(g.vet(ListSessions(sibling.path), now = 0), BridgeDenyCode.BAD_WORKDIR)
        allow(g.vet(ListSessions(root.path), now = 0))
        // ListDirectories carries no workdir — always admitted; the ROUTER scopes the response to the root
        allow(g.vet(ListDirectories(root = "/"), now = 0))
    }

    @Test
    fun seeded_owned_sessions_survive_a_restart_for_read_and_resume() {
        // a guard reconstructed from the persisted ledger recognises the guest's historical sessions
        val g = guard(seed = setOf("sid-from-disk"))
        allow(g.vet(ReadFile(root.path, "sid-from-disk", "x"), now = 0))
        allow(g.vet(OpenSession(root.path, resumeId = "sid-from-disk"), now = 0))
    }

    @Test
    fun an_expired_share_denies_every_frame() {
        val g = guard(expiresAt = 1_000)
        g.noteOpened("mine")
        deny(g.vet(OpenSession(root.path), now = 2_000), BridgeDenyCode.SHARE_EXPIRED)
        deny(g.vet(SendPrompt("mine", "hi"), now = 2_000), BridgeDenyCode.SHARE_EXPIRED)
        deny(g.vet(ListSessions(root.path), now = 2_000), BridgeDenyCode.SHARE_EXPIRED)
        // still valid just before expiry
        allow(g.vet(SendPrompt("mine", "hi"), now = 500))
    }

    @Test
    fun images_are_allowed_for_a_guest_unlike_a_bridge_but_prompt_size_is_capped() {
        val g = guard(); g.noteOpened("mine")
        allow(g.vet(SendPrompt("mine", "hi", images = listOf(ImageData("image/png", "QQ=="))), now = 0))
        deny(g.vet(SendPrompt("mine", "x".repeat(65 * 1024)), now = 0), BridgeDenyCode.PROMPT_TOO_LARGE)
    }

    @Test
    fun concurrency_and_rate_limits_apply() {
        val g = GuestGuard(BridgeSpec.guest("a", root.path, AccessTier.COLLABORATE, FUTURE)
            .copy(maxSessions = 2, opensPerMin = 2, promptsPerMin = 2))
        g.noteOpened("mine")
        allow(g.vet(OpenSession(root.path), now = 1_000, liveOwned = 0))
        allow(g.vet(OpenSession(root.path), now = 1_100, liveOwned = 1))
        deny(g.vet(OpenSession(root.path), now = 1_200, liveOwned = 2), BridgeDenyCode.TOO_MANY_SESSIONS)
        // rate window (opens=2/min) is separate from concurrency
        deny(g.vet(OpenSession(root.path), now = 1_300, liveOwned = 0), BridgeDenyCode.OPEN_RATE)
        allow(g.vet(SendPrompt("mine", "a"), now = 1_000))
        allow(g.vet(SendPrompt("mine", "b"), now = 1_100))
        deny(g.vet(SendPrompt("mine", "c"), now = 1_200), BridgeDenyCode.PROMPT_RATE)
    }

    private companion object { const val FUTURE = Long.MAX_VALUE }
}
