package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CommandSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashCommandScannerTest {

    private fun tmp(): Path = Files.createTempDirectory("scanner")

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    @Test
    fun builtins_present_even_with_no_command_dirs() {
        val cmds = SlashCommandScanner.scan(workdir = tmp(), home = tmp())
        assertTrue(cmds.any { it.name == "model" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "effort" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "compact" })
        assertTrue(cmds.any { it.name == "clear" })
        assertTrue(cmds.any { it.name == "review" })
        assertTrue(cmds.any { it.name == "security-review" })
        // CLI-embedded skills (no SKILL.md on disk) must come from the builtin list
        assertTrue(cmds.any { it.name == "simplify" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "code-review" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "verify" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "run" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "deep-research" && it.source == CommandSource.BUILTIN })
        assertTrue(cmds.any { it.name == "fewer-permission-prompts" && it.source == CommandSource.BUILTIN })
    }

    @Test
    fun command_file_parses_frontmatter_description_and_argument_hint() {
        val home = tmp()
        write(
            home.resolve(".claude/commands/deploy.md"),
            """
            ---
            description: "Deploy the app"
            argument-hint: <env>
            ---
            body
            """.trimIndent(),
        )
        val cmd = SlashCommandScanner.scan(tmp(), home).single { it.name == "deploy" }
        assertEquals("Deploy the app", cmd.description)
        assertEquals("<env>", cmd.argumentHint)
        assertEquals(CommandSource.USER, cmd.source)
    }

    @Test
    fun command_file_without_frontmatter_falls_back_to_first_body_line() {
        val home = tmp()
        write(home.resolve(".claude/commands/lint.md"), "# Run the linter\n\nDetails…")
        val cmd = SlashCommandScanner.scan(tmp(), home).single { it.name == "lint" }
        assertEquals("Run the linter", cmd.description)
        assertNull(cmd.argumentHint)
    }

    @Test
    fun project_command_shadows_user_command_of_same_name() {
        val home = tmp()
        val work = tmp()
        write(home.resolve(".claude/commands/deploy.md"), "---\ndescription: user one\n---\n")
        write(work.resolve(".claude/commands/deploy.md"), "---\ndescription: project one\n---\n")
        val cmd = SlashCommandScanner.scan(work, home).single { it.name == "deploy" }
        assertEquals("project one", cmd.description)
        assertEquals(CommandSource.PROJECT, cmd.source)
    }

    @Test
    fun skill_dirs_are_listed_but_never_shadow_commands_or_builtins() {
        val home = tmp()
        // use a skill name that is NOT a built-in (review/model are now built-ins) to test the listing + no-shadow rule
        write(home.resolve(".claude/skills/research/SKILL.md"), "---\ndescription: Research stuff\n---\n")
        write(home.resolve(".claude/skills/model/SKILL.md"), "---\ndescription: should not win\n---\n")
        val cmds = SlashCommandScanner.scan(tmp(), home)
        val research = cmds.single { it.name == "research" }
        assertEquals(CommandSource.SKILL, research.source)
        assertEquals("Research stuff", research.description)
        assertEquals(CommandSource.BUILTIN, cmds.single { it.name == "model" }.source)
    }

    @Test
    fun codex_lists_only_agents_skills_and_not_claude_commands_or_skills() {
        val home = tmp()
        val work = tmp()
        write(home.resolve(".claude/commands/claude-command.md"), "---\ndescription: claude command\n---\n")
        write(work.resolve(".claude/skills/claude-skill/SKILL.md"), "---\ndescription: claude skill\n---\n")
        write(home.resolve(".agents/skills/user-codex/SKILL.md"), "---\ndescription: user codex\n---\n")
        write(work.resolve(".agents/skills/project-codex/SKILL.md"), "---\ndescription: project codex\n---\n")

        val cmds = SlashCommandScanner.scan(work, home, agent = AgentKind.CODEX)

        assertTrue(cmds.none { it.name == "claude-command" || it.name == "claude-skill" })
        assertEquals("user codex", cmds.single { it.name == "user-codex" }.description)
        assertEquals("project codex", cmds.single { it.name == "project-codex" }.description)
    }

    @Test
    fun active_agent_skill_layout_is_isolated() {
        val home = tmp()
        write(home.resolve(".claude/skills/claude-only/SKILL.md"), "---\ndescription: claude\n---\n")
        write(home.resolve(".agents/skills/codex-only/SKILL.md"), "---\ndescription: codex\n---\n")

        val claude = SlashCommandScanner.scan(tmp(), home, agent = AgentKind.CLAUDE)
        val codex = SlashCommandScanner.scan(tmp(), home, agent = AgentKind.CODEX)

        assertTrue(claude.any { it.name == "claude-only" })
        assertTrue(claude.none { it.name == "codex-only" })
        assertTrue(codex.any { it.name == "codex-only" })
        assertTrue(codex.none { it.name == "claude-only" })
    }

    @Test
    fun project_skill_shadows_user_skill_for_the_active_agent() {
        val home = tmp()
        val work = tmp()
        write(home.resolve(".agents/skills/shared/SKILL.md"), "---\ndescription: user copy\n---\n")
        write(work.resolve(".agents/skills/Shared/SKILL.md"), "---\ndescription: project copy\n---\n")

        val cmd = SlashCommandScanner.scan(work, home, agent = AgentKind.CODEX)
            .single { it.name.equals("shared", ignoreCase = true) }

        assertEquals("Shared", cmd.name)
        assertEquals("project copy", cmd.description)
    }

    @Test
    fun nested_frontmatter_keys_are_ignored() {
        val home = tmp()
        write(
            home.resolve(".claude/skills/mem/SKILL.md"),
            """
            ---
            name: mem
            description: top level
            metadata:
              description: nested noise
            ---
            """.trimIndent(),
        )
        assertEquals("top level", SlashCommandScanner.scan(tmp(), home).single { it.name == "mem" }.description)
    }
}
