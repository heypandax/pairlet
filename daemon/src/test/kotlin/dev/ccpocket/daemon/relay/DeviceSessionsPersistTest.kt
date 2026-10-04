package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit 2026-10-04 (session-relay M6): devices.json is the allow-list every reconnect and the LAN gate
 * trust. It was written by truncate-then-write (a reader — or a kill -9 — between the two sees an empty or
 * half file, which load() turns into "no devices": every phone must re-pair), and from a snapshot taken
 * OUTSIDE the lock that guards the map (a concurrent change could be lost, or an older snapshot written last).
 */
class DeviceSessionsPersistTest {

    private val dir = createTempDirectory("ccp-persist").toFile()
    private val store = File(dir, "devices.json")

    private fun pub(seed: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(seed.encodeToByteArray())

    @Test
    fun a_reader_never_sees_a_half_written_allow_list() {
        val big = (0 until 2_000).associate { "device-$it" to ByteArray(32) { b -> (b + it).toByte() } }
        PairedDevices.save(big, store)
        val done = AtomicBoolean(false)
        val torn = AtomicInteger()
        val reader = thread {
            while (!done.get()) if (PairedDevices.load(store).size != big.size) torn.incrementAndGet()
        }
        repeat(300) { PairedDevices.save(big, store) }
        done.set(true)
        reader.join()
        assertEquals(0, torn.get(), "the LAN gate (load per handshake) read a truncated devices.json ${torn.get()} time(s)")
    }

    @Test
    fun concurrent_pairings_and_revocations_all_reach_disk() = runBlocking {
        val s = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = Identity.loadOrCreate(File(dir, "identity.json")),
            store = store,
        ) { _, _ -> }
        val expected = HashSet<String>()
        repeat(400) { batch ->
            val ids = (0 until 8).map { "dev-$batch-$it" }
            ids.forEach { s.onMintedTicket("ticket-$it") } // eight armed tickets for eight announces
            coroutineScope {
                ids.forEach { id -> launch(Dispatchers.Default) { s.onDevicePaired(id, pub(id)) } }
                // and revoke half of the previous batch at the same time
                if (batch > 0) (0 until 4).forEach { i -> launch(Dispatchers.Default) { s.onDeviceRevoked("dev-${batch - 1}-$i") } }
            }
            expected += ids
            if (batch > 0) (0 until 4).forEach { expected -= "dev-${batch - 1}-$it" }
            assertEquals(expected, PairedDevices.load(store).keys, "devices.json after batch $batch")
        }
    }
}
