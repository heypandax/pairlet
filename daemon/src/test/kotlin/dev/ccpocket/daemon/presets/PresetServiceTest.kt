package dev.ccpocket.daemon.presets

import dev.ccpocket.protocol.AuthBlockReason
import dev.ccpocket.protocol.AuthBlocker
import dev.ccpocket.protocol.DeletePreset
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.PresetsState
import dev.ccpocket.protocol.SavePreset
import dev.ccpocket.protocol.Secret
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PresetService: the account-switch semantics (mid-task refusal / idle auto-close / force) applied
 *  to preset activation, and the secrets red line on every emitted frame. */
class PresetServiceTest {

    private class Harness(
        var busy: List<AuthBlocker> = emptyList(),
    ) {
        val store = PresetStore.load(File(Files.createTempDirectory("preset-svc").toFile(), "presets.json"))
        var idleClosed = 0
        var busyClosed = 0
        val emitted = mutableListOf<Frame>()
        val service = PresetService(
            store,
            busyConversations = { busy },
            closeIdleConversations = { idleClosed++; 1 },
            closeBusyConversations = { busyClosed++; busy = emptyList(); 2 },
        )
        val emit: suspend (Frame) -> Unit = { emitted += it }
        fun lastState() = emitted.last() as PresetsState
    }

    private fun blocker() = AuthBlocker(convoId = "c1", sessionId = "s1", cwd = "/w/acme-web", reason = AuthBlockReason.EXECUTING)

    private fun seed(h: Harness, name: String = "Work proxy"): String {
        runBlocking { h.service.save(SavePreset(name = name, baseUrl = "https://api.example.com", token = Secret("sk-proxy-9f2a4c8e3f9a")), h.emit) }
        return h.lastState().presets.first { it.name == name }.id
    }

    @Test
    fun activate_closes_idle_and_switches_when_nothing_is_busy() = runBlocking {
        val h = Harness()
        val id = seed(h)
        h.service.activate(id, force = false, h.emit)
        val s = h.lastState()
        assertEquals(id, s.activeId)
        assertNull(s.error)
        assertEquals(1, h.idleClosed) // idle conversations resume from disk under the new env
        assertEquals(0, h.busyClosed)
    }

    @Test
    fun activate_is_refused_with_blockers_while_a_conversation_is_mid_task() = runBlocking {
        val h = Harness(busy = listOf(blocker()))
        val id = seed(h)
        h.service.activate(id, force = false, h.emit)
        val s = h.lastState()
        assertNull(s.activeId)                       // switch did NOT happen
        assertEquals(listOf("c1"), s.blockers.map { it.convoId })
        assertTrue(assertNotNull(s.error).contains("acme-web"))
        assertEquals(0, h.idleClosed)                // refusal closes nothing
    }

    @Test
    fun force_activate_stops_busy_conversations_then_switches() = runBlocking {
        val h = Harness(busy = listOf(blocker()))
        val id = seed(h)
        h.service.activate(id, force = true, h.emit)
        val s = h.lastState()
        assertEquals(id, s.activeId)
        assertNull(s.error)
        assertEquals(1, h.busyClosed)
        assertEquals(1, h.idleClosed)
    }

    @Test
    fun reactivating_the_active_preset_is_a_noop_that_closes_nothing() = runBlocking {
        val h = Harness()
        val id = seed(h)
        h.service.activate(id, force = false, h.emit)
        val closesAfterSwitch = h.idleClosed
        h.service.activate(id, force = false, h.emit)
        assertEquals(id, h.lastState().activeId)
        assertEquals(closesAfterSwitch, h.idleClosed) // no second close sweep
    }

    @Test
    fun deleting_the_active_preset_runs_the_switch_gate_deleting_idle_does_not() = runBlocking {
        val h = Harness()
        val a = seed(h, "A")
        val b = seed(h, "B")
        h.service.activate(a, force = false, h.emit)
        val closes = h.idleClosed

        h.service.delete(DeletePreset(b), h.emit)    // idle preset: no gate, no closes
        assertEquals(closes, h.idleClosed)
        assertEquals(listOf("A"), h.lastState().presets.map { it.name })

        h.busy = listOf(blocker())                   // active preset + busy session: refused
        h.service.delete(DeletePreset(a), h.emit)
        assertEquals(1, h.lastState().blockers.size)
        assertEquals(listOf("A"), h.lastState().presets.map { it.name })

        h.busy = emptyList()                         // …until idle: gate closes idles, then deletes
        h.service.delete(DeletePreset(a), h.emit)
        assertTrue(h.lastState().presets.isEmpty())
        assertNull(h.lastState().activeId)
        assertEquals(closes + 1, h.idleClosed)
    }

    @Test
    fun save_validation_reports_field_and_never_mutates() = runBlocking {
        val h = Harness()
        seed(h)
        h.service.save(SavePreset(name = "Work proxy", baseUrl = "https://other.example", token = Secret("sk-other-1234567890")), h.emit)
        val s = h.lastState()
        assertEquals("name", s.fieldError)
        assertTrue(assertNotNull(s.error).contains("already exists"))
        assertEquals(1, s.presets.size)
    }

    @Test
    fun every_emitted_frame_is_mask_only_no_plaintext_token_ever_rides_toPhone() = runBlocking {
        val h = Harness(busy = listOf(blocker()))
        val id = seed(h)
        h.service.activate(id, force = false, h.emit) // refusal frame
        h.busy = emptyList()
        h.service.activate(id, force = false, h.emit) // success frame
        h.service.sendState(h.emit)                   // plain fetch

        h.emitted.forEach { f ->
            val json = PocketJson.encodeToString(Envelope(id = "x", ts = 0, body = f))
            assertFalse(json.contains("sk-proxy-9f2a4c8e3f9a"), "plaintext token leaked into a frame: $json")
            assertTrue(json.contains("sk-…••••3f9a"), "mask missing from state frame: $json")
        }
    }
}
