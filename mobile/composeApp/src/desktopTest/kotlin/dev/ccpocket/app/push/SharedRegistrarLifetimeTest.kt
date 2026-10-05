package dev.ccpocket.app.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The process-wide registrar used to run on the scope of whoever asked for it first — on Android the
 * composition root's `rememberCoroutineScope()`. An Activity recreation cancels that scope, and the
 * singleton (which outlives it) kept handing out an instance whose trigger loop and token collector
 * were dead: later pairings never registered and token rotations were never reported.
 */
class SharedRegistrarLifetimeTest {

    private class MapStore : PushStateStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class Platform : PushPlatform {
        override val token = MutableStateFlow<PushToken?>(null)
        override val failures = MutableSharedFlow<PushFailureEvent>()
        override fun requestToken(prompt: Boolean, request: Long) {}
        override fun readAuthorization(cb: (PushAuthorization) -> Unit) = cb(PushAuthorization.AUTHORIZED)
    }

    @BeforeTest fun setUp() = PushRegistrar.resetSharedForTest()
    @AfterTest fun tearDown() = PushRegistrar.resetSharedForTest()

    @Test
    fun theSharedRegistrarOutlivesTheFirstCallersScope() {
        val platform = Platform()
        val store = MapStore()
        val firstUi = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registrar = PushRegistrar.shared(firstUi, platform, store)
        firstUi.cancel() // the Activity is recreated: its composition (and that scope) is gone

        val secondUi = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            assertSame(registrar, PushRegistrar.shared(secondUi, platform, store), "still one process-wide coordinator")
            // the OS hands over a token after the recreation — a live coordinator records it
            platform.token.value = PushToken("android", "tok-after-recreate")
            assertNotNull(store.map[PushRegistrar.K_DEVICE], "the token collector is still running")
        } finally {
            secondUi.cancel()
        }
    }
}
