package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire contract for the Git panel (#280) and Worktree management (#281) frames.
 *
 * What these pin down, in order of how badly they would hurt if they drifted:
 *  1. `op` stays a TOLERANT String. If someone "cleans it up" into an enum, `coerceInputValues` would
 *     rewrite an unknown verb into the enum's default — a mutating allow-list whose unknown value RUNS
 *     something. The test decodes a made-up verb and asserts it survives verbatim so the daemon can
 *     reject it by name.
 *  2. [GitActionResult.statusAfter] nests a [GitStatus] as a CONCRETE type, i.e. with no polymorphic "t"
 *     discriminator inside. That is what lets one frame carry a refreshed panel.
 *  3. Every new field is a trailing optional: an old peer's JSON (missing them) still decodes.
 */
class GitWireCompatTest {

    /** The shape an app/daemon that predates the worktree work would emit for a directory row. */
    @Serializable
    private data class OldDirectoryEntry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val hasSessions: Boolean = false,
    )

    @Test
    fun git_status_request_and_reply_roundtrip_with_wire_safe_defaults() {
        val req = Envelope(id = "g1", ts = 0, body = FetchGitStatus("c1", "/repo"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/git.status\"" in reqJson, reqJson)
        assertTrue("\"withBranches\":false" in reqJson, reqJson) // encodeDefaults
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val resp = Envelope(
            id = "g2", ts = 0,
            body = GitStatus(
                "c1", "/repo",
                branch = "feat/auth-refactor", upstream = "origin/feat/auth-refactor", ahead = 2, behind = 1,
                staged = listOf(GitFileEntry("src/a.kt", "M", 12, 3)),
                unstaged = listOf(GitFileEntry("src/b.kt", "M", 6, 2)),
                untracked = listOf(GitFileEntry("docs/c.md", "?")),
            ),
        )
        val respJson = PocketJson.encodeToString(resp)
        assertTrue("\"t\":\"pocket/git.statusResult\"" in respJson, respJson)
        assertEquals(resp, PocketJson.decodeFromString<Envelope>(respJson))
    }

    @Test
    fun git_status_omitting_every_optional_decodes_to_the_empty_clean_reading() {
        val s = PocketJson.decodeFromString<GitStatus>("""{"convoId":"c1","workdir":"/repo"}""")
        assertTrue(s.ok)
        assertFalse(s.notARepo)
        assertFalse(s.detached)
        assertFalse(s.initial)
        assertEquals(0, s.ahead)
        assertEquals(0, s.behind)
        assertEquals(emptyList(), s.staged)
        assertEquals(emptyList(), s.conflicted)
        assertEquals(0, s.worktreeCount)
        assertNull(s.branch)
    }

    @Test
    fun git_action_op_is_a_tolerant_string_so_an_unknown_verb_never_becomes_a_default_one() {
        // If `op` were an enum, coerceInputValues would silently rewrite this to the first/default verb
        // and the daemon would EXECUTE something. As a String it arrives verbatim and gets rejected by name.
        val a = PocketJson.decodeFromString<GitAction>(
            """{"convoId":"c1","workdir":"/repo","op":"reset --hard"}""",
        )
        assertEquals("reset --hard", a.op)
        assertFalse(a.op in GIT_OPS)
        assertEquals(emptyList(), a.paths)
        assertNull(a.confirmToken)
    }

    @Test
    fun git_action_roundtrips_and_the_two_step_token_rides_the_same_frame() {
        val first = Envelope(id = "g3", ts = 0, body = GitAction("c1", "/repo", GIT_OP_REVERT, paths = listOf("src/a.kt")))
        val firstJson = PocketJson.encodeToString(first)
        assertTrue("\"t\":\"pocket/git.action\"" in firstJson, firstJson)
        assertTrue("\"op\":\"revert\"" in firstJson, firstJson)
        assertEquals(first, PocketJson.decodeFromString<Envelope>(firstJson))

        val confirm = Envelope(
            id = "g4", ts = 0,
            body = GitAction("c1", "/repo", GIT_OP_REVERT, paths = listOf("src/a.kt"), confirmToken = "tok-1"),
        )
        assertEquals(confirm, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(confirm)))
    }

    @Test
    fun git_action_result_nests_a_status_snapshot_without_a_discriminator() {
        val res = GitActionResult(
            "c1", GIT_OP_STAGE, ok = true,
            statusAfter = GitStatus("c1", "/repo", branch = "main"),
        )
        val json = PocketJson.encodeToString(Envelope(id = "g5", ts = 0, body = res))
        // exactly ONE "t" key — the envelope body's. The nested snapshot is a concrete type.
        assertEquals(1, Regex("\"t\":").findAll(json).count(), json)
        assertTrue("\"statusAfter\":" in json, json)
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(Envelope(id = "g5", ts = 0, body = res), back)
        assertEquals("main", (back.body as GitActionResult).statusAfter?.branch)
    }

    @Test
    fun git_action_result_path_is_a_trailing_optional_both_directions() {
        // new daemon → new app: the worktree.add receipt carries the checkout it created
        val with = GitActionResult("c1", GIT_OP_WORKTREE_ADD, ok = true, path = "/repo-worktrees/feat-x")
        val json = PocketJson.encodeToString(Envelope(id = "g8", ts = 0, body = with))
        assertTrue("\"path\":\"/repo-worktrees/feat-x\"" in json, json)
        assertEquals(with, (PocketJson.decodeFromString<Envelope>(json).body as GitActionResult))

        // old daemon's frame (no path) → null: the app shows the receipt without the open-here verb
        val old = """{"id":"g9","ts":0,"body":{"t":"pocket/git.result","convoId":"c1","op":"worktree.add","ok":true}}"""
        assertEquals(null, (PocketJson.decodeFromString<Envelope>(old).body as GitActionResult).path)

        // explicitNulls=false: an unset path adds no bytes for old phones to trip on
        val plain = PocketJson.encodeToString(Envelope(id = "g10", ts = 0, body = GitActionResult("c1", GIT_OP_STAGE, ok = true)))
        assertTrue("path" !in plain, plain)
    }

    @Test
    fun git_preview_and_diff_roundtrip() {
        val prev = Envelope(
            id = "g6", ts = 0,
            body = GitActionPreview(
                "c1", GIT_OP_CHECKOUT, confirmToken = "tok-2", expiresAtMs = 1_700_000_000_000,
                files = listOf(GitFileEntry("src/a.kt", "M", 6, 2)), summary = "dirty-checkout", branch = "main",
            ),
        )
        val prevJson = PocketJson.encodeToString(prev)
        assertTrue("\"t\":\"pocket/git.preview\"" in prevJson, prevJson)
        assertEquals(prev, PocketJson.decodeFromString<Envelope>(prevJson))

        val diff = Envelope(id = "g7", ts = 0, body = GitDiff("c1", "/repo", "src/a.kt", staged = true, diff = "@@ -1 +1 @@\n-a\n+b\n", adds = 1, dels = 1))
        val diffJson = PocketJson.encodeToString(diff)
        assertTrue("\"t\":\"pocket/git.diffResult\"" in diffJson, diffJson)
        assertEquals(diff, PocketJson.decodeFromString<Envelope>(diffJson))
    }

    @Test
    fun worktree_frames_roundtrip_and_dirty_stays_three_valued() {
        val list = Envelope(id = "w1", ts = 0, body = ListWorktrees("c1", "/repo"))
        assertTrue("\"t\":\"pocket/worktree.list\"" in PocketJson.encodeToString(list))
        assertEquals(list, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(list)))

        val res = Envelope(
            id = "w2", ts = 0,
            body = WorktreeList(
                "c1", "/repo", repoRoot = "/repo",
                worktrees = listOf(
                    WorktreeEntry("/repo", branch = "main", head = "abc", isMain = true, dirty = false, dirtyCount = 0),
                    WorktreeEntry("/repo-worktrees/feat-x", branch = "feat/x", head = "def", dirty = null, activeSessionId = "s1"),
                ),
            ),
        )
        val json = PocketJson.encodeToString(res)
        assertTrue("\"t\":\"pocket/worktree.listResult\"" in json, json)
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(res, back)
        // explicitNulls = false → an unknown dirty is an ABSENT key, and absent decodes back to null,
        // which is what keeps "we have not looked" distinct from "clean".
        assertFalse("\"dirty\":null" in json, json)
        assertNull((back.body as WorktreeList).worktrees[1].dirty)

        val add = Envelope(id = "w3", ts = 0, body = AddWorktree("c1", "/repo", "feat/x", createBranch = true))
        assertTrue("\"t\":\"pocket/worktree.add\"" in PocketJson.encodeToString(add))
        assertEquals(add, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(add)))

        val rm = Envelope(id = "w4", ts = 0, body = RemoveWorktree("c1", "/repo", "/repo-worktrees/feat-x", confirmToken = "tok"))
        assertTrue("\"t\":\"pocket/worktree.remove\"" in PocketJson.encodeToString(rm))
        assertEquals(rm, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(rm)))
    }

    @Test
    fun directoryEntry_worktreeOf_is_a_trailing_optional_both_directions() {
        // an OLD daemon's row (no worktreeOf) decodes with the field null — plain local directory
        val legacy = PocketJson.decodeFromString<DirectoryEntry>(
            """{"path":"/p","name":"p","isDir":true,"hasSessions":true}""",
        )
        assertNull(legacy.worktreeOf)

        // an OLD app decodes a NEW daemon's row by ignoring the unknown key (ignoreUnknownKeys)
        val fresh = DirectoryEntry("/p-worktrees/feat-x", "feat-x", isDir = true, worktreeOf = "/p")
        val old = PocketJson.decodeFromString<OldDirectoryEntry>(PocketJson.encodeToString(fresh))
        assertEquals(OldDirectoryEntry("/p-worktrees/feat-x", "feat-x", true), old)
    }

    @Test
    fun an_unknown_git_frame_type_fails_to_decode_which_is_what_the_drop_path_relies_on() {
        // Every claim that "an old peer silently drops these" rests on ONE implementation fact: each
        // inbound decode site wraps decodeFromString in runCatching. This pins the other half of that
        // contract — decoding an unknown "t" really does throw. If someone ever "tidies" a decode site
        // into a bare call, compatibility flips from graceful to fatal, and this is where it shows.
        val future = """{"id":"17","ts":0,"to":"PEER","body":{"t":"pocket/git.frobnicate","convoId":"c1"}}"""
        assertTrue(runCatching { PocketJson.decodeFromString<Envelope>(future) }.isFailure)
    }

    @Test
    fun an_unknown_structured_field_is_skipped_without_losing_the_fields_after_it() {
        // The git family is unusually dense in object arrays (staged/unstaged/untracked/conflicted/
        // branches/worktrees), so the skip path an older app takes over a FUTURE structured field is
        // worth proving rather than assuming: an unknown array of objects sitting in the middle must not
        // swallow what follows it.
        val json = """
            {"t":"pocket/git.statusResult","convoId":"c1","workdir":"/repo",
             "staged":[{"path":"a.kt","code":"M","adds":1,"dels":0}],
             "submodules":[{"path":"vendor/x","sha":"deadbeef","nested":{"deep":[1,2,3]}}],
             "branch":"main","ahead":3,"worktreeCount":2}
        """.trimIndent()
        val s = PocketJson.decodeFromString<GitStatus>(json)
        assertEquals(listOf("a.kt"), s.staged.map { it.path })
        assertEquals("main", s.branch)   // read AFTER the unknown array
        assertEquals(3, s.ahead)
        assertEquals(2, s.worktreeCount)
    }

    @Test
    fun a_hand_written_older_result_decodes_its_nested_snapshot_with_and_without_a_discriminator() {
        // Round-tripping proves only that we agree with ourselves. This is the shape an older daemon
        // actually puts on the wire: a nested GitStatus written by the CONCRETE serializer, so no "t"
        // inside, and most keys absent.
        val legacy = """
            {"id":"g9","ts":0,"to":"PEER","body":{"t":"pocket/git.result",
             "convoId":"c1","op":"stage","ok":true,
             "statusAfter":{"convoId":"c1","workdir":"/repo"}}}
        """.trimIndent()
        val r = PocketJson.decodeFromString<Envelope>(legacy).body as GitActionResult
        assertEquals("/repo", r.statusAfter?.workdir)
        assertTrue(r.statusAfter!!.ok)              // defaults fill in
        assertEquals(0, r.statusAfter!!.worktreeCount)
        assertNull(r.workdir)                        // the trailing optional an older daemon omits

        // …and the other direction: a discriminator that somehow IS present is skipped rather than
        // fatal, so a future move to a polymorphic declaration could not break decoding older payloads.
        val withT = legacy.replace(
            """"statusAfter":{"convoId"""",
            """"statusAfter":{"t":"pocket/git.statusResult","convoId"""",
        )
        assertEquals("/repo", (PocketJson.decodeFromString<Envelope>(withT).body as GitActionResult).statusAfter?.workdir)
    }

    @Test
    fun the_reply_workdir_is_a_trailing_optional_on_both_two_step_frames() {
        val p = PocketJson.decodeFromString<GitActionPreview>("""{"convoId":"c1","op":"revert","confirmToken":"t"}""")
        assertNull(p.workdir)
        val r = PocketJson.decodeFromString<GitActionResult>("""{"convoId":"c1","op":"revert"}""")
        assertNull(r.workdir)
        val fresh = GitActionPreview("c1", GIT_OP_REVERT, "tok", workdir = "/repo")
        assertEquals(fresh, PocketJson.decodeFromString<GitActionPreview>(PocketJson.encodeToString(fresh)))
    }

    @Test
    fun the_allow_list_is_exactly_nine_git_verbs_plus_two_worktree_ones() {
        // A guard against quiet growth: adding a verb here is a deliberate act that must also update
        // the daemon's dispatch, the design board's contract table, and this number.
        assertEquals(11, GIT_OPS.size, GIT_OPS.toString())
        assertTrue(setOf("stage", "unstage", "commit", "fetch", "pull", "push", "checkout", "branch", "revert") - GIT_OPS == emptySet<String>())
        assertEquals(setOf(GIT_OP_REVERT, GIT_OP_CHECKOUT, GIT_OP_WORKTREE_REMOVE), GIT_TWO_STEP_OPS)
        // things that must NEVER be reachable
        for (forbidden in listOf("amend", "stash", "merge", "rebase", "reset", "clean", "push --force", "branch.delete")) {
            assertFalse(forbidden in GIT_OPS, forbidden)
        }
    }
}
