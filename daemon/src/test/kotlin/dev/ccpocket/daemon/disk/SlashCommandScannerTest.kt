package dev.ccpocket.daemon.disk

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
        assertTrue(cmds.any { it.name == "compact" })
        assertTrue(cmds.any { it.name == "clear" })
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
        write(home.resolve(".claude/skills/review/SKILL.md"), "---\ndescription: Review code\n---\n")
        write(home.resolve(".claude/skills/model/SKILL.md"), "---\ndescription: should not win\n---\n")
        val cmds = SlashCommandScanner.scan(tmp(), home)
        val review = cmds.single { it.name == "review" }
        assertEquals(CommandSource.SKILL, review.source)
        assertEquals("Review code", review.description)
        assertEquals(CommandSource.BUILTIN, cmds.single { it.name == "model" }.source)
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
