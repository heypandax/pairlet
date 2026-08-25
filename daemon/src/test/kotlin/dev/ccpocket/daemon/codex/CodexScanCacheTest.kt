package dev.ccpocket.daemon.codex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * PR #296 review, performance: the same rollout was re-parsed 3-4 times per session open and once per
 * ~1.5s observe tick, and the whole `~/.codex/sessions` tree was re-walked on every 10-second project
 * refresh (plus once per resume accessor). Everything here is memoized now, keyed by the mtime of the file
 * or directory it came from — never by a wall clock.
 *
 * These tests assert the memo through the only thing it changes from outside: content that moved WITHOUT
 * its mtime moving stays invisible, and content whose mtime moved is picked up. That is the honest
 * observable proof a memo exists (and, read the other way, exactly the staleness contract it promises).
 */
class CodexScanCacheTest {
    private val tmp = Files.createTempDirectory("ccp-codex-cache")
    private val stamp = FileTime.fromMillis(1_700_000_000_000)

    @BeforeTest
    fun reset() {
        CodexTranscriptScanner.clearForTest()
        CodexPaths.clearForTest()
    }

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
        CodexTranscriptScanner.clearForTest()
        CodexPaths.clearForTest()
    }

    private fun rollout(prompt: String, model: String) = """
        {"timestamp":"t0","type":"session_meta","payload":{"id":"thr-memo","cwd":"/repo","cli_version":"0.124.0"}}
        {"timestamp":"t1","type":"turn_context","payload":{"model":"$model"}}
        {"timestamp":"t2","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$prompt"}]}}
    """.trimIndent()

    /** Rewrite [file] and pin its mtime, so "changed content" and "changed mtime" are independent knobs. */
    private fun write(file: Path, text: String, mtime: FileTime) {
        file.writeText(text)
        Files.setLastModifiedTime(file, mtime)
    }

    @Test
    fun summarize_reparses_only_when_the_rollout_mtime_moves() {
        val f = tmp.resolve("rollout-2026-08-25T00-00-00-thr-memo.jsonl")
        write(f, rollout("first prompt", "gpt-5.6-sol"), stamp)
        assertEquals("first prompt", CodexTranscriptScanner.summarize(f, "/repo", emptyMap())?.firstPrompt)

        // same mtime, different bytes → the memo answers, deliberately stale
        write(f, rollout("second prompt", "gpt-5.6-sol"), stamp)
        assertEquals(
            "first prompt",
            CodexTranscriptScanner.summarize(f, "/repo", emptyMap())?.firstPrompt,
            "an unchanged mtime must not cost a re-parse",
        )

        // mtime moves (what a real append always does) → fresh parse
        write(f, rollout("second prompt", "gpt-5.6-sol"), FileTime.fromMillis(stamp.toMillis() + 1_000))
        assertEquals("second prompt", CodexTranscriptScanner.summarize(f, "/repo", emptyMap())?.firstPrompt)
    }

    @Test
    fun a_cached_parse_is_still_filtered_by_the_callers_workdir_and_retitled() {
        // the memo is keyed by FILE, so everything that depends on the caller — the cwd filter and the
        // shared title map — must still be applied per call, not baked into the cached value
        val f = tmp.resolve("rollout-2026-08-25T00-00-01-thr-memo.jsonl")
        write(f, rollout("build the thing", "gpt-5.6-sol"), stamp)

        assertEquals("build the thing", CodexTranscriptScanner.summarize(f, "/repo", emptyMap())?.title)
        assertNull(CodexTranscriptScanner.summarize(f, "/somewhere/else", emptyMap()), "wrong cwd, cached or not")
        assertEquals(
            "Named by Codex",
            CodexTranscriptScanner.summarize(f, "/repo", mapOf("thr-memo" to "Named by Codex"))?.title,
            "the index title map is a per-call input, never cached with the parse",
        )
    }

    @Test
    fun runtimeState_reparses_only_when_the_rollout_mtime_moves() {
        val f = tmp.resolve("rollout-2026-08-25T00-00-02-thr-memo.jsonl")
        write(f, rollout("p", "gpt-5.6-sol"), stamp)
        val first = CodexTranscriptScanner.runtimeState(f)
        assertEquals("gpt-5.6-sol", first.model)

        write(f, rollout("p", "gpt-5.6-mini"), stamp)
        assertSame(first, CodexTranscriptScanner.runtimeState(f), "same mtime → the memoized instance itself")

        write(f, rollout("p", "gpt-5.6-mini"), FileTime.fromMillis(stamp.toMillis() + 1_000))
        assertEquals("gpt-5.6-mini", CodexTranscriptScanner.runtimeState(f).model)
    }

    // ---- CodexPaths: directory listings + id index -------------------------------------------------

    /** `sessions/YYYY/MM/DD/rollout-<ts>-<id>.jsonl`, the real on-disk layout. */
    private fun tree(root: Path, id: String): Path {
        val leaf = root.resolve("2026/08/25")
        Files.createDirectories(leaf)
        return leaf.resolve("rollout-2026-08-25T00-00-00-$id.jsonl")
            .also { it.writeText("""{"timestamp":"t0","type":"session_meta","payload":{"id":"$id","cwd":"/repo"}}""") }
    }

    @Test
    fun a_settled_directory_is_listed_once_and_relisted_when_its_mtime_moves() {
        val root = Files.createDirectory(tmp.resolve("settled-root"))
        tree(root, "thr-1")
        val leaf = root.resolve("2026/08/25")
        val old = FileTime.fromMillis(System.currentTimeMillis() - 3_600_000)
        Files.setLastModifiedTime(leaf, old)

        assertEquals(1, CodexPaths.sessionFiles(root = root).size)

        // a second rollout appears, but the directory's mtime is pinned back → the cached listing stands
        tree(root, "thr-2")
        Files.setLastModifiedTime(leaf, old)
        assertEquals(1, CodexPaths.sessionFiles(root = root).size, "an unchanged dir mtime must not cost a readdir")

        // the real thing a new rollout does: it bumps the directory's mtime
        Files.setLastModifiedTime(leaf, FileTime.fromMillis(old.toMillis() + 1_000))
        assertEquals(2, CodexPaths.sessionFiles(root = root).size)
    }

    @Test
    fun a_just_touched_directory_is_never_trusted_from_cache() {
        // Guard against coarse-granularity filesystems: if a file lands in the same tick as our listing, a
        // cached listing would hide a brand-new session FOREVER (nothing would ever invalidate it). Today's
        // leaf directory is the only one young enough to pay this readdir.
        val root = Files.createDirectory(tmp.resolve("fresh-root"))
        tree(root, "thr-1")
        val leaf = root.resolve("2026/08/25")
        val justNow = Files.getLastModifiedTime(leaf)

        assertEquals(1, CodexPaths.sessionFiles(root = root).size)
        tree(root, "thr-2")
        Files.setLastModifiedTime(leaf, justNow) // same stamp as the listing we just took
        assertEquals(2, CodexPaths.sessionFiles(root = root).size, "a directory this young is re-listed")
    }

    @Test
    fun findSession_answers_from_its_index_and_self_heals_when_the_file_is_gone() {
        val root = Files.createDirectory(tmp.resolve("index-root"))
        val file = tree(root, "thr-idx")

        assertEquals(file, CodexPaths.findSession("thr-idx", root))

        // The proof the second lookup never walks: hand it a root that does not contain the file at all.
        // (Production calls this 4x per session open — that walk is what the index removes.)
        val empty = Files.createDirectory(tmp.resolve("empty-root"))
        assertEquals(file, CodexPaths.findSession("thr-idx", empty), "an indexed hit skips the tree entirely")

        // …and the index is validated by existence, so a deleted rollout falls back to the walk and misses
        Files.delete(file)
        assertNull(CodexPaths.findSession("thr-idx", root))
    }
}
