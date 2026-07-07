package dev.ccpocket.daemon.claude

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeHomeTest {

    @Test
    fun seeds_share_links_and_migrates_file_credentials_once() {
        val root = Files.createTempDirectory("ccp-home")
        val real = root.resolve("dot-claude").toFile().apply { mkdirs() }
        val realJson = root.resolve("dot-claude.json").toFile().apply { writeText("""{"mcpServers":{}}""") }
        real.toPath().resolve("CLAUDE.md").writeText("global memory")
        real.toPath().resolve(".credentials.json").writeText("""{"token":"t"}""")
        val home = root.resolve("claude-home").toFile()

        val prepared = assertNotNull(ClaudeHome.prepare(home, real, realJson))

        // dirs exist as links onto the REAL store — one shared history (issue #70 must keep working)
        for (d in listOf("projects", "todos", "agents", "commands", "skills", "plugins")) {
            val link = prepared.resolve(d)
            assertTrue(Files.isSymbolicLink(link), "$d should be a symlink")
            assertEquals(real.toPath().resolve(d), Files.readSymbolicLink(link))
            assertTrue(Files.isDirectory(real.toPath().resolve(d)), "$d target materialized")
        }
        // files link through — reading via the home sees the real content
        assertEquals("global memory", prepared.resolve("CLAUDE.md").readText())
        assertEquals("""{"mcpServers":{}}""", prepared.resolve(".claude.json").readText())
        // credentials are a PRIVATE COPY, not a link — the whole point of isolation
        val creds = prepared.resolve(".credentials.json")
        assertTrue(Files.exists(creds) && !Files.isSymbolicLink(creds))

        // idempotent re-run: same links, and an already-migrated credential file is not overwritten
        creds.writeText("""{"token":"daemon-own"}""")
        assertNotNull(ClaudeHome.prepare(home, real, realJson))
        assertEquals("""{"token":"daemon-own"}""", creds.readText())
    }

    @Test
    fun planted_credentials_symlink_is_dropped_never_written_through() {
        // security review L2: a pre-planted (dangling) link at .credentials.json must not receive the
        // token — the copy would otherwise write THROUGH it to an attacker-chosen path
        val root = Files.createTempDirectory("ccp-home3")
        val real = root.resolve("dot-claude").toFile().apply { mkdirs() }
        real.toPath().resolve(".credentials.json").writeText("""{"token":"t"}""")
        val realJson = root.resolve("dot-claude.json").toFile()
        val home = root.resolve("claude-home").toFile().apply { mkdirs() }
        val exfil = root.resolve("exfil.json")
        Files.createSymbolicLink(home.toPath().resolve(".credentials.json"), exfil)

        val prepared = assertNotNull(ClaudeHome.prepare(home, real, realJson))

        val dst = prepared.resolve(".credentials.json")
        assertTrue(Files.exists(dst) && !Files.isSymbolicLink(dst), "link replaced by a real private copy")
        assertTrue(!Files.exists(exfil), "nothing written through the planted link")
        assertEquals("""{"token":"t"}""", dst.readText())
    }

    @Test
    fun never_replaces_a_real_entry_with_a_link() {
        val root = Files.createTempDirectory("ccp-home2")
        val real = root.resolve("dot-claude").toFile().apply { mkdirs() }
        val realJson = root.resolve("dot-claude.json").toFile()
        val home = root.resolve("claude-home").toFile().apply { mkdirs() }
        // a REAL projects dir already sits in the home (e.g. claude ran here before seeding)
        val stray = home.toPath().resolve("projects")
        Files.createDirectories(stray.resolve("keep-me"))

        assertNotNull(ClaudeHome.prepare(home, real, realJson))

        assertTrue(!Files.isSymbolicLink(stray), "real dir must be left alone")
        assertTrue(Files.exists(stray.resolve("keep-me")))
    }
}
