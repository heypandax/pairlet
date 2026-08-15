package dev.ccpocket.app.desktop

import dev.ccpocket.protocol.update.ReleaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * issue #245: "Check for updates" hung on `Checking` forever whenever the probe threw or stalled. The
 * contract these tests pin is blunt — [resolveUpdateCheck] NEVER returns `Checking`/`Idle`, so the UI always
 * gets a state that offers a way forward (Failed carries a Retry button).
 */
class UpdateCheckStateTest {

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun release(v: String) = ReleaseClient.Release(v, emptyMap())

    // Real dispatcher, real clock: the probe runs on a detached IO scope, so runTest's virtual clock would
    // fast-forward the deadline past a probe that hasn't had a chance to answer yet.
    private suspend fun check(
        current: String = "1.7.7",
        timeoutMs: Long = 5_000,
        source: suspend () -> DkInstallSource = { DkInstallSource.STANDALONE },
        failureText: suspend (Throwable?) -> String = { "failed: ${it?.message}" },
        latest: suspend () -> ReleaseClient.Release?,
    ) = withContext(Dispatchers.Default) {
        resolveUpdateCheck(current, probeScope, latest, source, failureText, timeoutMs)
    }

    @Test
    fun newerReleaseBecomesAvailableAndCarriesTheRelease() = runTest {
        val out = check { release("1.8.0") }
        assertEquals(DkUpdateState.Available("1.8.0", DkInstallSource.STANDALONE), out.state)
        assertEquals("1.8.0", out.release?.version)
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        assertEquals(DkUpdateState.UpToDate("1.7.7"), check { release("1.7.7") }.state)
    }

    @Test
    fun unreachableProbeFails() = runTest {
        assertIs<DkUpdateState.Failed>(check { null }.state)
    }

    /** The core regression: a throwing probe used to be swallowed by the scope's handler, stranding Checking. */
    @Test
    fun throwingProbeFails() = runTest {
        val state = check { error("boom") }.state
        assertIs<DkUpdateState.Failed>(state)
        assertTrue("boom" in state.message, "cause should reach the user: ${state.message}")
    }

    /** A blocking probe (DNS black hole) must lose to the deadline instead of parking the UI on Checking. */
    @Test
    fun stalledProbeTimesOut() = runTest {
        val state = check(timeoutMs = 50) { Thread.sleep(3_000); release("9.9.9") }.state
        assertIs<DkUpdateState.Failed>(state)
        assertTrue("timed out" in state.message, "should read as a timeout: ${state.message}")
    }

    /** currentSource() walks the filesystem; a throw there must not strand the check either. */
    @Test
    fun throwingSourceFails() = runTest {
        assertIs<DkUpdateState.Failed>(check(source = { error("no bundle") }) { release("1.8.0") }.state)
    }

    /** Even fetching the failure copy is fallible (suspend resource load) — the literal fallback covers it. */
    @Test
    fun throwingFailureTextFallsBackToTheLiteral() = runTest {
        val state = check(failureText = { error("resources unavailable") }) { null }.state
        assertEquals(DkUpdateState.Failed(UPDATE_CHECK_FALLBACK_MSG), state)
    }

    @Test
    fun blankFailureTextFallsBackToTheLiteral() = runTest {
        val state = check(failureText = { "   " }) { null }.state
        assertEquals(DkUpdateState.Failed(UPDATE_CHECK_FALLBACK_MSG), state)
    }
}
