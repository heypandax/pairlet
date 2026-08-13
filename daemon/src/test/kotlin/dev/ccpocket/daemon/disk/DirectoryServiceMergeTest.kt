package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #184: "All projects" showed one directory twice — each backend records the same dir in its own
 * spelling (tilde / trailing separators / macOS `/var`↔`/private/var` symlinks) and the cross-source merge
 * deduped by a weak string key, so every variant became its own row (and, the same weak key being used for
 * the session match, the extra row opened onto an empty "New session" screen).
 *
 * These pin the [ProjectPaths.canonicalKey] merge: ANY spelling variant collapses into ONE row, live
 * enrichment lands on it regardless of spelling, and a client that never declared opencode support gets no
 * row that ONLY opencode history sustains (mechanism ② — its session list strips opencode sessions, so
 * such a row could only ever show the bare "New session" CTA).
 */
class DirectoryServiceMergeTest {

    private val projects = Files.createTempDirectory("ccp-projects")
    private val work = Files.createTempDirectory("ccp-work") // /var/… on macOS: realpath differs (/private/var/…)
    private val link = work.parent.resolve("${work.fileName}-lnk").also { Files.createSymbolicLink(it, work) }
    // external mtimes must beat the freshly-written claude transcript's real mtime to be observable
    private val future = System.currentTimeMillis() + 600_000

    @AfterTest
    fun cleanup() {
        Files.deleteIfExists(link)
        projects.toFile().deleteRecursively()
        work.toFile().deleteRecursively()
    }

    /** A claude project dir whose newest transcript records [cwd] as its authoritative working dir. */
    private fun claudeProject(cwd: String, name: String = "p1") {
        val dir = projects.resolve(name).also { it.createDirectories() }
        dir.resolve("s1.jsonl").writeText("""{"cwd":"$cwd"}""" + "\n")
    }

    private fun service(
        codex: Map<String, Long> = emptyMap(),
        opencode: Map<String, Long> = emptyMap(),
        liveClaudeCwds: () -> Set<String> = { emptySet() },
        nowMillis: () -> Long = System::currentTimeMillis,
    ) = DirectoryService(
        projectsRoot = { projects },
        codexCwds = { codex },
        opencodeCwds = { opencode },
        liveClaudeCwds = liveClaudeCwds,
        nowMillis = nowMillis,
    )

    @Test
    fun variant_spellings_across_backends_merge_into_one_row() {
        claudeProject(work.toString()) // claude: the raw temp spelling
        val svc = service(
            codex = mapOf("$link/" to future), // codex: symlinked + trailing slash
            opencode = mapOf(work.toRealPath().toString() to future + 1), // opencode: fully realpath'd
        )
        val rows = svc.listDirectories(null)
        assertEquals(1, rows.size, "all three spellings are ONE directory, got: ${rows.map { it.path }}")
        assertEquals(future + 1, rows.single().lastModified, "the merged row sorts by the newest source")
        assertEquals(
            listOf(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.OPENCODE),
            rows.single().sessionAgents,
            "the merged row reports every backend with resumable history for project-level filtering",
        )
    }

    @Test
    fun external_only_variants_collapse_to_one_row_keeping_the_newest_mtime() {
        // no claude history at all — the codex/opencode variants alone must still be one row
        val svc = service(
            codex = mapOf(link.toString() to 100L),
            opencode = mapOf("$work/" to 200L),
        )
        val row = svc.listDirectories(null).single()
        assertEquals(200L, row.lastModified, "the group keeps its max mtime")
        assertEquals("$work/", row.path, "the newest variant's spelling is the row identity")
        assertTrue(row.hasSessions)
        assertEquals(listOf(AgentKind.CODEX, AgentKind.OPENCODE), row.sessionAgents)
    }

    @Test
    fun opencode_only_rows_are_dropped_for_clients_without_the_capability() {
        claudeProject(work.toString())
        val ocOnly = Files.createTempDirectory("ccp-oc-only")
        try {
            // one dir is claude+opencode (spelling variant), the other is opencode-ONLY
            val svc = service(opencode = mapOf(ocOnly.toString() to 50L, "$work/" to future))
            val without = svc.listDirectories(null, includeOpencode = false)
            assertEquals(
                listOf(work.toString()), without.map { it.path },
                "an opencode-only dir must not reach a client whose session list strips opencode",
            )
            val with = svc.listDirectories(null, includeOpencode = true)
            assertEquals(setOf(work.toString(), ocOnly.toString()), with.map { it.path }.toSet())
            assertEquals(listOf(AgentKind.OPENCODE), with.single { it.path == ocOnly.toString() }.sessionAgents)
        } finally {
            ocOnly.toFile().deleteRecursively()
        }
    }

    @Test
    fun live_enrichment_lands_on_the_merged_row_across_spellings() {
        claudeProject(link.toString()) // claude recorded the SYMLINK spelling…
        val live = ActiveSession(
            "sid-1", executing = true, agent = AgentKind.OPENCODE, executingAuthoritative = true,
        )
        // …while the daemon conversation's workdir is the realpath (OpenSession canonicalizes on open)
        val rows = service().listDirectories(null, liveByCwd = mapOf(work.toRealPath().toString() to listOf(live)))
        val row = rows.single()
        assertTrue(row.open, "the live conversation must enrich the variant-spelled row")
        assertEquals("sid-1", row.activeSessionId)
        assertTrue(row.activeSessions.single().executingAuthoritative, "daemon-owned turn truth survives enrichment")
    }

    @Test
    fun claudeHistoryBusyScalarMatchesCanonicalWorkdirAcrossSymlinkSpellings() {
        claudeProject(link.toString())

        val row = service().listDirectories(
            null,
            busyCwds = setOf(work.toRealPath().toString()),
        ).single()

        assertTrue(row.busy, "project-level busy must use the same canonical identity as live sessions")
    }

    @Test
    fun externalOnlyBusyScalarMatchesCanonicalWorkdirAcrossSymlinkSpellings() {
        val row = service(codex = mapOf(link.toString() to future)).listDirectories(
            null,
            busyCwds = setOf(work.toRealPath().toString()),
        ).single()

        assertTrue(row.busy, "external-only rows must not lose daemon background work at a symlink boundary")
    }

    @Test
    fun terminalTranscriptRecencyIsVisibleButExplicitlyNonAuthoritative() {
        val transcript = projects.resolve("terminal").also { it.createDirectories() }.resolve("terminal-sid.jsonl")
        transcript.writeText(
            """{"type":"user","cwd":"$link","message":{"content":"run tests"}}""" + "\n",
        )
        val writtenAt = 1_000_000L
        Files.setLastModifiedTime(transcript, FileTime.fromMillis(writtenAt))
        var now = writtenAt + 1
        val svc = service(
            // Real lsof output is canonical even when Claude was launched through [link].
            liveClaudeCwds = { setOf(work.toRealPath().toString()) },
            nowMillis = { now },
        )

        val fresh = svc.listDirectories(null).single().activeSessions.single()
        assertTrue(fresh.executing, "the terminal mtime heuristic keeps its existing Running affordance")
        assertEquals(false, fresh.executingAuthoritative, "mtime is never completion evidence")

        now = writtenAt + 30_001
        val expired = svc.listDirectories(null).single().activeSessions.single()
        assertEquals(false, expired.executing)
        assertEquals(false, expired.executingAuthoritative, "expiry remains explicitly non-authoritative")
    }
}
