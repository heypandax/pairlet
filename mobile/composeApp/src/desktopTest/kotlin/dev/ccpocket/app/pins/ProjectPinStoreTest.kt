package dev.ccpocket.app.pins

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Durable, shared pin state on the client (issue #362): persist-before-publish, one document and one sequence
 * counter per computer across every holder, crash-atomic files with explicit and indeterminate failure, recovery
 * that never overwrites what it cannot read, binding leases that retire with a pairing change, and the one-time
 * legacy migration whose complete list lands with its marker. Everything runs over temp directories or in-memory
 * fakes — never the developer's real app data.
 */
class ProjectPinStoreTest {

    private val dir: File = Files.createTempDirectory("ccp-client-pins").toFile()

    @AfterTest
    fun tearDown() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    /** In-memory persistence with switchable failures. An indeterminate write stores the document but reports it
     *  unconfirmed, like a rename whose directory sync failed. */
    private class FakePersistence : ProjectPinPersistence {
        val files = LinkedHashMap<String, String>()
        var writeOutcome: PinFileWrite = PinFileWrite.Durable
        var failReadsRemaining = 0
        var failRecover = false
        var failList = false
        var recoveryArtifacts = false
        var writes = 0

        override fun read(name: String): PinFileRead = synchronized(this) {
            if (failReadsRemaining > 0) { failReadsRemaining--; return PinFileRead.Failed("io") }
            files[name]?.let { PinFileRead.Found(it) } ?: PinFileRead.Missing
        }

        override fun write(name: String, text: String): PinFileWrite = synchronized(this) {
            if (writeOutcome != PinFileWrite.NotWritten) { files[name] = text; writes++ }
            writeOutcome
        }

        override fun recover(name: String): PinFileRead = synchronized(this) {
            if (failRecover) PinFileRead.Failed("sync") else read(name)
        }

        override fun list(): PinFileListing = synchronized(this) {
            if (failList) PinFileListing.Failed else PinFileListing.Ready(files.keys.toList(), recoveryArtifacts)
        }
    }

    private var tokens = 0
    private fun token() = "stream-" + (++tokens).toString().padStart(16, '0')

    private fun enqueue(doc: PinStateDoc, path: String, pinned: Boolean = true) =
        (ProjectPinReducer.enqueue(doc, path, pinned) as ProjectPinReducer.Edit.Applied).doc

    private fun owner(id: String) = PairedDaemon("wss://relay.invalid", id, "pk-$id", "dev-$id", "cred-$id")
    private fun guest(id: String) = owner(id).copy(role = BindingRole.GUEST)

    /** A correlated fetch reply on the binding's device, acknowledging through [ack]. */
    private fun fetch(doc: PinStateDoc, device: String, ack: Long, rev: Long) = ProjectPinReducer.applyReply(
        doc,
        ProjectPinsState("sub", "r", doc.stream.id, ack, ProjectPinsSnapshot("inc-1", rev)),
        PinRequestContext(PinRequestKind.FETCH, doc.stream.id, "sub", "r", null, emptyList()),
        device,
        ::token,
    ).doc

    @Test
    fun a_change_is_saved_before_it_is_visible_and_a_failed_save_changes_nothing() {
        val storage = FakePersistence()
        val scope = ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Owner("acct"))
        val seen = mutableListOf<List<String>>()
        scope.addListener { seen += it }

        assertTrue(scope.update { enqueue(it, "/a") })
        assertEquals(listOf("/a"), scope.visible())
        assertEquals(listOf(listOf("/a")), seen)
        assertTrue(storage.files.getValue("owner-acct").contains("/a"), "the document reached storage")

        storage.writeOutcome = PinFileWrite.NotWritten
        val before = scope.document()
        assertEquals(PinUpdate.NotSaved(PinSyncIssue.StorageFailed), scope.updateTrusted { enqueue(it, "/b") })
        assertSame(before, scope.document(), "memory still holds the last durable document")
        assertEquals(listOf("/a"), scope.visible())
        assertEquals(1, seen.size, "no listener hears about a change that was not saved")

        storage.writeOutcome = PinFileWrite.Durable
        assertTrue(scope.update { enqueue(it, "/b") }, "a save that failed before replacing leaves the scope usable")
        assertEquals(listOf(1L, 2L), scope.document().pending.map { it.seq })
    }

    @Test
    fun an_unconfirmed_save_keeps_the_old_view_blocks_every_change_and_adopts_only_what_recovery_reads_back() {
        val storage = FakePersistence()
        val scope = ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Owner("acct"))
        assertTrue(scope.update { enqueue(it, "/a") })
        val durable = scope.document()
        val seen = mutableListOf<List<String>>()
        scope.addListener { seen += it }

        storage.writeOutcome = PinFileWrite.Indeterminate
        storage.failRecover = true
        assertIs<PinUpdate.Indeterminate>(scope.updateTrusted { enqueue(it, "/b") })
        assertSame(durable, scope.document(), "the previous document stays visible")
        assertEquals(PinSyncIssue.StorageFailed, scope.storageIssue())

        storage.writeOutcome = PinFileWrite.Durable
        val writes = storage.writes
        assertIs<PinUpdate.Blocked>(scope.updateTrusted { it }, "not even a no-op passes while the outcome is unknown")
        assertIs<PinUpdate.Blocked>(scope.updateTrusted { enqueue(it, "/c") })
        assertFalse(scope.update { enqueue(it, "/c") })
        assertEquals(writes, storage.writes, "the cached copy never overwrites what may have survived")
        assertTrue(seen.isEmpty())

        storage.failRecover = false // the unconfirmed save did land: recovery reads it back and builds on it
        assertIs<PinUpdate.Committed>(scope.updateTrusted { enqueue(it, "/c") })
        assertEquals(listOf("/a", "/b", "/c"), scope.document().pending.map { it.path })
        assertNull(scope.storageIssue())
    }

    @Test
    fun every_holder_of_one_computer_shares_one_document_and_one_sequence_counter() {
        val storage = FakePersistence()
        val registry = ProjectPinRegistry(storage, ::token)
        val first = registry.scope(PinScopeKey.Owner("acct"))
        assertSame(first, registry.scope(PinScopeKey.Owner("acct")))

        // two repositories/panes for the same computer, editing concurrently from different threads
        val start = CountDownLatch(1)
        val workers = (0 until 2).map { w ->
            thread {
                start.await()
                repeat(200) { i -> check(registry.scope(PinScopeKey.Owner("acct")).update { enqueue(it, "/w$w-$i") }) }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val doc = first.document()
        assertEquals((1L..400L).toList(), doc.pending.map { it.seq }, "no sequence allocated twice, none skipped")
        assertEquals(401, doc.stream.nextSeq)
        val stored = assertIs<ProjectPinCodec.Decoded.Ok>(ProjectPinCodec.decode(storage.files.getValue("owner-acct")))
        assertEquals(doc, stored.doc, "the last durable document is the one in memory")
    }

    @Test
    fun a_restart_reloads_the_outbox_and_its_sent_mark_exactly() {
        val before = ProjectPinRegistry(FileProjectPinPersistence(dir), ::token).scope(PinScopeKey.Owner("acct"))
        assertTrue(before.update { ProjectPinReducer.markSent(enqueue(enqueue(it, "/a"), "/b", pinned = false), 2) })
        val after = ProjectPinRegistry(FileProjectPinPersistence(dir), ::token).scope(PinScopeKey.Owner("acct"))
        assertEquals(before.document(), after.document())
        assertEquals(listOf(ProjectPinOp(1, "/a", true), ProjectPinOp(2, "/b", false)), after.document().pending)
        assertEquals(2, after.document().stream.sentSeq)
    }

    @Test
    fun an_undecodable_document_is_left_intact_and_read_only() {
        val garbage = "{\"v\":1,\"stream\":{\"id\":".encodeToByteArray()
        dir.mkdirs(); File(dir, "owner-acct.json").writeBytes(garbage)
        val scope = ProjectPinRegistry(FileProjectPinPersistence(dir), ::token).scope(PinScopeKey.Owner("acct"))
        assertEquals(PinSyncIssue.LocalStateReset, scope.loadIssue)
        assertEquals(PinSyncIssue.LocalStateReset, scope.storageIssue())
        assertFalse(scope.update { enqueue(it, "/a") }, "nothing is written over it")
        assertContentEquals(garbage, File(dir, "owner-acct.json").readBytes(), "the unreadable bytes are kept for recovery")
        assertEquals(listOf("owner-acct.json"), dir.list()!!.toList(), "and nothing is moved aside to make room")
    }

    @Test
    fun a_newer_apps_document_is_never_rewritten() {
        val newer = """{"v":2,"stream":{"id":"future-stream-0123456789"},"somethingNew":true}"""
        dir.mkdirs(); File(dir, "owner-acct.json").writeText(newer)
        val scope = ProjectPinRegistry(FileProjectPinPersistence(dir), ::token).scope(PinScopeKey.Owner("acct"))
        assertEquals(PinSyncIssue.StorageFailed, scope.loadIssue)
        assertFalse(scope.update { enqueue(it, "/a") })
        assertEquals(newer, File(dir, "owner-acct.json").readText())
    }

    @Test
    fun a_transient_read_failure_is_retried_instead_of_overwriting_stored_state() {
        val storage = FakePersistence()
        val seeded = enqueue(ProjectPinReducer.fresh(token()), "/kept")
        storage.files["owner-acct"] = ProjectPinCodec.encode(seeded)
        storage.failReadsRemaining = 1
        val scope = ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Owner("acct"))
        assertEquals(PinSyncIssue.StorageFailed, scope.loadIssue)
        assertTrue(scope.update { enqueue(it, "/new") })
        assertEquals(listOf("/kept", "/new"), scope.document().pending.map { it.path }, "the retry read what was stored")
    }

    @Test
    fun the_codec_refuses_documents_whose_outbox_could_open_a_gap_or_reuse_a_sequence() {
        val ok = ProjectPinReducer.markSent(enqueue(enqueue(ProjectPinReducer.fresh(token()), "/a"), "/b"), 1)
        assertEquals(ok, assertIs<ProjectPinCodec.Decoded.Ok>(ProjectPinCodec.decode(ProjectPinCodec.encode(ok))).doc)
        val gap = ok.copy(pending = listOf(ok.pending[1]))
        assertEquals(ProjectPinCodec.Decoded.Corrupt, ProjectPinCodec.decode(ProjectPinCodec.encode(gap)))
        val sentBeyond = ok.copy(stream = ok.stream.copy(sentSeq = 9))
        assertEquals(ProjectPinCodec.Decoded.Corrupt, ProjectPinCodec.decode(ProjectPinCodec.encode(sentBeyond)))
        val badStream = ok.copy(stream = ok.stream.copy(id = "x"))
        assertEquals(ProjectPinCodec.Decoded.Corrupt, ProjectPinCodec.decode(ProjectPinCodec.encode(badStream)))
        val lookupBeyondAck = ok.copy(resolutionPending = listOf(ProjectPinOp(1, "/a", true)))
        assertEquals(ProjectPinCodec.Decoded.Corrupt, ProjectPinCodec.decode(ProjectPinCodec.encode(lookupBeyondAck)))
    }

    // ---- legacy migration ----

    @Test
    fun one_owner_claims_the_legacy_list_once_and_its_marker_lands_with_the_operations() {
        val storage = FakePersistence()
        val registry = ProjectPinRegistry(storage, ::token)
        val legacy = "/a\n/b\n/c"
        storage.writeOutcome = PinFileWrite.NotWritten
        registry.migrateLegacyIfNeeded(legacy, listOf(owner("A"), guest("G")), owner("A"))
        assertTrue(storage.files.isEmpty(), "a failed write lands neither marker nor operations")

        storage.writeOutcome = PinFileWrite.Durable
        registry.migrateLegacyIfNeeded(legacy, listOf(owner("A"), guest("G")), guest("G"))
        val doc = registry.scope(PinScopeKey.Owner("A")).document()
        assertEquals(listOf("/c", "/b", "/a"), doc.pending.map { it.path })
        assertEquals(true, doc.legacy?.claimed)

        registry.migrateLegacyIfNeeded(legacy, listOf(owner("A")), owner("A"))
        ProjectPinRegistry(storage, ::token).migrateLegacyIfNeeded(legacy, listOf(owner("A")), owner("A")) // a restart
        assertEquals(3, registry.scope(PinScopeKey.Owner("A")).document().pending.size, "claimed exactly once")
        assertTrue(ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Guest("G")).document().pending.isEmpty())
    }

    @Test
    fun a_legacy_list_beyond_the_outbox_lands_whole_once_and_a_restart_neither_replays_nor_drops_it() {
        val legacy = (ProjectPinReducer.MAX_PENDING downTo 0).joinToString("\n") { "/p$it" } // 2049 entries
        ProjectPinRegistry(FileProjectPinPersistence(dir), ::token).migrateLegacyIfNeeded(legacy, listOf(owner("A")), owner("A"))
        val restarted = ProjectPinRegistry(FileProjectPinPersistence(dir), ::token)
        restarted.migrateLegacyIfNeeded(legacy, listOf(owner("A")), owner("A"))
        val doc = restarted.scope(PinScopeKey.Owner("A")).document()
        assertEquals(ProjectPinReducer.MAX_PENDING, doc.pending.size)
        assertEquals(2049, doc.stream.nextSeq, "nothing was enqueued twice")
        assertEquals(LegacyPins(legacyDigest(legacy), claimed = false, fallback = listOf("/p2048"), eligible = listOf("/p2048")), doc.legacy)
        assertNull(restarted.migrationIssue)
    }

    @Test
    fun several_owners_keep_the_legacy_list_as_a_local_fallback_on_the_active_computer_only() {
        val storage = FakePersistence()
        val registry = ProjectPinRegistry(storage, ::token)
        registry.migrateLegacyIfNeeded("/a\n/b", listOf(owner("A"), owner("B")), owner("B"))
        val b = registry.scope(PinScopeKey.Owner("B")).document()
        assertEquals(listOf("/a", "/b"), b.legacy?.fallback)
        assertTrue(b.pending.isEmpty(), "nothing is sent to any computer for an unproven path")
        assertNull(registry.scope(PinScopeKey.Owner("A")).document().legacy)

        // later only A remains paired: the list already landed on B, so it is not guessed onto A
        ProjectPinRegistry(storage, ::token).migrateLegacyIfNeeded("/a\n/b", listOf(owner("A")), owner("A"))
        assertTrue(ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Owner("A")).document().pending.isEmpty())
    }

    @Test
    fun no_owner_or_an_unreadable_document_defers_the_migration_without_guessing() {
        val storage = FakePersistence()
        ProjectPinRegistry(storage, ::token).migrateLegacyIfNeeded("/a", listOf(guest("G")), guest("G"))
        ProjectPinRegistry(storage, ::token).migrateLegacyIfNeeded("/a", listOf(owner("A"), owner("B")), guest("G"))
        assertTrue(storage.files.isEmpty())

        storage.files["owner-X"] = "not json"
        val registry = ProjectPinRegistry(storage, ::token)
        registry.migrateLegacyIfNeeded("/a", listOf(owner("A")), owner("A"))
        assertFalse(storage.files.containsKey("owner-A"), "an unreadable document might already hold the marker")
        assertEquals(PinSyncIssue.MigrationUncertain, registry.migrationIssue)
    }

    @Test
    fun a_failed_listing_or_a_recovery_artifact_blocks_migration_but_not_other_computers() {
        val listingFails = FakePersistence().apply { failList = true }
        val r1 = ProjectPinRegistry(listingFails, ::token)
        r1.migrateLegacyIfNeeded("/a\n/b", listOf(owner("A")), owner("A"))
        assertTrue(listingFails.files.isEmpty())
        assertEquals(PinSyncIssue.MigrationUncertain, r1.migrationIssue)
        assertTrue(r1.scope(PinScopeKey.Owner("B")).update { enqueue(it, "/b-only") }, "other healthy computers keep working")
        assertNull(r1.scope(PinScopeKey.Owner("B")).document().legacy, "…without importing the backup")

        val artifact = FakePersistence().apply { recoveryArtifacts = true }
        val r2 = ProjectPinRegistry(artifact, ::token)
        r2.migrateLegacyIfNeeded("/a\n/b", listOf(owner("A")), owner("A"))
        assertTrue(artifact.files.isEmpty())
        assertEquals(PinSyncIssue.MigrationUncertain, r2.migrationIssue)

        val empty = ProjectPinRegistry(FileProjectPinPersistence(File(dir, "never-created")), ::token)
        empty.migrateLegacyIfNeeded("/a\n/b", listOf(owner("A")), owner("A"))
        assertNull(empty.migrationIssue, "a store directory that does not exist yet is genuinely empty")
        assertEquals(2, empty.scope(PinScopeKey.Owner("A")).document().pending.size)
    }

    // ---- file persistence ----

    @Test
    fun desktop_tests_never_resolve_the_developers_real_pin_directory() {
        val resolved = defaultProjectPinDirectory().absolutePath
        assertFalse(resolved.startsWith(File(System.getProperty("user.home"), ".cc-pocket-app").absolutePath), resolved)
    }

    @Test
    fun a_file_write_that_cannot_complete_reports_not_written_and_leaves_the_old_document() {
        val storage = FileProjectPinPersistence(dir)
        assertEquals(PinFileWrite.Durable, storage.write("owner-acct", "one"))
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-x------"))
        try {
            assertEquals(PinFileWrite.NotWritten, storage.write("owner-acct", "two"))
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(PinFileRead.Found("one"), storage.read("owner-acct"))
        assertEquals(PinFileRead.Found("one"), storage.recover("owner-acct"))
        assertEquals(listOf("owner-acct.json"), dir.list()!!.toList(), "no temp file is left behind")
        assertContentEquals("one".encodeToByteArray(), File(dir, "owner-acct.json").readBytes())
        assertEquals(PinFileListing.Ready(listOf("owner-acct"), hasRecoveryArtifacts = false), storage.list())
    }

    @Test
    fun a_file_store_lists_earlier_recovery_artifacts_and_treats_a_missing_directory_as_empty() {
        val missing = FileProjectPinPersistence(File(dir, "absent"))
        assertEquals(PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false), missing.list())
        assertEquals(PinFileRead.Missing, missing.recover("owner-acct"))

        val storage = FileProjectPinPersistence(dir)
        assertEquals(PinFileWrite.Durable, storage.write("owner-acct", ProjectPinCodec.encode(ProjectPinReducer.fresh(token()))))
        File(dir, "owner-old.json.corrupt-1700000000000").writeText("unreadable")
        assertEquals(PinFileListing.Ready(listOf("owner-acct"), hasRecoveryArtifacts = true), storage.list())
        val registry = ProjectPinRegistry(storage, ::token)
        registry.migrateLegacyIfNeeded("/a", listOf(owner("A")), owner("A"))
        assertEquals(PinSyncIssue.MigrationUncertain, registry.migrationIssue)
        assertFalse(File(dir, PinScopeKey.Owner("A").storageName + ".json").exists())
    }

    // ---- binding authority, leases and the shared flush latch ----

    @Test
    fun authority_is_installed_once_and_replaced_only_by_a_pairing_change_that_rereads_current_bindings() {
        val registry = ProjectPinRegistry(FakePersistence(), ::token)
        val original = owner("A")
        var stored = listOf(original)
        registry.initializeAuthorityOnce { stored }
        val lease = assertNotNull(registry.acquireLease(original))
        assertEquals(lease, registry.acquireLease(original), "two holders of the same identity share one epoch")

        val replaced = original.copy(deviceId = "dev-A2", credential = "cred-A2")
        registry.initializeAuthorityOnce { listOf(replaced) } // a later constructor installs nothing
        assertNull(registry.acquireLease(replaced), "acquiring never installs")
        assertTrue(registry.isCurrent(lease))

        stored = listOf(replaced) // the pairing store changed; the refresh reads it at call time
        registry.refreshAfterPairingChange { stored }
        assertFalse(registry.isCurrent(lease))
        assertNull(registry.acquireLease(original), "an old controller cannot re-acquire with the old credential")
        val next = assertNotNull(registry.acquireLease(replaced))
        assertTrue(next.epoch > lease.epoch)

        val rotated = replaced.copy(credential = "cred-A3")
        registry.refreshAfterPairingChange { listOf(rotated) }
        assertFalse(registry.isCurrent(next), "a credential-only replacement retires the lease too")
        registry.refreshAfterPairingChange { listOf(rotated.copy(label = "renamed")) }
        assertNotNull(registry.acquireLease(rotated), "a label change is not a replacement")
        registry.refreshAfterPairingChange { emptyList() }
        assertNull(registry.acquireLease(rotated), "removal retires everything")
    }

    @Test
    fun a_guest_binding_on_the_owners_account_neither_holds_nor_disturbs_owner_authority() {
        val o = owner("A")
        val g = o.copy(deviceId = "dev-G", credential = "cred-G", role = BindingRole.GUEST)

        val initialized = ProjectPinRegistry(FakePersistence(), ::token)
        initialized.initializeAuthorityOnce { listOf(o, g) }
        assertNotNull(initialized.acquireLease(o), "a legitimate guest sibling does not void the owner")
        assertNull(initialized.acquireLease(g), "a guest never gets an owner lease")

        val registry = ProjectPinRegistry(FakePersistence(), ::token)
        registry.initializeAuthorityOnce { listOf(o) }
        val lease = assertNotNull(registry.acquireLease(o))
        registry.refreshAfterPairingChange { listOf(o, g) }
        assertTrue(registry.isCurrent(lease), "adding a guest keeps the owner's epoch")
        assertEquals(lease, registry.acquireLease(o))
        registry.refreshAfterPairingChange { listOf(o) }
        assertTrue(registry.isCurrent(lease), "removing the guest keeps it too")

        registry.refreshAfterPairingChange { listOf(g) }
        assertFalse(registry.isCurrent(lease), "a guest-only account has no owner authority")
        assertNull(registry.acquireLease(g))
        assertNull(registry.acquireLease(o))

        val conflicting = ProjectPinRegistry(FakePersistence(), ::token)
        conflicting.initializeAuthorityOnce { listOf(o, o.copy(deviceId = "dev-A2", credential = "cred-A2"), g) }
        assertNull(conflicting.acquireLease(o), "two different OWNER identities for one account still speak for nothing")
    }

    @Test
    fun only_a_definite_no_such_entry_probe_reads_as_missing_or_an_empty_store() {
        val enoent = 2
        assertEquals(PinPathProbe.Present, PinPathProbe.classify(0, 0, enoent))
        assertEquals(PinPathProbe.Missing, PinPathProbe.classify(-1, enoent, enoent), "ENOENT, including a missing ancestor")
        assertEquals(PinPathProbe.Failed(13), PinPathProbe.classify(-1, 13, enoent), "EACCES is not absence")
        assertEquals(PinPathProbe.Failed(5), PinPathProbe.classify(-1, 5, enoent), "EIO is not absence")
        assertEquals(PinPathProbe.Failed(0), PinPathProbe.classify(-1, 0, enoent), "an unexplained failure is not absence")

        var inspected = 0
        val found = PinFileRead.Found("doc")
        assertEquals(found, readProbed(PinPathProbe.Present) { inspected++; found })
        assertEquals(PinFileRead.Missing, readProbed(PinPathProbe.Missing) { inspected++; found })
        assertIs<PinFileRead.Failed>(readProbed(PinPathProbe.Failed(13)) { inspected++; found })
        assertEquals(1, inspected, "an absent or uninspectable path is never read, synced or recovered past")

        val listed = PinFileListing.Ready(listOf("owner-a"), hasRecoveryArtifacts = false)
        assertEquals(listed, listProbed(PinPathProbe.Present) { listed })
        assertEquals(PinFileListing.Ready(emptyList(), false), listProbed(PinPathProbe.Missing) { listed })
        assertEquals(PinFileListing.Failed, listProbed(PinPathProbe.Failed(5)) { listed }, "never proof that no marker exists")
    }

    @Test
    fun a_stale_controller_cannot_update_after_replacement_and_same_identity_controllers_share_one_sequence() {
        val registry = ProjectPinRegistry(FakePersistence(), ::token)
        val a = owner("A")
        registry.initializeAuthorityOnce { listOf(a) }
        val first = registry.acquireLease(a)!!
        val second = registry.acquireLease(a)!!
        val scope = registry.scope(PinScopeKey.Owner("A"))
        assertIs<PinUpdate.Committed>(scope.updateFor(first) { enqueue(it, "/one") })
        assertIs<PinUpdate.Committed>(scope.updateFor(second) { enqueue(it, "/two") })
        assertEquals(listOf(1L, 2L), scope.document().pending.map { it.seq })
        assertEquals(PinUpdate.StaleLease, registry.scope(PinScopeKey.Owner("B")).updateFor(first) { enqueue(it, "/x") })

        registry.refreshAfterPairingChange { listOf(a.copy(credential = "cred-new")) }
        var evaluated = false
        assertEquals(PinUpdate.StaleLease, scope.updateFor(first) { evaluated = true; enqueue(it, "/late") })
        assertFalse(evaluated, "the stale change is not even evaluated")
        assertEquals(2, scope.document().pending.size)
        assertFalse(scope.canFlush(first))
    }

    @Test
    fun lookup_work_belongs_to_the_binding_that_created_it_and_never_survives_a_restart() {
        val storage = FakePersistence()
        val registry = ProjectPinRegistry(storage, ::token)
        val a = owner("A")
        registry.initializeAuthorityOnce { listOf(a) }
        val lease = registry.acquireLease(a)!!
        val scope = registry.scope(PinScopeKey.Owner("A"))
        scope.updateFor(lease) { fetch(it, a.deviceId, 0, 0) }
        scope.updateFor(lease) { ProjectPinReducer.markSent(enqueue(it, "/a"), 1) }
        scope.updateFor(lease) { fetch(it, a.deviceId, 1, 1) } // the commit is proven, its resolution is not
        assertEquals(listOf(ProjectPinOp(1, "/a", true)), scope.lookupBatchFor(lease))
        assertTrue(storage.files.getValue(PinScopeKey.Owner("A").storageName!!).contains("resolutionPending"))

        val restarted = ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Owner("A"))
        assertTrue(restarted.document().resolutionPending.isEmpty(), "no provable originating credential after a restart")
        assertEquals(1, restarted.document().stream.ackedSeq)

        registry.refreshAfterPairingChange { listOf(a.copy(credential = "cred-new")) }
        val replaced = registry.acquireLease(a.copy(credential = "cred-new"))!!
        assertTrue(scope.lookupBatchFor(replaced).isEmpty(), "a replaced credential cannot send the old binding's lookups")
        assertIs<PinUpdate.Committed>(scope.updateFor(replaced) { it })
        assertTrue(scope.document().resolutionPending.isEmpty(), "its first transition drops them as knowledge…")
        assertTrue(scope.document().pending.isEmpty(), "…never as intent")
    }

    @Test
    fun a_refusal_latch_is_shared_by_every_controller_and_listeners_hear_only_after_the_caller_publishes() {
        val storage = FakePersistence()
        val registry = ProjectPinRegistry(storage, ::token)
        val a = owner("A")
        registry.initializeAuthorityOnce { listOf(a) }
        val lease = registry.acquireLease(a)!!
        val scope = registry.scope(PinScopeKey.Owner("A"))
        assertFalse(scope.canFlush(lease), "nothing is sent before a fetch binds the stream")
        scope.updateFor(lease) { fetch(it, a.deviceId, 0, 0) }
        assertTrue(scope.canFlush(lease))

        val heard = mutableListOf<List<String>>()
        scope.addListener { heard += it }
        val update = assertIs<PinUpdate.Committed>(scope.updateFor(lease, deferPublish = true) { enqueue(it, "/b") })
        assertTrue(heard.isEmpty(), "the caller latches its state first")
        scope.setFlushBlocked(PinSyncIssue.Refused(ProjectPinErrors.CAPACITY, null))
        update.publish()
        update.publish()
        assertEquals(1, heard.size)
        assertFalse(scope.canFlush(registry.acquireLease(a)!!), "another controller of the same computer is latched too")
        assertTrue(scope.update { enqueue(it, "/c") })
        assertFalse(scope.canFlush(lease), "listener churn does not clear the latch")
        scope.clearFlushBlockedOnExplicitTrigger()
        assertTrue(scope.canFlush(lease))

        storage.writeOutcome = PinFileWrite.Indeterminate
        storage.failRecover = true
        assertIs<PinUpdate.Indeterminate>(scope.updateTrusted { enqueue(it, "/d") })
        assertFalse(scope.canFlush(lease), "storage trouble overrides flush permission")
    }

    @Test
    fun scope_keys_isolate_roles_and_the_demo_is_never_written() {
        assertNotEquals(PinScopeKey.Owner("abc").storageName, PinScopeKey.Guest("abc").storageName)
        assertEquals(PinScopeKey.Owner("acct"), PinScopeKey.of(owner("acct"), demo = false))
        assertEquals(PinScopeKey.Guest("acct"), PinScopeKey.of(guest("acct"), demo = false))
        assertEquals(PinScopeKey.Inbox, PinScopeKey.of(owner("acct").copy(role = BindingRole.COLLABORATOR), demo = false))
        assertEquals(PinScopeKey.Demo, PinScopeKey.of(owner("acct"), demo = true))
        assertEquals(PinScopeKey.Unpaired, PinScopeKey.of(null, demo = false))
        assertTrue(PinScopeKey.Owner("abc").synced && !PinScopeKey.Guest("abc").synced)

        val storage = FakePersistence()
        val demo = ProjectPinRegistry(storage, ::token).scope(PinScopeKey.Demo)
        assertTrue(demo.update { (ProjectPinReducer.setLocal(it, "/demo", true) as ProjectPinReducer.Edit.Applied).doc })
        assertEquals(listOf("/demo"), demo.visible())
        assertEquals(0, storage.writes)

        assertNotEquals(safePinName("ABC"), safePinName("abc"), "case-only differences never share a file")
        assertTrue(safePinName("../../etc").none { it == '/' || it == '.' })
    }
}
