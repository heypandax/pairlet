package dev.ccpocket.app.pairing

import dev.ccpocket.app.secure.SecureStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Binding roles and the two SEPARATE stores (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §3.2.1-2).
 *
 * The bug being fenced off: the paired list was keyed on accountId alone, so redeeming ANY second
 * credential for a daemon you already had — a folder-share guest invite, or (before it moved out entirely)
 * a collaborator link — silently replaced the owner binding. One QR scan could downgrade a whole computer
 * to one folder, or to an inbox with no session access at all.
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

    @Test
    fun rolesRoundTripThroughTheStore() {
        Pairing.upsert(binding("a", "d1", BindingRole.OWNER))
        Pairing.upsert(binding("b", "d2", BindingRole.GUEST))
        assertEquals(
            mapOf("a" to BindingRole.OWNER, "b" to BindingRole.GUEST),
            Pairing.loadAll().associate { it.accountId to it.role },
        )
    }

    // ── §3.2.2: one daemon may hold several credentials, and they must not clobber each other ─────

    @Test
    fun aGuestShareForADaemonYouOwnAddsABindingInsteadOfReplacingIt() {
        Pairing.upsert(binding("acct-a", "dev-owner", BindingRole.OWNER))
        Pairing.upsert(binding("acct-a", "dev-guest", BindingRole.GUEST))
        val all = Pairing.loadAll()
        assertEquals(2, all.size, "different credentials for one daemon are two bindings, not one")
        assertEquals(setOf(BindingRole.OWNER, BindingRole.GUEST), all.map { it.role }.toSet())
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
        Pairing.upsert(binding("acct-a", "dev-guest", BindingRole.GUEST))
        Pairing.upsert(binding("acct-a", "dev-owner", BindingRole.OWNER))
        Pairing.setActive("acct-a")
        assertEquals("dev-owner", Pairing.active()?.deviceId, "\"switch to that computer\" means the richer credential")
    }

    // ── §3.2.2: collaborator links are a separate store — an inbox, never a computer ──────────────

    @Test
    fun collaboratorLinksNeverEnterTheComputerList() {
        Pairing.upsert(binding("acct-mine", "dev-owner", BindingRole.OWNER))
        Pairing.setActive("acct-mine")
        Pairing.upsertCollaborator(binding("acct-colleague", "dev-collab", BindingRole.COLLABORATOR))

        assertEquals(listOf("acct-mine"), Pairing.loadAll().map { it.accountId }, "a contact is not a machine")
        assertEquals(listOf("acct-colleague"), Pairing.collaboratorLinks().map { it.accountId })
        assertEquals("acct-mine", Pairing.active()?.accountId, "connecting a colleague must not switch your active computer")
    }

    @Test
    fun aCollaboratorLinkForADaemonYouAlsoOwnLeavesTheOwnerBindingAlone() {
        Pairing.upsert(binding("acct-a", "dev-owner", BindingRole.OWNER))
        Pairing.upsertCollaborator(binding("acct-a", "dev-collab", BindingRole.COLLABORATOR))
        assertEquals(BindingRole.OWNER, Pairing.loadAll().single().role)
        assertEquals("dev-collab", Pairing.collaboratorLinks().single().deviceId)
    }

    @Test
    fun reScanningAContactSupersedesTheDeadCredential() {
        Pairing.upsertCollaborator(binding("acct-colleague", "dev-1", BindingRole.COLLABORATOR))
        Pairing.upsertCollaborator(binding("acct-colleague", "dev-2", BindingRole.COLLABORATOR))
        val links = Pairing.collaboratorLinks()
        assertEquals(1, links.size, "the old credential is dead the moment the new one exists")
        assertEquals("dev-2", links.single().deviceId)
    }

    @Test
    fun theStoredRoleIsForcedEvenIfTheCallerPassesTheWrongOne() {
        Pairing.upsertCollaborator(binding("acct-x", "dev-x", BindingRole.OWNER))
        assertEquals(BindingRole.COLLABORATOR, Pairing.collaboratorLinks().single().role)
    }

    @Test
    fun removingAContactDropsOnlyItsLink() {
        Pairing.upsertCollaborator(binding("acct-1", "d1", BindingRole.COLLABORATOR))
        Pairing.upsertCollaborator(binding("acct-2", "d2", BindingRole.COLLABORATOR))
        Pairing.removeCollaborator("acct-1")
        assertEquals(listOf("acct-2"), Pairing.collaboratorLinks().map { it.accountId })
    }

    @Test
    fun anEmptyCollaboratorStoreIsNotAnError() {
        assertTrue(Pairing.collaboratorLinks().isEmpty())
        SecureStore.putString("collab_links", "not json at all")
        assertTrue(Pairing.collaboratorLinks().isEmpty(), "a corrupt inbox store degrades to empty, never crashes")
        assertNull(Pairing.active())
    }
}
