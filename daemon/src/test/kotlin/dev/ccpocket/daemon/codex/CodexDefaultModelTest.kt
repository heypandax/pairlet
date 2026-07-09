package dev.ccpocket.daemon.codex

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pre-first-turn Codex default-model resolver (issue #96): top-level `model` in config.toml only. */
class CodexDefaultModelTest {
    private fun config(body: String): Path {
        val dir = Files.createTempDirectory("ccp-codex")
        val f = File(dir.toFile(), "config.toml")
        f.writeText(body)
        return f.toPath()
    }

    @Test
    fun reads_top_level_model() {
        assertEquals("gpt-5.5", CodexDefaultModel.resolve(config("""model = "gpt-5.5"""" + "\n")))
    }

    @Test
    fun tolerates_single_quotes_and_trailing_comment() {
        assertEquals("gpt-5-codex", CodexDefaultModel.resolve(config("model = 'gpt-5-codex'  # my default\n")))
    }

    @Test
    fun ignores_model_inside_a_table_section() {
        val body = """
            approval_policy = "on-request"

            [profiles.fast]
            model = "gpt-4o-mini"
        """.trimIndent()
        assertNull(CodexDefaultModel.resolve(config(body)))
    }

    @Test
    fun missing_file_returns_null() {
        assertNull(CodexDefaultModel.resolve(Path.of("/nonexistent/config.toml")))
    }

    @Test
    fun no_model_key_returns_null() {
        assertNull(CodexDefaultModel.resolve(config("approval_policy = \"on-request\"\n")))
    }
}
