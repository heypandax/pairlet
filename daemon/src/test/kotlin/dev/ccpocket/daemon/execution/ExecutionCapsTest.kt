package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.ExecutionGrantInfo
import dev.ccpocket.protocol.ExecutionGrantQuery
import dev.ccpocket.protocol.ExecutionRunAccepted
import dev.ccpocket.protocol.ExecutionRunCancel
import dev.ccpocket.protocol.ExecutionRunOutput
import dev.ccpocket.protocol.ExecutionRunResult
import dev.ccpocket.protocol.ExecutionRunState
import dev.ccpocket.protocol.ExecutionRunStatus
import dev.ccpocket.protocol.ExecutionRunSubmit
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * EXHAUSTIVE over the sealed request/response hierarchies, like CollaboratorCapsTest: a frame added to the
 * protocol later is denied to an execution link until someone edits the expected set below on purpose.
 */
class ExecutionCapsTest {

    @Test
    fun execution_ingress_is_exactly_the_grant_query_and_the_run_frames() {
        val allowed = sealedLeaves(ToDaemon::class)
            .filter { ExecutionCaps.ingressAllowed(instantiateFrame(it) as Frame) }
            .toSet()
        assertEquals(
            setOf(
                ExecutionGrantQuery::class,
                ExecutionRunSubmit::class, ExecutionRunStatus::class, ExecutionRunResult::class, ExecutionRunCancel::class,
            ),
            allowed,
        )
    }

    @Test
    fun execution_egress_is_exactly_grant_info_the_run_replies_and_errors() {
        val allowed = sealedLeaves(ToPhone::class)
            .filter { ExecutionCaps.egressAllowed(instantiateFrame(it) as Frame) }
            .toSet()
        assertEquals(
            setOf(
                ExecutionGrantInfo::class,
                ExecutionRunAccepted::class, ExecutionRunState::class, ExecutionRunOutput::class,
                PocketError::class,
            ),
            allowed,
        )
    }

    @Test
    fun no_existing_restricted_credential_admits_any_execution_frame() {
        // EVERY execution frame, both directions, against EVERY other restricted whitelist — enumerated off
        // ExecutionCaps itself, so a frame added to the execution plane is covered here without an edit
        val requests = sealedLeaves(ToDaemon::class)
            .map { instantiateFrame(it) as Frame }.filter { ExecutionCaps.ingressAllowed(it) }
        val replies = sealedLeaves(ToPhone::class)
            .map { instantiateFrame(it) as Frame }.filter { ExecutionCaps.egressAllowed(it) && it !is PocketError }
        assertEquals(5, requests.size); assertEquals(4, replies.size)
        for (f in requests) {
            for (p in CollaboratorPurpose.entries) assertFalse(CollaboratorCaps.ingressAllowed(f, p), "collaborator $p ingress ${f.name()}")
            assertFalse(BridgeCaps.ingressAllowed(f), "bridge ingress ${f.name()}")
            assertFalse(GuestCaps.ingressAllowed(f), "guest ingress ${f.name()}")
        }
        for (f in replies) {
            for (p in CollaboratorPurpose.entries) assertFalse(CollaboratorCaps.egressAllowed(f, p), "collaborator $p egress ${f.name()}")
            assertFalse(BridgeCaps.egressAllowed(f), "bridge egress ${f.name()}")
            assertFalse(GuestCaps.egressAllowed(f), "guest egress ${f.name()}")
        }
    }
}
