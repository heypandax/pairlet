package dev.ccpocket.daemon.git

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AddWorktree
import dev.ccpocket.protocol.FetchGitStatus
import dev.ccpocket.protocol.GIT_CONFIRM_TTL_MS
import dev.ccpocket.protocol.GIT_OP_BRANCH
import dev.ccpocket.protocol.GIT_OP_CHECKOUT
import dev.ccpocket.protocol.GIT_OP_COMMIT
import dev.ccpocket.protocol.GIT_OP_REVERT
import dev.ccpocket.protocol.GIT_OP_STAGE
import dev.ccpocket.protocol.GIT_OP_UNSTAGE
import dev.ccpocket.protocol.GitAction
import dev.ccpocket.protocol.GitActionPreview
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.ListWorktrees
import dev.ccpocket.protocol.ReadGitDiff
import dev.ccpocket.protocol.RemoveWorktree
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end behaviour of [GitService] against REAL git repositories built in a temp directory.
 *
 * Why real git rather than a mock: the properties worth defending here are properties of the boundary —
 * that `--` really does stop git's option parser, that `checkout --` really does keep staged content,
 * that a rename really is emitted as two NUL records. A fake would only prove we agree with ourselves.
 *
 * Every repo is isolated from the developer's own git configuration (no global/system config, no signing,
 * no hooks), so a machine with `commit.gpgsign = true` or a template dir does not turn these red.
 */
class GitServiceTest {

    @TempDir
    lateinit var tmp: Path

    private var clock = 1_700_000_000_000L

    private fun service(
        live: Map<String, List<ActiveSession>> = emptyMap(),
        external: Set<String> = emptySet(),
    ) = GitService(
        liveByCwd = { live }, externalCwds = { external }, nowMs = { clock },
        // the production budget is 4s of UX patience; under a full parallel suite that is a coin flip,
        // and these tests are about the enrichment logic, not about how loaded the machine is
        worktreeStatusBudgetMs = 60_000,
    )

    // ------------------------------------------------------------- fixtures

    private fun sh(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true)
        pb.environment().apply {
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_CONFIG_SYSTEM", "/dev/null")
            put("GIT_TERMINAL_PROMPT", "0")
            put("LC_ALL", "C")
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    /** A repo with one commit on `main`, isolated from the machine's git config. */
    private fun repo(name: String = "repo"): Path {
        val dir = tmp.resolve(name).also { it.createDirectories() }
        sh(dir, "init", "-q", "-b", "main")
        sh(dir, "config", "--local", "user.email", "test@example.com")
        sh(dir, "config", "--local", "user.name", "Test")
        sh(dir, "config", "--local", "commit.gpgsign", "false")
        dir.resolve("README.md").writeText("hello\n")
        sh(dir, "add", "-A")
        sh(dir, "commit", "-q", "-m", "initial")
        return dir
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    private fun status(svc: GitService, dir: Path, withBranches: Boolean = false) =
        runBlocking { svc.status(FetchGitStatus("c1", dir.toString(), withBranches), dir) }

    private fun act(svc: GitService, dir: Path, op: String, paths: List<String> = emptyList(), message: String? = null, branch: String? = null, token: String? = null) =
        runBlocking { svc.act(GitAction("c1", dir.toString(), op, paths, message, branch, token), dir) }

    // ---------------------------------------------------------------- reads

    @Test
    fun status_reads_the_four_groups_and_their_line_counts_from_a_real_repository() {
        assumeTrue(gitAvailable())
        val dir = repo()
        dir.resolve("README.md").writeText("hello\nworld\n")     // worktree change
        dir.resolve("staged.txt").writeText("a\nb\nc\n")
        sh(dir, "add", "staged.txt")                              // index change
        dir.resolve("fresh.txt").writeText("new\n")               // untracked

        val st = status(service(), dir)
        assertTrue(st.ok)
        assertFalse(st.notARepo)
        assertEquals("main", st.branch)
        assertNull(st.upstream)
        assertEquals(0, st.ahead)
        assertEquals(0, st.behind)
        assertEquals(listOf("staged.txt"), st.staged.map { it.path })
        assertEquals(listOf("README.md"), st.unstaged.map { it.path })
        assertEquals(listOf("fresh.txt"), st.untracked.map { it.path })
        assertTrue(st.conflicted.isEmpty())
        // numstat rode along on both sides
        assertEquals(3, st.staged.single().adds)
        assertEquals(1, st.unstaged.single().adds)
    }

    @Test
    fun a_session_in_a_subdirectory_sees_the_whole_repository_and_gets_its_own_workdir_echoed_back() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val sub = dir.resolve("module/src").also { it.createDirectories() }
        dir.resolve("top.txt").writeText("a\n")                 // outside the session's own directory
        sub.resolve("deep.txt").writeText("b\n")

        val svc = service()
        val st = runBlocking { svc.status(FetchGitStatus("c1", sub.toString()), sub) }
        // git runs at the TOP LEVEL, so paths are repository-relative and the change above the session
        // is visible — "the whole repository state" is the point of the panel.
        assertEquals(setOf("top.txt", "module/src/deep.txt"), st.untracked.map { it.path }.toSet())
        // …but the reply echoes the workdir the CLIENT sent, not the repo root: the app matches on the
        // string it sent, so echoing the canonical root would drop every reply for a subdirectory session.
        assertEquals(sub.toString(), st.workdir)

        val acted = runBlocking {
            svc.act(GitAction("c1", sub.toString(), GIT_OP_STAGE, listOf("top.txt")), sub)
        } as GitActionResult
        assertTrue(acted.ok, acted.error + acted.stderr)
        assertEquals(sub.toString(), acted.statusAfter?.workdir)
    }

    @Test
    fun a_directory_that_is_not_a_repository_is_a_normal_answer_not_an_error() {
        assumeTrue(gitAvailable())
        val plain = tmp.resolve("plain").also { it.createDirectories() }
        val st = status(service(), plain)
        assertTrue(st.ok)      // the app hides the Git tab; it does not show a failure
        assertTrue(st.notARepo)
        assertNull(st.error)
    }

    @Test
    fun an_empty_repository_reports_initial_and_still_lists_its_untracked_files() {
        assumeTrue(gitAvailable())
        val dir = tmp.resolve("empty").also { it.createDirectories() }
        sh(dir, "init", "-q", "-b", "main")
        dir.resolve("a.txt").writeText("x\n")
        val st = status(service(), dir)
        assertTrue(st.initial)
        assertEquals("main", st.branch)
        assertEquals(listOf("a.txt"), st.untracked.map { it.path })
    }

    @Test
    fun a_detached_head_is_reported_as_such_and_blocks_push() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val head = sh(dir, "rev-parse", "HEAD").trim()
        sh(dir, "checkout", "-q", "--detach", head)
        val st = status(service(), dir)
        assertTrue(st.detached)
        assertEquals(head.take(7), st.branch)
    }

    @Test
    fun branches_come_back_when_asked_for_and_mark_the_current_one() {
        assumeTrue(gitAvailable())
        val dir = repo()
        sh(dir, "branch", "feat/x")
        val st = status(service(), dir, withBranches = true)
        assertEquals(setOf("main", "feat/x"), st.branches.map { it.name }.toSet())
        assertEquals("main", st.branches.single { it.current }.name)
        assertTrue(status(service(), dir).branches.isEmpty()) // not asked for = not paid for
    }

    @Test
    fun diff_shows_the_working_side_and_the_staged_side_separately() {
        assumeTrue(gitAvailable())
        val dir = repo()
        dir.resolve("README.md").writeText("hello\nstaged\n")
        sh(dir, "add", "README.md")
        dir.resolve("README.md").writeText("hello\nstaged\nworking\n")

        val svc = service()
        val staged = runBlocking { svc.diff(ReadGitDiff("c1", dir.toString(), "README.md", staged = true), dir) }
        assertTrue(staged.ok)
        assertTrue("+staged" in staged.diff.orEmpty(), staged.diff.orEmpty())
        assertFalse("+working" in staged.diff.orEmpty())

        val working = runBlocking { svc.diff(ReadGitDiff("c1", dir.toString(), "README.md", staged = false), dir) }
        assertTrue(working.ok)
        assertTrue("+working" in working.diff.orEmpty(), working.diff.orEmpty())
        assertEquals(1, working.adds)
    }

    @Test
    fun an_untracked_file_still_gets_a_diff_through_the_null_device_fallback() {
        assumeTrue(gitAvailable())
        val dir = repo()
        dir.resolve("fresh.txt").writeText("one\ntwo\n")
        val d = runBlocking { service().diff(ReadGitDiff("c1", dir.toString(), "fresh.txt", staged = false), dir) }
        assertTrue(d.ok, d.error)
        assertEquals(2, d.adds)
    }

    // -------------------------------------------------------------- verbs

    @Test
    fun stage_unstage_and_commit_round_trip_and_the_result_carries_a_refreshed_status() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("a.txt").writeText("a\n")

        val staged = act(svc, dir, GIT_OP_STAGE, paths = listOf("a.txt")) as GitActionResult
        assertTrue(staged.ok, staged.error)
        // statusAfter is the whole point of the frame: the panel refreshes with no second round trip
        assertEquals(listOf("a.txt"), staged.statusAfter?.staged?.map { it.path })

        val unstaged = act(svc, dir, GIT_OP_UNSTAGE, paths = listOf("a.txt")) as GitActionResult
        assertTrue(unstaged.ok, unstaged.error)
        assertEquals(listOf("a.txt"), unstaged.statusAfter?.untracked?.map { it.path })

        act(svc, dir, GIT_OP_STAGE, paths = listOf("a.txt"))
        val committed = act(svc, dir, GIT_OP_COMMIT, message = "add a") as GitActionResult
        assertTrue(committed.ok, committed.error + committed.stderr)
        assertTrue(committed.statusAfter?.staged.isNullOrEmpty())
        assertTrue("add a" in sh(dir, "log", "-1", "--pretty=%s"))
    }

    @Test
    fun unstaging_in_a_repository_with_no_commits_uses_rm_cached_instead_of_reset() {
        assumeTrue(gitAvailable())
        val dir = tmp.resolve("initial").also { it.createDirectories() }
        sh(dir, "init", "-q", "-b", "main")
        dir.resolve("a.txt").writeText("a\n")
        val svc = service()
        act(svc, dir, GIT_OP_STAGE, paths = listOf("a.txt"))
        val r = act(svc, dir, GIT_OP_UNSTAGE, paths = listOf("a.txt")) as GitActionResult
        // `reset HEAD --` would have failed here: there is no HEAD to reset back to.
        assertTrue(r.ok, r.error + r.stderr)
        assertEquals(listOf("a.txt"), r.statusAfter?.untracked?.map { it.path })
        assertTrue(dir.resolve("a.txt").exists()) // the file itself survives
    }

    @Test
    fun commit_is_refused_without_a_message_and_while_the_index_is_unmerged() {
        assumeTrue(gitAvailable())
        val dir = conflictedRepo()
        val svc = service()
        assertEquals("a commit needs a message", (act(svc, dir, GIT_OP_COMMIT, message = "   ") as GitActionResult).error)
        val blocked = act(svc, dir, GIT_OP_COMMIT, message = "try anyway") as GitActionResult
        assertFalse(blocked.ok)
        assertTrue(blocked.error.orEmpty().contains("conflict"), blocked.error.orEmpty())
    }

    @Test
    fun conflicts_are_listed_read_only_and_nothing_lands_in_staged() {
        assumeTrue(gitAvailable())
        val dir = conflictedRepo()
        val st = status(service(), dir)
        assertEquals(listOf("f.txt"), st.conflicted.map { it.path })
        assertEquals("UU", st.conflicted.single().xy)
        assertTrue(st.staged.none { it.path == "f.txt" })
    }

    @Test
    fun branch_creation_validates_the_name_and_refuses_a_duplicate() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        assertFalse((act(svc, dir, GIT_OP_BRANCH, branch = "--force") as GitActionResult).ok)
        assertFalse((act(svc, dir, GIT_OP_BRANCH, branch = "bad name") as GitActionResult).ok)
        assertFalse((act(svc, dir, GIT_OP_BRANCH, branch = "main") as GitActionResult).ok)
        val ok = act(svc, dir, GIT_OP_BRANCH, branch = "feat/new") as GitActionResult
        assertTrue(ok.ok, ok.error)
        assertEquals("feat/new", ok.statusAfter?.branch)
    }

    @Test
    fun push_without_an_upstream_is_refused_rather_than_creating_one() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val r = act(service(), dir, dev.ccpocket.protocol.GIT_OP_PUSH) as GitActionResult
        assertFalse(r.ok)
        assertTrue(r.error.orEmpty().contains("no upstream"), r.error.orEmpty())
    }

    /**
     * A real diverged pair, which is the state the phone reported from (↑1↓1), against the two verbs
     * that behave differently there (issue #280 真机反馈 5 + 6):
     *
     *  · `pull --ff-only` must come back with [GitActionResult.notFastForward] — that flag is the ONLY
     *    thing that turns the strip amber and says "needs a merge" instead of painting a red failure.
     *    If a git wording change ever slipped past `looksNonFastForward`, the A6 state would silently
     *    disappear into the generic red one and nobody would notice by reading the code.
     *  · `push` must report the `! [rejected] … (fetch first)` line, NOT the `To <url>` banner git prints
     *    first — the complaint that started this fix.
     */
    @Test
    fun a_diverged_upstream_makes_pull_amber_and_push_report_the_rejected_line_not_the_banner() {
        assumeTrue(gitAvailable())
        val bare = tmp.resolve("remote.git")
        sh(tmp, "init", "--bare", "-q", "-b", "main", bare.toString())
        // the "other computer": seeds the remote, then moves it on by one commit
        val other = repo("other")
        sh(other, "remote", "add", "origin", bare.toString())
        sh(other, "push", "-q", "-u", "origin", "main")
        // the machine the phone is driving — a clone, so it has a real upstream
        sh(tmp, "clone", "-q", bare.toString(), "local")
        val local = tmp.resolve("local")
        sh(local, "config", "--local", "user.email", "test@example.com")
        sh(local, "config", "--local", "user.name", "Test")
        sh(local, "config", "--local", "commit.gpgsign", "false")
        other.resolve("theirs.txt").writeText("them\n")
        sh(other, "add", "-A"); sh(other, "commit", "-q", "-m", "theirs"); sh(other, "push", "-q")
        local.resolve("ours.txt").writeText("us\n")
        sh(local, "add", "-A"); sh(local, "commit", "-q", "-m", "ours")

        val svc = service()
        val pull = act(svc, local, dev.ccpocket.protocol.GIT_OP_PULL) as GitActionResult
        assertFalse(pull.ok)
        assertTrue(pull.notFastForward, "A6 needs this flag; stderr was: ${pull.stderr}")
        assertTrue(pull.stderr.startsWith("fatal:"), pull.stderr)

        // the status the phone was staring at: one out, one in
        val st = status(svc, local)
        assertEquals(1, st.ahead)
        assertEquals(1, st.behind)

        val push = act(svc, local, dev.ccpocket.protocol.GIT_OP_PUSH) as GitActionResult
        assertFalse(push.ok)
        assertFalse(push.stderr.startsWith("To "), "the To banner is the line we must NOT send: ${push.stderr}")
        assertTrue(push.stderr.contains("[rejected]"), push.stderr)
        assertTrue(push.stderr.contains("fetch first") || push.stderr.contains("non-fast-forward"), push.stderr)
    }

    // -------------------------------------------------- the injection red line

    @Test
    fun a_file_whose_name_looks_like_a_flag_is_treated_as_a_file_because_of_the_dash_dash() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        // Without `--`, `git add --cached` is "unknown option" and `git checkout -- -rf.txt` would read
        // -rf.txt as flags. These names are legal on disk, so the marker is the only thing between us
        // and a mis-parse that could touch something the user never named.
        dir.resolve("--cached").writeText("x\n")
        dir.resolve("-rf.txt").writeText("y\n")

        val staged = act(svc, dir, GIT_OP_STAGE, paths = listOf("--cached", "-rf.txt")) as GitActionResult
        assertTrue(staged.ok, staged.error + staged.stderr)
        assertEquals(setOf("--cached", "-rf.txt"), staged.statusAfter?.staged?.map { it.path }?.toSet())

        val unstaged = act(svc, dir, GIT_OP_UNSTAGE, paths = listOf("--cached")) as GitActionResult
        assertTrue(unstaged.ok, unstaged.error + unstaged.stderr)

        // and the read path too: a diff for a flag-shaped name must be a diff of that file
        val d = runBlocking { svc.diff(ReadGitDiff("c1", dir.toString(), "-rf.txt", staged = true), dir) }
        assertTrue(d.ok, d.error)
    }

    @Test
    fun a_verb_outside_the_allow_list_is_refused_by_name_and_starts_no_process() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val before = sh(dir, "rev-parse", "HEAD").trim()
        for (forbidden in listOf("reset", "reset --hard", "stash", "merge", "rebase", "push --force", "clean", "")) {
            val r = act(service(), dir, forbidden) as GitActionResult
            assertFalse(r.ok, forbidden)
            assertTrue(r.error.orEmpty().startsWith("unsupported git operation"), "$forbidden -> ${r.error}")
        }
        assertEquals(before, sh(dir, "rev-parse", "HEAD").trim())
    }

    @Test
    fun the_commit_message_can_never_be_read_as_an_option() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("a.txt").writeText("a\n")
        act(svc, dir, GIT_OP_STAGE, paths = listOf("a.txt"))
        // -m's VALUE: an option-shaped message is a message
        val r = act(svc, dir, GIT_OP_COMMIT, message = "--amend --author=evil") as GitActionResult
        assertTrue(r.ok, r.error + r.stderr)
        assertEquals("--amend --author=evil", sh(dir, "log", "-1", "--pretty=%s").trim())
        assertEquals(2, sh(dir, "rev-list", "--count", "HEAD").trim().toInt()) // it is a NEW commit, not an amend
        assertEquals("Test", sh(dir, "log", "-1", "--pretty=%an").trim())
    }

    // -------------------------------------------------------- two-step tokens

    @Test
    fun reverting_a_file_takes_two_steps_and_keeps_the_staged_content() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("README.md").writeText("hello\nstaged\n")
        sh(dir, "add", "README.md")
        dir.resolve("README.md").writeText("hello\nstaged\nworking\n")

        val preview = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md"))
        assertIs<GitActionPreview>(preview)
        assertEquals(GIT_OP_REVERT, preview.op)
        assertEquals("revert", preview.summary)
        assertEquals(listOf("README.md"), preview.files.map { it.path })
        assertTrue(preview.confirmToken.isNotEmpty())
        assertEquals(clock + GIT_CONFIRM_TTL_MS, preview.expiresAtMs)

        val done = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md"), token = preview.confirmToken) as GitActionResult
        assertTrue(done.ok, done.error + done.stderr)
        // the sheet promises "staged content is kept" — `checkout --` restores FROM the index, so it is
        assertEquals("hello\nstaged\n", dir.resolve("README.md").readText())
        assertEquals(listOf("README.md"), done.statusAfter?.staged?.map { it.path })
    }

    @Test
    fun a_confirm_token_is_single_use() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("README.md").writeText("changed\n")
        val preview = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md")) as GitActionPreview
        assertTrue((act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md"), token = preview.confirmToken) as GitActionResult).ok)

        // the user edits again; a replayed token must NOT be able to discard the new work
        dir.resolve("README.md").writeText("changed again\n")
        val replay = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md"), token = preview.confirmToken) as GitActionResult
        assertFalse(replay.ok)
        assertTrue(replay.error.orEmpty().contains("expired"), replay.error.orEmpty())
        assertEquals("changed again\n", dir.resolve("README.md").readText())
    }

    @Test
    fun a_confirm_token_expires() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("README.md").writeText("changed\n")
        val preview = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md")) as GitActionPreview

        clock += GIT_CONFIRM_TTL_MS + 1
        val late = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md"), token = preview.confirmToken) as GitActionResult
        assertFalse(late.ok)
        assertEquals("changed\n", dir.resolve("README.md").readText())
    }

    @Test
    fun a_token_cannot_be_re_aimed_at_a_different_file_or_verb_or_conversation() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        dir.resolve("README.md").writeText("changed\n")
        dir.resolve("other.txt").writeText("keep me\n")
        sh(dir, "add", "other.txt"); sh(dir, "commit", "-q", "-m", "other")
        dir.resolve("other.txt").writeText("keep me too\n")

        val preview = act(svc, dir, GIT_OP_REVERT, paths = listOf("README.md")) as GitActionPreview

        // different file
        val reaimed = act(svc, dir, GIT_OP_REVERT, paths = listOf("other.txt"), token = preview.confirmToken) as GitActionResult
        assertFalse(reaimed.ok)
        assertEquals("keep me too\n", dir.resolve("other.txt").readText())

        // different conversation
        val other = runBlocking {
            svc.act(GitAction("c2", dir.toString(), GIT_OP_REVERT, listOf("README.md"), confirmToken = preview.confirmToken), dir)
        } as GitActionResult
        assertFalse(other.ok)
        assertEquals("changed\n", dir.resolve("README.md").readText())
    }

    @Test
    fun checking_out_over_a_dirty_tree_takes_two_steps_and_a_clean_one_does_not() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        sh(dir, "branch", "feat/x")

        // clean: one tap
        val clean = act(svc, dir, GIT_OP_CHECKOUT, branch = "feat/x")
        assertIs<GitActionResult>(clean)
        assertTrue(clean.ok, clean.error + clean.stderr)
        assertEquals("feat/x", clean.statusAfter?.branch)

        // dirty: preview first, and the preview names what would be discarded
        dir.resolve("README.md").writeText("dirty\n")
        val preview = act(svc, dir, GIT_OP_CHECKOUT, branch = "main")
        assertIs<GitActionPreview>(preview)
        assertEquals("dirty-checkout", preview.summary)
        assertEquals("main", preview.branch)
        assertEquals(listOf("README.md"), preview.files.map { it.path })
        assertEquals("feat/x", sh(dir, "rev-parse", "--abbrev-ref", "HEAD").trim()) // nothing happened yet

        val done = act(svc, dir, GIT_OP_CHECKOUT, branch = "main", token = preview.confirmToken) as GitActionResult
        assertTrue(done.ok, done.error + done.stderr)
        assertEquals("main", done.statusAfter?.branch)
    }

    @Test
    fun the_dirty_checkout_preview_names_the_staged_side_too_because_force_discards_it() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        sh(dir, "branch", "feat/x")
        dir.resolve("staged-only.txt").writeText("s\n")
        sh(dir, "add", "staged-only.txt")            // dies to `checkout --force`, and only that
        dir.resolve("README.md").writeText("dirty\n") // dies too

        val preview = act(svc, dir, GIT_OP_CHECKOUT, branch = "feat/x") as GitActionPreview
        // a sheet that listed only the working side would promise something --force does not honour
        assertEquals(setOf("README.md", "staged-only.txt"), preview.files.map { it.path }.toSet())
    }

    @Test
    fun a_branch_and_a_file_of_the_same_name_is_never_ambiguous() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        sh(dir, "branch", "release")
        dir.resolve("release").writeText("a file that shares the branch name\n")
        sh(dir, "add", "release"); sh(dir, "commit", "-q", "-m", "add release file")

        // without the trailing `--`, git refuses this as ambiguous (or, worse, reads it as a pathspec
        // and overwrites the file from the index)
        val r = act(svc, dir, GIT_OP_CHECKOUT, branch = "release") as GitActionResult
        assertTrue(r.ok, r.error + r.stderr)
        assertEquals("release", sh(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
    }

    @Test
    fun checkout_only_accepts_a_branch_git_itself_listed() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        for (bogus in listOf("no-such-branch", "--orphan", "origin/main", "HEAD")) {
            val r = act(svc, dir, GIT_OP_CHECKOUT, branch = bogus) as GitActionResult
            assertFalse(r.ok, bogus)
            assertTrue(r.error.orEmpty().contains("no local branch"), "$bogus -> ${r.error}")
        }
        assertEquals("main", sh(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
    }

    // ------------------------------------------------------------ worktrees

    @Test
    fun worktree_add_lands_in_the_sibling_policy_directory_and_the_list_sees_it() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        val added = runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "feat/auth", createBranch = true), dir) }
        assertTrue(added.ok, added.error + added.stderr)

        val expected = tmp.resolve("repo-worktrees").resolve("feat-auth")
        assertTrue(expected.exists(), "expected $expected")

        val list = runBlocking { svc.listWorktrees(ListWorktrees("c1", dir.toString()), dir) }
        assertTrue(list.ok, list.error)
        assertEquals(2, list.worktrees.size)
        assertTrue(list.worktrees[0].isMain)
        assertEquals("main", list.worktrees[0].branch)
        assertEquals("feat/auth", list.worktrees[1].branch)
        assertFalse(list.worktrees[1].isMain)
        assertEquals(false, list.worktrees[1].dirty) // status was collected for real

        // the status frame now advertises the family, which is what unlocks the surface in the app
        assertEquals(2, status(svc, dir).worktreeCount)
        // and a branch already checked out elsewhere says WHERE, so the sheet can dim the row
        assertEquals(expected.toRealPath().toString(), status(svc, dir, withBranches = true).branches.single { it.name == "feat/auth" }.checkedOutAt?.let { Path.of(it).toRealPath().toString() })
    }

    @Test
    fun worktree_add_refuses_a_branch_already_checked_out_and_an_unknown_one() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }

        val again = runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "feat/x"), dir) }
        assertFalse(again.ok)
        assertTrue(again.error.orEmpty().contains("already checked out"), again.error.orEmpty())

        val unknown = runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "nope"), dir) }
        assertFalse(unknown.ok)

        val badName = runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "--force", createBranch = true), dir) }
        assertFalse(badName.ok)
    }

    @Test
    fun a_worktree_path_outside_the_policy_directory_is_refused() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        val escapes = listOf(
            "/tmp/anywhere",                                   // absolute, elsewhere
            tmp.resolve("elsewhere").toString(),               // sibling of the policy dir, not inside it
            tmp.resolve("repo-worktrees/a/b").toString(),      // nested, not a single leaf
            tmp.resolve("repo-worktrees/../escape").toString(), // climbs back out
            dir.resolve("inside").toString(),                  // inside the repository itself
        )
        for (p in escapes) {
            val r = runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true, path = p), dir) }
            assertFalse(r.ok, "should refuse $p")
            assertTrue(r.error.orEmpty().contains("must live directly in"), "$p -> ${r.error}")
            assertFalse(Path.of(p).normalize().exists(), "nothing may be created at $p")
        }
    }

    @Test
    fun the_path_policy_is_a_pure_function_of_the_repo_root_and_the_branch() {
        val root = tmp.resolve("proj").also { it.createDirectories() }
        val svc = service()
        val repo = GitService.Repo(root, null, 1)
        val dir = tmp.resolve("proj-worktrees")

        assertEquals(dir.resolve("feat-x"), svc.worktreePath(repo, "feat/x", null))
        assertEquals(dir.resolve("main"), svc.worktreePath(repo, "main", null))
        // an explicit leaf inside the policy dir is honoured
        assertEquals(dir.resolve("custom"), svc.worktreePath(repo, "feat/x", dir.resolve("custom").toString()))
        // everything else is refused
        assertNull(svc.worktreePath(repo, "feat/x", "/etc"))
        assertNull(svc.worktreePath(repo, "feat/x", dir.resolve("a/b").toString()))
        assertNull(svc.worktreePath(repo, "feat/x", root.resolve("nested").toString()))
        assertNull(svc.worktreePath(repo, "feat/x", tmp.resolve("proj-worktrees/../out").toString()))
        assertNull(svc.worktreePath(repo, "feat/x", dir.resolve(".hidden").toString()))
    }

    @Test
    fun removing_the_main_worktree_is_refused_outright() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val r = runBlocking { service().removeWorktree(RemoveWorktree("c1", dir.toString(), dir.toString()), dir) }
        assertIs<GitActionResult>(r)
        assertFalse(r.ok)
        assertEquals("the main worktree cannot be removed", r.error)
        assertTrue(dir.exists())
    }

    @Test
    fun removing_a_linked_worktree_takes_two_steps_and_itemises_what_would_be_lost() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        runBlocking { svc.addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }
        val wt = tmp.resolve("repo-worktrees").resolve("feat-x")
        wt.resolve("scratch.txt").writeText("uncommitted\n")

        val preview = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString()), dir) }
        assertIs<GitActionPreview>(preview)
        assertEquals("worktree-dirty", preview.summary)
        assertFalse(preview.blocked)
        assertEquals(listOf("scratch.txt"), preview.files.map { it.path })
        assertEquals("feat/x", preview.branch)
        assertTrue(wt.exists()) // still there — the preview is not the act

        val done = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString(), preview.confirmToken), dir) }
        assertIs<GitActionResult>(done)
        assertTrue(done.ok, done.error + done.stderr)
        assertFalse(wt.exists())
        // the BRANCH survives — the directory is what was deleted
        assertTrue("feat/x" in sh(dir, "branch", "--format=%(refname:short)"))
    }

    @Test
    fun a_worktree_with_a_live_session_is_refused_even_with_a_valid_confirm_token() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val plain = service()
        runBlocking { plain.addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }
        val wt = tmp.resolve("repo-worktrees").resolve("feat-x").toRealPath()

        val svc = service(live = mapOf(wt.toString() to listOf(ActiveSession("sess-1", "Review relay reconnect handling"))))
        val preview = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString()), dir) }
        assertIs<GitActionPreview>(preview)
        // a block, not a warning: the sheet renders an information panel with an inert button
        assertTrue(preview.blocked)
        assertEquals("a session is running in this worktree", preview.blockedReason)

        val refused = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString(), preview.confirmToken), dir) }
        assertIs<GitActionResult>(refused)
        assertFalse(refused.ok)
        assertTrue(refused.error.orEmpty().contains("stop it first"), refused.error.orEmpty())
        assertTrue(wt.exists())
    }

    @Test
    fun a_session_that_starts_during_the_confirm_window_still_wins_over_the_token() {
        assumeTrue(gitAvailable())
        val dir = repo()
        runBlocking { service().addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }
        val wt = tmp.resolve("repo-worktrees").resolve("feat-x").toRealPath()

        // the liveness view is read again at the side effect, not just when the preview was minted
        var live: Map<String, List<ActiveSession>> = emptyMap()
        val svc = GitService(liveByCwd = { live }, nowMs = { clock })
        val preview = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString()), dir) } as GitActionPreview
        assertFalse(preview.blocked)

        live = mapOf(wt.toString() to listOf(ActiveSession("sess-late")))
        val refused = runBlocking { svc.removeWorktree(RemoveWorktree("c1", dir.toString(), wt.toString(), preview.confirmToken), dir) } as GitActionResult
        assertFalse(refused.ok)
        assertTrue(wt.exists())
    }

    @Test
    fun the_worktree_list_names_the_session_running_in_each_checkout() {
        assumeTrue(gitAvailable())
        val dir = repo()
        runBlocking { service().addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }
        val wt = tmp.resolve("repo-worktrees").resolve("feat-x").toRealPath()
        val svc = service(live = mapOf(wt.toString() to listOf(ActiveSession("sess-1", "Fix the parser"))))
        val list = runBlocking { svc.listWorktrees(ListWorktrees("c1", dir.toString()), dir) }
        val linked = list.worktrees.single { !it.isMain }
        assertEquals("sess-1", linked.activeSessionId)
        assertEquals("Fix the parser", linked.activeSessionTitle)
        assertNull(list.worktrees.single { it.isMain }.activeSessionId)
    }

    @Test
    fun a_linked_worktree_directory_is_recognised_without_running_git() {
        assumeTrue(gitAvailable())
        val dir = repo()
        runBlocking { service().addWorktree(AddWorktree("c1", dir.toString(), "feat/x", createBranch = true), dir) }
        val wt = tmp.resolve("repo-worktrees").resolve("feat-x")

        assertEquals(dir.toRealPath().toString(), WorktreeMarks.mainWorktreeOf(wt)?.let { Path.of(it).toRealPath().toString() })
        assertNull(WorktreeMarks.mainWorktreeOf(dir))                  // the main worktree has a .git DIRECTORY
        assertNull(WorktreeMarks.mainWorktreeOf(tmp))                  // no .git at all
        // a submodule's gitdir points at .git/modules/…, not .git/worktrees/… — not a worktree
        val fake = tmp.resolve("sub").also { it.createDirectories() }
        fake.resolve(".git").writeText("gitdir: /somewhere/.git/modules/sub\n")
        assertNull(WorktreeMarks.mainWorktreeOf(fake))
    }

    @Test
    fun only_one_mutating_operation_runs_per_conversation_at_a_time() {
        assumeTrue(gitAvailable())
        val dir = repo()
        val svc = service()
        // drive the guard directly: a second act() while the first holds the convo must be refused
        // rather than fanning out git processes.
        val results = runBlocking {
            kotlinx.coroutines.coroutineScope {
                val a = async { svc.act(GitAction("c-busy", dir.toString(), GIT_OP_STAGE, listOf("README.md")), dir) }
                val b = async { svc.act(GitAction("c-busy", dir.toString(), GIT_OP_STAGE, listOf("README.md")), dir) }
                listOf(a.await(), b.await())
            }
        }
        // at most one succeeded; if the second lost the race it says so instead of running
        val refused = results.filterIsInstance<GitActionResult>().filter { !it.ok }
        assertTrue(refused.all { it.error.orEmpty().contains("already running") || it.ok }, refused.map { it.error }.toString())
    }

    // ------------------------------------------------------------- helpers

    /** A repo left mid-merge with exactly one unmerged path, `f.txt`. */
    private fun conflictedRepo(): Path {
        val dir = repo("conflict")
        dir.resolve("f.txt").writeText("base\n")
        sh(dir, "add", "f.txt"); sh(dir, "commit", "-q", "-m", "base")
        sh(dir, "checkout", "-q", "-b", "side")
        dir.resolve("f.txt").writeText("side\n")
        sh(dir, "commit", "-q", "-am", "side")
        sh(dir, "checkout", "-q", "main")
        dir.resolve("f.txt").writeText("main\n")
        sh(dir, "commit", "-q", "-am", "main")
        sh(dir, "merge", "side") // conflicts on purpose
        assertNotNull(Files.readString(dir.resolve("f.txt")))
        return dir
    }
}
