package dev.ccpocket.app.pairing

import dev.ccpocket.app.secure.SecureStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Binding roles in the paired-computer store (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.2.1-2).
 *
 * The bug being fenced off: the paired list was keyed on accountId alone, so redeeming ANY second
 * credential for a daemon you already had — then a folder-share guest invite, a feature since retired —
 * silently replaced the owner binding. One QR scan could downgrade a whole computer to one folder.
 */
class BindingRoleStoreTest {

    // Gradle runs every desktop test class in ONE JVM against ONE SecureStore file, so a test that wipes
    // the pairing keys would reach into whatever an unrelated repository test set up. Snapshot and restore.
    private val keys = listOf("paired_daemons", "paired_daemon", "active_account", "collab_links")
    private var saved: Map<String, String?> = emptyMap()

    private fun clear() = keys.forEach(SecureStore::remove)

    @BeforeTest
    fun setUp() {
        saved = keys.associateWith { SecureStore.getString(it) }
        clear()
    }

    @AfterTest
    fun tearDown() {
        clear()
        saved.forEach { (k, v) -> if (v != null) SecureStore.putString(k, v) }
    }

    private fun binding(account: String, device: String, role: BindingRole = BindingRole.OWNER) =
        PairedDaemon(relay = "wss://r", accountId = account, daemonPub = "pk", deviceId = device, credential = "c$device", role = role)

    // ── role decoding: old records, and values only a newer build knows ───────────────────────────

    @Test
    fun aRecordWrittenBeforeRolesExistedReadsAsOwner() {
        // exactly the JSON an older build persisted: no `role` key at all
        SecureStore.putString(
            "paired_daemons",
            """[{"relay":"wss://r","accountId":"a","daemonPub":"pk","deviceId":"d1","credential":"c"}]""",
        )
        val all = Pairing.loadAll()
        assertEquals(1, all.size, "a missing role must not fail the decode and unpair the computer")
        assertEquals(BindingRole.OWNER, all.single().role)
    }

    @Test
    fun anUnknownRoleFromANewerBuildDegradesToOwnerInsteadOfDroppingTheList() {
        SecureStore.putString(
            "paired_daemons",
            """[{"relay":"wss://r","accountId":"a","daemonPub":"pk","deviceId":"d1","credential":"c","role":"auditor"}]""",
        )
        assertEquals(BindingRole.OWNER, Pairing.loadAll().single().role)
    }

    /** Folder Share is retired and its `guest` role with it. No released build ever stored one (every redeem
     *  defaulted to OWNER), but were one on disk — in either spelling — it reads like any value this build does
     *  not know: OWNER, without failing the list decode or unpairing the computers beside it. */
    @Test
    fun aStoredGuestRoleFromTheRetiredFolderShareReadsAsOwner() {
        SecureStore.putString(
            "paired_daemons",
            """[{"relay":"wss://r","accountId":"a","daemonPub":"pk","deviceId":"d1","credential":"c","role":"guest"},""" +
                """{"relay":"wss://r","accountId":"b","daemonPub":"pk","deviceId":"d2","credential":"c","role":"GUEST"},""" +
                """{"relay":"wss://r","accountId":"c","daemonPub":"pk","deviceId":"d3","credential":"c","role":"owner"}]""",
        )
        val all = Pairing.loadAll()
        assertEquals(listOf("a", "b", "c"), all.map { it.accountId }, "the legacy role must not drop any computer")
        assertEquals(listOf(BindingRole.OWNER, BindingRole.OWNER, BindingRole.OWNER), all.map { it.role })
        Pairing.setActive("a")
        assertEquals("a", Pairing.active()?.accountId, "and it is a computer like any other")
    }

    @Test
    fun rolesRoundTripThroughTheStore() {
        Pairing.upsert(binding("a", "d1", BindingRole.OWNER))
        Pairing.upsert(binding("b", "d2", BindingRole.COLLABORATOR))
        assertEquals(
            mapOf("a" to BindingRole.OWNER, "b" to BindingRole.COLLABORATOR),
            Pairing.loadAll().associate { it.accountId to it.role },
        )
    }

    // ── §3.2.2: one daemon may hold several credentials, and they must not clobber each other ─────

    @Test
    fun aSecondRoleForADaemonYouOwnAddsABindingInsteadOfReplacingIt() {
        Pairing.upsert(binding("acct-a", "dev-owner", BindingRole.OWNER))
        Pairing.upsert(binding("acct-a", "dev-other", BindingRole.COLLABORATOR))
        val all = Pairing.loadAll()
        assertEquals(2, all.size, "different credentials for one daemon are two bindings, not one")
        assertEquals(setOf(BindingRole.OWNER, BindingRole.COLLABORATOR), all.map { it.role }.toSet())
    }

    @Test
    fun rePairingTheSameComputerStillRefreshesInPlace() {
        Pairing.upsert(binding("acct-a", "dev-1", BindingRole.OWNER))
        // a re-pair mints a FRESH random deviceId — keying on (accountId, deviceId) alone would leave the
        // dead first credential in the machine list forever
        Pairing.upsert(binding("acct-a", "dev-2", BindingRole.OWNER))
        val all = Pairing.loadAll()
        assertEquals(1, all.size)
        assertEquals("dev-2", all.single().deviceId)
    }

    @Test
    fun removingDaemonBindingDropsItsCredentialAndChoosesAnotherActiveComputer() {
        Pairing.upsert(binding("acct-a", "dev-a"))
        Pairing.upsert(binding("acct-b", "dev-b"))
        Pairing.setActive("acct-a")

        val remaining = Pairing.remove("acct-a")

        assertEquals(listOf("acct-b"), remaining.map { it.accountId })
        assertEquals("acct-b", Pairing.active()?.accountId)
    }

    @Test
    fun theOwnerBindingWinsWhenSeveralShareAnAccount() {
        Pairing.upsert(binding("acct-a", "dev-other", BindingRole.COLLABORATOR))
        Pairing.upsert(binding("acct-a", "dev-owner", BindingRole.OWNER))
        Pairing.setActive("acct-a")
        assertEquals("dev-owner", Pairing.active()?.deviceId, "\"switch to that computer\" means the richer credential")
    }

    // ── Collaborator Links are retired: an older build's data decodes, is ignored, and is left alone ──

    /** An older build kept Collaborator Links in their own `collab_links` entry. This build neither reads it
     *  into anything (no computer, no active account) nor rewrites or clears it. */
    @Test
    fun aStoredCollaboratorLinkIsIgnoredAndLeftAsItWas() {
        val legacy = """[{"relay":"wss://r","accountId":"acct-colleague","daemonPub":"pk","deviceId":"dev-collab",""" +
            """"credential":"c","role":"collaborator"}]"""
        SecureStore.putString("collab_links", legacy)
        Pairing.upsert(binding("acct-mine", "dev-owner", BindingRole.OWNER))
        Pairing.setActive("acct-mine")

        assertEquals(listOf("acct-mine"), Pairing.loadAll().map { it.accountId }, "a contact is not a machine")
        assertEquals("acct-mine", Pairing.active()?.accountId)
        Pairing.remove("acct-mine")
        assertNull(Pairing.active())
        assertEquals(legacy, SecureStore.getString("collab_links"), "the retired store is not touched")
    }

    /** The enum value stays so a record carrying it still DECODES — dropping it would degrade the role to
     *  OWNER (the tolerant default) and hand a contact credential the computer list's trust. */
    @Test
    fun theRetiredCollaboratorRoleStillDecodes() {
        SecureStore.putString(
            "paired_daemons",
            """[{"relay":"wss://r","accountId":"a","daemonPub":"pk","deviceId":"d1","credential":"c","role":"collaborator"}]""",
        )
        assertEquals(BindingRole.COLLABORATOR, Pairing.loadAll().single().role)
    }
}
