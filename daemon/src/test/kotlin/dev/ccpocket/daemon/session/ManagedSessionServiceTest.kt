package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.ManagedProjectRead
import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.disk.canonicalManagedWorkdir
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.EnableManagedSessions
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.MANAGED_DISCOVER_MAX_PAGES
import dev.ccpocket.protocol.MANAGED_FRAME_BUDGET_BYTES
import dev.ccpocket.protocol.MANAGED_GROUPS_MAX
import dev.ccpocket.protocol.MANAGED_SESSIONS_MAX
import dev.ccpocket.protocol.MANAGED_WORKDIR_MAX_CHARS
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedScanDiagnostics
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionOrigin
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.isValidManagedWorkdir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #360 phase two, service layer: projection (membership/order from the store only, availability from scan
 * completeness), list and discovery paging under the byte budget with MACed cursors, per-connection read limits,
 * scan reuse across pages, adversarial worst-case frames (including refusals), the scan → import → restart fixture
 * over real Claude transcripts, and the owner-only creation-association crash boundaries.
 */
class ManagedSessionServiceTest {
    private val tmp: Path = Files.createTempDirectory("ccp-managed-svc")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: File = Files.createDirectories(tmp.resolve("store")).toFile()
    private val project: Path = Files.createDirectories(tmp.resolve("proj"))
    private val workdir: String = assertNotNull(canonicalManagedWorkdir(project.toString()))
    private var storeClock = 1_000L
    private var now = 1_000_000L

    /** What each agent's scan returns; absent = a complete empty scan. */
    private val scans = HashMap<AgentKind, (String) -> SessionScan>()
    @Volatile private var scanCalls = 0
    private val groups = ArrayList<SessionGroup>()
    private val assign = HashMap<String, String>()
    private val archived = HashSet<String>()
    private val busy = HashSet<String>()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private fun service(
        budget: Int = MANAGED_FRAME_BUDGET_BYTES,
        canonicalize: (String) -> String? = ::canonicalManagedWorkdir,
        files: DurablePinFiles = DurablePinFiles(),
        cacheTtlMs: Long = 0,
        maxReads: Int = ManagedSessionService.MAX_READS_PER_CONNECTION,
    ) = ManagedSessionService(
        store = ManagedSessionStore(root, files, canonicalize) { storeClock++ },
        scope = scope,
        scan = { wd, a -> scanCalls++; scans[a]?.invoke(wd) ?: SessionScan(a, wd, emptyList(), ScanCompleteness.COMPLETE) },
        validateWorkdir = canonicalize,
        agents = setOf(AgentKind.CLAUDE, AgentKind.CODEX),
        pendingFile = File(root, "pending-registrations.json"),
        files = files,
        groupsFor = { groups.toList() },
        groupOf = { _, sid -> assign[sid] },
        archivedIds = { archived.toSet() },
        busyIds = { busy.toSet() },
        clock = { now },
        frameBudgetBytes = budget,
        scanCacheTtlMs = cacheTtlMs,
        maxReadsPerConnection = maxReads,
    )

    private fun row(id: String, agent: AgentKind, modified: Long, title: String = "title $id") =
        SessionSummary(id, title, "prompt $id", 1, workdir, modified, agent = agent)

    private fun setScan(agent: AgentKind, vararg rows: SessionSummary, completeness: ScanCompleteness = ScanCompleteness.COMPLETE) {
        scans[agent] = { wd -> SessionScan(agent, wd, rows.toList(), completeness) }
    }

    private fun bytes(frame: ToPhone) =
        PocketJson.encodeToString(Envelope("1234567890", System.currentTimeMillis(), body = frame)).encodeToByteArray().size

    private suspend fun ManagedSessionService.state(frame: Frame): ManagedSessionsState = when (frame) {
        is ListManagedSessions -> list(frame)
        else -> mutate(frame as ToDaemon).first
    }

    private fun ids(state: ManagedSessionsState) = state.items!!.map { it.sessionId }

    // ── projection ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun enable_adopts_the_legacy_order_and_later_outside_changes_only_touch_metadata() = runBlocking {
        val svc = service()
        setScan(AgentKind.CLAUDE, row("c", AgentKind.CLAUDE, 1), row("a", AgentKind.CLAUDE, 3), row("b", AgentKind.CLAUDE, 2))
        val enabled = svc.state(EnableManagedSessions("r1", workdir, AgentKind.CLAUDE))
        assertNull(enabled.error)
        assertEquals(listOf("a", "b", "c"), ids(enabled), "adopted in the legacy list's newest-first order")
        assertEquals(ManagedMigrationState.READY, enabled.agents!!.single().migration)
        assertTrue(enabled.complete)
        assertNull(enabled.nextCursor)

        setScan(AgentKind.CLAUDE, row("d", AgentKind.CLAUDE, 9), row("c", AgentKind.CLAUDE, 8, "renamed c"), row("b", AgentKind.CLAUDE, 7), row("a", AgentKind.CLAUDE, 3))
        busy += "b"
        assign["b"] = "g1"
        val listed = svc.list(ListManagedSessions("r2", workdir, AgentKind.CLAUDE))
        assertEquals(listOf("a", "b", "c"), ids(listed), "membership and order never follow the scan")
        val c = listed.items!!.single { it.sessionId == "c" }
        assertEquals("renamed c", c.summary!!.title, "metadata does follow the scan")
        assertEquals(8, c.summary!!.lastModified)
        val b = listed.items!!.single { it.sessionId == "b" }
        assertTrue(b.summary!!.busy)
        assertEquals("g1", b.group)
        assertTrue(listed.items!!.all { it.summary!!.cwd.isEmpty() }, "rows never repeat the project path")
        assertTrue(listed.items!!.all { it.lastKnownTitle == null && it.lastKnownModified == null })

        val found = svc.discover(DiscoverSessions("r3", workdir, AgentKind.CLAUDE))
        assertEquals(listOf("d", "c", "b", "a"), found.items.map { it.sessionId })
        assertEquals(listOf(false, true, true, true), found.items.map { it.alreadyManaged })
        assertTrue(found.complete)
    }

    @Test
    fun availability_follows_scan_completeness_and_archived_members_are_hidden() = runBlocking {
        val svc = service()
        setScan(AgentKind.CLAUDE, row("a", AgentKind.CLAUDE, 3), row("b", AgentKind.CLAUDE, 2), row("gone", AgentKind.CLAUDE, 1, "last title"))
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))

        setScan(AgentKind.CLAUDE, row("a", AgentKind.CLAUDE, 3), row("b", AgentKind.CLAUDE, 2))
        val missing = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE)).items!!.single { it.sessionId == "gone" }
        assertEquals(ManagedAvailability.MISSING, missing.availability)
        assertNull(missing.summary)
        assertEquals("last title", missing.lastKnownTitle)

        setScan(AgentKind.CLAUDE, completeness = ScanCompleteness.PERMISSION_DENIED)
        val unknown = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(listOf("a", "b", "gone"), ids(unknown), "an unreadable history never hides a member")
        assertTrue(unknown.items!!.all { it.availability == ManagedAvailability.UNKNOWN })
        assertEquals(ManagedScanDiagnostics.PERMISSION_DENIED, unknown.agents!!.single().diagnostic)

        archived += "b"
        assertEquals(listOf("a", "gone"), ids(svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))))
    }

    @Test
    fun an_all_agents_list_is_one_project_order_and_flags_ids_shared_across_agents() = runBlocking {
        val svc = service()
        setScan(AgentKind.CLAUDE, row("shared", AgentKind.CLAUDE, 2), row("c1", AgentKind.CLAUDE, 1))
        setScan(AgentKind.CODEX, row("shared", AgentKind.CODEX, 5), row("x1", AgentKind.CODEX, 4))
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CODEX))
        groups += SessionGroup("g1", "G", 0)
        val all = svc.list(ListManagedSessions("r", workdir, allAgents = true))
        assertTrue(all.allAgents)
        assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), all.agents!!.map { it.agent })
        assertEquals(listOf("shared" to AgentKind.CODEX, "x1" to AgentKind.CODEX, "shared" to AgentKind.CLAUDE, "c1" to AgentKind.CLAUDE),
            all.items!!.map { it.sessionId to it.agent })
        assertEquals(setOf("shared"), all.items!!.filter { it.groupAmbiguous }.map { it.sessionId }.toSet())
        assertEquals(listOf(SessionGroup("g1", "G", 0)), all.groups)
    }

    @Test
    fun malformed_requests_are_refused_without_defaulting_an_agent() = runBlocking {
        val svc = service()
        suspend fun code(f: Frame) = svc.state(f).error
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(EnableManagedSessions("r", workdir, null)))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(ImportSession("r", workdir, null, "s")))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(RemoveManagedSession("r", workdir, null, "s")))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(ListManagedSessions("r", workdir)))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(ListManagedSessions("r", workdir, AgentKind.CLAUDE, allAgents = true)))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, svc.discover(DiscoverSessions("r", workdir, null)).error)
        assertEquals(ManagedSessionErrors.UNSUPPORTED, code(EnableManagedSessions("r", workdir, AgentKind.KIMI)))
        assertEquals(ManagedSessionErrors.INVALID_WORKDIR, code(ListManagedSessions("r", tmp.resolve("nope").toString(), AgentKind.CLAUDE)))
        assertEquals(ManagedSessionErrors.INVALID_REQUEST, code(ImportSession("r", workdir, AgentKind.CLAUDE, "../x")))
        assertEquals(0, scanCalls, "no refusal scanned anything")
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".json") }, "and none wrote the store")
    }

    @Test
    fun every_refusal_echoes_a_bounded_workdir_and_stays_within_the_budget() = runBlocking {
        val svc = service()
        for (huge in listOf("中".repeat(1_000_000), "".repeat(500_000), "/" + "x".repeat(3_000_000))) {
            val frames: List<ToPhone> = listOf(
                svc.state(ListManagedSessions("r", huge, allAgents = true)),
                svc.state(ListManagedSessions("r", huge)),
                svc.state(EnableManagedSessions("r", huge, AgentKind.CLAUDE)),
                svc.state(EnableManagedSessions("r", huge, null)),
                svc.state(ImportSession("r", huge, AgentKind.CLAUDE, "s")),
                svc.state(RemoveManagedSession("r", huge, null, "../bad")),
                svc.discover(DiscoverSessions("r", huge, AgentKind.CLAUDE)),
                svc.discover(DiscoverSessions("r", huge, null, query = "q".repeat(10_000), cursor = "c".repeat(10_000))),
            )
            for (f in frames) {
                val echoed = (f as? ManagedSessionsState)?.workdir ?: (f as DiscoveredSessions).workdir
                assertTrue(echoed.length <= MANAGED_WORKDIR_MAX_CHARS, "echoed ${echoed.length} chars")
                assertTrue(bytes(f) <= MANAGED_FRAME_BUDGET_BYTES, "a refusal is ${bytes(f)} bytes")
                assertNotNull((f as? ManagedSessionsState)?.error ?: (f as DiscoveredSessions).error)
            }
        }
        assertEquals(0, scanCalls)
    }

    @Test
    fun enable_import_and_remove_replies_carry_the_outcome_and_failures_leave_no_state() = runBlocking {
        val svc = service()
        setScan(AgentKind.CODEX, row("t1", AgentKind.CODEX, 1), completeness = ScanCompleteness.TRUNCATED)
        val refused = svc.state(EnableManagedSessions("r", workdir, AgentKind.CODEX))
        assertEquals(ManagedSessionErrors.SCAN_INCOMPLETE, refused.error)
        assertEquals(ManagedScanDiagnostics.TRUNCATED, refused.diagnostic)
        assertNull(refused.items)

        setScan(AgentKind.CODEX, row("t1", AgentKind.CODEX, 1))
        val imported = svc.state(ImportSession("r", workdir, AgentKind.CODEX, "t1"))
        assertTrue(imported.changed)
        assertFalse(imported.alreadyManaged)
        assertEquals(ManagedSessionOrigin.EXPLICIT_IMPORT, imported.entry!!.origin)
        val again = svc.state(ImportSession("r", workdir, AgentKind.CODEX, "t1"))
        assertFalse(again.changed)
        assertTrue(again.alreadyManaged)
        assertEquals(ManagedSessionErrors.NOT_FOUND, svc.state(ImportSession("r", workdir, AgentKind.CODEX, "t2")).error)
        val removed = svc.state(RemoveManagedSession("r", workdir, AgentKind.CODEX, "t1"))
        assertTrue(removed.changed)
        assertEquals(emptyList(), removed.items)

        svc.store.fileFor(workdir).writeText("{broken")
        val corrupt = svc.state(ListManagedSessions("r", workdir, AgentKind.CODEX))
        assertEquals(ManagedSessionErrors.STORE_CORRUPT, corrupt.error)
        assertTrue(corrupt.readOnly)
        assertNull(corrupt.items)
        assertEquals(ManagedSessionErrors.STORE_CORRUPT, svc.state(ImportSession("r", workdir, AgentKind.CODEX, "t1")).error)
    }

    @Test
    fun a_corrupt_or_unreadable_store_answers_a_list_with_the_exact_refusal_shape() = runBlocking {
        setScan(AgentKind.CLAUDE, row("a", AgentKind.CLAUDE, 1))
        val healthy = service()
        assertNull(healthy.state(ImportSession("r", workdir, AgentKind.CLAUDE, "a")).error)

        healthy.store.fileFor(workdir).writeText("{corrupt")
        for (req in listOf(ListManagedSessions("c1", workdir, AgentKind.CLAUDE), ListManagedSessions("c2", workdir, allAgents = true))) {
            val corrupt = healthy.list(req)
            assertEquals(ManagedSessionErrors.STORE_CORRUPT, corrupt.error)
            assertTrue(corrupt.readOnly)
            assertNull(corrupt.agents)
            assertNull(corrupt.items)
            assertEquals(req.requestId, corrupt.requestId)
            assertEquals(workdir, corrupt.canonicalWorkdir)
            assertNull(corrupt.nextCursor)
            assertFalse(corrupt.complete)
        }
        healthy.store.fileFor(workdir).delete()

        // a write whose outcome is unknown leaves this process unable to trust the project: Unreadable
        val unsure = service(files = DurablePinFiles(forceDirectory = { throw java.io.IOException("dir sync failed") }))
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE, unsure.state(ImportSession("r", workdir, AgentKind.CLAUDE, "a")).error)
        val unreadable = unsure.list(ListManagedSessions("u1", workdir, AgentKind.CLAUDE))
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE, unreadable.error)
        assertFalse(unreadable.readOnly)
        assertNull(unreadable.agents)
        assertNull(unreadable.items)
        assertEquals("u1", unreadable.requestId)
    }

    @Test
    fun the_store_directory_is_owner_only() = runBlocking {
        if (Files.getFileAttributeView(root.toPath(), PosixFileAttributeView::class.java) == null) return@runBlocking
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val svc = service()
        setScan(AgentKind.CLAUDE, row("a", AgentKind.CLAUDE, 1))
        assertNull(svc.state(ImportSession("r", workdir, AgentKind.CLAUDE, "a")).error)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.toPath())))

        val fresh = tmp.resolve("fresh-root").toFile()
        val created = ManagedSessionStore(fresh)
        assertTrue(created.import(workdir, AgentKind.CLAUDE, "a", SessionScan(AgentKind.CLAUDE, workdir, listOf(row("a", AgentKind.CLAUDE, 1)), ScanCompleteness.COMPLETE))
            is dev.ccpocket.daemon.disk.ManagedMutation.Committed)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh.toPath())), "a directory the store had to create")
    }

    // ── paging, cursors, read limits, scan reuse ────────────────────────────────────────────────────────

    @Test
    fun list_pages_follow_their_cursor_and_a_stale_foreign_or_forged_cursor_is_refused() = runBlocking {
        val svc = service(budget = 4_000)
        val rows = (0 until 30).map { row("s%02d".format(it), AgentKind.CLAUDE, 100L - it) }
        setScan(AgentKind.CLAUDE, *rows.toTypedArray())
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))

        val seen = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = cursor))
            assertNull(page.error, page.message)
            seen += ids(page)
            assertEquals(page.nextCursor == null, page.complete)
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue(pages > 1)
        assertEquals(rows.map { it.sessionId }, seen, "every member exactly once, in order")

        val next = assertNotNull(svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE)).nextCursor)
        assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.list(ListManagedSessions("r", workdir, allAgents = true, cursor = next)).error, "another scope")
        val parts = next.split(".")
        val skipped = "${parts[0]}.25.${parts[2]}.${parts[3]}"
        assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = skipped)).error, "an edited offset")
        assertEquals(ManagedSessionErrors.CURSOR_INVALID, service(budget = 4_000).list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = next)).error, "another daemon process")
        svc.state(RemoveManagedSession("r", workdir, AgentKind.CLAUDE, "s00"))
        assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = next)).error, "the list changed underneath")
    }

    @Test
    fun a_row_larger_than_the_budget_is_still_sent_alone() = runBlocking {
        val svc = service(budget = 1)
        setScan(AgentKind.CLAUDE, row("a", AgentKind.CLAUDE, 2), row("b", AgentKind.CLAUDE, 1))
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))
        val first = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(listOf("a"), ids(first))
        val second = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = first.nextCursor))
        assertEquals(listOf("b"), ids(second))
        assertTrue(second.complete)
    }

    @Test
    fun discovery_matches_pages_and_binds_its_cursor_and_a_forged_page_number_cannot_pass_the_limit() = runBlocking {
        val svc = service()
        setScan(AgentKind.CLAUDE, row("id-1", AgentKind.CLAUDE, 5, "Fix login"), row("id-2", AgentKind.CLAUDE, 4, "refactor"),
            row("id-3", AgentKind.CLAUDE, 3, "FIX tests"), row("id-4", AgentKind.CLAUDE, 2, "docs"), row("ID-5", AgentKind.CLAUDE, 1, "other"))
        assertEquals(listOf("id-1", "id-3"), svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = "fix")).items.map { it.sessionId })
        assertEquals(listOf("ID-5"), svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = "id-5")).items.map { it.sessionId })

        val p1 = svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, limit = 2))
        assertEquals(listOf("id-1", "id-2"), p1.items.map { it.sessionId })
        val p2 = svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, cursor = p1.nextCursor, limit = 2))
        assertEquals(listOf("id-3", "id-4"), p2.items.map { it.sessionId })
        val p3 = svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, cursor = p2.nextCursor, limit = 2))
        assertEquals(listOf("ID-5"), p3.items.map { it.sessionId })
        assertTrue(p3.complete)

        assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = "fix", cursor = p1.nextCursor)).error)
        assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.discover(DiscoverSessions("r", workdir, AgentKind.CODEX, cursor = p1.nextCursor)).error)
        val (_, offset, _, mac) = p1.nextCursor!!.split(".")
        for (forged in listOf("v1.$offset.0.$mac", "v1.0.${MANAGED_DISCOVER_MAX_PAGES - 1}.$mac", "v1.$offset.1.${"0".repeat(24)}")) {
            assertEquals(ManagedSessionErrors.CURSOR_INVALID, svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, cursor = forged, limit = 2)).error, forged)
        }

        // the genuine walk stops at the page limit however many rows remain
        setScan(AgentKind.CLAUDE, *(0 until MANAGED_DISCOVER_MAX_PAGES + 5).map { row("p%03d".format(it), AgentKind.CLAUDE, 1_000L - it) }.toTypedArray())
        var cursor: String? = null
        var pages = 0
        var last: DiscoveredSessions
        do {
            last = svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, cursor = cursor, limit = 1))
            assertNull(last.error)
            pages++
            cursor = last.nextCursor
        } while (cursor != null)
        assertEquals(MANAGED_DISCOVER_MAX_PAGES, pages)
        assertEquals(ManagedScanDiagnostics.PAGE_LIMIT, last.diagnostic)
        assertFalse(last.complete)

        setScan(AgentKind.CLAUDE, row("ok", AgentKind.CLAUDE, 2), row("bad/id", AgentKind.CLAUDE, 1))
        val partial = svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(listOf("ok"), partial.items.map { it.sessionId })
        assertEquals(ManagedScanDiagnostics.PARTIAL, partial.diagnostic)
    }

    @Test
    fun cursor_mac_input_is_unambiguous_and_a_cursor_never_transfers_to_a_crafted_query() = runBlocking {
        // security review R1: field boundaries are length-prefixed, so shifting content between fields changes the bytes
        fun input(vararg parts: String) = ManagedSessionService.macInput(parts.toList())
        assertNotEquals(input("ab", "c"), input("a", "bc"))
        assertNotEquals(input("a b", "c"), input("a", "b c"))
        assertNotEquals(input("a\u0000b", "c"), input("a", "b\u0000c"))
        assertNotEquals(input("1:a", ""), input("", "1:a"))
        assertNotEquals(input("q", "5", "39"), input("q:5", "3", "9"))
        assertEquals(input("x", ""), input("x", ""), "deterministic")

        val svc = service()
        setScan(AgentKind.CLAUDE, *(0 until 5).map { row("q-$it", AgentKind.CLAUDE, 10L - it) }.toTypedArray())
        val cursor = assertNotNull(svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = "q", limit = 1)).nextCursor)
        assertNull(svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = "q", cursor = cursor, limit = 1)).error, "control: its own query")
        for (crafted in listOf("q x", "q\u0000x", "q:x", "1:q", "q\u00000", "q 1")) {
            assertEquals(ManagedSessionErrors.CURSOR_INVALID,
                svc.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE, query = crafted, cursor = cursor, limit = 1)).error, "query <$crafted>")
        }
    }

    @Test
    fun the_store_directory_is_owner_only_from_creation_even_when_the_first_write_fails() {
        if (Files.getFileAttributeView(tmp, PosixFileAttributeView::class.java) == null) return
        // security review R4: a failed first write used to leave the directory the durable writer created at umask width
        val fresh = tmp.resolve("r4-root").toFile()
        val failing = ManagedSessionStore(fresh, DurablePinFiles(atomicMove = { _, _ -> throw java.io.IOException("disk full") }), ::canonicalManagedWorkdir) { 1L }
        val out = failing.import(workdir, AgentKind.CLAUDE, "a", SessionScan(AgentKind.CLAUDE, workdir, listOf(row("a", AgentKind.CLAUDE, 1)), ScanCompleteness.COMPLETE))
        assertTrue(out is dev.ccpocket.daemon.disk.ManagedMutation.Refused)
        assertTrue(fresh.isDirectory)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh.toPath())))
    }

    private class Collect : OutboundSink {
        val frames = CopyOnWriteArrayList<Frame>()
        override suspend fun emit(frame: Frame) { frames += frame }
    }

    @Test
    fun reads_are_capped_per_connection() = runBlocking {
        val gate = CountDownLatch(1)
        val svc = service()
        scans[AgentKind.CLAUDE] = { wd -> gate.await(10, TimeUnit.SECONDS); SessionScan(AgentKind.CLAUDE, wd, emptyList(), ScanCompleteness.COMPLETE) }
        val sink = Collect()
        repeat(3) { svc.accept(ListManagedSessions("r$it", workdir, AgentKind.CLAUDE), sink, "conn-A") }
        withTimeout(5_000) { while (sink.frames.isEmpty()) delay(10) }
        val busy = sink.frames.single() as ManagedSessionsState
        assertEquals(ManagedSessionErrors.STORE_UNAVAILABLE, busy.error, "the third concurrent read on one connection is refused")
        assertEquals("r2", busy.requestId)
        val other = Collect()
        svc.accept(DiscoverSessions("d", workdir, AgentKind.CLAUDE), other, "conn-B")
        delay(200)
        assertTrue(other.frames.isEmpty(), "another connection has its own allowance (still waiting on the scan)")
        gate.countDown()
        withTimeout(5_000) { while (sink.frames.size < 3 || other.frames.isEmpty()) delay(10) }
        assertEquals(2, sink.frames.count { (it as ManagedSessionsState).error == null })
        svc.accept(ListManagedSessions("again", workdir, AgentKind.CLAUDE), sink, "conn-A")
        withTimeout(5_000) { while (sink.frames.size < 4) delay(10) }
        assertNull((sink.frames.last() as ManagedSessionsState).error, "finished reads free the allowance")
    }

    @Test
    fun paging_reuses_one_scan_until_it_expires_or_the_list_changes() = runBlocking {
        val svc = service(budget = 3_000, cacheTtlMs = 10_000)
        setScan(AgentKind.CLAUDE, *(0 until 20).map { row("s%02d".format(it), AgentKind.CLAUDE, 100L - it) }.toTypedArray())
        svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))
        val afterEnable = scanCalls
        var cursor: String? = null
        var pages = 0
        do {
            val page = svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE, cursor = cursor))
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue(pages > 2)
        assertEquals(afterEnable + 1, scanCalls, "one scan served every page")
        svc.discover(DiscoverSessions("d", workdir, AgentKind.CLAUDE))
        assertEquals(afterEnable + 1, scanCalls, "and discovery at the same revision")

        now += 10_001
        svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(afterEnable + 2, scanCalls, "an expired scan is taken again")

        val beforeRemove = scanCalls
        svc.state(RemoveManagedSession("r", workdir, AgentKind.CLAUDE, "s00"))
        svc.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(beforeRemove + 1, scanCalls, "a new store revision takes one fresh scan (the remove reply), which the next read reuses")
        val beforeImport = scanCalls
        svc.state(ImportSession("r", workdir, AgentKind.CLAUDE, "s00"))
        assertEquals(beforeImport + 1, scanCalls, "a mutation always verifies against a fresh scan")
    }

    // ── adversarial worst-case frames (wire review P1-1) ────────────────────────────────────────────────

    @Test
    fun adversarial_worst_case_managed_lists_and_discovery_pages_stay_within_the_byte_budget() = runBlocking {
        val wide = "中".repeat(2048)
        val anyPath: (String) -> String? = { p -> p.takeIf(::isValidManagedWorkdir) }
        val svc = service(canonicalize = anyPath)
        val control = "".repeat(20_000)
        val cwd = "c".repeat(1024)
        fun id(agent: AgentKind, i: Int) = (if (agent == AgentKind.CLAUDE) "a" else "b").repeat(120) + "%08d".format(i)
        fun rows(agent: AgentKind) = (0 until MANAGED_SESSIONS_MAX).map { i ->
            SessionSummary(
                id(agent, i), control, control, Int.MAX_VALUE, cwd, Long.MAX_VALUE - i, "".repeat(5_000), "".repeat(5_000),
                true, true, agent, "".repeat(5_000), "g".repeat(64), forkedFrom = id(agent, i + 1), rewindOf = id(agent, i + 2),
            )
        }
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            val r = rows(agent)
            scans[agent] = { wd -> SessionScan(agent, wd, r, ScanCompleteness.COMPLETE) }
            val out = svc.state(EnableManagedSessions("r".repeat(128), wide, agent))
            assertNull(out.error, out.message)
            assertTrue(bytes(out) <= MANAGED_FRAME_BUDGET_BYTES, "enable reply ${bytes(out)}")
        }
        repeat(1_000) { i -> groups += SessionGroup("g".repeat(60) + "%04d".format(i), "".repeat(60), i) }
        for (sid in rows(AgentKind.CLAUDE).map { it.sessionId }) assign[sid] = "g".repeat(64)
        busy += rows(AgentKind.CLAUDE).map { it.sessionId }

        suspend fun walk(unavailableCodex: Boolean): Int {
            if (unavailableCodex) scans[AgentKind.CODEX] = { wd -> SessionScan(AgentKind.CODEX, wd, emptyList(), ScanCompleteness.ERROR) }
            var cursor: String? = null
            var total = 0
            do {
                val page = svc.list(ListManagedSessions("r".repeat(128), wide, allAgents = true, cursor = cursor))
                assertNull(page.error, page.message)
                val size = bytes(page)
                assertTrue(size <= MANAGED_FRAME_BUDGET_BYTES, "a managed page is $size bytes")
                assertTrue(page.groups!!.size <= MANAGED_GROUPS_MAX)
                total += page.items!!.size
                cursor = page.nextCursor
            } while (cursor != null)
            return total
        }
        assertEquals(2 * MANAGED_SESSIONS_MAX, walk(unavailableCodex = false))
        assertEquals(2 * MANAGED_SESSIONS_MAX, walk(unavailableCodex = true), "last-known fallbacks are bounded as well")
        assertTrue(bytes(svc.state(ImportSession("r".repeat(128), wide, AgentKind.CLAUDE, "extra"))) <= MANAGED_FRAME_BUDGET_BYTES)

        val discoverRows = (0 until 5_000).map { i ->
            SessionSummary("d".repeat(120) + "%08d".format(i), control, control, 1, cwd, i.toLong(), agent = AgentKind.CLAUDE)
        }
        scans[AgentKind.CLAUDE] = { wd -> SessionScan(AgentKind.CLAUDE, wd, discoverRows, ScanCompleteness.COMPLETE) }
        val page = svc.discover(DiscoverSessions("r".repeat(128), wide, AgentKind.CLAUDE, query = "".repeat(200), limit = 50))
        assertEquals(50, page.items.size)
        assertTrue(bytes(page) <= MANAGED_FRAME_BUDGET_BYTES, "a discovery page is ${bytes(page)} bytes")
    }

    // ── fixture: scan → import → restart ────────────────────────────────────────────────────────────────

    @Test
    fun fixture_real_claude_transcripts_scan_import_and_survive_a_restart() = runBlocking {
        val transcripts = Files.createDirectories(tmp.resolve("claude-project"))
        fun transcript(id: String, mtime: Long) = transcripts.resolve("$id.jsonl").also {
            it.writeText("""{"type":"user","message":{"role":"user","content":"hello $id"},"cwd":"$workdir"}""" + "\n")
            Files.setLastModifiedTime(it, FileTime.fromMillis(mtime))
        }
        val one = "0b9a0001-aaaa-bbbb-cccc-000000000001"
        val two = "0b9a0002-aaaa-bbbb-cccc-000000000002"
        transcript(one, 1_000)
        transcript(two, 2_000)
        scans[AgentKind.CLAUDE] = { wd -> claudeProjectScan(transcripts, wd) }

        val first = service()
        val found = first.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE))
        assertTrue(found.complete)
        assertEquals(listOf(two, one), found.items.map { it.sessionId })
        assertNull(first.state(ImportSession("r", workdir, AgentKind.CLAUDE, one)).error)
        val before = transcripts.resolve("$one.jsonl").toFile().readBytes()

        val restarted = service()
        val listed = restarted.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertEquals(listOf(one), ids(listed))
        assertEquals(ManagedAvailability.AVAILABLE, listed.items!!.single().availability)
        assertEquals("hello $one", listed.items!!.single().summary!!.firstPrompt)
        assertTrue(restarted.discover(DiscoverSessions("r", workdir, AgentKind.CLAUDE)).items.single { it.sessionId == one }.alreadyManaged)
        assertTrue(before.contentEquals(transcripts.resolve("$one.jsonl").toFile().readBytes()), "the native transcript is untouched")

        Files.delete(transcripts.resolve("$one.jsonl"))
        assertEquals(ManagedAvailability.MISSING, restarted.list(ListManagedSessions("r", workdir, AgentKind.CLAUDE)).items!!.single().availability)
    }

    // ── creation association (owner-only) ───────────────────────────────────────────────────────────────

    private fun report(sid: String, parent: String? = null, agent: AgentKind = AgentKind.CLAUDE, current: Boolean = true, owner: Boolean = true) =
        NativeSessionReport("convo", agent, workdir, sid, parent, { current }, owner)

    private fun members(svc: ManagedSessionService, agent: AgentKind = AgentKind.CLAUDE) =
        (svc.store.read(workdir) as? ManagedProjectRead.Loaded)?.state?.orderedMembers(agent)?.map { it.key.nativeSessionId to it.origin }.orEmpty()

    private fun notices(svc: ManagedSessionService, key: Any = "owner"): CopyOnWriteArrayList<PocketError> =
        CopyOnWriteArrayList<PocketError>().also { list -> svc.attach(key, onRegisterError = { list += it }) { } }

    @Test
    fun an_owner_created_session_is_registered_once_for_claude_and_codex() = runBlocking {
        val svc = service()
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            svc.onNativeSession(report("new-1", agent = agent))
            svc.onNativeSession(report("new-1", agent = agent))
            assertEquals(listOf("new-1" to ManagedSessionOrigin.CREATED_HERE), members(svc, agent))
        }
        assertEquals(emptyList(), svc.pendingEntries())
    }

    @Test
    fun restricted_credentials_sessions_never_register_and_cannot_starve_the_owner() = runBlocking {
        val svc = service()
        val seen = notices(svc)
        repeat(MANAGED_SESSIONS_MAX + 1) { i -> svc.onNativeSession(report("guest-%04d".format(i), owner = false)) }
        assertEquals(emptyList(), members(svc), "a bridge / guest / collaborator session stays in discovery")
        assertFalse(svc.store.fileFor(workdir).exists(), "nothing was written for them")
        assertEquals(emptyList(), svc.pendingEntries())
        assertTrue(seen.isEmpty())

        svc.onNativeSession(report("owner-new"))
        assertEquals(listOf("owner-new" to ManagedSessionOrigin.CREATED_HERE), members(svc))
        setScan(AgentKind.CLAUDE, row("legacy", AgentKind.CLAUDE, 1), row("owner-new", AgentKind.CLAUDE, 2))
        val enabled = svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE))
        assertNull(enabled.error, "no CAPACITY: restricted creations consumed nothing")
        assertEquals(listOf("owner-new", "legacy"), ids(enabled))
    }

    @Test
    fun late_inits_unmanaged_agents_and_in_place_resumes_register_nothing() = runBlocking {
        val svc = service()
        svc.onNativeSession(report("stale", current = false))
        svc.onNativeSession(report("kimi", agent = AgentKind.KIMI))
        svc.onNativeSession(report("same", parent = "same"))
        svc.onNativeSession(NativeSessionReport("c", AgentKind.CLAUDE, tmp.resolve("nope").toString(), "s", null, { true }, true))
        assertEquals(emptyList(), members(svc))
        assertFalse(svc.store.fileFor(workdir).exists())
    }

    @Test
    fun a_branch_of_a_member_lands_beside_it_and_a_branch_of_a_non_member_proves_nothing() = runBlocking {
        val svc = service()
        val seen = notices(svc)
        svc.onNativeSession(report("parent"))
        svc.onNativeSession(report("top"))
        svc.onNativeSession(report("fork", parent = "parent"))
        assertEquals(listOf("top", "fork", "parent"), members(svc).map { it.first })
        svc.onNativeSession(report("orphan-fork", parent = "outside"))
        assertFalse(members(svc).any { it.first == "orphan-fork" })
        delay(200)
        assertTrue(seen.isEmpty(), "no evidence is not a failure")
        assertEquals(emptyList(), svc.pendingEntries())
    }

    @Test
    fun a_failed_registration_notifies_owner_subscribers_neutrally_keeps_evidence_and_finishes_after_repair() = runBlocking {
        val svc = service()
        val owner = notices(svc, "dev:owner")
        svc.store.fileFor(workdir).also { it.parentFile.mkdirs() }.writeText("{damaged")
        svc.onNativeSession(report("created-while-broken"))
        withTimeout(5_000) { while (owner.isEmpty()) delay(10) }
        val notice = owner.single()
        assertEquals(ManagedSessionService.REGISTER_FAILED, notice.code)
        assertEquals("convo", notice.convoId)
        assertFalse(notice.message.contains("Import", ignoreCase = true), "neutral wording for clients without an import entry: ${notice.message}")
        assertEquals(listOf("created-while-broken"), svc.pendingEntries()!!.map { it.sessionId })
        assertEquals("{damaged", svc.store.fileFor(workdir).readText())

        svc.store.fileFor(workdir).delete()
        val next = service()
        next.recoverPending()
        assertEquals(listOf("created-while-broken" to ManagedSessionOrigin.CREATED_HERE), members(next))
        assertEquals(emptyList(), next.pendingEntries())
    }

    @Test
    fun a_registration_refused_for_capacity_is_settled_not_left_pending() = runBlocking {
        val svc = service()
        val seen = notices(svc)
        setScan(AgentKind.CLAUDE, *(0 until MANAGED_SESSIONS_MAX).map { row("m%04d".format(it), AgentKind.CLAUDE, it.toLong()) }.toTypedArray())
        assertNull(svc.state(EnableManagedSessions("r", workdir, AgentKind.CLAUDE)).error)
        svc.onNativeSession(report("overflow"))
        withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
        assertTrue(seen.single().message.contains(ManagedSessionErrors.CAPACITY))
        assertEquals(emptyList(), svc.pendingEntries(), "a retry can never succeed: nothing stays pending")
    }

    @Test
    fun crash_windows_around_the_store_write_recover_idempotently() = runBlocking {
        val pending = File(root, "pending-registrations.json")
        fun writePending(vararg sids: String) = pending.writeText(
            """{"schemaVersion":1,"entries":[${sids.joinToString(",") { """{"agent":"claude","workdir":"$workdir","sessionId":"$it","parentSessionId":null,"at":1}""" }}]}""",
        )
        val svc = service()
        svc.onNativeSession(report("committed"))
        writePending("committed", "uncommitted")
        val boot = service()
        boot.recoverPending()
        assertEquals(listOf("uncommitted", "committed"), members(boot).map { it.first })
        assertEquals(emptyList(), boot.pendingEntries())
        boot.recoverPending()
        assertEquals(2, members(boot).size)

        pending.writeText("not json")
        service().recoverPending()
        assertEquals("not json", pending.readText(), "an unreadable evidence file is left for inspection, never replaced")
    }
}
