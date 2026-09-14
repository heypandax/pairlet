package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.session.ScanCompleteness
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionOrigin
import dev.ccpocket.protocol.SessionSummary
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #360 phase two: the managed session store and its first migration. Every test runs on temp directories
 * only; the real `~/.cc-pocket` is never touched.
 */
class ManagedSessionStoreTest {
    private val tmp: Path = Files.createTempDirectory("ccp-managed")
    private val root: File = Files.createDirectories(tmp.resolve("store")).toFile()
    private val project: Path = Files.createDirectories(tmp.resolve("proj"))
    private val workdir = project.toString()
    private val canonical = assertNotNull(canonicalManagedWorkdir(workdir))
    private var now = 1_000L

    @AfterTest
    fun cleanup() {
        tmp.toFile().walkBottomUp().forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
        tmp.toFile().deleteRecursively()
    }

    private fun store(files: DurablePinFiles = DurablePinFiles()) =
        ManagedSessionStore(root, files, ::canonicalManagedWorkdir) { now++ }

    private fun row(id: String, agent: AgentKind = AgentKind.CLAUDE, modified: Long = 0) =
        SessionSummary(id, "title $id", "prompt", 1, workdir, modified, agent = agent)

    private fun scan(agent: AgentKind, vararg ids: String, completeness: ScanCompleteness = ScanCompleteness.COMPLETE) =
        SessionScan(agent, workdir, ids.map { row(it, agent) }, completeness)

    private fun loaded(s: ManagedSessionStore): ManagedProjectState = assertIs<ManagedProjectRead.Loaded>(s.read(workdir)).state

    private fun ids(state: ManagedProjectState, agent: AgentKind = AgentKind.CLAUDE) = state.orderedMembers(agent).map { it.key.nativeSessionId }

    private fun storeFile(s: ManagedSessionStore) = s.fileFor(canonical)

    // ── first migration ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun migration_commits_members_visible_order_and_ready_in_one_durable_write() {
        val s = store()
        val out = assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a", "b", "c"), visibleOrder = listOf("b", "a")))
        assertTrue(out.changed)
        assertEquals(listOf("b", "a", "c"), ids(out.state), "visible order first, unlisted rows follow in scan order")
        assertEquals(ManagedMigrationState.READY, out.state.migration(AgentKind.CLAUDE))
        assertTrue(out.state.members.all { it.origin == ManagedSessionOrigin.LEGACY_ADOPTED })
        assertEquals(1, out.state.revision)
        // a restart sees exactly the committed document
        val reread = loaded(store())
        assertEquals(out.state, reread)
        assertEquals(ManagedMigrationState.UNINITIALIZED, reread.migration(AgentKind.CODEX), "other agents are untouched")
        if (Files.getFileAttributeView(storeFile(s).toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(storeFile(s).toPath())))
        }
        assertFalse(workdir in storeFile(s).name, "no path string steers the file name")
    }

    @Test
    fun an_incomplete_scan_never_migrates_and_writes_nothing() {
        val s = store()
        for (c in ScanCompleteness.entries - ScanCompleteness.COMPLETE) {
            val out = assertIs<ManagedMutation.Refused>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a", completeness = c)), "$c")
            assertEquals(ManagedSessionErrors.SCAN_INCOMPLETE, out.error)
        }
        // "failed scan" rendered as an empty list by the legacy path is not proof either
        assertIs<ManagedMutation.Refused>(s.migrate(workdir, AgentKind.CLAUDE, SessionScan(AgentKind.CLAUDE, workdir, emptyList(), ScanCompleteness.ERROR)))
        assertFalse(storeFile(s).exists())
        assertIs<ManagedProjectRead.Missing>(s.read(workdir))
    }

    @Test
    fun a_complete_empty_scan_is_a_real_migration_of_an_empty_project() {
        val out = assertIs<ManagedMutation.Committed>(store().migrate(workdir, AgentKind.CODEX, scan(AgentKind.CODEX)))
        assertEquals(ManagedMigrationState.READY, out.state.migration(AgentKind.CODEX))
        assertTrue(out.state.members.isEmpty())
    }

    @Test
    fun migration_is_idempotent_and_a_ready_agent_absorbs_no_later_scan() {
        val s = store()
        val first = assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a", "b")))
        val bytes = storeFile(s).readBytes()
        val again = assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "outside-new", "a", "b")))
        assertFalse(again.changed)
        assertEquals(first.state, again.state)
        assertContentEquals(bytes, storeFile(s).readBytes(), "a repeat commits nothing")
        // even an incomplete later scan on a READY agent is answered with the stored list, not refused
        assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, completeness = ScanCompleteness.ERROR)))
        assertEquals(listOf("a", "b"), ids(loaded(s)))
    }

    @Test
    fun each_agent_migrates_independently_and_a_failed_one_stays_on_the_legacy_list() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a")))
        assertIs<ManagedMutation.Refused>(s.migrate(workdir, AgentKind.CODEX, scan(AgentKind.CODEX, "x", completeness = ScanCompleteness.TRUNCATED)))
        val state = loaded(s)
        assertEquals(ManagedMigrationState.READY, state.migration(AgentKind.CLAUDE))
        assertEquals(ManagedMigrationState.UNINITIALIZED, state.migration(AgentKind.CODEX))
        assertTrue(state.orderedMembers(AgentKind.CODEX).isEmpty(), "no partial codex list is persisted")
    }

    @Test
    fun migration_keeps_members_registered_before_it() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.recordCreated(workdir, AgentKind.CLAUDE, "made-here"))
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "gone-native", scan(AgentKind.CLAUDE, "gone-native")))
        val out = assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "old", "made-here")))
        assertEquals(listOf("old", "made-here", "gone-native"), ids(out.state))
        assertEquals(ManagedSessionOrigin.CREATED_HERE, out.state.member(AgentKind.CLAUDE, "made-here")?.origin, "origin is never rewritten")
        assertEquals(ManagedSessionOrigin.EXPLICIT_IMPORT, out.state.member(AgentKind.CLAUDE, "gone-native")?.origin)
    }

    @Test
    fun a_scan_row_this_store_cannot_key_keeps_the_agent_on_the_legacy_list() {
        val out = assertIs<ManagedMutation.Refused>(store().migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "ok", "bad/id")))
        assertEquals(ManagedSessionErrors.SCAN_INCOMPLETE, out.error)
    }

    // ── crash-atomic writes ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun an_interrupted_write_leaves_the_previous_document_and_no_ready_state() {
        assertIs<ManagedMutation.Committed>(store().import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a")))
        val before = storeFile(store()).readBytes()
        val failing = store(DurablePinFiles(atomicMove = { _, _ -> throw IOException("disk full") }))
        val out = assertIs<ManagedMutation.Refused>(failing.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a", "b")))
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE, out.error)
        assertContentEquals(before, storeFile(failing).readBytes())
        assertEquals(ManagedMigrationState.UNINITIALIZED, loaded(failing).migration(AgentKind.CLAUDE))
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp file is left behind")
        // nothing is poisoned by a provably-unchanged failure: a retry with a healthy disk succeeds
        assertIs<ManagedMutation.Committed>(store().migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a", "b")))
    }

    @Test
    fun a_write_of_unknown_outcome_is_never_acknowledged_and_recovers_on_restart() {
        val unsure = store(DurablePinFiles(forceDirectory = { throw IOException("dir sync failed") }))
        val out = assertIs<ManagedMutation.Refused>(unsure.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a")))
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE, out.error)
        assertIs<ManagedProjectRead.Unreadable>(unsure.read(workdir), "this instance no longer trusts its view of the project")
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE,
            assertIs<ManagedMutation.Refused>(unsure.remove(workdir, AgentKind.CLAUDE, "a")).error)
        // a restarted daemon re-reads whatever landed — here the whole new document, READY with its members
        val state = loaded(store())
        assertEquals(ManagedMigrationState.READY, state.migration(AgentKind.CLAUDE))
        assertEquals(listOf("a"), ids(state))
    }

    // ── damaged or foreign documents ────────────────────────────────────────────────────────────────────

    @Test
    fun a_corrupt_or_unknown_schema_file_is_read_only_and_never_overwritten() {
        val s = store()
        val file = storeFile(s)
        val cases = listOf(
            "not json at all",
            """{"schemaVersion":2,"canonicalWorkdir":"$canonical","members":[],"order":[]}""",
            """{"schemaVersion":1,"revision":1,"canonicalWorkdir":"$canonical","perAgentMigration":{},"members":[],"order":[],"futureField":1}""",
            """{"schemaVersion":1,"revision":1,"canonicalWorkdir":"$canonical","perAgentMigration":{"kimi":"ready"},"members":[],"order":[]}""",
            """{"schemaVersion":1,"revision":1,"canonicalWorkdir":"$canonical","perAgentMigration":{},"members":[{"key":{"agent":"claude","canonicalWorkdir":"$canonical","nativeSessionId":"a"},"origin":"legacy_adopted","createdAt":1,"lastKnownSummary":null}],"order":[]}""",
            """{"schemaVersion":1,"revision":1,"canonicalWorkdir":"/somewhere/else","perAgentMigration":{},"members":[],"order":[]}""",
        )
        for (text in cases) {
            file.writeText(text)
            assertIs<ManagedProjectRead.Corrupt>(s.read(workdir), text)
            listOf(
                s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "a")),
                s.import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a")),
                s.remove(workdir, AgentKind.CLAUDE, "a"),
                s.recordCreated(workdir, AgentKind.CLAUDE, "a"),
            ).forEach { assertEquals(ManagedSessionErrors.STORE_CORRUPT, assertIs<ManagedMutation.Refused>(it).error) }
            assertEquals(text, file.readText(), "the damaged document is preserved byte for byte")
        }
    }

    @Test
    fun an_unreadable_file_is_reported_and_never_treated_as_empty() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a")))
        val file = storeFile(s)
        if (!file.setReadable(false, false) || file.canRead()) return // running as a user that bypasses permissions
        assertIs<ManagedProjectRead.Unreadable>(s.read(workdir))
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE,
            assertIs<ManagedMutation.Refused>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE))).error)
    }

    // ── import / remove ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun import_is_idempotent_remove_drops_only_the_registration_and_reimport_works() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "old")))
        val imported = assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "ext", scan(AgentKind.CLAUDE, "ext", "old")))
        assertTrue(imported.changed)
        assertEquals(listOf("ext", "old"), ids(imported.state), "a new import lands on top")
        assertEquals(ManagedSessionOrigin.EXPLICIT_IMPORT, imported.state.member(AgentKind.CLAUDE, "ext")?.origin)
        assertEquals("title ext", imported.state.member(AgentKind.CLAUDE, "ext")?.lastKnownSummary?.title)

        val repeat = assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "ext", scan(AgentKind.CLAUDE, completeness = ScanCompleteness.ERROR)))
        assertFalse(repeat.changed, "a repeat import returns the same member without a scan or a write")
        assertEquals(imported.state.revision, repeat.state.revision)

        val removed = assertIs<ManagedMutation.Committed>(s.remove(workdir, AgentKind.CLAUDE, "ext"))
        assertTrue(removed.changed)
        assertEquals(listOf("old"), ids(removed.state))
        assertFalse(assertIs<ManagedMutation.Committed>(s.remove(workdir, AgentKind.CLAUDE, "ext")).changed)

        val back = assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "ext", scan(AgentKind.CLAUDE, "ext")))
        assertEquals(listOf("ext", "old"), ids(back.state))
        assertEquals(ManagedMigrationState.READY, back.state.migration(AgentKind.CLAUDE))
    }

    @Test
    fun import_proves_existence_from_the_daemons_own_scan() {
        val s = store()
        assertEquals(ManagedSessionErrors.NOT_FOUND,
            assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CLAUDE, "nope", scan(AgentKind.CLAUDE, "other"))).error)
        assertEquals(ManagedSessionErrors.SCAN_INCOMPLETE,
            assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CLAUDE, "nope", scan(AgentKind.CLAUDE, "other", completeness = ScanCompleteness.PARTIAL))).error)
        // a row found in a partial scan does prove the session exists
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "seen", scan(AgentKind.CLAUDE, "seen", completeness = ScanCompleteness.PARTIAL)))
        // a scan of another project cannot vouch for this one
        val elsewhere = Files.createDirectories(tmp.resolve("elsewhere")).toString()
        val foreign = SessionScan(AgentKind.CLAUDE, elsewhere, listOf(row("x")), ScanCompleteness.COMPLETE)
        assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CLAUDE, "x", foreign))
    }

    @Test
    fun a_member_whose_native_record_vanished_is_kept() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a")))
        // a later complete scan without it changes nothing in the store; availability is the projection's call
        assertFalse(assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE))).changed)
        assertEquals(listOf("a"), ids(loaded(s)))
        assertEquals("title a", loaded(s).member(AgentKind.CLAUDE, "a")?.lastKnownSummary?.title)
    }

    @Test
    fun record_created_is_idempotent_and_keeps_the_first_origin_and_position() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "t1", scan(AgentKind.CODEX, "t1")))
        assertIs<ManagedMutation.Committed>(s.recordCreated(workdir, AgentKind.CODEX, "t2"))
        val repeat = assertIs<ManagedMutation.Committed>(s.recordCreated(workdir, AgentKind.CODEX, "t1"))
        assertFalse(repeat.changed)
        assertEquals(ManagedSessionOrigin.EXPLICIT_IMPORT, repeat.state.member(AgentKind.CODEX, "t1")?.origin)
        assertEquals(listOf("t2", "t1"), ids(repeat.state, AgentKind.CODEX))
    }

    // ── identity ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_same_native_id_under_two_agents_is_two_members_and_is_never_attributed_by_guess() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CLAUDE, scan(AgentKind.CLAUDE, "same", "c-only")))
        val both = assertIs<ManagedMutation.Committed>(s.migrate(workdir, AgentKind.CODEX, scan(AgentKind.CODEX, "same")))
        assertEquals(3, both.state.members.size)
        assertEquals(setOf("same"), both.state.ambiguousSessionIds())
        assertEquals(listOf("same", "c-only"), ids(both.state, AgentKind.CLAUDE))
        assertEquals(listOf("same"), ids(both.state, AgentKind.CODEX))

        val afterRemove = assertIs<ManagedMutation.Committed>(s.remove(workdir, AgentKind.CLAUDE, "same"))
        assertEquals(listOf("same"), ids(afterRemove.state, AgentKind.CODEX), "removing one agent's member leaves the other")
        assertTrue(afterRemove.state.ambiguousSessionIds().isEmpty())

        // a scan whose rows are labelled with another agent is refused, not re-labelled
        val mislabelled = SessionScan(AgentKind.CLAUDE, workdir, listOf(row("z", AgentKind.CODEX)), ScanCompleteness.COMPLETE)
        assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CLAUDE, "z", mislabelled))
        assertIs<ManagedMutation.Refused>(store().migrate(Files.createDirectories(tmp.resolve("p2")).toString(), AgentKind.CLAUDE, mislabelled))
    }

    @Test
    fun path_aliases_resolve_to_one_project() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a")))
        val alias = Files.createSymbolicLink(tmp.resolve("alias"), project).toString()
        assertEquals(canonical, s.resolveProject(alias))
        assertEquals(canonical, s.resolveProject("$workdir/"))
        assertEquals(canonical, s.resolveProject("$workdir/../proj"))
        assertEquals(listOf("a"), ids(assertIs<ManagedProjectRead.Loaded>(s.read(alias)).state))
    }

    @Test
    fun malformed_or_out_of_scope_workdirs_are_refused_before_anything_is_read() {
        val s = store()
        val outside = Files.createDirectories(tmp.resolve("outside"))
        val scopeRoot = Files.createDirectories(tmp.resolve("scope"))
        val inside = Files.createDirectories(scopeRoot.resolve("inside"))
        val escape = Files.createSymbolicLink(scopeRoot.resolve("escape"), outside)
        val aFile = Files.writeString(tmp.resolve("file.txt"), "x")
        val scope = listOf(scopeRoot.toString())

        for (bad in listOf("proj", "", "  ", "/definitely/not/here/${System.nanoTime()}", aFile.toString(), "$workdir\n", "~nobody-${System.nanoTime()}")) {
            assertNull(s.resolveProject(bad), "must refuse <$bad>")
            assertIs<ManagedProjectRead.InvalidWorkdir>(s.read(bad))
            assertEquals(ManagedSessionErrors.INVALID_WORKDIR,
                assertIs<ManagedMutation.Refused>(s.import(bad, AgentKind.CLAUDE, "a", scan(AgentKind.CLAUDE, "a"))).error)
        }
        assertNotNull(s.resolveProject(inside.toString(), scope))
        assertNotNull(s.resolveProject(scopeRoot.toString(), scope))
        assertNull(s.resolveProject(escape.toString(), scope), "a symlink out of the scope resolves to its real target")
        assertNull(s.resolveProject("${inside}/../../outside", scope), "dot-dot out of the scope")
        assertNull(s.resolveProject("${scopeRoot}-sibling".also { Files.createDirectories(Path.of(it)) }, scope), "prefix is not containment")
        assertIs<ManagedProjectRead.InvalidWorkdir>(s.read(escape.toString(), scope))
        assertTrue(root.listFiles()!!.isEmpty(), "no refused request created a file")
    }

    @Test
    fun unmanaged_agents_and_malformed_ids_are_refused() {
        val s = store()
        for (agent in AgentKind.entries.filter { it !in MANAGED_SESSION_AGENTS }) {
            assertEquals(ManagedSessionErrors.UNSUPPORTED,
                assertIs<ManagedMutation.Refused>(s.migrate(workdir, agent, scan(agent, "a"))).error)
        }
        for (bad in listOf("../x", "a/b", "", ".", "x".repeat(200))) {
            assertEquals(ManagedSessionErrors.INVALID_REQUEST,
                assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CLAUDE, bad, scan(AgentKind.CLAUDE, bad))).error, bad)
        }
        assertFalse(storeFile(s).exists())
    }
}
