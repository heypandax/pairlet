package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectPathsTest {

    @Test
    fun dirKey_keeps_alnum_and_hyphens() {
        // hyphens in path segments are preserved (cc-pocket stays cc-pocket)
        assertEquals(
            "-Users-dev-Desktop-Project-app-cc-pocket",
            ProjectPaths.dirKey("/Users/dev/Desktop/Project/app/cc-pocket"),
        )
    }

    @Test
    fun dirKey_maps_underscore_and_dot_to_hyphen() {
        // regression: cwds with '_' or '.' must match claude's on-disk folder name. Replacing only
        // '/' produced "…ht_binary_ios_makefork2" while claude wrote "…ht-binary-ios-makefork2",
        // so session fetch/open silently returned empty / file-not-found.
        assertEquals(
            "-Users-make-Desktop-Work-Develop2-ht-binary-ios-makefork2",
            ProjectPaths.dirKey("/Users/make/Desktop/Work/Develop2/ht_binary_ios_makefork2"),
        )
        assertEquals("-Users-x-my-app-v2--config", ProjectPaths.dirKey("/Users/x/my.app_v2/.config"))
        assertEquals("-private-tmp-skdbg-IYBb", ProjectPaths.dirKey("/private/tmp/skdbg.IYBb"))
        // runs are not collapsed: a hyphen immediately followed by '_' yields '--'
        assertEquals("-a-r0--2yuiwum-work", ProjectPaths.dirKey("/a/r0-_2yuiwum/work"))
    }

    @Test
    fun dirForUnder_uses_fast_dirkey_path_when_that_dir_exists() {
        val root = Files.createTempDirectory("ccp-proj")
        try {
            val workdir = "/Users/x/proj"
            val expected = root.resolve(ProjectPaths.dirKey(workdir)).also { it.createDirectories() }
            assertEquals(expected, ProjectPaths.dirForUnder(root, workdir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dirForUnder_falls_back_to_recorded_cwd_when_dirkey_misses() {
        // Windows case: claude's actual on-disk folder name need NOT equal dirKey(cwd). The real dir is
        // located by the authoritative `cwd` recorded inside its newest transcript instead — correct on
        // any OS. This is what fixes "Windows daemon: resume/接续 shows blank".
        val root = Files.createTempDirectory("ccp-proj")
        try {
            val workdir = """C:\Users\x\proj"""
            val realDir = root.resolve("claude-encoded-name-that-does-not-match-dirkey").also { it.createDirectories() }
            realDir.resolve("s1.jsonl").writeText(
                """{"type":"user","cwd":"C:\\Users\\x\\proj","message":{"role":"user","content":"hi"}}""" + "\n",
            )
            assertTrue(!root.resolve(ProjectPaths.dirKey(workdir)).exists()) // the lossy dirKey dir is absent
            assertEquals(realDir, ProjectPaths.dirForUnder(root, workdir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dirForUnder_matches_recorded_cwd_across_slash_and_trailing_separator() {
        // issue #19/#22 Windows: the resume path can ask with a different slash direction / trailing separator
        // (and, on Windows, casing) than claude recorded — e.g. after a toRealPath() canonicalization, or for
        // a UNC path. The fallback normalizes both sides before matching so the transcript is still found.
        val root = Files.createTempDirectory("ccp-proj")
        try {
            val realDir = root.resolve("windows-encoded-dir").also { it.createDirectories() }
            realDir.resolve("s.jsonl").writeText(
                """{"type":"user","cwd":"C:\\Users\\x\\proj","message":{"role":"user","content":"hi"}}""" + "\n",
            )
            // asked with forward slashes AND a trailing separator — the divergent form that missed before
            assertEquals(realDir, ProjectPaths.dirForUnder(root, "C:/Users/x/proj/"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dirForUnder_returns_dirkey_path_when_nothing_matches() {
        // brand-new session: no dir exists yet → keep the dirKey path so claude creates/uses it as before
        val root = Files.createTempDirectory("ccp-proj")
        try {
            val workdir = "/Users/x/brand-new"
            assertEquals(root.resolve(ProjectPaths.dirKey(workdir)), ProjectPaths.dirForUnder(root, workdir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── canonicalKey (issue #184): the cross-backend project identity every merge/match keys by ──

    @Test
    fun canonicalKey_merges_trailing_and_doubled_separators_of_an_existing_dir() {
        val dir = Files.createTempDirectory("ccp-canon")
        try {
            val key = ProjectPaths.canonicalKey(dir.toString())
            assertEquals(key, ProjectPaths.canonicalKey("$dir/"))
            assertEquals(key, ProjectPaths.canonicalKey("$dir//"))
            assertEquals(key, ProjectPaths.canonicalKey("${dir.parent}//${dir.fileName}/"))
            // the key is the filesystem's own answer (realpath), not any of the input spellings
            assertEquals(ProjectPaths.normCwd(dir.toRealPath().toString()), key)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalKey_resolves_a_symlink_to_the_same_identity_as_its_target() {
        // the /var ↔ /private/var class of variant: two backends record the same dir through different links
        val parent = Files.createTempDirectory("ccp-canon")
        try {
            val real = parent.resolve("real").also { it.createDirectories() }
            val link = parent.resolve("link")
            Files.createSymbolicLink(link, real)
            assertEquals(ProjectPaths.canonicalKey(real.toString()), ProjectPaths.canonicalKey(link.toString()))
            assertEquals(ProjectPaths.canonicalKey(real.toString()), ProjectPaths.canonicalKey("$link/"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalKey_expands_tilde_to_the_daemon_users_home() {
        // issue #184's exact shape: OpenCode logging home one way, Claude another
        assertEquals(ProjectPaths.canonicalKey(System.getProperty("user.home")), ProjectPaths.canonicalKey("~"))
        assertEquals(
            ProjectPaths.canonicalKey(System.getProperty("user.home") + "/Desktop"),
            ProjectPaths.canonicalKey("~/Desktop"),
        )
    }

    @Test
    fun canonicalKey_falls_back_to_string_normalization_for_missing_paths() {
        // deleted projects still in history: no realpath to ask, so variants must still normalize together…
        assertEquals(ProjectPaths.canonicalKey("/no/such/ccp184/x"), ProjectPaths.canonicalKey("/no/such/ccp184//x/"))
        assertEquals(ProjectPaths.canonicalKey("/no/such/ccp184/x"), ProjectPaths.canonicalKey("/no/such/ccp184/./x"))
        // …while genuinely different dirs stay apart (conservative: no guessing)
        assertNotEquals(ProjectPaths.canonicalKey("/no/such/ccp184/x"), ProjectPaths.canonicalKey("/no/such/ccp184/y"))
    }

    @Test
    fun canonicalKey_missing_path_is_not_cached_stale_across_creation() {
        // memoization must never freeze a pre-creation fallback key: once the dir exists, the key is its realpath
        val parent = Files.createTempDirectory("ccp-canon") // on macOS: /var/… whose realpath is /private/var/…
        try {
            val child = parent.resolve("later")
            ProjectPaths.canonicalKey(child.toString()) // fallback answer — must NOT stick
            child.createDirectories()
            assertEquals(ProjectPaths.normCwd(child.toRealPath().toString()), ProjectPaths.canonicalKey(child.toString()))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun dirForUnder_matches_recorded_cwd_across_symlink_variants() {
        // issue #184: the merged project row is realpath'd before the claude scan; a claude that recorded
        // the SYMLINKED spelling must still be found — else the deduped row lists no claude sessions
        val root = Files.createTempDirectory("ccp-proj")
        val work = Files.createTempDirectory("ccp-work")
        val link = work.parent.resolve("${work.fileName}-lnk").also { Files.createSymbolicLink(it, work) }
        try {
            val realDir = root.resolve("claude-dir").also { it.createDirectories() }
            realDir.resolve("s1.jsonl").writeText("""{"cwd":"$link"}""" + "\n")
            val asked = work.toRealPath().toString()
            assertTrue(!root.resolve(ProjectPaths.dirKey(asked)).exists(), "precondition: the dirKey fast path misses")
            assertEquals(realDir, ProjectPaths.dirForUnder(root, asked))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(link)
            work.toFile().deleteRecursively()
        }
    }
}
