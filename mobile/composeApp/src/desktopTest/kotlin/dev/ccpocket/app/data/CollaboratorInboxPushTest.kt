package dev.ccpocket.app.data

import dev.ccpocket.app.PushRoute
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.push.PushTokens
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recipient's side of the offline offer push (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.4).
 *
 * A targeted push can only reach the token registered under the INBOX's own deviceId — the colleague's
 * relay account deliberately excludes contacts from its device fan-out. Push registration used to belong to
 * the primary link alone (`pinnedTo != null` returned early), so a Collaborator Link had no token anywhere
 * and §3.4 was unreachable no matter what the daemon and relay did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollaboratorInboxPushTest {

    private lateinit var scope: CoroutineScope
    private var savedPlatform: String? = null

    @BeforeTest fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(TestCoroutineScheduler()))
        // the "off converges" test writes the REAL store (one file shared by every desktop test class) —
        // snapshot it, exactly as CollaboratorInboxRepoTest does for the link list
        savedPlatform = SecureStore.getString(PocketRepository.K_PUSH_PLATFORM)
        PushTokens.deliverForTest(null)
        PushRoute.pending.value = null
        PushRoute.pendingHandoff.value = null
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        savedPlatform
            ?.let { SecureStore.putString(PocketRepository.K_PUSH_PLATFORM, it) }
            ?: SecureStore.remove(PocketRepository.K_PUSH_PLATFORM)
        PushTokens.deliverForTest(null)
        PushRoute.pending.value = null
        PushRoute.pendingHandoff.value = null
    }

    private fun repo(role: BindingRole, dialed: MutableList<RegisterPush>) =
        PocketRepository(
            scope,
            pinnedTo = PairedDaemon(
                relay = "wss://127.0.0.1:9", accountId = "acct-colleague", daemonPub = "pk",
                deviceId = "dev-me", credential = "cred", role = role,
            ),
        ).apply {
            useRelay = true
            sessionActive.value = true
            notificationsOn.value = true // independent of whatever a previous run persisted
            directLinkUp = { true }      // observe the token through the one-shot dial rather than a socket
            pushDial = { _, f -> dialed += f }
            onSendForTest = {}
        }

    @Test
    fun anInboxLinkRegistersItsOwnPushToken() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A")) // the platform token the primary link also sees
        val dialed = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.COLLABORATOR, dialed)

        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))

        assertEquals(
            listOf(RegisterPush("ios", "tok-A")), dialed.toList(),
            "without its own token the contact's daemon has nothing to target — §3.4 cannot deliver",
        )
    }

    @Test
    fun aFleetSatelliteStillLeavesPushRegistrationToThePrimaryLink() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val dialed = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.OWNER, dialed)

        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))

        assertTrue(
            dialed.isEmpty(),
            "another of YOUR machines needs no separate token — the primary link's registration already wakes this phone",
        )
    }

    @Test
    fun aTokenArrivingAfterTheAttachStillReachesTheInbox() {
        val dialed = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.COLLABORATOR, dialed)

        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague")) // no token yet (cold start)
        assertTrue(dialed.isEmpty())

        PushTokens.deliverForTest(PushToken("android", "tok-late")) // APNs/FCM answers a moment later

        assertEquals(listOf(RegisterPush("android", "tok-late")), dialed.toList())
    }

    @Test
    fun turningNotificationsOffDeregistersTheInboxToo() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val dialed = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.COLLABORATOR, dialed)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        assertEquals(1, dialed.size)

        // what CollaboratorInbox.onNotificationsChanged fans out from the primary link's Settings switch
        r.setNotificationsEnabled(false)

        assertEquals(
            "", dialed.last().token,
            "\"notifications off\" must silence contacts too, not just your own computers",
        )
    }

    /**
     * "Off" has to converge even when this launch never obtained a platform token. Turning notifications
     * off never starts the platform stack (starting it is what prompts), so `pushToken` is null on the next
     * cold start — and if the clearing register was lost the first time (app killed, outbox drained), the
     * relay would keep a live token forever while the UI reads "off". A contact's daemon is a pusher now,
     * so an un-clearable token is someone ELSE's ability to buzz this phone.
     */
    @Test
    fun offConvergesEvenWithNoPlatformTokenThisLaunch() {
        // launch 1: a token was registered, so its platform is remembered
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val first = CopyOnWriteArrayList<RegisterPush>()
        repo(BindingRole.COLLABORATOR, first).receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        assertEquals(listOf(RegisterPush("ios", "tok-A")), first.toList())

        // launch 2: notifications are already off, so nothing ever starts the platform stack
        PushTokens.deliverForTest(null)
        val second = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.COLLABORATOR, second).apply { notificationsOn.value = false }

        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))

        assertEquals(1, second.size, "the clear must still be sent: ${second.toList()}")
        assertEquals("", second.single().token)
        assertEquals("ios", second.single().platform, "the remembered platform identifies the row to clear")
    }

    @Test
    fun severingAContactLinkClearsTheTokenItRegistered() {
        PushTokens.deliverForTest(PushToken("ios", "tok-A"))
        val dialed = CopyOnWriteArrayList<RegisterPush>()
        val r = repo(BindingRole.COLLABORATOR, dialed)
        r.receiveControlForTest(Attached(Role.DEVICE, "acct-colleague"))
        assertEquals(1, dialed.size)

        r.deregisterPush() // what CollaboratorInbox.remove() does before dropping the credential

        assertEquals(
            "", dialed.last().token,
            "once the credential is gone we could never de-register — their daemon would push forever",
        )
    }

    @Test
    fun anOfferPushTapRoutesToTheInboxAndNeverToASession() {
        PushRoute.openHandoff("h-42")

        assertEquals("h-42", PushRoute.pendingHandoff.value)
        assertNull(PushRoute.pending.value, "an offer alert carries no session — it must not open one")

        PushRoute.pendingHandoff.value = null
        PushRoute.openHandoff("") // a malformed/absent `hid` custom key is ignored, not routed as ""
        assertNull(PushRoute.pendingHandoff.value)
    }
}
