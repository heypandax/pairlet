package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.CollaboratorPurpose
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
 *
 * Since ReviewRequest M1 the whitelist is also PURPOSE-scoped (REVIEW-REQUEST.md §13.3): one credential
 * kind now backs two features whose recipients must not be interchangeable, so every assertion below
 * states which kind of link it is talking about.
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

    /** Denied whatever the link was minted for — the shape every escalation pin below wants. */
    private fun deniedToEveryPurpose(frame: Frame): Boolean =
        CollaboratorPurpose.entries.none { CollaboratorCaps.ingressAllowed(frame, it) }

    @Test
    fun collaborator_ingress_whitelist_is_exactly_the_handoff_plus_granted_session_set() {
        // The ONLY request types a collaborator may send: its own handoff lifecycle, the granted source
        // session while a bound handoff is IN_PROGRESS, and — since ReviewRequest M1 — the RECIPIENT half
        // of the review plane. Everything else — discovery, management, mode/tier changes, re-invites —
        // stays denied.
        val allowed = HANDOFF_PLANE + GRANTED_SESSION + REVIEW_PLANE
        val toDaemon = leaves(ToDaemon::class)
        assertTrue(toDaemon.size >= 20, "sanity: found ${toDaemon.size} ToDaemon leaves — reflection wired up")
        for (cls in toDaemon) {
            val inst = instantiate(cls) as Frame
            val name = serialNameOf(cls)
            // the union above is split by PURPOSE, and the granted-session frames go with the feature
            // that GRANTS a session — only Session Handoff does. A Review link therefore reaches its own
            // seven frames and nothing else.
            val expectedForHandoff = name in allowed && name !in REVIEW_PLANE
            val expectedForReview = name in REVIEW_PLANE
            assertEquals(
                expectedForHandoff, CollaboratorCaps.ingressAllowed(inst, CollaboratorPurpose.SESSION_HANDOFF),
                "ingressAllowed(${cls.simpleName}, SESSION_HANDOFF) should be $expectedForHandoff",
            )
            assertEquals(
                expectedForReview, CollaboratorCaps.ingressAllowed(inst, CollaboratorPurpose.REVIEW),
                "ingressAllowed(${cls.simpleName}, REVIEW) should be $expectedForReview",
            )
            // a purpose this build cannot read reaches NOTHING — including the session surface, which
            // is the set where an unclassified link would do the most damage
            assertFalse(
                CollaboratorCaps.ingressAllowed(inst, CollaboratorPurpose.UNKNOWN),
                "ingressAllowed(${cls.simpleName}, UNKNOWN) must fail closed",
            )
        }
    }

    /**
     * The two features share a credential KIND but not a recipient (REVIEW-REQUEST.md §13.3), so the
     * gate is purpose-scoped in BOTH directions. A Session Handoff device that could send review
     * mutations would be answering work it was never sent; one that could RECEIVE review frames would
     * learn the ids and briefs of it.
     */
    @Test
    fun each_purpose_reaches_only_its_own_plane_in_both_directions() {
        val handoffFrame = dev.ccpocket.protocol.AcceptHandoff("h1")
        val reviewFrame = dev.ccpocket.protocol.AcknowledgeReviewRequest("rr_1")

        assertTrue(CollaboratorCaps.ingressAllowed(handoffFrame, CollaboratorPurpose.SESSION_HANDOFF))
        assertFalse(CollaboratorCaps.ingressAllowed(handoffFrame, CollaboratorPurpose.REVIEW))
        assertTrue(CollaboratorCaps.ingressAllowed(reviewFrame, CollaboratorPurpose.REVIEW))
        assertFalse(
            CollaboratorCaps.ingressAllowed(reviewFrame, CollaboratorPurpose.SESSION_HANDOFF),
            "a handoff device must not send a review mutation",
        )

        val handoffReply = dev.ccpocket.protocol.HandoffListing()
        val reviewReply = dev.ccpocket.protocol.ReviewListing()
        assertTrue(CollaboratorCaps.egressAllowed(handoffReply, CollaboratorPurpose.SESSION_HANDOFF))
        assertFalse(CollaboratorCaps.egressAllowed(handoffReply, CollaboratorPurpose.REVIEW))
        assertTrue(CollaboratorCaps.egressAllowed(reviewReply, CollaboratorPurpose.REVIEW))
        assertFalse(
            CollaboratorCaps.egressAllowed(reviewReply, CollaboratorPurpose.SESSION_HANDOFF),
            "a handoff device must never receive a review listing",
        )

        listOf(handoffFrame, reviewFrame).forEach {
            assertFalse(CollaboratorCaps.ingressAllowed(it, CollaboratorPurpose.UNKNOWN))
        }
        listOf(handoffReply, reviewReply).forEach {
            assertFalse(CollaboratorCaps.egressAllowed(it, CollaboratorPurpose.UNKNOWN))
        }
    }

    @Test
    fun collaborator_can_never_reshape_the_owners_lists_or_approval_policy() {
        // the escalation-relevant ones pinned by name so a widening is a visible diff
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.SetApprovalPrefs(true)))     // #201
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.SetSessionArchived("/w", "s", true))) // #202
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.ListArchivedSessions))       // #202
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.SetPushPrefs(true)))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.RunShellCommand("c", "env", "/w")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.AuthLogin()))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.SwitchDirectory("c", "/etc")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.GroupCreate("/w", "g")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.RenameSession("/w", "s", "t")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.CreateShare("/x")))
        // ReviewRequest owner plane — pinned by name for the same reason as the two above: a widening
        // here would let a contact create requests as the owner, or withdraw/close the owner's
        assertTrue(
            deniedToEveryPurpose(
                dev.ccpocket.protocol.CreateReviewRequest("d", "t", dev.ccpocket.protocol.ReviewBrief()),
            ),
        )
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.CancelReviewRequest("rr_1")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.CloseReviewRequest("rr_1")))
        // the OWNER-LOCAL plane: every one of these exposes THIS machine's whole peer inbox or its
        // contact ledger, across every colleague
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.ListReviewContacts))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.CreateReviewInvite()))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.JoinReviewContact("ccpocket://collab#x")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.RemoveReviewContact("pl_1")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.ListReviewInbox()))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.PrepareReviewRequest("rr_1")))
        assertTrue(deniedToEveryPurpose(dev.ccpocket.protocol.ActOnReviewInbox("rr_1")))
    }

    private companion object {
        val HANDOFF_PLANE = setOf(
            "pocket/handoff.accept", "pocket/handoff.decline", "pocket/handoff.return", "pocket/handoff.list",
        )

        /** ReviewRequest, RECIPIENT plane only (REVIEW-REQUEST.md §11.1). Deliberately NOT
         *  pocket/review.create / .cancel / .close — those are the owner's, and admitting one would let a
         *  contact mint a request in someone else's name or close its own result. */
        val REVIEW_PLANE = setOf(
            "pocket/review.list", "pocket/review.get", "pocket/review.delivered",
            "pocket/review.acknowledge", "pocket/review.start", "pocket/review.decline",
            "pocket/review.respond",
        )

        /** The granted Source Session. Owned by SESSION_HANDOFF — it is the only feature that issues a
         *  grant — and additionally gated on a live one by [CollaboratorGuard]. */
        val GRANTED_SESSION = setOf(
            "pocket/session.open", "pocket/prompt", "pocket/turn.cancel", "pocket/verdict",
            "pocket/session.close", "pocket/history.page", "pocket/files.list",
            "pocket/file.read", "pocket/diff.read",
        )
    }
}
