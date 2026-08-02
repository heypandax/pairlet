package dev.ccpocket.daemon.handoff

import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.SessionControllerLease
import dev.ccpocket.protocol.SessionHandoff
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence of handoffs + leases (SESSION-HANDOFF.md §9.2): keyed upserts, reload fidelity,
 *  the one-lease-per-session file shape, 0600, and corrupt-file resilience. */
class HandoffStoreTest {

    private val dir = createTempDirectory("ccp-handoff-store").toFile()
    private val path = File(dir, "handoffs.json")

    private fun handoff(id: String, session: String = "s1", status: HandoffStatus = HandoffStatus.WAITING) =
        SessionHandoff(
            id = id, sourceSessionId = session, workdir = "/w", initiatorDeviceId = "devA",
            status = status, createdAt = 1, expiresAt = 2,
        )

    private fun lease(session: String = "s1", controller: String = "devB") = SessionControllerLease(
        sessionId = session, handoffId = "h1", controllerDeviceId = controller,
        acquiredAt = 1, leaseExpiresAt = 9,
    )

    @Test
    fun puts_are_keyed_upserts_and_survive_a_reload() {
        val store = HandoffStore.load(path)
        store.putHandoff(handoff("h1"))
        store.putHandoff(handoff("h2", session = "s2"))
        store.putHandoff(handoff("h1", status = HandoffStatus.IN_PROGRESS)) // replace, not append
        store.putLease(lease())

        val back = HandoffStore.load(path)
        assertEquals(2, back.handoffs().size)
        assertEquals(HandoffStatus.IN_PROGRESS, back.handoffById("h1")!!.status)
        assertEquals("devB", back.leaseOf("s1")!!.controllerDeviceId)
        assertNull(back.leaseOf("s2"))
    }

    @Test
    fun the_file_cannot_hold_two_leases_for_one_session() {
        val store = HandoffStore.load(path)
        store.putLease(lease(controller = "devB"))
        store.putLease(lease(controller = "devC")) // keyed on sessionId — replaces
        assertEquals(1, store.leases().size)
        assertEquals("devC", store.leaseOf("s1")!!.controllerDeviceId)
        assertTrue(store.removeLease("s1"))
        assertFalse(store.removeLease("s1"), "second remove reports nothing held")
    }

    @Test
    fun replaceAll_lands_both_lists_in_one_persist() {
        val store = HandoffStore.load(path)
        store.putHandoff(handoff("h1"))
        store.putLease(lease())
        store.replaceAll(listOf(handoff("h9", session = "s9")), emptyList())
        val back = HandoffStore.load(path)
        assertEquals(listOf("h9"), back.handoffs().map { it.id })
        assertTrue(back.leases().isEmpty())
    }

    @Test
    fun the_store_file_is_owner_only_on_posix() {
        val store = HandoffStore.load(path)
        store.putHandoff(handoff("h1"))
        val perms = runCatching { Files.getPosixFilePermissions(path.toPath()) }.getOrNull()
            ?: return // non-POSIX filesystem (Windows CI) — the ACL inherits the profile dir
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun a_missing_or_corrupt_file_loads_empty() {
        assertTrue(HandoffStore.load(path).handoffs().isEmpty())
        path.parentFile.mkdirs()
        path.writeText("not json at all {")
        val store = HandoffStore.load(path)
        assertTrue(store.handoffs().isEmpty())
        assertTrue(store.leases().isEmpty())
        store.putHandoff(handoff("h1")) // and it heals on the next write
        assertEquals(1, HandoffStore.load(path).handoffs().size)
    }
}
