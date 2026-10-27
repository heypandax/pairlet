package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * A READ-ONLY, redacted view of the user's dsh profiles — the facts needed to tell "this plugin is installed in
 * another profile" apart from every other reason a model is missing.
 *
 * WHY: dsh installs plugins PER PROFILE (`dsh plugin --profile <name> add <package>`, recorded in
 * `$DSH_HOME/profiles/<name>/package.json`), and Pairlet always starts the `acp` profile ([DshLauncher.PROFILE]).
 * A provider plugin a user added to the profile they use every day (`web`, `tui`, a custom one) is therefore
 * absent from every catalogue Pairlet can read, and nothing in the ACP answer says why.
 *
 * WHAT IS READ, AND WHAT NEVER IS: each profile's directory name, its `dsh.profile.bundles`, and its
 * `dependencies` as package name + version spec — a local `file:`/`link:` spec is reduced to `<local>`, since it
 * is a path. `cordis.patch.yml` is only COUNTED (its top-level entries), never quoted: it is where a provider's
 * keys and endpoints live. Credentials, sessions and any absolute path are out of scope. Nothing is written.
 */
object DshProfiles {
    data class Profile(
        val name: String,
        val bundles: List<String>,
        /** package name → redacted version spec, in file order. */
        val plugins: Map<String, String>,
        /** Top-level entries in `cordis.patch.yml`; null when the file is absent or unreadable. */
        val patchEntries: Int?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every profile under `[home]/profiles`, sorted by name; empty when there is none or it cannot be read. */
    fun read(home: Path): List<Profile> {
        val root = home.resolve("profiles")
        if (!root.isDirectory()) return emptyList()
        return runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList())
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .map(::profile)
    }

    private fun profile(dir: Path): Profile {
        val pkg = dir.resolve("package.json").takeIf { it.isRegularFile() }
            ?.let { runCatching { json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
        val bundles = pkg?.obj("dsh")?.obj("profile")?.arr("bundles")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val plugins = LinkedHashMap<String, String>()
        pkg?.obj("dependencies")?.forEach { (name, spec) ->
            plugins[name] = redactSpec((spec as? JsonPrimitive)?.contentOrNull)
        }
        return Profile(dir.name, bundles, plugins, patchEntries(dir.resolve("cordis.patch.yml")))
    }

    /** `^1.2.3` / `1.2.3-rc.1` / a dist-tag pass through; anything that can carry a path or URL does not. */
    internal fun redactSpec(spec: String?): String {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return "?"
        if (s.startsWith("npm:")) return s // an alias names a registry package, not a place on disk
        val local = listOf("file:", "link:", "portal:", "workspace:", "git", "http:", "https:", "~", ".")
        return if (local.any { s.startsWith(it) } || '/' in s || '\\' in s) "<local>" else s
    }

    /** Counts top-level `- ` items of the patch list; `[]` and comments count as zero. Never returns content. */
    private fun patchEntries(file: Path): Int? {
        if (!file.isRegularFile()) return null
        return runCatching { file.readLines().count { it.startsWith("- ") || it == "-" } }.getOrNull()
    }

    /**
     * Plugins present in some other profile but not in [target] → the profiles that have them. These are the
     * likeliest reason a provider's models are missing from the session Pairlet starts.
     */
    fun missingFrom(profiles: List<Profile>, target: String = DshLauncher.PROFILE): Map<String, List<String>> {
        val have = profiles.firstOrNull { it.name == target }?.plugins?.keys.orEmpty()
        val out = LinkedHashMap<String, MutableList<String>>()
        profiles.filter { it.name != target }.forEach { p ->
            p.plugins.keys.filter { it !in have }.forEach { out.getOrPut(it) { mutableListOf() } += p.name }
        }
        return out
    }

    /** One sentence for a "model not offered" reason, or null when no profile has a plugin `acp` lacks. */
    fun missingPluginHint(profiles: List<Profile>, target: String = DshLauncher.PROFILE): String? {
        val missing = missingFrom(profiles, target).takeIf { it.isNotEmpty() } ?: return null
        val listed = missing.entries.take(MAX_HINT_PLUGINS).joinToString(", ") { (pkg, where) ->
            "$pkg (in ${where.joinToString("/")})"
        }
        return "Plugins installed in other dsh profiles but not in `$target`: $listed" +
            (if (missing.size > MAX_HINT_PLUGINS) ", …" else "") +
            ". If the model comes from one of them, also install it into `$target` " +
            "(dsh plugin --profile $target add <package>) along with its provider settings."
    }

    /**
     * The redacted report `pairlet diagnostics --dsh` prints for a user to paste into a support thread. Safe by
     * construction: only what [Profile] holds, plus whether `DSH_HOME` is overridden (never its value).
     */
    fun report(home: Path, dshVersion: String?, homeOverridden: Boolean): String = buildString {
        val profiles = read(home)
        appendLine("dsh version: ${dshVersion ?: "unknown (dsh not found or did not answer)"}")
        appendLine("DSH_HOME overridden: ${if (homeOverridden) "yes" else "no"}")
        appendLine("profile Pairlet starts: ${DshLauncher.PROFILE}")
        if (profiles.isEmpty()) {
            appendLine("profiles: none found")
            return@buildString
        }
        profiles.forEach { p ->
            appendLine("profile ${p.name}:")
            appendLine("  bundles: ${p.bundles.ifEmpty { listOf("-") }.joinToString(", ")}")
            appendLine("  plugins: ${if (p.plugins.isEmpty()) "-" else p.plugins.entries.joinToString(", ") { "${it.key}@${it.value}" }}")
            appendLine("  patch entries: ${p.patchEntries ?: "-"}")
        }
        val missing = missingFrom(profiles)
        appendLine(
            "plugins missing from ${DshLauncher.PROFILE}: " +
                if (missing.isEmpty()) "none" else missing.entries.joinToString(", ") { "${it.key} (in ${it.value.joinToString("/")})" },
        )
    }

    /** `dsh --version`, bounded; null when it cannot be run. */
    fun dshVersion(exe: Path): String? = runCatching {
        val p = ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start()
        if (!p.waitFor(VERSION_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        p.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()?.take(64)
    }.getOrNull()

    private const val MAX_HINT_PLUGINS = 4
    private const val VERSION_TIMEOUT_S = 10L
}
