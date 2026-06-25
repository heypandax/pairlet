package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryServiceTest {

    private val svc = DirectoryService()

    @Test
    fun open_existing_directory_passes_through() {
        val dir = Files.createTempDirectory("ccp-ds")
        try {
            // an already-existing readable dir resolves to its real path; nothing is created
            assertEquals(dir.toRealPath(), svc.validateOrCreateWorkdir(dir.toString()))
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun open_new_name_under_existing_parent_creates_one_leaf() {
        val parent = Files.createTempDirectory("ccp-ds")
        val target = parent.resolve("brand-new-project")
        try {
            assertTrue(!target.exists(), "precondition: leaf does not exist yet")
            val out = svc.validateOrCreateWorkdir(target.toString())
            assertEquals(target.toRealPath(), out, "returns the created dir's real path")
            assertTrue(target.isDirectory(), "leaf was created as a directory")
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun open_new_name_under_missing_parent_is_rejected() {
        val parent = Files.createTempDirectory("ccp-ds")
        Files.delete(parent) // parent gone → must NOT mkdir -p a deep tree
        val target = parent.resolve("proj")
        assertNull(svc.validateOrCreateWorkdir(target.toString()))
        assertTrue(!parent.exists(), "no parent tree was materialised")
    }

    @Test
    fun open_path_that_is_an_existing_file_is_rejected() {
        val parent = Files.createTempDirectory("ccp-ds")
        val file = parent.resolve("a-file").also { it.writeText("x") }
        try {
            assertNull(svc.validateOrCreateWorkdir(file.toString())) // exists but isn't a directory
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(parent)
        }
    }
}
