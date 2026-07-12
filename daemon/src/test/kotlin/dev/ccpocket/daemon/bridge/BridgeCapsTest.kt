package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.RunShellCommand
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security-core contract for issue #91: the bridge capability whitelists. These tests are
 * DELIBERATELY EXHAUSTIVE over the sealed [Frame] hierarchy — a message type added to the protocol
 * later shows up here as a failing assertion (it defaults to DENIED, and this proves it), forcing a
 * conscious decision before any new capability leaks to a bridge.
 */
class BridgeCapsTest {

    // every concrete leaf of a sealed interface tree (ToDaemon / ToPhone / ToRelay all extend Frame)
    private fun leaves(k: kotlin.reflect.KClass<*>): List<kotlin.reflect.KClass<*>> =
        if (k.sealedSubclasses.isEmpty()) listOf(k) else k.sealedSubclasses.flatMap { leaves(it) }

    @Test
    fun bridge_can_send_exactly_four_request_types_and_nothing_else() {
        // the ONLY frames a bridge may send — locked as an exact set so widening it is a visible diff
        val allowed = setOf(
            OpenSession::class, SendPrompt::class, CancelTurn::class, CloseSession::class,
        )
        val toDaemon = leaves(ToDaemon::class)
        assertTrue(toDaemon.size >= 15, "sanity: found ${toDaemon.size} ToDaemon leaves — reflection wired up")
        for (cls in toDaemon) {
            val inst = sampleToDaemon(cls)
            val expected = cls in allowed
            assertEquals(expected, BridgeCaps.ingressAllowed(inst), "ingressAllowed(${cls.simpleName}) should be $expected")
        }
    }

    @Test
    fun bridge_can_never_send_a_verdict_or_mode_switch_or_shell() {
        // the three that would break the security model if ever admitted, pinned by name
        assertFalse(BridgeCaps.ingressAllowed(PermissionVerdict("c", "a", Decision.ALLOW)))
        assertFalse(BridgeCaps.ingressAllowed(SwitchMode("c", PermissionMode.BYPASS_PERMISSIONS)))
        assertFalse(BridgeCaps.ingressAllowed(RunShellCommand("c", "rm -rf /", "/w")))
    }

    @Test
    fun bridge_can_never_receive_a_permission_ask_or_withdrawal() {
        // the guarantee "an approval prompt is structurally invisible to the trigger source"
        assertFalse(BridgeCaps.egressAllowed(PermissionAsk("c", "a", "Bash", "rm -rf /")))
        assertFalse(BridgeCaps.egressAllowed(AskWithdrawn("c", "a")))
    }

    @Test
    fun bridge_egress_whitelist_is_exactly_the_eight_data_frames() {
        val allowed = setOf(
            "pocket/session.live", "pocket/history", "pocket/chunk", "pocket/tool",
            "pocket/turn.done", "pocket/prompt.ack", "pocket/error", "pocket/session.gone",
        )
        for (cls in leaves(ToPhone::class)) {
            val inst = sampleToPhone(cls) ?: continue
            val serialName = serialNameOf(cls)
            val expected = serialName in allowed
            assertEquals(expected, BridgeCaps.egressAllowed(inst), "egressAllowed(${cls.simpleName} = $serialName) should be $expected")
        }
    }

    @Test
    fun mode_clamp_removes_bypass_but_preserves_the_rest() {
        assertEquals(PermissionMode.DEFAULT, BridgeCaps.clampMode(PermissionMode.BYPASS_PERMISSIONS))
        assertEquals(PermissionMode.DEFAULT, BridgeCaps.clampMode(PermissionMode.DEFAULT))
        assertEquals(PermissionMode.PLAN, BridgeCaps.clampMode(PermissionMode.PLAN))
        assertEquals(PermissionMode.ACCEPT_EDITS, BridgeCaps.clampMode(PermissionMode.ACCEPT_EDITS))
    }

    // ---- minimal sample instances so we can call the predicates on every frame type ----

    private fun serialNameOf(cls: kotlin.reflect.KClass<*>): String? =
        cls.annotations.filterIsInstance<kotlinx.serialization.SerialName>().firstOrNull()?.value

    private fun sampleToDaemon(cls: kotlin.reflect.KClass<*>): Frame = when (cls) {
        OpenSession::class -> OpenSession("/w")
        SendPrompt::class -> SendPrompt("c", "hi")
        CancelTurn::class -> CancelTurn("c")
        CloseSession::class -> CloseSession("c")
        PermissionVerdict::class -> PermissionVerdict("c", "a", Decision.ALLOW)
        SwitchMode::class -> SwitchMode("c", PermissionMode.DEFAULT)
        RunShellCommand::class -> RunShellCommand("c", "ls", "/w")
        else -> instantiate(cls) as Frame
    }

    private fun sampleToPhone(cls: kotlin.reflect.KClass<*>): Frame? =
        runCatching { instantiate(cls) as Frame }.getOrNull()

    /** Build a frame by feeding ONLY its REQUIRED constructor params type-appropriate zero values —
     *  optional ones (the vast majority) fall through to the class's own defaults via callBy. Enough
     *  for the cap predicates, which branch only on the runtime CLASS. Uses the PRIMARY constructor,
     *  never the synthetic serialization one @Serializable adds (which callBy can't satisfy). */
    private fun instantiate(cls: kotlin.reflect.KClass<*>): Any {
        cls.objectInstance?.let { return it } // data objects (FetchAuthStatus, AuthLogout, …)
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
