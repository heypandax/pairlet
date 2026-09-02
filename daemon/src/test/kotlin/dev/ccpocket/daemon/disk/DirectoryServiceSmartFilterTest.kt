package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.PATH_FILTER_SMART
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file browser's smart filter ([PATH_FILTER_SMART]) on [DirectoryService.listPathEntries].
 *
 * Against REAL repositories for the same reason [dev.ccpocket.daemon.git.GitServiceTest] is: the claim
 * being defended is a claim about `git check-ignore` — that a directory pattern matches a bare child
 * name, that a TRACKED file matching an ignore pattern is still reported as visible, that a
 * non-repository degrades instead of failing. A fake would only prove we agree with ourselves.
 *
 * The fixture repos are isolated from the developer's git config, but the PROBE deliberately is not —
 * a user's global `core.excludesFile` is part of what "ignored" means on their machine. Fixture names
 * are therefore chosen to be ones no plausible global ignore file mentions.
 */
class DirectoryServiceSmartFilterTest {

    @TempDir
    lateinit var tmp: Path

    // ------------------------------------------------------------- fixtures

    private fun git(dir: Path, vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true)
        pb.environment().apply {
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_CONFIG_SYSTEM", "/dev/null")
            put("GIT_TERMINAL_PROMPT", "0")
            put("LC_ALL", "C")
        }
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)

    /** `<tmp>/<name>` with an initialised repo and the given `.gitignore` body. */
    private fun repo(name: String, ignore: String): Path {
        val dir = tmp.resolve(name).also { it.createDirectories() }
        git(dir, "init", "-q", "-b", "main")
        git(dir, "config", "--local", "user.email", "test@example.com")
        git(dir, "config", "--local", "user.name", "Test")
        dir.resolve(".gitignore").writeText(ignore)
        return dir
    }

    private suspend fun names(dir: Path, subPath: String = "", limit: Int = 500, filter: String? = PATH_FILTER_SMART) =
        DirectoryService().listPathEntries(dir.toString(), subPath, limit, filter)!!.first.map { it.name }

    // ---------------------------------------------------------------- tests

    @Test
    fun smart_drops_dot_entries_and_everything_gitignore_excludes() = runBlocking {
        assumeTrue(gitAvailable(), "git is required for the ignore half of the filter")
        val dir = repo("proj", "vendored/\n*.generated.kt\n")
        dir.resolve("vendored/dep").createDirectories()     // ignored directory, matched by name alone
        dir.resolve("Main.kt").writeText("fun main() {}")
        dir.resolve("Schema.generated.kt").writeText("// generated")
        dir.resolve("src").createDirectories()
        dir.resolve(".claude").createDirectories()          // dot dir: hidden without asking git

        assertEquals(listOf("src", "Main.kt"), names(dir))
        // …and the unfiltered listing still holds everything, so this is a VIEW and not a deletion
        assertTrue(names(dir, filter = null).containsAll(listOf(".claude", ".gitignore", "vendored", "Schema.generated.kt")))
    }

    @Test
    fun a_tracked_file_matching_an_ignore_pattern_stays_visible() = runBlocking {
        assumeTrue(gitAvailable(), "git is required for the ignore half of the filter")
        // `git add -f` is how a file that matches .gitignore ends up in the repository anyway. It IS the
        // project's source at that point, so the browser must show it — this is what makes the default
        // (index-aware) check-ignore the right call, and it would break the day someone adds --no-index.
        val dir = repo("tracked", "*.lock\n")
        dir.resolve("pnpm.lock").writeText("lockfile")
        dir.resolve("stale.lock").writeText("not tracked")
        git(dir, "add", "-f", "pnpm.lock")

        assertEquals(listOf("pnpm.lock"), names(dir))
    }

    @Test
    fun smart_in_a_plain_directory_drops_only_dot_entries() = runBlocking {
        // no repository above this temp dir: check-ignore exits 128, which must degrade to "show them"
        // rather than to an error or an empty folder
        val dir = tmp.resolve("plain").also { it.createDirectories() }
        dir.resolve("notes.md").writeText("n")
        dir.resolve("vendored").createDirectories()
        dir.resolve(".hidden").createDirectories()
        dir.resolve(".env").writeText("secret")

        assertEquals(listOf("vendored", "notes.md"), names(dir))
    }

    @Test
    fun smart_filters_before_the_limit_truncates() = runBlocking {
        assumeTrue(gitAvailable(), "git is required for the ignore half of the filter")
        // The point of the whole feature: 40 ignored entries sort ahead of the two real files, so a
        // filter applied AFTER the cap would hand the browser a page of pure noise and a "there is more"
        // flag, while the project's own files never appear at all.
        val dir = repo("noisy", "node_modules/\n")
        dir.resolve("node_modules").createDirectories()
        repeat(40) { i -> dir.resolve("node_modules/pkg-$i").createDirectories() }
        dir.resolve("app.kt").writeText("a")
        dir.resolve("build.gradle.kts").writeText("b")

        val (entries, truncated) = DirectoryService().listPathEntries(dir.toString(), "", 5, PATH_FILTER_SMART)!!
        assertEquals(listOf("app.kt", "build.gradle.kts"), entries.map { it.name })
        assertFalse(truncated, "a page that fits after filtering must not claim there is more")

        // the same directory one level in, where every child is ignored by the parent pattern
        val (inside, _) = DirectoryService().listPathEntries(dir.toString(), "node_modules", 5, PATH_FILTER_SMART)!!
        assertEquals(5, inside.size, "a directory the user deliberately opened still lists its children")
    }
}
