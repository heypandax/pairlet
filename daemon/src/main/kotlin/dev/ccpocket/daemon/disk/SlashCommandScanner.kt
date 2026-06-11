package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.CommandSource
import dev.ccpocket.protocol.SlashCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Discovers the slash commands a conversation can offer in the phone's composer:
 *
 *  - daemon/claude built-ins that survive `-p --input-format stream-json`
 *  - custom command files: `~/.claude/commands/<name>.md` (user) and `<workdir>/.claude/commands/<name>.md` (project)
 *  - skills: `~/.claude/skills/<name>/SKILL.md` and `<workdir>/.claude/skills/<name>/SKILL.md`
 *
 * Custom commands and skills are *expanded by claude itself* when their text lands on stdin;
 * the daemon only needs the names + descriptions for autocomplete. `/model` is the exception —
 * it is intercepted and handled by [dev.ccpocket.daemon.conversation.Conversation].
 */
object SlashCommandScanner {

    // claude `-p` ignores most interactive commands (/cost, /status, …); only list what works there.
    private val builtins = listOf(
        SlashCommand("model", "Switch the model for this session", "<name>"),
        SlashCommand("compact", "Compact the conversation to free up context", "[instructions]"),
        SlashCommand("clear", "Clear the conversation history"),
    )

    fun scan(workdir: Path, home: Path = Path.of(System.getProperty("user.home"))): List<SlashCommand> {
        val byName = LinkedHashMap<String, SlashCommand>()
        builtins.forEach { byName[it.name] = it }
        // project commands shadow user commands of the same name (claude's own precedence)
        commandFiles(home.resolve(".claude/commands"), CommandSource.USER).forEach { byName[it.name] = it }
        commandFiles(workdir.resolve(".claude/commands"), CommandSource.PROJECT).forEach { byName[it.name] = it }
        // skills never shadow an explicit command file
        (skills(home.resolve(".claude/skills")) + skills(workdir.resolve(".claude/skills")))
            .forEach { byName.putIfAbsent(it.name, it) }
        return byName.values.sortedWith(compareBy({ it.source != CommandSource.BUILTIN }, { it.name }))
    }

    private fun commandFiles(root: Path, source: CommandSource): List<SlashCommand> {
        if (!root.isDirectory()) return emptyList()
        return runCatching {
            Files.walk(root, 3).use { stream ->
                stream.filter { it.isRegularFile() && it.extension == "md" }
                    .map { file ->
                        val fm = frontmatter(file)
                        SlashCommand(
                            name = file.nameWithoutExtension,
                            description = fm.description ?: firstBodyLine(file),
                            argumentHint = fm.argumentHint,
                            source = source,
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun skills(root: Path): List<SlashCommand> {
        if (!root.isDirectory()) return emptyList()
        return runCatching {
            Files.list(root).use { stream ->
                stream.filter { it.isDirectory() && it.resolve("SKILL.md").isRegularFile() }
                    .map { dir ->
                        val fm = frontmatter(dir.resolve("SKILL.md"))
                        SlashCommand(
                            name = dir.fileName.toString(),
                            description = fm.description ?: "",
                            argumentHint = fm.argumentHint,
                            source = CommandSource.SKILL,
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private data class Frontmatter(val description: String?, val argumentHint: String?)

    /** Minimal YAML frontmatter read: top-level `description:` and `argument-hint:` scalars only. */
    private fun frontmatter(file: Path): Frontmatter {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return Frontmatter(null, null)
        if (lines.firstOrNull()?.trim() != "---") return Frontmatter(null, null)
        var description: String? = null
        var argumentHint: String? = null
        for (line in lines.drop(1)) {
            if (line.trim() == "---") break
            if (line.firstOrNull()?.isWhitespace() == true) continue // nested keys (metadata: …)
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim().removeSurrounding("\"").removeSurrounding("'")
            when (key) {
                "description" -> description = value.take(MAX_DESC).ifBlank { null }
                "argument-hint" -> argumentHint = value.ifBlank { null }
            }
        }
        return Frontmatter(description, argumentHint)
    }

    /** Fallback description: the first non-empty line after the frontmatter block. */
    private fun firstBodyLine(file: Path): String {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return ""
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            i = 1
            while (i < lines.size && lines[i].trim() != "---") i++
            i++ // past the closing ---
        }
        while (i < lines.size && lines[i].isBlank()) i++
        return lines.getOrNull(i)?.trim()?.removePrefix("#")?.trim()?.take(MAX_DESC) ?: ""
    }

    private const val MAX_DESC = 120
}
