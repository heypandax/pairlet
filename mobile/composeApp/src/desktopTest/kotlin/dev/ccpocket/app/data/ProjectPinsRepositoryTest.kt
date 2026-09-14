package dev.ccpocket.app.data

import dev.ccpocket.app.net.PinEnqueueResult
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.pins.FileProjectPinPersistence
import dev.ccpocket.app.pins.PinFileListing
import dev.ccpocket.app.pins.PinFileRead
import dev.ccpocket.app.pins.PinFileWrite
import dev.ccpocket.app.pins.PinScopeKey
import dev.ccpocket.app.pins.PinSyncIssue
import dev.ccpocket.app.pins.ProjectPinPersistence
import dev.ccpocket.app.pins.ProjectPinRegistry
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Project-pin sync as the repository drives it (issue #362), with frames fed in and captured at the pin writer seam —
 * no network. What is pinned here is the wiring: per-computer scopes at every binding edge, fetch-first sync
 * generations started only by a pin-capable DaemonInfo, correlation of replies and pushes, durable outbox
 * behaviour across restarts, the re-pair quarantine, the legacy migration hook, and isolation of guest/demo pins.
 */
class ProjectPinsRepositoryTest {

    private val dir: File = Files.createTempDirectory("ccp-repo-pins").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val registry = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))

    // switchDaemon persists the active account; the desktop test store is shared across this Test task
    private var savedActive: String? = null

    @BeforeTest
    fun setUp() {
        savedActive = Pairing.activeAccount()
        PinIssueBoard.shared.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        Pairing.setActive(savedActive)
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun binding(id: String, device: String = "dev-$id", role: BindingRole = BindingRole.OWNER) =
        PairedDaemon(relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = device, credential = "c-$id", role = role)

    /** The pairing store's authority as the primary would install it, for a registry these tests construct. */
    private fun seeded(reg: ProjectPinRegistry) = reg.apply { initializeAuthorityOnce { listOf(binding("acct-a"), binding("acct-b")) } }

    /** A pinned (satellite-style) repository on the paired transport that captures the pin frames it queues. */
    private fun repo(target: PairedDaemon, sent: MutableList<Frame>, reg: ProjectPinRegistry = registry) =
        PocketRepository(scope, pinnedTo = target, projectPinRegistry = reg).apply { capturePins(sent) }

    private fun PocketRepository.capturePins(sent: MutableList<Frame>) {
        onSendForTest = { sent += it }
        pinWriterForTest = { frame, _ -> sent += frame; PinEnqueueResult.ACCEPTED }
        useRelay = true
    }

    private fun MutableList<Frame>.pins() = filterIsInstance<SyncProjectPins>()

    private fun snapshot(rev: Long, vararg paths: String, inc: String = "inc-1") =
        ProjectPinsSnapshot(inc, rev, paths.map { ProjectPin(it, "K" + it.hashCode().toUInt().toString(16)) })

    private fun ack(request: SyncProjectPins, ackSeq: Long, snap: ProjectPinsSnapshot?, error: String? = null) =
        ProjectPinsState(request.subscriptionId, request.requestId, request.streamId, ackSeq, snap, error)

    private fun doc(account: String, reg: ProjectPinRegistry = registry) = reg.scope(PinScopeKey.Owner(account)).document()

    /** Start a generation and answer its fetch: returns the fetch request. */
    private fun PocketRepository.syncWith(sent: MutableList<Frame>, snap: ProjectPinsSnapshot, ackSeq: Long = 0): SyncProjectPins {
        receiveForTest(DaemonInfo(supportsProjectPins = true))
        val fetch = sent.pins().last()
        assertTrue(fetch.ops.isEmpty(), "every generation starts with a fetch")
        receiveForTest(ack(fetch, ackSeq, snap))
        return fetch
    }

    @Test
    fun an_old_daemon_keeps_pins_per_computer_with_their_intent_and_gets_no_pin_frame() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/one")
        assertEquals(listOf("/p/one"), a.pinnedPaths)
        a.receiveForTest(DaemonInfo()) // a daemon that predates #362
        assertTrue(sent.pins().isEmpty(), "no pin frame to a daemon that never advertised pin sync")

        val restarted = ProjectPinRegistry(FileProjectPinPersistence(dir))
        assertEquals(listOf(ProjectPinOp(1, "/p/one", true)), doc("acct-a", restarted).pending, "the intent is durable")
        assertTrue(repo(binding("acct-b"), sent).pinnedPaths.isEmpty(), "another computer's pins are its own")
    }

    @Test
    fun a_generation_fetches_first_then_flushes_and_an_ack_clears_the_outbox() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/one")
        a.receiveForTest(DaemonInfo(supportsProjectPins = true))
        val fetch = sent.pins().single()
        assertTrue(fetch.ops.isEmpty(), "no operation leaves before the fetch reply")

        a.receiveForTest(ack(fetch, 0, snapshot(0)))
        val flush = sent.pins().last()
        assertEquals(listOf(ProjectPinOp(1, "/p/one", true)), flush.ops)
        assertEquals(fetch.subscriptionId, flush.subscriptionId)
        assertEquals("inc-1", flush.expectedIncarnation, "a non-empty request names the store it was queued against")
        assertEquals(1, doc("acct-a").stream.sentSeq, "the batch was marked possibly-sent before it left")

        a.receiveForTest(ack(flush, 1, snapshot(1, "/p/one")))
        assertTrue(doc("acct-a").pending.isEmpty())
        assertEquals(listOf("/p/one"), a.pinnedPaths)
        assertEquals(2, sent.pins().size, "nothing more to send")
    }

    @Test
    fun replies_and_pushes_from_a_retired_generation_are_ignored() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/x")
        a.receiveForTest(DaemonInfo(supportsProjectPins = true))
        val oldFetch = sent.pins().single()
        a.receiveForTest(DaemonInfo(supportsProjectPins = true)) // the link reconnected
        val newFetch = sent.pins().last()
        assertNotEquals(oldFetch.subscriptionId, newFetch.subscriptionId)

        a.receiveForTest(ack(oldFetch, 0, snapshot(0)))
        a.receiveForTest(ProjectPinsState(oldFetch.subscriptionId, snapshot = snapshot(7, "/p/ghost")))
        assertEquals(2, sent.pins().size, "a late reply from the retired generation starts no flush")
        assertEquals(null, doc("acct-a").stream.incarnation, "…and binds nothing")
        assertEquals(listOf("/p/x"), a.pinnedPaths)

        a.receiveForTest(ack(newFetch, 0, snapshot(0)))
        assertEquals(listOf(ProjectPinOp(1, "/p/x", true)), sent.pins().last().ops)
    }

    @Test
    fun a_computer_switch_retires_the_generation_and_shows_only_the_targets_pins() {
        val sent = mutableListOf<Frame>()
        val a = binding("acct-a"); val b = binding("acct-b")
        repo(b, mutableListOf()).togglePin("/b/only") // B's own list, written through its own scope
        val primary = PocketRepository(scope, projectPinRegistry = registry).apply {
            capturePins(sent)
            paired.value = a
        }
        primary.switchDaemon(a) // binds A (dials a closed port and fails fast — no real network)
        primary.togglePin("/a/only")
        primary.receiveForTest(DaemonInfo(supportsProjectPins = true))
        val aFetch = sent.pins().last()

        primary.switchDaemon(b)
        assertEquals(listOf("/b/only"), primary.pinnedPaths, "the target's own scope, never the outgoing list")
        primary.receiveForTest(ack(aFetch, 0, snapshot(3, "/a/remote")))
        assertEquals(listOf("/b/only"), primary.pinnedPaths, "A's late reply cannot apply to B")
        assertEquals(null, doc("acct-a").stream.incarnation)
        assertTrue(doc("acct-b").accepted == null)
    }

    @Test
    fun a_fleet_promote_keeps_each_computers_own_pins() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val outgoing = repo(a, mutableListOf()).apply { togglePin("/a/only") }
        val hot = repo(b, mutableListOf()).apply { togglePin("/b/only") }
        hot.adoptShellState(outgoing)
        assertEquals(listOf("/b/only"), hot.pinnedPaths)
        assertEquals(listOf("/a/only"), outgoing.pinnedPaths)
    }

    @Test
    fun a_restart_before_the_ack_resends_the_same_sequence_or_recovers_the_lost_ack() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/one")
        a.syncWith(sent, snapshot(0))
        val firstSend = sent.pins().last()
        assertEquals(1, firstSend.ops.single().seq)

        // the app dies before any reply; nothing committed on the daemon
        val sent2 = mutableListOf<Frame>()
        val reg2 = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))
        val a2 = repo(binding("acct-a"), sent2, reg2)
        assertEquals(listOf("/p/one"), a2.pinnedPaths)
        val fetch2 = a2.syncWith(sent2, snapshot(0), ackSeq = 0)
        assertEquals(firstSend.streamId, fetch2.streamId)
        assertEquals(firstSend.ops, sent2.pins().last().ops, "the same operation with the same sequence — deduplicated if it did land")

        // another restart: this time it HAD committed and only the ACK was lost
        val sent3 = mutableListOf<Frame>()
        val reg3 = seeded(ProjectPinRegistry(FileProjectPinPersistence(dir)))
        val a3 = repo(binding("acct-a"), sent3, reg3)
        a3.syncWith(sent3, snapshot(1, "/p/one"), ackSeq = 1)
        assertTrue(doc("acct-a", reg3).pending.isEmpty(), "the fetch's cursor clears the intent; it is never fresh intent again")
        val lookup = sent3.pins().last()
        assertEquals(2, sent3.pins().size, "only a lookup of the acknowledged operation follows, once")
        assertEquals(firstSend.ops, lookup.ops, "…with its original sequence, so the daemon deduplicates it")
        assertEquals("inc-1", lookup.expectedIncarnation)
    }

    @Test
    fun two_repositories_for_one_computer_never_allocate_the_same_sequence() {
        val r1 = repo(binding("acct-a"), mutableListOf())
        val r2 = repo(binding("acct-a"), mutableListOf())
        r1.togglePin("/one"); r2.togglePin("/two"); r1.togglePin("/three"); r2.togglePin("/one")
        assertEquals(
            listOf(ProjectPinOp(1, "/one", true), ProjectPinOp(2, "/two", true), ProjectPinOp(3, "/three", true), ProjectPinOp(4, "/one", false)),
            doc("acct-a").pending,
        )
        assertEquals(listOf("/three", "/two"), r1.pinnedPaths)
        assertEquals(r1.pinnedPaths.toList(), r2.pinnedPaths.toList(), "both holders see the one shared list")
    }

    @Test
    fun re_pairing_retains_uncertain_intent_locally_and_never_sends_it_again() {
        registry.refreshAfterPairingChange { listOf(binding("acct-a", device = "dev-1")) }
        val sent = mutableListOf<Frame>()
        val before = repo(binding("acct-a", device = "dev-1"), sent)
        before.togglePin("/p/x")
        before.syncWith(sent, snapshot(0))
        val oldStream = sent.pins().last().streamId // pinned, sent — committed, but the ACK never arrived

        // the same computer re-paired: a fresh transport identity; meanwhile a sibling unpinned /p/x
        registry.refreshAfterPairingChange { listOf(binding("acct-a", device = "dev-2")) }
        val sent2 = mutableListOf<Frame>()
        val after = repo(binding("acct-a", device = "dev-2"), sent2)
        after.syncWith(sent2, snapshot(2))
        assertEquals(1, sent2.pins().size, "only the fetch: the uncertain pin is not replayed")
        assertEquals(listOf("/p/x"), after.pinnedPaths, "kept on this device only")
        assertEquals(PinSyncIssue.RetainedLocally(1), after.projectPinSyncIssue.value)
        assertEquals(PinIssueKind.RETAINED_LOCALLY, after.projectPinIssueNotice.value?.kind)

        after.togglePin("/p/x") // the user re-confirms: an explicit unpin supersedes the retained intent
        val confirm = sent2.pins().last()
        assertNotEquals(oldStream, confirm.streamId)
        assertEquals(listOf(ProjectPinOp(1, "/p/x", false)), confirm.ops)
        assertEquals(2, sent.pins().size, "the old identity's repository sent its fetch and flush, and nothing on the new stream")
    }

    @Test
    fun the_primary_places_the_legacy_list_once_and_leaves_the_legacy_key_untouched() {
        val keys = listOf("paired_daemons", "active_account", "pinned_projects")
        val saved = keys.associateWith { SecureStore.getString(it) }
        try {
            val owner = binding("acct-legacy")
            SecureStore.remove("paired_daemons")
            Pairing.upsert(owner)
            SecureStore.putString("pinned_projects", "/legacy/new\n/legacy/old")

            val primary = PocketRepository(scope, projectPinRegistry = registry)
            assertEquals(listOf("/legacy/new", "/legacy/old"), primary.pinnedPaths)
            assertEquals(listOf("/legacy/old", "/legacy/new"), doc("acct-legacy").pending.map { it.path }, "oldest first")
            PocketRepository(scope, projectPinRegistry = registry) // a second primary (or a restart) claims nothing more
            assertEquals(2, doc("acct-legacy").pending.size)
            assertEquals("/legacy/new\n/legacy/old", SecureStore.getString("pinned_projects"), "the backup key is only read")
        } finally {
            saved.forEach { (k, v) -> if (v == null) SecureStore.remove(k) else SecureStore.putString(k, v) }
        }
    }

    @Test
    fun guest_and_demo_pins_stay_local_and_send_no_pin_frames() {
        val sent = mutableListOf<Frame>()
        val guest = repo(binding("acct-a", role = BindingRole.GUEST), sent)
        guest.togglePin("/shared/folder")
        guest.receiveForTest(DaemonInfo(supportsProjectPins = true))
        assertTrue(sent.pins().isEmpty())
        assertEquals(listOf("/shared/folder"), guest.pinnedPaths)
        assertTrue(doc("acct-a").pending.isEmpty(), "a guest binding never writes the owner's list")

        val demoSent = mutableListOf<Frame>()
        val demo = PocketRepository(scope, projectPinRegistry = registry).apply { capturePins(demoSent); useRelay = false }
        demo.enterDemo()
        demo.togglePin("/demo/project")
        assertEquals(listOf("/demo/project"), demo.pinnedPaths)
        demo.disconnect()
        assertTrue("/demo/project" !in demo.pinnedPaths, "the demo's pins leave with the demo")
        assertTrue(demoSent.pins().isEmpty())
        assertTrue(dir.listFiles().orEmpty().none { it.readText().contains("/demo/project") }, "never written anywhere")
    }

    @Test
    fun pushes_apply_only_to_the_current_subscription_and_an_unfamiliar_store_triggers_a_fetch() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        val fetch = a.syncWith(sent, snapshot(1, "/p/a"))
        a.receiveForTest(ProjectPinsState("someone-else-0123456789", snapshot = snapshot(5, "/p/zzz")))
        assertEquals(listOf("/p/a"), a.pinnedPaths)
        a.receiveForTest(ProjectPinsState(fetch.subscriptionId, snapshot = snapshot(2, "/p/b", "/p/a")))
        assertEquals(listOf("/p/b", "/p/a"), a.pinnedPaths)
        val before = sent.pins().size
        a.receiveForTest(ProjectPinsState(fetch.subscriptionId, snapshot = snapshot(9, inc = "inc-2")))
        assertEquals(listOf("/p/b", "/p/a"), a.pinnedPaths, "an unfamiliar store is never adopted from a push")
        assertEquals(before + 1, sent.pins().size)
        assertTrue(sent.pins().last().ops.isEmpty(), "…it asks for a correlated fetch instead")
    }

    @Test
    fun a_refusal_waits_for_the_next_trigger_instead_of_retrying() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/one")
        a.syncWith(sent, snapshot(0))
        val flush = sent.pins().last()
        a.receiveForTest(ack(flush, 0, snapshot(0), error = ProjectPinErrors.CAPACITY))
        assertEquals(PinSyncIssue.Refused(ProjectPinErrors.CAPACITY, null), a.projectPinSyncIssue.value)
        assertEquals(2, sent.pins().size, "no immediate resend")
        assertEquals(1, doc("acct-a").pending.size, "the refused change stays queued")

        a.togglePin("/p/two") // a new explicit action is a trigger
        assertEquals(listOf(ProjectPinOp(1, "/p/one", true), ProjectPinOp(2, "/p/two", true)), sent.pins().last().ops)
    }

    @Test
    fun only_this_computers_own_listing_proves_a_legacy_fallback_path() {
        val sent = mutableListOf<Frame>()
        registry.migrateLegacyIfNeeded("/l/mine\n/l/elsewhere", listOf(binding("acct-a"), binding("acct-b")), binding("acct-a"))
        val a = repo(binding("acct-a"), sent)
        assertEquals(listOf("/l/mine", "/l/elsewhere"), a.pinnedPaths)
        a.receiveForTest(Directories(listOf(DirectoryEntry("/l/mine", "mine", isDir = true))))
        assertTrue(doc("acct-a").pending.isEmpty(), "no claim without a syncing connection to this computer")
        a.syncWith(sent, snapshot(0))
        a.receiveForTest(Directories(listOf(DirectoryEntry("/l/mine", "mine", isDir = true))))
        assertEquals(listOf(ProjectPinOp(1, "/l/mine", true)), sent.pins().last().ops)
        assertEquals(listOf("/l/elsewhere"), doc("acct-a").legacy?.fallback, "the unproven path stays local")
    }

    @Test
    fun a_late_reply_for_an_abandoned_stream_releases_the_slot_and_the_new_stream_goes_out() {
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        a.togglePin("/p/x")
        val fetch = a.syncWith(sent, snapshot(1))
        val staleFlush = sent.pins().last() // in flight, unanswered
        a.togglePin("/p/y") // queued behind the in-flight batch: certainly never sent
        a.receiveForTest(ProjectPinsState(fetch.subscriptionId, snapshot = snapshot(4, inc = "inc-2"))) // store reset
        val refetch = sent.pins().last()
        assertTrue(refetch.ops.isEmpty())
        a.receiveForTest(ack(refetch, 0, snapshot(0, inc = "inc-2"))) // rebases: /p/x retained, /p/y moves to seq 1
        val resumed = sent.pins().last()
        assertNotEquals(staleFlush.streamId, resumed.streamId)
        assertEquals(listOf(ProjectPinOp(1, "/p/y", true)), resumed.ops, "the new stream is flushed once the fetch lands")
        assertEquals("inc-2", resumed.expectedIncarnation)

        a.receiveForTest(ack(staleFlush, 1, snapshot(2, "/p/x"))) // the old stream's reply finally lands
        assertEquals(resumed, sent.pins().last(), "it acknowledges nothing on the new stream and sends nothing")
        assertEquals(listOf("/p/x"), doc("acct-a").quarantined.map { it.path }, "the uncertain pin is still never sent")
    }

    /** Real file storage whose listing, re-read or save can be made to fail; counts document reads and saves. */
    private class Flaky(private val real: ProjectPinPersistence) : ProjectPinPersistence by real {
        var failList = false
        var failRecover = false
        var failWrite = false
        var io = 0
        override fun list(): PinFileListing = if (failList) PinFileListing.Failed else real.list()
        override fun read(name: String): PinFileRead = real.read(name).also { io++ }
        override fun recover(name: String): PinFileRead = (if (failRecover) PinFileRead.Failed("io") else real.recover(name)).also { io++ }
        override fun write(name: String, text: String): PinFileWrite = (if (failWrite) PinFileWrite.NotWritten else real.write(name, text)).also { io++ }
    }

    @Test
    fun a_retry_that_reads_and_writes_nothing_leaves_a_dismissed_save_failure_which_a_real_save_ends() {
        val flaky = Flaky(FileProjectPinPersistence(File(dir, "unwritable"))).apply { failWrite = true }
        val reg = seeded(ProjectPinRegistry(flaky))
        val sent = mutableListOf<Frame>()
        val owner = repo(binding("acct-a"), sent, reg)
        val local = ProjectPinLink(reg, scope, onVisible = {}).apply { bind(null, demo = false) }
        val ownerKey = PinScopeKey.Owner("acct-a")
        owner.togglePin("/p/one")
        local.setPinned("/u/one", true)
        val ownerFailure = owner.projectPinIssueNotice.value
        val localFailure = local.notice.value
        assertEquals(PinIssueKind.STORAGE_FAILED, ownerFailure?.kind)
        assertEquals(PinIssueKind.STORAGE_FAILED, localFailure?.kind)
        owner.dismissProjectPinIssue(ownerFailure!!)
        local.dismiss(localFailure!!)
        assertNull(reg.scope(ownerKey).storageIssue(), "a save that failed before replacing anything leaves the scope ready")

        val io = flaky.io
        owner.retryProjectPins()
        local.retry()
        assertEquals(io, flaky.io, "the retry read and wrote nothing")
        assertEquals(PinSyncIssue.StorageFailed, owner.projectPinSyncIssue.value, "…so it proved no recovery")
        assertEquals(PinSyncIssue.StorageFailed, local.issue.value)
        assertEquals(ownerFailure.id, PinIssueBoard.shared.occurrenceIdForTest(ownerKey, PinIssueKind.STORAGE_FAILED))
        assertEquals(localFailure.id, PinIssueBoard.shared.occurrenceIdForTest(PinScopeKey.Unpaired, PinIssueKind.STORAGE_FAILED))
        assertNull(owner.projectPinIssueNotice.value, "still dismissed")
        assertNull(local.notice.value)

        flaky.failWrite = false // saving works again: an actual save proves it
        owner.togglePin("/p/one")
        local.setPinned("/u/one", true)
        assertEquals(io + 2, flaky.io)
        assertNull(PinIssueBoard.shared.occurrenceIdForTest(ownerKey, PinIssueKind.STORAGE_FAILED))
        assertNull(PinIssueBoard.shared.occurrenceIdForTest(PinScopeKey.Unpaired, PinIssueKind.STORAGE_FAILED))
        assertNull(owner.projectPinSyncIssue.value)
        assertNull(local.issue.value)
        assertEquals(listOf("/p/one"), owner.pinnedPaths)
        assertEquals(listOf("/u/one"), local.visible())
        assertTrue(sent.pins().isEmpty())
    }

    @Test
    fun a_working_sync_and_edit_leave_unresolved_migration_problems_on_screen() {
        // an old pin this computer cannot accept stays in the local fallback
        registry.migrateLegacyIfNeeded("/l/ok\n/l/bad", listOf(binding("acct-a"), binding("acct-b")), binding("acct-a"))
        val sent = mutableListOf<Frame>()
        val a = repo(binding("acct-a"), sent)
        assertEquals(PinSyncIssue.LegacyNotMigrated(1), a.projectPinSyncIssue.value)
        val legacy = a.projectPinIssueNotice.value
        assertEquals(PinIssueKind.LEGACY_NOT_MIGRATED, legacy?.kind)
        a.syncWith(sent, snapshot(0))
        assertEquals(PinSyncIssue.LegacyNotMigrated(1), a.projectPinSyncIssue.value, "a working fetch does not settle the old pin")
        assertEquals(legacy, a.projectPinIssueNotice.value, "…and it is still the same notice")
        a.togglePin("/p/new")
        assertEquals(PinSyncIssue.LegacyNotMigrated(1), a.projectPinSyncIssue.value, "nor does an edit")
        a.receiveForTest(ack(sent.pins().last(), 1, snapshot(1, "/p/new")))
        assertEquals(legacy, a.projectPinIssueNotice.value, "nor its acknowledgement")

        // the old list could not be assigned at all: registry-wide uncertainty, which no pin sync can settle
        val flaky = Flaky(FileProjectPinPersistence(File(dir, "uncertain"))).apply { failList = true }
        val reg = seeded(ProjectPinRegistry(flaky))
        reg.migrateLegacyIfNeeded("/u/one", listOf(binding("acct-a"), binding("acct-b")), binding("acct-a"))
        flaky.failList = false
        val sentU = mutableListOf<Frame>()
        val u = repo(binding("acct-a"), sentU, reg)
        assertEquals(PinSyncIssue.MigrationUncertain, u.projectPinSyncIssue.value)
        val uncertain = u.projectPinIssueNotice.value
        u.syncWith(sentU, snapshot(0))
        u.togglePin("/p/new")
        u.receiveForTest(ack(sentU.pins().last(), 1, snapshot(1, "/p/new")))
        assertEquals(PinSyncIssue.MigrationUncertain, u.projectPinSyncIssue.value)
        assertEquals(uncertain, u.projectPinIssueNotice.value)
    }

    @Test
    fun retry_recovers_readable_storage_for_guest_and_unpaired_pins_and_sends_no_pin_frame() {
        val guestBinding = binding("acct-g", role = BindingRole.GUEST)
        repo(guestBinding, mutableListOf()).togglePin("/g/saved") // saved while storage worked
        ProjectPinLink(registry, scope, onVisible = {}).apply { bind(null, demo = false); setPinned("/u/saved", true) }

        val flaky = Flaky(FileProjectPinPersistence(dir)).apply { failRecover = true }
        val reg = seeded(ProjectPinRegistry(flaky))
        val sent = mutableListOf<Frame>()
        val guest = repo(guestBinding, sent, reg)
        val unpairedVisible = mutableListOf<List<String>>()
        val unpaired = ProjectPinLink(reg, scope, onVisible = { unpairedVisible += it }).apply { bind(null, demo = false) }
        val owner = repo(binding("acct-a"), sent, reg)
        for (notice in listOf(guest.projectPinIssueNotice.value, unpaired.notice.value, owner.projectPinIssueNotice.value)) {
            assertEquals(PinIssueKind.STORAGE_FAILED, notice?.kind)
            assertTrue(notice!!.retryable)
        }
        assertTrue(guest.pinnedPaths.isEmpty())

        guest.retryProjectPins()
        unpaired.retry()
        assertEquals(PinIssueKind.STORAGE_FAILED, guest.projectPinIssueNotice.value?.kind, "still unreadable: still the problem")
        assertEquals(PinSyncIssue.StorageFailed, unpaired.issue.value)

        reg.refreshAfterPairingChange { listOf(binding("acct-a").copy(credential = "c-replaced"), binding("acct-b")) }
        flaky.failRecover = false
        guest.retryProjectPins()
        unpaired.retry()
        owner.retryProjectPins()

        assertEquals(listOf("/g/saved"), guest.pinnedPaths, "the guest's saved pins are back")
        assertNull(guest.projectPinSyncIssue.value)
        assertNull(guest.projectPinIssueNotice.value)
        assertEquals(listOf("/u/saved"), unpaired.visible())
        assertEquals(listOf("/u/saved"), unpairedVisible.last())
        assertNull(unpaired.issue.value)
        assertNull(unpaired.notice.value)
        assertEquals(PinSyncIssue.StorageFailed, reg.scope(PinScopeKey.Owner("acct-a")).storageIssue(), "a replaced owner binding recovers nothing")
        assertTrue(sent.pins().isEmpty(), "local recovery never sends")
    }

    @Test
    fun a_torn_down_repository_stops_listening_to_its_computers_shared_pins() {
        val shared = registry.scope(PinScopeKey.Owner("acct-a"))
        val retired = repo(binding("acct-a"), mutableListOf())
        val live = repo(binding("acct-a"), mutableListOf())
        assertEquals(2, shared.listenerCount())
        retired.disconnect() // how the fleet and the collaborator inbox retire a link
        assertEquals(1, shared.listenerCount())
        live.togglePin("/p/after")
        assertTrue(retired.pinnedPaths.isEmpty(), "a retired repository is no longer reachable or updated")
        assertEquals(listOf("/p/after"), live.pinnedPaths)

        // dropped without disconnect(): its coroutine scope ending removes the listener as well
        val composition = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        PocketRepository(composition, pinnedTo = binding("acct-a"), projectPinRegistry = registry)
        assertEquals(2, shared.listenerCount())
        composition.cancel()
        val deadline = System.currentTimeMillis() + 5_000
        while (shared.listenerCount() != 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, shared.listenerCount())

        retired.startRelay() // a released repository that comes back rebinds (dials a closed port, fails fast)
        assertEquals(listOf("/p/after"), retired.pinnedPaths)
        assertEquals(2, shared.listenerCount())
    }
}
