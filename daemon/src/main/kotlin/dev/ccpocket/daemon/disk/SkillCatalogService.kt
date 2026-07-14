package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.PluginInfo
import dev.ccpocket.protocol.SkillCatalog
import dev.ccpocket.protocol.SkillInfo
import dev.ccpocket.protocol.SkillScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Builds the installed skills/plugins catalog for the desktop browse page (issue #132).
 *
 * Skills: `~/.claude/skills/<name>/SKILL.md` (user) + `<workdir>/.claude/skills/<name>/SKILL.md`
 * (project) — the FULL top-level frontmatter plus a capped body excerpt, a superset of what
 * [SlashCommandScanner] extracts for the composer autocomplete.
 *
 * Plugins: `~/.claude/plugins/installed_plugins.json` — the CLI's install ledger (v2 maps
 * `"name@marketplace"` → a list of install records carrying `installPath`). Each record's manifest
 * lives at `<installPath>/.claude-plugin/plugin.json`. Without a readable ledger the cache layout
 * (`plugins/cache/<marketplace>/<plugin>[/<version>]`) is scanned instead. Every read is
 * best-effort: a missing or garbled file degrades to whatever could be read (a bare directory
 * name at worst) — never an error.
 */
object SkillCatalogService {

    fun build(workdir: Path?, home: Path = Path.of(System.getProperty("user.home"))): SkillCatalog {
        val skills = skillsIn(home.resolve(".claude/skills"), SkillScope.USER) +
            (workdir?.let { skillsIn(it.resolve(".claude/skills"), SkillScope.PROJECT) } ?: emptyList())
        return SkillCatalog(
            skills = skills.sortedWith(compareBy({ it.scope != SkillScope.USER }, { it.name.lowercase() })),
            plugins = plugins(home.resolve(".claude/plugins")).sortedBy { it.name.lowercase() },
        )
    }

    // ── skills ───────────────────────────────────────────────────────────────────────────────────

    private fun skillsIn(root: Path, scope: SkillScope): List<SkillInfo> {
        if (!root.isDirectory()) return emptyList()
        return runCatching {
            Files.list(root).use { stream ->
                stream.filter { it.isDirectory() && it.resolve("SKILL.md").isRegularFile() }
                    .map { dir -> skill(dir, scope) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun skill(dir: Path, scope: SkillScope): SkillInfo {
        val lines = runCatching { Files.readAllLines(dir.resolve("SKILL.md")) }.getOrNull() ?: emptyList()
        val (meta, bodyStart) = frontmatter(lines)
        val body = lines.drop(bodyStart).joinToString("\n").trim()
        return SkillInfo(
            name = dir.fileName.toString(),
            description = meta["description"] ?: "",
            scope = scope,
            meta = meta.filterKeys { it != "description" },
            excerpt = body.take(EXCERPT_MAX),
            truncated = body.length > EXCERPT_MAX,
            path = dir.toAbsolutePath().toString(),
        )
    }

    /** Top-level `key: value` scalars of a YAML frontmatter block → (map, index of the first body line).
     *  Same minimal-YAML discipline as [SlashCommandScanner] (nested/indented keys skipped), but keeps
     *  EVERY top-level key. No frontmatter (or an unclosed block) → empty map, body from line 0 / none. */
    private fun frontmatter(lines: List<String>): Pair<Map<String, String>, Int> {
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to 0
        val meta = LinkedHashMap<String, String>()
        var i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val line = lines[i]
            i++
            if (line.isBlank() || line.first().isWhitespace()) continue // nested keys (metadata: …) / continuations
            val key = line.substringBefore(':', "").trim()
            if (key.isEmpty()) continue
            val value = line.substringAfter(':', "").trim().removeSurrounding("\"").removeSurrounding("'")
            if (value.isNotEmpty()) meta[key] = value.take(META_VALUE_MAX)
        }
        return meta to (i + 1).coerceAtMost(lines.size) // past the closing --- (or EOF when unclosed)
    }

    // ── plugins ──────────────────────────────────────────────────────────────────────────────────

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun plugins(root: Path): List<PluginInfo> {
        val fromLedger = runCatching { parseLedger(root.resolve("installed_plugins.json")) }.getOrDefault(emptyList())
        if (fromLedger.isNotEmpty()) return fromLedger
        return runCatching { scanCache(root.resolve("cache")) }.getOrDefault(emptyList())
    }

    /** The CLI's install ledger: `{"version":2,"plugins":{"name@marketplace":[{scope,installPath,version,…}]}}`. */
    private fun parseLedger(file: Path): List<PluginInfo> {
        if (!file.isRegularFile()) return emptyList()
        val entries = lenientJson.parseToJsonElement(Files.readString(file))
            .jsonObject["plugins"] as? JsonObject ?: return emptyList()
        return entries.mapNotNull { (key, value) ->
            // v2 ships a list of install records; tolerate a bare object (defensive against format drift)
            val rec = when (value) {
                is JsonArray -> value.firstOrNull() as? JsonObject
                is JsonObject -> value
                else -> null
            }
            val dir = rec?.str("installPath")?.let { runCatching { Path.of(it) }.getOrNull() }
            runCatching {
                plugin(
                    name = key.substringBefore('@').ifBlank { key },
                    marketplace = key.substringAfter('@', "").ifBlank { null },
                    scope = rec?.str("scope"),
                    version = rec?.str("version"),
                    dir = dir,
                )
            }.getOrNull()
        }
    }

    /** No readable ledger — list what's physically cached: `cache/<marketplace>/<plugin>[/<version>]`. */
    private fun scanCache(cache: Path): List<PluginInfo> {
        if (!cache.isDirectory()) return emptyList()
        val markets = Files.list(cache).use { s -> s.filter { it.isDirectory() }.toList() }
        return markets.flatMap { market ->
            val pluginDirs = runCatching {
                Files.list(market).use { s -> s.filter { it.isDirectory() }.toList() }
            }.getOrDefault(emptyList())
            pluginDirs.mapNotNull { pluginDir ->
                runCatching {
                    plugin(
                        name = pluginDir.fileName.toString(),
                        marketplace = market.fileName.toString(),
                        scope = null,
                        version = null,
                        dir = manifestDir(pluginDir),
                    )
                }.getOrNull()
            }
        }
    }

    /** The directory whose `.claude-plugin/plugin.json` describes this plugin: the dir itself, or its
     *  newest version child (installs land under `<plugin>/<version>/`). Falls back to the dir. */
    private fun manifestDir(pluginDir: Path): Path {
        if (pluginDir.resolve(MANIFEST).isRegularFile()) return pluginDir
        val versioned = runCatching {
            Files.list(pluginDir).use { s ->
                s.filter { it.isDirectory() && it.resolve(MANIFEST).isRegularFile() }.toList()
            }
        }.getOrDefault(emptyList())
        return versioned.maxByOrNull { it.fileName.toString() } ?: pluginDir
    }

    /** Merge ledger facts with the manifest + README under [dir]; the manifest wins on name/description. */
    private fun plugin(name: String, marketplace: String?, scope: String?, version: String?, dir: Path?): PluginInfo {
        val manifest = dir?.resolve(MANIFEST)?.takeIf { it.isRegularFile() }
            ?.let { runCatching { lenientJson.parseToJsonElement(Files.readString(it)).jsonObject }.getOrNull() }
        val readme = dir?.resolve("README.md")?.takeIf { it.isRegularFile() }
            ?.let { runCatching { Files.readString(it) }.getOrNull() }?.trim() ?: ""
        val author = when (val a = manifest?.get("author")) {
            is JsonObject -> a.str("name")
            is JsonPrimitive -> a.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
        val commands = (manifest?.get("commands") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { it.substringAfterLast('/').removeSuffix(".md").removePrefix("/") }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return PluginInfo(
            name = manifest?.str("name") ?: name,
            description = manifest?.str("description") ?: "",
            version = manifest?.str("version") ?: version,
            marketplace = marketplace,
            scope = scope,
            author = author,
            homepage = manifest?.str("homepage"),
            commands = commands,
            excerpt = readme.take(EXCERPT_MAX),
            truncated = readme.length > EXCERPT_MAX,
            path = dir?.toAbsolutePath()?.toString(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val MANIFEST = ".claude-plugin/plugin.json"
    private const val EXCERPT_MAX = 4_096   // body/README cap — a browse page, not a file transfer
    private const val META_VALUE_MAX = 1_000 // per-frontmatter-value cap (descriptions can run long)
}
