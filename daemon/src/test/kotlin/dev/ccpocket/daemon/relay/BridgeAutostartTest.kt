package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeRunners
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.InProcessBridgeEngine
import dev.ccpocket.protocol.BridgeRunnerSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Audit F1: an autostart adapter whose start() never returns (the Feishu SDK while Feishu is unreachable)
 * must not hold up the relay loop. RelayClient.run() lives inside Main's `runBlocking`, i.e. ONE thread —
 * so the autostart must leave that thread, not merely be a sibling coroutine on it.
 */
class BridgeAutostartTest {
    private val tmp: File = Files.createTempDirectory("ccp-autostart").toFile()
    private val release = CountDownLatch(1)

    @AfterTest fun cleanup() {
        release.countDown()
        tmp.deleteRecursively()
    }

    private inner class NeverConnects : InProcessBridgeEngine {
        val entered = CountDownLatch(1)
        override val running = false
        override val lastError: String? = null
        override fun ownedConvoIds(): Set<String> = emptySet()
        override fun start(): String? {
            entered.countDown()
            release.await() // models ws.Client.start() looping in reconnect()
            return null
        }
        override fun stop() {}
        override suspend fun revokeAndShutdown() {}
    }

    @Test
    fun a_hung_autostart_adapter_does_not_stop_the_relay_loop_from_proceeding() {
        val runners = BridgeRunners(rootDir = File(tmp, "runners"), store = File(tmp, "runners.json"))
        val engine = NeverConnects()
        runners.registerEngine("test-hung") { _, _, _, _, _ -> engine }
        runBlocking {
            runners.attachInProcess(
                "feishu-bot",
                BridgeRunnerSpec(scriptPath = "", kind = "test-hung", autostart = true),
                BridgeSpec(name = "feishu-bot", workdirs = listOf(tmp.path)),
            )
        }

        val relayLoopReached = CountDownLatch(1)
        // the same shape as Main: runBlocking { relayClient.run() } → launchBridgeAutostart → connect loop
        val daemonMain = thread(isDaemon = true, name = "fake-daemon-main") {
            runBlocking {
                launchBridgeAutostart(runners)
                delay(50) // connectOnce() suspends on the network here
                relayLoopReached.countDown()
            }
        }
        try {
            assertTrue(engine.entered.await(5, TimeUnit.SECONDS), "the autostart adapter was never started")
            assertTrue(
                relayLoopReached.await(5, TimeUnit.SECONDS),
                "the relay loop is stuck behind a bridge adapter whose start() never returns",
            )
        } finally {
            release.countDown()
            daemonMain.join(5_000)
        }
    }
}
