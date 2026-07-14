package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.SkillScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillCatalogServiceTest {

    private fun tmp(): Path = Files.createTempDirectory("catalog")

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    // ── skills ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun empty_dirs_yield_an_empty_catalog() {
        val cat = SkillCatalogService.build(workdir = tmp(), home = tmp())
        assertTrue(cat.skills.isEmpty())
        assertTrue(cat.plugins.isEmpty())
    }

    @Test
    fun skill_carries_full_frontmatter_and_body_excerpt() {
        val home = tmp()
        write(
            home.resolve(".claude/skills/brain/SKILL.md"),
            """
            ---
            name: brain
            description: "Persist conclusions to the vault"
            argument-hint: <topic>
            license: MIT
            metadata:
              description: nested noise
            ---

            # Brain

            First body paragraph.
            """.trimIndent(),
        )
        val s = SkillCatalogService.build(workdir = null, home = home).skills.single()
        assertEquals("brain", s.name)
        assertEquals("Persist conclusions to the vault", s.description)
        assertEquals(SkillScope.USER, s.scope)
        // description surfaced separately; every other top-level scalar kept, nested keys skipped
        assertEquals(mapOf("name" to "brain", "argument-hint" to "<topic>", "license" to "MIT"), s.meta)
        assertTrue(s.excerpt.startsWith("# Brain"))
        assertTrue("First body paragraph." in s.excerpt)
        assertFalse("nested noise" in s.excerpt)
        assertFalse(s.truncated)
        assertEquals(home.resolve(".claude/skills/brain").toAbsolutePath().toString(), s.path)
    }

    @Test
    fun project_skills_are_scoped_and_listed_alongside_user_skills() {
        val home = tmp()
        val work = tmp()
        write(home.resolve(".claude/skills/alpha/SKILL.md"), "---\ndescription: user skill\n---\nbody")
        write(work.resolve(".claude/skills/beta/SKILL.md"), "---\ndescription: project skill\n---\nbody")
        val skills = SkillCatalogService.build(workdir = work, home = home).skills
        assertEquals(listOf("alpha", "beta"), skills.map { it.name }) // user block first, then project
        assertEquals(SkillScope.USER, skills[0].scope)
        assertEquals(SkillScope.PROJECT, skills[1].scope)
    }

    @Test
    fun long_body_is_truncated_and_flagged() {
        val home = tmp()
        val body = "x".repeat(10_000)
        write(home.resolve(".claude/skills/big/SKILL.md"), "---\ndescription: d\n---\n$body")
        val s = SkillCatalogService.build(workdir = null, home = home).skills.single()
        assertEquals(4_096, s.excerpt.length)
        assertTrue(s.truncated)
    }

    @Test
    fun skill_without_frontmatter_keeps_the_whole_body_as_excerpt() {
        val home = tmp()
        write(home.resolve(".claude/skills/raw/SKILL.md"), "# Raw skill\n\ncontent")
        val s = SkillCatalogService.build(workdir = null, home = home).skills.single()
        assertEquals("", s.description)
        assertTrue(s.meta.isEmpty())
        assertTrue(s.excerpt.startsWith("# Raw skill"))
    }

    @Test
    fun dirs_without_skill_md_are_ignored() {
        val home = tmp()
        Files.createDirectories(home.resolve(".claude/skills/not-a-skill"))
        write(home.resolve(".claude/skills/real/SKILL.md"), "---\ndescription: d\n---\n")
        assertEquals(listOf("real"), SkillCatalogService.build(null, home).skills.map { it.name })
    }

    // ── plugins ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun ledger_plugin_merges_install_record_with_manifest_and_readme() {
        val home = tmp()
        val install = home.resolve(".claude/plugins/cache/official/hud/0.0.10")
        write(
            install.resolve(".claude-plugin/plugin.json"),
            """
            {
              "name": "claude-hud",
              "description": "Real-time statusline HUD",
              "version": "0.0.10",
              "author": {"name": "Jarrod"},
              "homepage": "https://example.com",
              "commands": ["./commands/setup.md", "./commands/configure.md"]
            }
            """.trimIndent(),
        )
        write(install.resolve("README.md"), "# claude-hud\n\nHUD readme.")
        write(
            home.resolve(".claude/plugins/installed_plugins.json"),
            """
            {"version": 2, "plugins": {"hud@official": [
              {"scope": "user", "installPath": "$install", "version": "0.0.10"}
            ]}}
            """.trimIndent(),
        )
        val p = SkillCatalogService.build(workdir = null, home = home).plugins.single()
        assertEquals("claude-hud", p.name) // manifest name wins over the ledger key
        assertEquals("Real-time statusline HUD", p.description)
        assertEquals("0.0.10", p.version)
        assertEquals("official", p.marketplace)
        assertEquals("user", p.scope)
        assertEquals("Jarrod", p.author)
        assertEquals("https://example.com", p.homepage)
        assertEquals(listOf("setup", "configure"), p.commands)
        assertTrue(p.excerpt.startsWith("# claude-hud"))
        assertFalse(p.truncated)
    }

    @Test
    fun ledger_entry_with_missing_manifest_degrades_to_the_key_name() {
        val home = tmp()
        write(
            home.resolve(".claude/plugins/installed_plugins.json"),
            """{"version": 2, "plugins": {"ghost@somewhere": [{"scope": "user", "installPath": "/nonexistent/path", "version": "1.0.0"}]}}""",
        )
        val p = SkillCatalogService.build(workdir = null, home = home).plugins.single()
        assertEquals("ghost", p.name)
        assertEquals("", p.description)
        assertEquals("1.0.0", p.version) // ledger version survives without a manifest
        assertEquals("somewhere", p.marketplace)
    }

    @Test
    fun garbled_ledger_falls_back_to_scanning_the_cache_layout() {
        val home = tmp()
        write(home.resolve(".claude/plugins/installed_plugins.json"), "{ not json !!")
        // versioned layout: cache/<marketplace>/<plugin>/<version>/.claude-plugin/plugin.json
        write(
            home.resolve(".claude/plugins/cache/mkt/tool/2.0.0/.claude-plugin/plugin.json"),
            """{"name": "tool", "description": "from cache", "version": "2.0.0"}""",
        )
        val p = SkillCatalogService.build(workdir = null, home = home).plugins.single()
        assertEquals("tool", p.name)
        assertEquals("from cache", p.description)
        assertEquals("mkt", p.marketplace)
        assertNull(p.scope) // no ledger record to say
    }

    @Test
    fun cache_scan_prefers_the_newest_version_dir() {
        val home = tmp()
        write(home.resolve(".claude/plugins/cache/mkt/tool/1.0.0/.claude-plugin/plugin.json"), """{"version": "1.0.0"}""")
        write(home.resolve(".claude/plugins/cache/mkt/tool/1.0.9/.claude-plugin/plugin.json"), """{"version": "1.0.9"}""")
        val p = SkillCatalogService.build(workdir = null, home = home).plugins.single()
        assertEquals("1.0.9", p.version)
    }

    @Test
    fun no_plugins_dir_at_all_is_fine() {
        assertTrue(SkillCatalogService.build(workdir = null, home = tmp()).plugins.isEmpty())
    }
}
