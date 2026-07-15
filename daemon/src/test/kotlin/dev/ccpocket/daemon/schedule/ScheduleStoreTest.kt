package dev.ccpocket.daemon.schedule

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence round-trip for scheduled tasks (issue #137): a daemon restart must lose nothing. */
class ScheduleStoreTest {

    private fun tmpFile() = Files.createTempDirectory("ccp-sched").resolve("schedules.json").toFile()

    private val entry = ScheduleEntry(
        id = "id-1", workdir = "/home/u/proj", prompt = "run the nightly report",
        runAtMs = 1_720_000_000_000, intervalMs = 24 * 3600 * 1000L,
        resumeId = "sess-9", agent = AgentKind.CODEX, model = "gpt-x",
        mode = PermissionMode.ACCEPT_EDITS, label = "nightly",
        nextRunAtMs = 1_720_000_000_000, lastRunAtMs = null, lastOutcome = null,
    )

    @Test
    fun roundtrip_survives_a_reload() {
        val path = tmpFile()
        ScheduleStore.load(path).add(entry)
        val back = ScheduleStore.load(path).all()
        assertEquals(listOf(entry), back, "every field must survive the disk round-trip")
    }

    @Test
    fun update_and_remove_persist() {
        val path = tmpFile()
        val store = ScheduleStore.load(path)
        store.add(entry)
        store.update(entry.copy(nextRunAtMs = null, lastRunAtMs = 5L, lastOutcome = "ok"))
        val reloaded = ScheduleStore.load(path)
        assertNull(reloaded.byId("id-1")!!.nextRunAtMs)
        assertEquals("ok", reloaded.byId("id-1")!!.lastOutcome)
        assertTrue(reloaded.remove("id-1"))
        assertFalse(reloaded.remove("id-1"), "second remove of the same id is a no-op")
        assertTrue(ScheduleStore.load(path).all().isEmpty())
    }

    @Test
    fun corrupt_or_missing_file_loads_empty_never_throws() {
        val path = tmpFile()
        assertTrue(ScheduleStore.load(path).all().isEmpty(), "missing file → empty store")
        path.writeText("{ not json !!!")
        assertTrue(ScheduleStore.load(path).all().isEmpty(), "corrupt file → empty store, no crash at boot")
    }

    @Test
    fun unknown_future_keys_are_tolerated() {
        val path = tmpFile()
        path.writeText(
            """{"v":9,"entries":[{"id":"a","workdir":"/w","prompt":"p","runAtMs":1,"futureField":{"x":1}}],"futureTop":true}""",
        )
        val all = ScheduleStore.load(path).all()
        assertEquals(1, all.size, "a newer file format must still load the fields we know")
        assertEquals("a", all[0].id)
    }
}
