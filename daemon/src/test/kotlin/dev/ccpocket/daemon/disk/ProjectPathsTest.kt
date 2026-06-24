package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectPathsTest {

    @Test
    fun dirKey_keeps_alnum_and_hyphens() {
        // hyphens in path segments are preserved (cc-pocket stays cc-pocket)
        assertEquals(
            "-Users-lidapeng-Desktop-Project-app-cc-pocket",
            ProjectPaths.dirKey("/Users/lidapeng/Desktop/Project/app/cc-pocket"),
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
}
