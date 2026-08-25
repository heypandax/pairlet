package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempDirsTest {

    @Test
    fun unix_temp_children_match_and_case_stays_significant() {
        val roots = listOf("/tmp", "/var/folders")
        assertTrue(TempDirs.isUnderSystemTemp("/tmp/modlens-work-abc", roots))
        assertTrue(TempDirs.isUnderSystemTemp("/var/folders/x1/T/one-shot", roots))
        assertTrue(TempDirs.isUnderSystemTemp("/tmp", roots), "the root itself counts")
        // Unix filesystems are case-sensitive: /TMP is a different directory
        assertTrue(!TempDirs.isUnderSystemTemp("/TMP/x", roots))
        // prefix must be a PATH prefix, not a string prefix
        assertTrue(!TempDirs.isUnderSystemTemp("/tmpfiles/x", roots))
        assertTrue(!TempDirs.isUnderSystemTemp("/home/u/project", roots))
    }

    @Test
    fun windows_shaped_paths_fold_case_and_separators() {
        // the reported polluter: %LOCALAPPDATA%\Temp\modlens-work-* (issue #290)
        val roots = listOf("""C:\Users\fa\AppData\Local\Temp""")
        assertTrue(TempDirs.isUnderSystemTemp("""C:\Users\fa\AppData\Local\Temp\modlens-work-eDE9K6""", roots))
        assertTrue(TempDirs.isUnderSystemTemp("""c:\users\fa\appdata\local\temp\x""", roots), "NTFS is case-insensitive")
        assertTrue(TempDirs.isUnderSystemTemp("C:/Users/fa/AppData/Local/Temp/x", roots), "either separator spelling")
        assertTrue(!TempDirs.isUnderSystemTemp("""C:\Users\fa\Projects\app""", roots))
    }

    @Test
    fun the_default_roots_cover_this_machines_java_tmpdir() {
        val tmp = java.nio.file.Files.createTempDirectory("ccp-td")
        try {
            assertTrue(TempDirs.isUnderSystemTemp(tmp.toString()), "a real createTempDirectory child must match")
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
        assertEquals(false, TempDirs.isUnderSystemTemp(System.getProperty("user.home")))
    }
}
