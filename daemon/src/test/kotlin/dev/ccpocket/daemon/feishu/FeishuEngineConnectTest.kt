package dev.ccpocket.daemon.feishu

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit F1: while Feishu is unreachable the SDK's ws.Client.start() never returns (it loops in reconnect()
 * on the calling thread). The engine's start() must stay bounded so neither the daemon's startup nor the
 * runner's lifecycle lock is held hostage. No SDK client and no network: the link is a stand-in.
 */
class FeishuEngineConnectTest {
    private val tmp: File = Files.createTempDirectory("ccp-feishu-connect").toFile()
    private val release = CountDownLatch(1)
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    @AfterTest fun cleanup() {
        release.countDown()
        tmp.deleteRecursively()
    }

    private fun engine(link: FeishuEventLink) = FeishuEngine(
        name = "feishu-bot",
        spec = BridgeSpec(name = "feishu-bot", workdirs = listOf(tmp.path)),
        env = mapOf("FEISHU_APP_ID" to "cli_test", "FEISHU_APP_SECRET" to "not-a-secret"),
        core = DaemonCore(emptyMap()),
        stateDir = tmp,
        logLine = { logs += it },
        eventLinkFactory = { _, _, _ -> link },
        connectWaitMs = 200,
    )

    private inner class UnreachableFeishu : FeishuEventLink {
        val putDowns = AtomicInteger()
        override fun start() { release.await() } // the SDK's endless reconnect loop
        override fun putDown() { putDowns.incrementAndGet() }
    }

    @Test
    fun start_returns_promptly_while_feishu_is_unreachable_and_keeps_retrying_in_the_background() {
        val link = UnreachableFeishu()
        val engine = engine(link)
        val result = CompletableFuture<String?>()
        thread(isDaemon = true) { result.complete(engine.start()) }

        val outcome = runCatching { result.get(5, TimeUnit.SECONDS) }
        assertTrue(outcome.isSuccess, "FeishuEngine.start() is stuck inside the SDK's reconnect loop")
        assertNull(outcome.getOrNull(), "an unreachable network is not a configuration error")
        assertTrue(engine.running, "the engine owns the link and the SDK keeps retrying — same as a runtime reconnect")
        val note = assertNotNull(engine.lastError, "the owner's card must say the link is not up yet")
        assertTrue("重试" in note, note)
        assertNull(engine.start(), "a second start while still connecting is a no-op")
    }

    @Test
    fun a_link_that_connects_after_stop_is_put_down_instead_of_orphaned() {
        val link = UnreachableFeishu()
        val engine = engine(link)
        val started = CompletableFuture<String?>()
        thread(isDaemon = true) { started.complete(engine.start()) }
        assertNull(runCatching { started.get(5, TimeUnit.SECONDS) }.getOrElse { throw AssertionError("start() hung", it) })
        engine.stop()
        assertFalse(engine.running)
        val before = link.putDowns.get()

        release.countDown() // the network came back: the abandoned attempt finally connects
        val deadline = System.currentTimeMillis() + 5_000
        while (link.putDowns.get() == before && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(link.putDowns.get() > before, "a connection nobody owns any more must be dropped")
        assertFalse(engine.running)
    }

    @Test
    fun a_credential_rejection_is_still_reported_synchronously() {
        val engine = engine(object : FeishuEventLink {
            override fun start() = throw IllegalStateException("app secret invalid")
            override fun putDown() {}
        })
        val err = assertNotNull(engine.start())
        assertTrue("app secret invalid" in err, err)
        assertFalse(engine.running)
        assertEquals("couldn't start: app secret invalid", engine.lastError)
    }
}
