package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.RevokeShare
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security-core contract for issue #115: the GUEST capability whitelists. DELIBERATELY EXHAUSTIVE
 * over the sealed [Frame] hierarchy — a message type added to the protocol later shows up here as a
 * failing assertion (it defaults to DENIED), forcing a conscious decision before any new capability
 * leaks to a scoped guest. A guest is wider than a bridge (it approves its own asks, browses its scope)
 * but the whole management plane stays denied.
 */
class GuestCapsTest {

    private fun leaves(k: kotlin.reflect.KClass<*>): List<kotlin.reflect.KClass<*>> =
        if (k.sealedSubclasses.isEmpty()) listOf(k) else k.sealedSubclasses.flatMap { leaves(it) }

    @Test
    fun guest_ingress_whitelist_is_exactly_the_interactive_scoped_set() {
        // the ONLY request types a guest may send — locked as an exact serial-name set so widening it is
        // a visible diff. Crucially includes pocket/verdict (a guest answers its OWN asks) and the scoped
        // list/read surfaces, but NEVER the management plane (auth / usage / push.prefs / shell.run /
        // switchDir / the share.* control plane a guest could use to re-share the machine).
        val allowed = setOf(
            "pocket/session.open", "pocket/prompt", "pocket/turn.cancel", "pocket/session.close",
            "pocket/verdict", "pocket/mode.switch", "pocket/rule.clear", "pocket/job.stop",
            "pocket/dirs.list", "pocket/sessions.list", "pocket/path.list", "pocket/files.list",
            "pocket/file.read", "pocket/diff.read", "pocket/audio.chunk", "pocket/audio.cancel",
        )
        val toDaemon = leaves(ToDaemon::class)
        assertTrue(toDaemon.size >= 20, "sanity: found ${toDaemon.size} ToDaemon leaves — reflection wired up")
        for (cls in toDaemon) {
            val inst = instantiate(cls) as Frame
            val expected = serialNameOf(cls) in allowed
            assertEquals(expected, GuestCaps.ingressAllowed(inst), "ingressAllowed(${cls.simpleName}) should be $expected")
        }
    }

    @Test
    fun guest_can_never_send_management_plane_frames() {
        // the escalation-critical ones pinned by name
        assertFalse(GuestCaps.ingressAllowed(RunShellCommand("c", "env", "/w")))            // no standalone terminal
        assertFalse(GuestCaps.ingressAllowed(CreateShare("/x")))                            // cannot re-share
        assertFalse(GuestCaps.ingressAllowed(RevokeShare("dev")))
        assertFalse(GuestCaps.ingressAllowed(dev.ccpocket.protocol.FetchUsage()))           // no owner usage/quota
        assertFalse(GuestCaps.ingressAllowed(dev.ccpocket.protocol.AuthLogin()))            // no account switching
        assertFalse(GuestCaps.ingressAllowed(dev.ccpocket.protocol.FetchAuthStatus))
        assertFalse(GuestCaps.ingressAllowed(dev.ccpocket.protocol.SetPushPrefs(true)))      // no owner push toggle
        assertFalse(GuestCaps.ingressAllowed(dev.ccpocket.protocol.SwitchDirectory("c", "/etc"))) // can't leave the scope
    }

    @Test
    fun guest_CAN_send_a_verdict_the_defining_difference_from_a_bridge() {
        // a guest approves its own session's asks — a bridge never can
        assertTrue(GuestCaps.ingressAllowed(PermissionVerdict("c", "a", Decision.ALLOW)))
        // and BridgeCaps still denies it, so the two classes stay distinct
        assertFalse(BridgeCaps.ingressAllowed(PermissionVerdict("c", "a", Decision.ALLOW)))
    }

    @Test
    fun guest_egress_whitelist_admits_asks_but_not_identity_or_management_frames() {
        val allowed = setOf(
            // the eight data-plane frames
            "pocket/session.live", "pocket/history", "pocket/chunk", "pocket/tool",
            "pocket/turn.done", "pocket/prompt.ack", "pocket/error", "pocket/session.gone",
            // interactive additions a scoped guest needs
            "pocket/ask", "pocket/ask.withdrawn", "pocket/commands", "pocket/jobs",
            "pocket/dirs", "pocket/sessions", "pocket/path.entries", "pocket/files",
            "pocket/file.content", "pocket/diff.content", "pocket/transcript",
        )
        for (cls in leaves(ToPhone::class)) {
            val inst = runCatching { instantiate(cls) as Frame }.getOrNull() ?: continue
            val serialName = serialNameOf(cls)
            val expected = serialName in allowed
            assertEquals(expected, GuestCaps.egressAllowed(inst), "egressAllowed(${cls.simpleName} = $serialName) should be $expected")
        }
    }

    @Test
    fun guest_CAN_receive_an_ask_but_never_the_owners_identity_or_share_frames() {
        assertTrue(GuestCaps.egressAllowed(PermissionAsk("c", "a", "Bash", "ls"))) // it answers its own
        // never the owner-only / identity frames
        assertFalse(GuestCaps.egressAllowed(dev.ccpocket.protocol.AuthState()))
        assertFalse(GuestCaps.egressAllowed(dev.ccpocket.protocol.Usage()))
        assertFalse(GuestCaps.egressAllowed(dev.ccpocket.protocol.DaemonInfo("ws://192.168.1.5:8765"))) // no LAN address
        assertFalse(GuestCaps.egressAllowed(dev.ccpocket.protocol.ShareListing()))
        assertFalse(GuestCaps.egressAllowed(dev.ccpocket.protocol.ShareCreated(ok = true)))
    }

    @Test
    fun mode_clamp_respects_the_tier_ceiling_and_never_reaches_bypass() {
        // Review ceiling = DEFAULT: anything more autonomous clamps down; PLAN (more cautious) is kept
        assertEquals(PermissionMode.DEFAULT, GuestCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS, AccessTier.REVIEW))
        assertEquals(PermissionMode.DEFAULT, GuestCaps.clampMode(PermissionMode.ACCEPT_EDITS, AccessTier.REVIEW))
        assertEquals(PermissionMode.DEFAULT, GuestCaps.clampMode(PermissionMode.DEFAULT, AccessTier.REVIEW))
        assertEquals(PermissionMode.PLAN, GuestCaps.clampMode(PermissionMode.PLAN, AccessTier.REVIEW))
        // Collaborate/Autonomous ceiling = ACCEPT_EDITS, still NEVER bypass
        assertEquals(PermissionMode.ACCEPT_EDITS, GuestCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS, AccessTier.COLLABORATE))
        assertEquals(PermissionMode.ACCEPT_EDITS, GuestCaps.clampMode(PermissionMode.ACCEPT_EDITS, AccessTier.COLLABORATE))
        assertEquals(PermissionMode.DEFAULT, GuestCaps.clampMode(PermissionMode.DEFAULT, AccessTier.COLLABORATE))
        assertEquals(PermissionMode.ACCEPT_EDITS, GuestCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS, AccessTier.AUTONOMOUS))
        // an UNKNOWN (future) tier degrades to the SAFEST ceiling — never falls open
        assertEquals(PermissionMode.DEFAULT, GuestCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS, AccessTier.UNKNOWN))
        // no tier ever yields bypass
        for (tier in AccessTier.entries) {
            assertTrue(GuestCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS, tier) != PermissionMode.BYPASS_PERMISSIONS)
        }
    }

    private fun serialNameOf(cls: kotlin.reflect.KClass<*>): String? =
        cls.annotations.filterIsInstance<kotlinx.serialization.SerialName>().firstOrNull()?.value

    /** Build a frame from its required primary-ctor params (optionals fall through to defaults) — enough
     *  for the cap predicates, which branch only on the runtime class. Mirrors BridgeCapsTest. */
    private fun instantiate(cls: kotlin.reflect.KClass<*>): Any {
        cls.objectInstance?.let { return it }
        val ctor = cls.primaryConstructor ?: cls.constructors.first()
        val provided = ctor.parameters.filterNot { it.isOptional }.associateWith { p ->
            val t = p.type.classifier as? kotlin.reflect.KClass<*>
            when {
                t == String::class -> "x"
                t == Int::class -> 0
                t == Long::class -> 0L
                t == Boolean::class -> false
                t == List::class -> emptyList<Any>()
                t == Map::class -> emptyMap<Any, Any>()
                t?.java?.isEnum == true -> t.java.enumConstants.first()
                t?.isData == true -> instantiate(t)
                else -> null
            }
        }
        return ctor.callBy(provided)
    }
}
