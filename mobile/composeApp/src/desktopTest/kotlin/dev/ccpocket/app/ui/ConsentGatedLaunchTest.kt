package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.SessionRoute
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App Review 5.1.2(i): before the one-time data disclosure is accepted nothing may leave the device — no
 * analytics event, no pairing redeem from a link the system camera cold-started the app with, no connect for a
 * tapped push. Those routes wait for consent instead of being dropped; a user who already consented launches
 * exactly as before.
 */
@OptIn(ExperimentalTestApi::class)
class ConsentGatedLaunchTest {
    private val seen = mutableListOf<TelEvent>()
    private lateinit var scope: CoroutineScope
    private var savedActive: String? = null
    private val b = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "cgl-acct-b", daemonPub = "pk-b", deviceId = "dev", credential = "c-b")
    private val pairLink = "ccpocket://pair?relay=wss%3A%2F%2F127.0.0.1%3A9&acct=cgl-acct-b&dpk=pk-b&ticket=tkt"
    private val dials = mutableListOf<String>()

    @BeforeTest fun setUp() {
        seen.clear()
        telemetryTap = { e, _: Map<TelKey, Any> -> synchronized(seen) { seen += e } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        savedActive = Pairing.activeAccount()
    }

    @AfterTest fun tearDown() {
        telemetryTap = null
        Pairing.remove(b.accountId)
        Pairing.setActive(savedActive)
        scope.cancel()
    }

    /** consent decided in memory only — the persisted flag is shared with every other test of the run */
    private fun repo(consented: Boolean, paired: PairedDaemon? = null) = PocketRepository(scope).apply {
        privacyConsented.value = consented
        this.paired.value = paired
        redeemForTest = { b }
        dialForTest = { p, _ -> dials += p.accountId; awaitCancellation() }
    }

    private fun events() = synchronized(seen) { seen.toList() }

    @Test
    fun beforeConsentNothingIsTrackedAndTheLinkWaitsThenRunsOnConsent() = runComposeUiTest {
        val r = repo(consented = false)
        val links = MutableStateFlow<String?>(pairLink)
        val pushes = MutableStateFlow<SessionRoute?>(null)
        setContent { ConsentGatedLaunchEffects(r, links, pushes) }
        waitForIdle()

        assertTrue(events().isEmpty(), "no analytics before consent, saw ${events()}")
        assertEquals(pairLink, links.value, "the link is parked, not consumed")
        assertNull(r.paired.value, "nothing redeemed")
        assertTrue(dials.isEmpty())

        r.privacyConsented.value = true
        waitForIdle()

        assertTrue(TelEvent.AppLaunch in events(), "app_launch goes out once the user agreed")
        assertTrue(TelEvent.PairStarted in events(), "the parked link is handled after consent")
        assertNull(links.value)
        assertEquals(b.accountId, r.paired.value?.accountId)
        assertEquals(listOf(b.accountId), dials)
    }

    @Test
    fun aTappedPushWaitsForConsentToo() = runComposeUiTest {
        val r = repo(consented = false, paired = b)
        val links = MutableStateFlow<String?>(null)
        val pushes = MutableStateFlow<SessionRoute?>(SessionRoute("/w", "sid-1"))
        setContent { ConsentGatedLaunchEffects(r, links, pushes) }
        waitForIdle()

        assertNotNull(pushes.value, "the push route is parked")
        assertTrue(dials.isEmpty(), "an unconsented launch does not connect, even when paired")

        r.privacyConsented.value = true
        waitForIdle()

        assertNull(pushes.value)
        assertEquals(listOf(b.accountId), dials, "consent connects once (launch reconnect and push share the link)")
    }

    @Test
    fun aConsentedUserLaunchesAndHandlesTheLinkAtOnce() = runComposeUiTest {
        val r = repo(consented = true)
        val links = MutableStateFlow<String?>(pairLink)
        setContent { ConsentGatedLaunchEffects(r, links, MutableStateFlow(null)) }
        waitForIdle()

        assertEquals(TelEvent.AppLaunch, events().first(), "app_launch first, as before")
        assertNull(links.value)
        assertEquals(b.accountId, r.paired.value?.accountId)
    }
}
