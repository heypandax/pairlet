package dev.ccpocket.app.data

import dev.ccpocket.app.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The new-session Full-access confirmation is shown until it has been accepted once on this device. The
 * acceptance is read at repository CONSTRUCTION, so it has to survive a relaunch and a fleet promote.
 */
class FullAccessConfirmationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // the desktop SecureStore is shared by every test in this JVM — never leak an acceptance into another case
    @BeforeTest
    @AfterTest
    fun clear() = SecureStore.remove(PocketRepository.K_FULL_ACCESS_CONFIRMED)

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun acceptanceIsPersistedAcrossARelaunch() {
        val first = PocketRepository(scope)
        assertFalse(first.fullAccessConfirmed.value, "a fresh install still confirms Full access")
        first.acknowledgeFullAccess()
        assertTrue(first.fullAccessConfirmed.value)
        assertTrue(PocketRepository(scope).fullAccessConfirmed.value, "the next launch remembers the acceptance")
    }

    @Test
    fun aFleetPromoteNeverForgetsAnAcceptance() {
        // a satellite built before the acceptance holds a stale mirror; promoting it adopts the acceptance
        val stale = PocketRepository(scope)
        val accepted = PocketRepository(scope).apply { acknowledgeFullAccess() }
        stale.adoptShellState(accepted)
        assertTrue(stale.fullAccessConfirmed.value)

        // the reverse: the promoted side recorded it, the outgoing primary's mirror predates it
        val olderPrimary = PocketRepository(scope).apply { fullAccessConfirmed.value = false }
        accepted.adoptShellState(olderPrimary)
        assertTrue(accepted.fullAccessConfirmed.value, "a one-way flag is never copied back to false")
    }
}
