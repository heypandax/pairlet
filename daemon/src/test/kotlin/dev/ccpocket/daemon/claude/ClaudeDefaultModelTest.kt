package dev.ccpocket.daemon.claude

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pre-first-turn default-model resolver (issue #96). Env + home are injected so the real machine's
 * ~/.claude / $ANTHROPIC_MODEL never leak into the assertions; precedence and defensiveness are the point.
 */
class ClaudeDefaultModelTest {
    private val noEnv: (String) -> String? = { null }

    private fun userDir(model: String?): java.nio.file.Path {
        val dir = Files.createTempDirectory("ccp-cfg")
        if (model != null) File(dir.toFile(), "settings.json").writeText("""{"model":"$model"}""")
        return dir
    }

    @Test
    fun reads_model_from_user_settings() {
        val m = ClaudeDefaultModel.resolve("/repo", userDir("opus"), noEnv)
        assertEquals("opus", m)
    }

    @Test
    fun env_var_overrides_settings() {
        val env: (String) -> String? = { if (it == "ANTHROPIC_MODEL") "sonnet" else null }
        val m = ClaudeDefaultModel.resolve("/repo", userDir("opus"), env)
        assertEquals("sonnet", m)
    }

    @Test
    fun settings_env_block_wins_over_top_level_model() {
        val dir = Files.createTempDirectory("ccp-cfg")
        File(dir.toFile(), "settings.json").writeText("""{"model":"opus","env":{"ANTHROPIC_MODEL":"haiku"}}""")
        assertEquals("haiku", ClaudeDefaultModel.resolve("/repo", dir, noEnv))
    }

    @Test
    fun project_settings_override_user_settings() {
        val workdir = Files.createTempDirectory("ccp-proj")
        Files.createDirectories(File(workdir.toFile(), ".claude").toPath())
        File(workdir.toFile(), ".claude/settings.json").writeText("""{"model":"fable"}""")
        val m = ClaudeDefaultModel.resolve(workdir.toString(), userDir("opus"), noEnv)
        assertEquals("fable", m)
    }

    @Test
    fun local_project_settings_win_over_shared() {
        val workdir = Files.createTempDirectory("ccp-proj")
        Files.createDirectories(File(workdir.toFile(), ".claude").toPath())
        File(workdir.toFile(), ".claude/settings.json").writeText("""{"model":"fable"}""")
        File(workdir.toFile(), ".claude/settings.local.json").writeText("""{"model":"sonnet"}""")
        assertEquals("sonnet", ClaudeDefaultModel.resolve(workdir.toString(), userDir("opus"), noEnv))
    }

    @Test
    fun nothing_configured_returns_null() {
        // empty user dir (no settings.json), empty project, no env — and a temp home so the ~/.claude
        // fallback can't read the real machine's config
        val home = Files.createTempDirectory("ccp-home").toFile()
        assertNull(ClaudeDefaultModel.resolve("/repo", userConfigDir = null, env = noEnv, home = home))
        assertNull(ClaudeDefaultModel.resolve("/repo", userDir(null), noEnv))
    }

    @Test
    fun malformed_settings_degrades_to_null_no_throw() {
        val dir = Files.createTempDirectory("ccp-cfg")
        File(dir.toFile(), "settings.json").writeText("{ this is not json ")
        assertNull(ClaudeDefaultModel.resolve("/repo", dir, noEnv))
    }
}
