package dev.ccpocket.daemon.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The porcelain parse matrix (#280 §7 / #281 §6). Synthetic records rather than a live repository, so
 * every shape is exercised deterministically — including the ones a fixture cannot conjure on demand
 * (a `prunable` worktree, a bare main, a copy record) and the ones that would need a remote (ahead/behind).
 * [GitServiceTest] then proves the same parser against real git output end to end.
 */
class GitPorcelainTest {

    /** NUL-terminated records, the way `--porcelain=v2 -z` actually writes them. */
    private fun z(vararg records: String) = records.joinToString("\u0000") + "\u0000"

    @Test
    fun ordinary_changes_split_into_the_staged_and_unstaged_groups_by_the_xy_pair() {
        val st = GitPorcelain.parseStatusV2(
            z(
                "# branch.oid 1111111111111111111111111111111111111111",
                "# branch.head feat/auth-refactor",
                "# branch.upstream origin/feat/auth-refactor",
                "# branch.ab +2 -1",
                "1 M. N... 100644 100644 100644 aaa bbb src/staged-only.kt",
                "1 .M N... 100644 100644 100644 aaa bbb src/work-only.kt",
                "1 MM N... 100644 100644 100644 aaa bbb src/both.kt",
                "1 A. N... 000000 100644 100644 000 ccc src/added.kt",
                "1 .D N... 100644 100644 000000 aaa bbb src/deleted.kt",
                "? docs/untracked.md",
                "! build/ignored.txt",
            ),
        )
        assertEquals("feat/auth-refactor", st.branch)
        assertEquals("origin/feat/auth-refactor", st.upstream)
        assertEquals(2, st.ahead)
        assertEquals(1, st.behind)
        assertFalse(st.detached)
        assertFalse(st.initial)

        assertEquals(listOf("src/staged-only.kt", "src/both.kt", "src/added.kt"), st.staged.map { it.path })
        assertEquals(listOf("M", "M", "A"), st.staged.map { it.code })
        assertEquals(listOf("src/work-only.kt", "src/both.kt", "src/deleted.kt"), st.unstaged.map { it.path })
        assertEquals(listOf("M", "M", "D"), st.unstaged.map { it.code })
        // the partially staged file is in BOTH groups on purpose — that is the two truths the
        // Working/Staged toggle exists to show, and collapsing it would hide half the commit.
        assertTrue("src/both.kt" in st.staged.map { it.path } && "src/both.kt" in st.unstaged.map { it.path })
        assertEquals(listOf("docs/untracked.md"), st.untracked.map { it.path })
        assertTrue(st.conflicted.isEmpty())
        // `!` ignored rows are never surfaced (we don't pass --ignored, but the parser is defensive)
        assertFalse(st.untracked.any { it.path.startsWith("build/") })
    }

    @Test
    fun a_rename_record_takes_its_source_from_the_following_nul_record() {
        val st = GitPorcelain.parseStatusV2(
            z(
                "# branch.head main",
                "2 R. N... 100644 100644 100644 aaa bbb R100 src/new-name.kt",
                "src/old-name.kt",
                "2 C. N... 100644 100644 100644 aaa bbb C075 src/copy.kt",
                "src/source.kt",
                "1 .M N... 100644 100644 100644 aaa bbb src/after-the-rename.kt",
            ),
        )
        assertEquals(listOf("src/new-name.kt", "src/copy.kt"), st.staged.map { it.path })
        assertEquals(listOf("R", "C"), st.staged.map { it.code })
        assertEquals(listOf("src/old-name.kt", "src/source.kt"), st.staged.map { it.origPath })
        // the source record must be CONSUMED, not read as a row of its own — the regression this pins is
        // a phantom untracked/modified entry named after the rename source.
        assertFalse(st.unstaged.any { it.path == "src/old-name.kt" })
        assertEquals(listOf("src/after-the-rename.kt"), st.unstaged.map { it.path })
    }

    @Test
    fun unmerged_paths_land_only_in_conflicted_and_keep_their_raw_xy() {
        val st = GitPorcelain.parseStatusV2(
            z(
                "# branch.head main",
                "u UU N... 100644 100644 100644 100644 h1 h2 h3 src/both-modified.kt",
                "u DU N... 100644 100644 100644 100644 h1 h2 h3 src/deleted-by-us.kt",
                "1 .M N... 100644 100644 100644 aaa bbb src/plain.kt",
            ),
        )
        assertEquals(listOf("src/both-modified.kt", "src/deleted-by-us.kt"), st.conflicted.map { it.path })
        assertEquals(listOf("U", "U"), st.conflicted.map { it.code })
        // the raw pair rides along so the APP can say "both modified" in the user's language
        assertEquals(listOf("UU", "DU"), st.conflicted.map { it.xy })
        // nothing can be staged while the index is unmerged, so a conflict must not double-count
        assertTrue(st.staged.none { it.path.startsWith("src/both") || it.path.startsWith("src/deleted") })
        assertTrue(st.unstaged.none { it.path.startsWith("src/both") || it.path.startsWith("src/deleted") })
    }

    @Test
    fun detached_head_reports_the_short_oid_and_no_branch_name() {
        val st = GitPorcelain.parseStatusV2(
            z("# branch.oid deadbeefcafe1234567890", "# branch.head (detached)", "1 .M N... 1 1 1 a b f.kt"),
        )
        assertTrue(st.detached)
        assertEquals("deadbee", st.branch)
        assertNull(st.upstream)
    }

    @Test
    fun an_empty_repository_is_initial_not_a_failure() {
        val st = GitPorcelain.parseStatusV2(z("# branch.oid (initial)", "# branch.head main", "? README.md"))
        assertTrue(st.initial)
        assertEquals("main", st.branch)
        assertEquals(listOf("README.md"), st.untracked.map { it.path })
    }

    @Test
    fun ahead_behind_survives_either_ordering_and_a_missing_header_reads_as_zero() {
        assertEquals(0 to 0, GitPorcelain.parseStatusV2(z("# branch.head main")).let { it.ahead to it.behind })
        assertEquals(7 to 0, GitPorcelain.parseStatusV2(z("# branch.ab +7 -0")).let { it.ahead to it.behind })
        assertEquals(0 to 4, GitPorcelain.parseStatusV2(z("# branch.ab +0 -4")).let { it.ahead to it.behind })
    }

    @Test
    fun a_path_with_spaces_survives_because_we_read_the_nul_form() {
        val st = GitPorcelain.parseStatusV2(z("# branch.head main", "1 .M N... 1 1 1 a b my docs/a file.md", "? another file.txt"))
        assertEquals(listOf("my docs/a file.md"), st.unstaged.map { it.path })
        assertEquals(listOf("another file.txt"), st.untracked.map { it.path })
    }

    @Test
    fun each_group_is_capped_and_says_so_instead_of_silently_shortening() {
        val many = (1..50).map { "? f$it.txt" }.toTypedArray()
        val st = GitPorcelain.parseStatusV2(z("# branch.head main", *many), cap = 10)
        assertEquals(10, st.untracked.size)
        assertTrue(st.truncated)
        assertFalse(GitPorcelain.parseStatusV2(z("# branch.head main", *many), cap = 100).truncated)
    }

    @Test
    fun an_unknown_header_from_a_newer_git_is_ignored_rather_than_breaking_the_parse() {
        val st = GitPorcelain.parseStatusV2(z("# branch.head main", "# stash 3", "1 .M N... 1 1 1 a b f.kt"))
        assertEquals("main", st.branch)
        assertEquals(1, st.unstaged.size)
    }

    // ---------------------------------------------------------------- numstat

    @Test
    fun numstat_reads_plain_binary_and_rename_records() {
        val raw = "12\t3\tsrc/a.kt\u0000-\t-\tassets/logo.png\u000031\t4\t\u0000src/old.kt\u0000src/new.kt\u0000"
        val m = GitPorcelain.parseNumstat(raw)
        assertEquals(12 to 3, m["src/a.kt"])
        // a binary file reports "-": null counts, so the row shows NO numbers rather than a false zero
        assertEquals(null to null, m["assets/logo.png"])
        // a rename's counts belong to the DESTINATION, which is the second of the two extra records
        assertEquals(31 to 4, m["src/new.kt"])
        assertFalse("src/old.kt" in m)
    }

    @Test
    fun counts_are_merged_by_path_and_left_null_where_numstat_said_nothing() {
        val entries = listOf(
            dev.ccpocket.protocol.GitFileEntry("src/a.kt", "M"),
            dev.ccpocket.protocol.GitFileEntry("src/b.kt", "M"),
        )
        val merged = GitPorcelain.withCounts(entries, mapOf("src/a.kt" to (5 to 1)))
        assertEquals(5, merged[0].adds)
        assertEquals(1, merged[0].dels)
        assertNull(merged[1].adds)
        assertNull(merged[1].dels)
    }

    // --------------------------------------------------------------- branches

    @Test
    fun branches_parse_and_sort_current_first_then_newest_commit() {
        val raw = listOf(
            "main\u0000 \u00001700000000\u0000origin/main",
            "feat/x\u0000*\u00001700000500\u0000",
            "old/thing\u0000 \u00001600000000\u0000",
            "recent\u0000 \u00001700000900\u0000origin/recent",
        ).joinToString("\n")
        val bs = GitPorcelain.parseBranches(raw)
        assertEquals(listOf("feat/x", "recent", "main", "old/thing"), bs.map { it.name })
        assertTrue(bs[0].current)
        assertEquals("origin/main", bs.first { it.name == "main" }.upstream)
        assertNull(bs.first { it.name == "feat/x" }.upstream)
        assertEquals(1_700_000_900L, bs.first { it.name == "recent" }.lastCommitAt)
    }

    // -------------------------------------------------------------- worktrees

    @Test
    fun worktree_porcelain_covers_main_linked_detached_locked_prunable_and_bare() {
        val raw = """
            worktree /repo
            HEAD 1111111
            branch refs/heads/main

            worktree /repo-worktrees/feat-x
            HEAD 2222222
            branch refs/heads/feat/x

            worktree /repo-worktrees/spike
            HEAD 3333333
            detached

            worktree /repo-worktrees/held
            HEAD 4444444
            branch refs/heads/held
            locked being used offline

            worktree /repo-worktrees/gone
            HEAD 5555555
            branch refs/heads/gone
            prunable gitdir file points to non-existent location

            worktree /srv/mirror.git
            bare
        """.trimIndent()
        val ws = GitPorcelain.parseWorktrees(raw)
        assertEquals(6, ws.size)

        // git lists the primary worktree first, and that is the ONLY definition of "main" we use —
        // it is what makes Remove absent from its menu rather than merely disabled.
        assertTrue(ws[0].isMain)
        assertEquals("/repo", ws[0].path)
        assertEquals("main", ws[0].branch)
        assertTrue(ws.drop(1).none { it.isMain })

        assertEquals("feat/x", ws[1].branch) // refs/heads/ prefix is shortened, slashes inside survive

        assertTrue(ws[2].detached)
        assertNull(ws[2].branch)

        assertTrue(ws[3].locked)
        assertEquals("being used offline", ws[3].lockReason)

        assertTrue(ws[4].prunable)
        assertEquals("gitdir file points to non-existent location", ws[4].prunableReason)

        assertTrue(ws[5].bare)
        assertNull(ws[5].head)

        // dirty is untouched by the parser: "we have not looked yet" must not be confused with "clean"
        assertTrue(ws.all { it.dirty == null && it.dirtyCount == null })
    }

    @Test
    fun a_bare_locked_flag_without_a_reason_still_reads_as_locked() {
        val ws = GitPorcelain.parseWorktrees("worktree /a\nHEAD 1\nbranch refs/heads/m\nlocked\n")
        assertTrue(ws.single().locked)
        assertNull(ws.single().lockReason)
    }

    @Test
    fun a_trailing_block_without_a_blank_line_is_still_flushed() {
        val ws = GitPorcelain.parseWorktrees("worktree /a\nHEAD 1\n\nworktree /b\nHEAD 2")
        assertEquals(listOf("/a", "/b"), ws.map { it.path })
    }

    // -------------------------------------------------------- names and paths

    @Test
    fun branch_name_validation_refuses_everything_git_would_and_everything_argv_would() {
        for (good in listOf("main", "feat/x", "release/2.0", "fix-1", "a_b.c", "very/deep/name")) {
            assertTrue(GitPorcelain.isValidBranchName(good), good)
        }
        for (bad in listOf(
            "", "-f", "--force", "/leading", "trailing/", "trailing.", "x.lock", "a..b", "a//b",
            "with space", "tilde~1", "caret^", "colon:x", "q?", "star*", "brack[et", "back\\slash",
            "@", "a@{b", ".hidden", "feat/.hidden", "feat/x.lock",
        )) {
            assertFalse(GitPorcelain.isValidBranchName(bad), "should refuse: $bad")
        }
        // control characters, including the NUL our own parsers use as a separator
        assertFalse(GitPorcelain.isValidBranchName("a\u0000b"))
        assertFalse(GitPorcelain.isValidBranchName("a\nb"))
    }

    @Test
    fun the_slug_flattens_slashes_to_dashes_and_nothing_else() {
        assertEquals("feat-auth-refactor", GitPorcelain.branchSlug("feat/auth-refactor"))
        assertEquals("fix-frame-split", GitPorcelain.branchSlug("fix/frame-split"))
        assertEquals("main", GitPorcelain.branchSlug("main"))
        assertEquals("a-b-c", GitPorcelain.branchSlug("a/b/c"))
    }

    @Test
    fun a_safe_leaf_is_one_plain_directory_name() {
        for (good in listOf("feat-x", "a", "v1.2", "a_b-c.d")) assertTrue(GitPorcelain.isSafeLeaf(good), good)
        for (bad in listOf("", ".", "..", ".hidden", "a/b", "a\\b", "a b", "a\u0000b", "x".repeat(200))) {
            assertFalse(GitPorcelain.isSafeLeaf(bad), "should refuse: $bad")
        }
    }

    @Test
    fun diff_line_counting_ignores_the_file_headers() {
        val diff = """
            --- a/src/a.kt
            +++ b/src/a.kt
            @@ -1,3 +1,4 @@
             context
            -removed
            +added one
            +added two
        """.trimIndent()
        assertEquals(2 to 1, GitPorcelain.countDiffLines(diff))
    }

    @Test
    fun the_non_fast_forward_refusal_is_recognised_from_gits_own_words() {
        assertTrue(GitService.looksNonFastForward("fatal: Not possible to fast-forward, aborting."))
        assertTrue(GitService.looksNonFastForward("hint: You have divergent branches and need to specify how to reconcile them."))
        assertFalse(GitService.looksNonFastForward("fatal: could not read from remote repository"))
        assertFalse(GitService.looksNonFastForward(""))
    }
}
