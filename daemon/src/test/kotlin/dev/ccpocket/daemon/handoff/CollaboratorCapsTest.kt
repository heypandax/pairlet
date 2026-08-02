package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ToDaemon
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security-core contract for a COLLABORATOR's inbound capability whitelist (SESSION-HANDOFF §8).
 * DELIBERATELY EXHAUSTIVE over the sealed [ToDaemon] hierarchy, the same shape as `GuestCapsTest` and
 * `BridgeCapsTest`: a message type added to the protocol later shows up here as a failing assertion
 * (it defaults to DENIED), forcing a conscious decision before a new capability reaches someone who
 * merely holds a handoff. Until #201/#202 this class had no such pin — the two new owner-plane frames
 * were denied only by the `else -> false` fallthrough, with nothing guarding the next one.
 */
class CollaboratorCapsTest {

    private fun leaves(k: kotlin.reflect.KClass<*>): List<kotlin.reflect.KClass<*>> =
        if (k.sealedSubclasses.isEmpty()) listOf(k) else k.sealedSubclasses.flatMap { leaves(it) }

    private fun serialNameOf(cls: kotlin.reflect.KClass<*>): String? =
        cls.annotations.filterIsInstance<kotlinx.serialization.SerialName>().firstOrNull()?.value

    /** Build a frame from its required primary-ctor params — enough for a predicate that branches only
     *  on the runtime class. Mirrors GuestCapsTest/BridgeCapsTest. */
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

    @Test
    fun collaborator_ingress_whitelist_is_exactly_the_handoff_plus_granted_session_set() {
        // The ONLY request types a collaborator may send: its own handoff lifecycle, and the granted
        // source session while a bound handoff is IN_PROGRESS. Everything else — discovery, management,
        // mode/tier changes, re-invites — stays denied.
        val allowed = setOf(
            "pocket/handoff.accept", "pocket/handoff.decline", "pocket/handoff.return",
            "pocket/handoff.list",
            "pocket/session.open", "pocket/prompt", "pocket/turn.cancel", "pocket/verdict",
            "pocket/session.close", "pocket/history.page", "pocket/files.list",
            "pocket/file.read", "pocket/diff.read",
        )
        val toDaemon = leaves(ToDaemon::class)
        assertTrue(toDaemon.size >= 20, "sanity: found ${toDaemon.size} ToDaemon leaves — reflection wired up")
        for (cls in toDaemon) {
            val inst = instantiate(cls) as Frame
            val expected = serialNameOf(cls) in allowed
            assertEquals(
                expected, CollaboratorCaps.ingressAllowed(inst),
                "ingressAllowed(${cls.simpleName}) should be $expected",
            )
        }
    }

    @Test
    fun collaborator_can_never_reshape_the_owners_lists_or_approval_policy() {
        // the escalation-relevant ones pinned by name so a widening is a visible diff
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.SetApprovalPrefs(true)))     // #201
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.SetSessionArchived("/w", "s", true))) // #202
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.ListArchivedSessions))       // #202
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.SetPushPrefs(true)))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.RunShellCommand("c", "env", "/w")))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.AuthLogin()))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.SwitchDirectory("c", "/etc")))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.GroupCreate("/w", "g")))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.RenameSession("/w", "s", "t")))
        assertFalse(CollaboratorCaps.ingressAllowed(dev.ccpocket.protocol.CreateShare("/x")))
    }
}
