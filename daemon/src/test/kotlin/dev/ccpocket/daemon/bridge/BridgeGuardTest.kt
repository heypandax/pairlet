package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-credential constraint + rate/concurrency enforcement (issue #91 §2/§3/§7). */
class BridgeGuardTest {

    private val root = createTempDirectory("ccp-bridge-guard").toFile().canonicalFile
    private val sibling = createTempDirectory("ccp-bridge-sibling").toFile().canonicalFile

    private fun guard(max: Int = 2, opens: Int = 6, prompts: Int = 20) =
        BridgeGuard(BridgeSpec("feishu", listOf(root.path), maxSessions = max, opensPerMin = opens, promptsPerMin = prompts))

    private fun allow(v: BridgeVerdict) = assertIs<BridgeVerdict.Allow>(v)
    private fun deny(v: BridgeVerdict, code: BridgeDenyCode) {
        val d = assertIs<BridgeVerdict.Deny>(v); assertEquals(code, d.code)
    }

    @Test
    fun open_under_an_allowlisted_root_is_allowed_and_canonicalized_mode_clamped() {
        val g = guard()
        val sub = File(root, "proj").apply { mkdirs() }
        val v = g.vet(OpenSession(sub.path, mode = PermissionMode.BYPASS_PERMISSIONS, takeOver = true), now = 0, liveOwned = 0)
        val a = allow(v).frame as OpenSession
        assertEquals(sub.canonicalFile.path, a.workdir)         // canonicalized
        assertEquals(PermissionMode.DEFAULT, a.mode)            // bypass clamped away
        assertTrue(!a.takeOver)                                 // take-over stripped
    }

    @Test
    fun open_outside_the_allowlist_is_denied() {
        deny(guard().vet(OpenSession(sibling.path), now = 0), BridgeDenyCode.BAD_WORKDIR)
    }

    @Test
    fun open_cannot_escape_the_root_via_dotdot() {
        val escape = File(root, "../ccp-bridge-sibling").path // canonicalizes OUT of root
        deny(guard().vet(OpenSession(escape), now = 0), BridgeDenyCode.BAD_WORKDIR)
    }

    @Test
    fun a_sibling_root_prefix_is_not_mistaken_for_under_root() {
        // path-segment aware: "<root>x" must NOT be treated as under "<root>"
        val g = BridgeGuard(BridgeSpec("b", listOf(root.path)))
        deny(g.vet(OpenSession(root.path + "x"), now = 0), BridgeDenyCode.BAD_WORKDIR)
    }

    @Test
    fun resume_is_denied_unless_the_session_is_this_bridge_s_own() {
        val g = guard()
        deny(g.vet(OpenSession(root.path, resumeId = "someone-elses"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        // after opening a convo and learning its sessionId, resuming THAT id is allowed
        val opened = allow(g.vet(OpenSession(root.path), now = 0)).frame as OpenSession
        g.noteOpened("convo-1"); g.noteSession("convo-1", "sid-own")
        allow(g.vet(OpenSession(root.path, resumeId = "sid-own"), now = 0, liveOwned = 0))
        assertEquals(root.path, opened.workdir)
    }

    @Test
    fun prompt_close_cancel_are_denied_for_a_convo_the_bridge_does_not_own() {
        val g = guard()
        deny(g.vet(SendPrompt("foreign", "hi"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        deny(g.vet(CloseSession("foreign"), now = 0), BridgeDenyCode.NOT_OWN_SESSION)
        // its own convo: allowed, and CloseSession.force is forced false
        g.noteOpened("mine")
        allow(g.vet(SendPrompt("mine", "hi"), now = 0))
        val c = allow(g.vet(CloseSession("mine", force = true), now = 0)).frame as CloseSession
        assertTrue(!c.force)
    }

    @Test
    fun prompt_size_and_images_are_capped() {
        val g = guard(); g.noteOpened("mine")
        deny(g.vet(SendPrompt("mine", "x".repeat(33 * 1024)), now = 0), BridgeDenyCode.PROMPT_TOO_LARGE)
        deny(g.vet(SendPrompt("mine", "hi", images = listOf(ImageData("image/png", "QQ=="))), now = 0), BridgeDenyCode.IMAGES_DENIED)
    }

    @Test
    fun concurrency_cap_counts_live_sessions_only() {
        val g = guard(max = 2)
        allow(g.vet(OpenSession(root.path), now = 0, liveOwned = 0))
        allow(g.vet(OpenSession(root.path), now = 0, liveOwned = 1))
        deny(g.vet(OpenSession(root.path), now = 0, liveOwned = 2), BridgeDenyCode.TOO_MANY_SESSIONS)
        // a reaped session frees the slot: liveOwned drops back under the cap → allowed again
        allow(g.vet(OpenSession(root.path), now = 0, liveOwned = 1))
    }

    @Test
    fun open_and_prompt_rate_limits_slide_over_a_minute() {
        val g = guard(opens = 2, prompts = 2); g.noteOpened("mine")
        allow(g.vet(OpenSession(root.path), now = 1_000, liveOwned = 0))
        allow(g.vet(OpenSession(root.path), now = 1_100, liveOwned = 0))
        deny(g.vet(OpenSession(root.path), now = 1_200, liveOwned = 0), BridgeDenyCode.OPEN_RATE)
        // 60s later the window has slid — allowed again
        allow(g.vet(OpenSession(root.path), now = 62_000, liveOwned = 0))

        allow(g.vet(SendPrompt("mine", "a"), now = 1_000))
        allow(g.vet(SendPrompt("mine", "b"), now = 1_100))
        deny(g.vet(SendPrompt("mine", "c"), now = 1_200), BridgeDenyCode.PROMPT_RATE)
    }
}
