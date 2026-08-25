package dev.ccpocket.daemon.feishu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeishuPolicyGateTest {
    @Test
    fun mutation_cannot_land_between_final_validation_and_grant_arm() = runBlocking {
        val gate = FeishuPolicyGate()
        var generation = 1
        val armEntered = CompletableDeferred<Unit>()
        val releaseArm = CompletableDeferred<Unit>()

        val claim = async {
            gate.claim("oc_1", validate = { generation == 1 }) {
                armEntered.complete(Unit)
                releaseArm.await()
                "armed"
            }
        }
        armEntered.await()
        val mutation = async { gate.withPolicy("oc_1") { generation = 2 } }

        // The mutation has started, but the claim owns the linearization point until its grant is armed.
        assertFalse(mutation.isCompleted)
        releaseArm.complete(Unit)
        val result = withTimeout(2_000) { claim.await() }
        withTimeout(2_000) { mutation.await() }

        assertTrue(result.valid)
        assertEquals("armed", result.value)
        assertEquals(2, generation)
    }

    @Test
    fun mutation_that_lands_first_invalidates_the_claim_without_arming() = runBlocking {
        val gate = FeishuPolicyGate()
        var generation = 1
        gate.withPolicy("oc_1") { generation = 2 }
        var armed = false

        val result = gate.claim("oc_1", validate = { generation == 1 }) {
            armed = true
            "must-not-arm"
        }

        assertFalse(result.valid)
        assertNull(result.value)
        assertFalse(armed)
    }

    @Test
    fun a_resolved_revoke_is_persisted_before_a_queued_claim_can_validate() = runBlocking {
        val gate = FeishuPolicyGate()
        var generation = 1
        val resolved = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val mutation = async {
            gate.withPolicy("oc_1") {
                // Model commands.handle resolving /untrust, followed by FeishuTrust.untrust in the SAME
                // critical section. A stale Guardian claim queued after resolution cannot slip between them.
                resolved.complete(Unit)
                releasePersist.await()
                generation = 2
            }
        }
        resolved.await()
        var armed = false
        val claim = async {
            gate.claim("oc_1", validate = { generation == 1 }) {
                armed = true
            }
        }
        assertFalse(claim.isCompleted, "claim must wait until the resolved revoke has persisted")
        releasePersist.complete(Unit)
        mutation.await()

        assertFalse(claim.await().valid)
        assertFalse(armed)
    }
}
