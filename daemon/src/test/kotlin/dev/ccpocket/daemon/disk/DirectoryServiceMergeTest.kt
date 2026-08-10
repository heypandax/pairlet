package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.SessionSummary
import java.nio.file.Files
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
        liveCodex: Set<String> = emptySet(),
        activeCodexSessions: (Set<String>) -> Map<String, SessionSummary> = { emptyMap() },
    ) = DirectoryService(
        projectsRoot = { projects },
        codexCwds = { codex },
        opencodeCwds = { opencode },
        liveCodexCwds = { liveCodex },
        activeCodexSessions = activeCodexSessions,
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
        val live = ActiveSession("sid-1", executing = true, agent = AgentKind.OPENCODE)
        // …while the daemon conversation's workdir is the realpath (OpenSession canonicalizes on open)
        val rows = service().listDirectories(null, liveByCwd = mapOf(work.toRealPath().toString() to listOf(live)))
        val row = rows.single()
        assertTrue(row.open, "the live conversation must enrich the variant-spelled row")
        assertEquals("sid-1", row.activeSessionId)
    }

    @Test
    fun external_codex_process_promotes_its_newest_rollout_to_an_active_session() {
        val summary = SessionSummary(
            sessionId = "codex-live",
            title = "External Codex",
            firstPrompt = "work",
            messageCount = 2,
            cwd = work.toString(),
            lastModified = future,
            live = true,
            agent = AgentKind.CODEX,
        )
        val row = service(
            codex = mapOf(work.toString() to future),
            // The process probe may report a realpath/symlink spelling different from the rollout.
            liveCodex = setOf(link.toString()),
            activeCodexSessions = { cwds -> cwds.associateWith { summary } },
        ).listDirectories(null).single()

        assertTrue(row.open, "an external Codex CLI should put the project in the active section")
        assertEquals("codex-live", row.activeSessionId)
        assertEquals("External Codex", row.activeSessionTitle)
        assertEquals(listOf(AgentKind.CODEX), row.activeSessions.map { it.agent })
        assertTrue(row.executing, "fresh rollout activity should carry the running state")
    }

    @Test
    fun every_external_codex_project_is_reported_not_only_the_newest_one() {
        val other = Files.createTempDirectory("ccp-codex-other")
        try {
            val summaries = mapOf(
                ProjectPaths.canonicalKey(work.toString()) to SessionSummary(
                    "codex-a", "A", "work A", 1, work.toString(), future, agent = AgentKind.CODEX,
                ),
                ProjectPaths.canonicalKey(other.toString()) to SessionSummary(
                    "codex-b", "B", "work B", 1, other.toString(), future - 1, agent = AgentKind.CODEX,
                ),
            )
            val rows = service(
                codex = mapOf(work.toString() to future, other.toString() to future - 1),
                liveCodex = setOf(work.toString(), other.toString()),
                activeCodexSessions = { cwds ->
                    cwds.mapNotNull { cwd ->
                        summaries[ProjectPaths.canonicalKey(cwd)]?.let { cwd to it }
                    }.toMap()
                },
            ).listDirectories(null)

            assertEquals(setOf("codex-a", "codex-b"), rows.mapNotNull { it.activeSessionId }.toSet())
            assertTrue(rows.all { it.open })
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    @Test
    fun daemon_live_state_wins_when_external_codex_probe_finds_the_same_session() {
        val exact = ActiveSession("same", executing = false, busy = true, agent = AgentKind.CODEX)
        val heuristic = SessionSummary(
            sessionId = "same",
            title = "heuristic",
            firstPrompt = "work",
            messageCount = 1,
            cwd = work.toString(),
            lastModified = future,
            live = true,
            agent = AgentKind.CODEX,
        )
        val row = service(
            codex = mapOf(work.toString() to future),
            // No external Terminal/Codex Desktop process: the daemon-owned Codex conversation alone
            // must still trigger a transcript lookup so its project-card title is not null.
            liveCodex = emptySet(),
            activeCodexSessions = { cwds -> cwds.associateWith { heuristic } },
        ).listDirectories(null, liveByCwd = mapOf(work.toString() to listOf(exact))).single()

        assertEquals(1, row.activeSessions.size, "the same session must not appear twice")
        assertTrue(row.activeSessions.single().busy, "authoritative daemon state must beat the heuristic")
        assertTrue(!row.activeSessions.single().executing)
        assertEquals(
            "heuristic",
            row.activeSessions.single().title,
            "authoritative live state must retain the transcript title instead of replacing it with null",
        )
    }
}
