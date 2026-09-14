package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Workspace alias → canonical root, resolved on the target (#367 G0). */
class ExecutionWorkspacesTest {

    private val tmp = createTempDirectory("ccp-exec-ws").toFile().canonicalFile
    private val b = File(tmp, "a/b").apply { mkdirs() }
    private val bc = File(tmp, "a/bc").apply { mkdirs() }
    private val outside = File(tmp, "outside").apply { mkdirs() }

    @AfterTest
    fun cleanup() { tmp.deleteRecursively() }

    private fun grant(vararg ws: Pair<String, File>): ExecutionGrant {
        val pub = "pub"
        return ExecutionGrant(
            grantId = "xg_testtesttest", revision = 1, state = ExecutionGrantState.ACTIVE, createdAt = 1, expiresAt = 2,
            targetAccountId = "acct", targetDaemonPub = pub, targetDaemonFingerprint = ExecutionFingerprint.of(pub),
            sourceLabel = "s",
            workspaces = ws.map { (a, f) ->
                val root = ExecutionWorkspaces.canonicalRoot(f.path)!!
                WorkspaceAlias(a, root, ExecutionWorkspaces.fileKeyOf(root))
            },
            allowedAgents = listOf(AgentKind.CLAUDE), approvalCeiling = PermissionMode.DEFAULT,
            maxConcurrentRuns = 1, maxQueuedRuns = 1, runTimeoutMs = 60_000, perGrantRequestBudget = 1,
        )
    }

    private fun deny(code: String) = ExecutionWorkspaces.Resolution.Deny(code)

    @Test
    fun roots_must_be_absolute_existing_directories_other_than_filesystem_root() {
        assertNull(ExecutionWorkspaces.canonicalRoot("relative/dir"))
        assertNull(ExecutionWorkspaces.canonicalRoot("/"))
        assertNull(ExecutionWorkspaces.canonicalRoot(File(tmp, "missing").path))
        val file = File(tmp, "f.txt").apply { writeText("x") }
        assertNull(ExecutionWorkspaces.canonicalRoot(file.path))
        assertEquals(b.path, ExecutionWorkspaces.canonicalRoot(File(tmp, "a/bc/../b").path), "`..` collapses to the real directory")
    }

    @Test
    fun stored_roots_must_be_lexically_canonical() {
        assertTrue(ExecutionWorkspaces.lexicallyCanonical(b.path))
        for (bad in listOf("/", "", "relative", "${b.path}/", "${b.path}/..", "${b.path}/./x", "${tmp.path}//a", "/a/\u0000b")) {
            assertFalse(ExecutionWorkspaces.lexicallyCanonical(bad), "root=$bad")
        }
    }

    @Test
    fun the_source_can_only_name_an_alias_never_a_path() {
        val g = grant("app" to b)
        for (a in listOf(b.path, "/etc", "../app", "app/..", "~", "App", "a/b", "")) {
            assertEquals(deny("workspace_alias_invalid"), ExecutionWorkspaces.resolve(g, a), "alias=$a")
        }
        assertEquals(deny("workspace_not_allowed"), ExecutionWorkspaces.resolve(g, "docs"))
        assertEquals(ExecutionWorkspaces.Resolution.Ok(b.path), ExecutionWorkspaces.resolve(g, "app"))
    }

    @Test
    fun relative_paths_cannot_escape_by_dotdot_absolute_or_symlink() {
        File(b, "src").mkdirs()
        val g = grant("app" to b)
        assertEquals(ExecutionWorkspaces.Resolution.Ok(File(b, "src").path), ExecutionWorkspaces.resolve(g, "app", "src"))
        for (rel in listOf("..", "../bc", "src/../../bc", "/etc", "\\etc", "~/x", "C:\\x")) {
            assertEquals(deny("workspace_path_escape"), ExecutionWorkspaces.resolve(g, "app", rel), "rel=$rel")
        }
        Files.createSymbolicLink(File(b, "out").toPath(), outside.toPath())
        assertEquals(deny("workspace_path_escape"), ExecutionWorkspaces.resolve(g, "app", "out"))
        Files.createSymbolicLink(File(b, "inside").toPath(), File(b, "src").toPath())
        assertEquals(ExecutionWorkspaces.Resolution.Ok(File(b, "src").path), ExecutionWorkspaces.resolve(g, "app", "inside"))
    }

    @Test
    fun adjacent_prefix_is_not_inside_the_root() {
        val g = grant("app" to b)
        Files.createSymbolicLink(File(b, "sib").toPath(), bc.toPath())
        assertEquals(deny("workspace_path_escape"), ExecutionWorkspaces.resolve(g, "app", "sib"))
        val two = grant("app" to b, "app2" to bc)
        assertEquals(ExecutionWorkspaces.Resolution.Ok(bc.path), ExecutionWorkspaces.resolve(two, "app2"))
    }

    @Test
    fun a_root_replaced_or_removed_after_approval_stops_resolving() {
        val moved = File(tmp, "a/moved")
        val g = grant("app" to b)
        assertEquals(true, b.renameTo(moved))
        assertEquals(deny("workspace_root_changed"), ExecutionWorkspaces.resolve(g, "app"))
        Files.createSymbolicLink(b.toPath(), outside.toPath())
        assertEquals(deny("workspace_root_changed"), ExecutionWorkspaces.resolve(g, "app"))
    }

    @Test
    fun a_new_directory_recreated_at_the_same_path_is_not_the_approved_root() {
        val g = grant("app" to b)
        assertNotNull(g.workspaces.single().fileKey, "this platform exposes a directory identity")
        assertEquals(true, b.renameTo(File(tmp, "a/old")))
        assertTrue(b.mkdirs()) // same canonical path, different directory object
        assertEquals(deny("workspace_root_changed"), ExecutionWorkspaces.resolve(g, "app"))
    }
}
