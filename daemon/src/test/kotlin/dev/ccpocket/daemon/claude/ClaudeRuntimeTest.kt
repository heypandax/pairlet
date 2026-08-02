package dev.ccpocket.daemon.claude

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared claude launch context for auxiliary processes (reviewed-trust §21.2 P1-1): the reviewer must
 *  inherit the MAIN backend's binary override, credential store and preset routing — not re-derive its own. */
class ClaudeRuntimeTest {
    private val tmp: File = Files.createTempDirectory("ccp-runtime").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun applyTo_layers_config_dir_and_preset_env_and_strips_nested_session_marker() {
        val configDir = File(tmp, "claude-home").toPath()
        var preset: Map<String, String>? = mapOf("ANTHROPIC_BASE_URL" to "https://gw.example", "ANTHROPIC_AUTH_TOKEN" to "t1")
        val rt = ClaudeRuntime(binOverride = null, configDir = configDir) { preset }

        val env = mutableMapOf(
            "CLAUDECODE" to "1",
            // a stale key a preset owns must be SCRUBBED, not left to fight the preset's token
            "ANTHROPIC_API_KEY" to "stale",
            "PATH" to "/usr/bin",
        )
        rt.applyTo(env)
        assertEquals(configDir.toString(), env["CLAUDE_CONFIG_DIR"], "isolated credential store must reach the helper")
        assertEquals("https://gw.example", env["ANTHROPIC_BASE_URL"])
        assertEquals("t1", env["ANTHROPIC_AUTH_TOKEN"])
        assertFalse("CLAUDECODE" in env, "helper must not look like a nested agent session")
        assertFalse("ANTHROPIC_API_KEY" in env, "preset application must scrub competing credential vars")
        assertEquals("/usr/bin", env["PATH"], "unrelated env passes through")

        // presetEnv is read PER APPLICATION — a preset switch applies to the next process, no restart
        preset = mapOf("ANTHROPIC_AUTH_TOKEN" to "t2")
        val env2 = mutableMapOf<String, String>()
        rt.applyTo(env2)
        assertEquals("t2", env2["ANTHROPIC_AUTH_TOKEN"])
        assertNull(env2["ANTHROPIC_BASE_URL"], "the old preset's endpoint must not linger")
    }

    @Test
    fun no_isolation_and_no_preset_leaves_env_alone_except_the_session_marker() {
        val rt = ClaudeRuntime(binOverride = null, configDir = null) { null }
        val env = mutableMapOf("CLAUDECODE" to "1", "HOME" to "/Users/x")
        rt.applyTo(env)
        assertFalse("CLAUDE_CONFIG_DIR" in env, "no isolation ⇒ the default store stays in effect")
        assertEquals("/Users/x", env["HOME"])
        assertFalse("CLAUDECODE" in env)
    }

    @Test
    fun resolveExecutable_honors_the_explicit_bin_override() {
        // the --claude-bin machines are exactly where PATH resolution finds nothing (P1-1's failure mode)
        val fake = File(tmp, "claude").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val rt = ClaudeRuntime(binOverride = fake.absolutePath, configDir = null) { null }
        // canonical on both sides: macOS resolves /var → /private/var
        assertEquals(fake.canonicalPath, rt.resolveExecutable()?.toFile()?.canonicalPath)
        // a bogus override resolves to null (→ reviewer unavailable → ASK_OWNER), never a throw
        val broken = ClaudeRuntime(binOverride = File(tmp, "missing").absolutePath, configDir = null) { null }
        assertTrue(broken.resolveExecutable() == null || broken.resolveExecutable()!!.toFile().exists())
    }
}
