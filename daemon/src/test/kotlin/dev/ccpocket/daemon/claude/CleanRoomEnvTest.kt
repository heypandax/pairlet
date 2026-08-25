package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.PresetEnv
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API-transport exception to the clean room: `--setting-sources=` takes the user settings' `env` block
 * down with it, and on a gateway machine (cc-switch et al.) that block IS the API route — the v1.7.6
 * Windows bridge regression, where every restricted turn came back as the CLI's `<synthetic>` API failure
 * while the owner's terminal claude worked on the same box.
 *
 * Every value below is a dummy. The half of this file that must stay red-on-regression is the pairing set:
 * a destination and a credential may never come from different configuration layers — the same invariant
 * [GatewayCredentialPairingTest] pins for the model probe, now on the launch path.
 */
class CleanRoomEnvTest {

    private fun settingsDir(json: String?): File {
        val root = createTempDirectory("ccp-cleanroom").toFile()
        if (json != null) File(root, "settings.json").writeText(json)
        return root
    }

    private fun readFrom(json: String?): Map<String, String> =
        CleanRoomEnv.readAllowed(File(settingsDir(json), "settings.json"))

    private val gatewaySettings = """
        {"env":{
          "ANTHROPIC_BASE_URL":"https://gw.example/v1",
          "ANTHROPIC_AUTH_TOKEN":"dummy-settings-token",
          "ANTHROPIC_DEFAULT_OPUS_MODEL":"gw-opus",
          "ANTHROPIC_DEFAULT_SONNET_MODEL":"gw-sonnet",
          "ANTHROPIC_DEFAULT_HAIKU_MODEL":"gw-haiku",
          "CLAUDE_CODE_SUBAGENT_MODEL":"gw-sub"
        }}
    """.trimIndent()

    // ── the regression: the gateway route has to reach the child ─────────────

    @Test
    fun settings_gateway_route_and_model_aliases_reach_a_clean_room_launch() {
        val env = mutableMapOf("PATH" to "/usr/bin")
        CleanRoomEnv.applyTo(env, presetActive = false, userConfigDir = settingsDir(gatewaySettings).toPath())
        assertEquals("https://gw.example/v1", env[PresetEnv.BASE_URL])
        assertEquals("dummy-settings-token", env[PresetEnv.AUTH_TOKEN])
        assertEquals("gw-opus", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("gw-sonnet", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gw-haiku", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("gw-sub", env["CLAUDE_CODE_SUBAGENT_MODEL"])
        assertEquals("/usr/bin", env["PATH"]) // untouched
        assertEquals("1", env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
    }

    @Test
    fun the_daemons_own_config_dir_is_read_when_credential_isolation_is_on() {
        // isolation (issue #69) points CLAUDE_CONFIG_DIR at the daemon's own home, where settings.json is a
        // symlink back to the real one — the launch's `user` source is THAT file, so that is the one we read
        val isolated = settingsDir(gatewaySettings).toPath()
        val env = mutableMapOf<String, String>()
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = isolated,
            systemEnv = { if (it == "CLAUDE_CONFIG_DIR") settingsDir(null).toString() else null },
            home = settingsDir(null),
        )
        assertEquals("https://gw.example/v1", env[PresetEnv.BASE_URL])
    }

    // ── layer pairing (must stay red on regression) ──────────────────────────

    @Test
    fun a_competing_ambient_credential_is_scrubbed_before_the_settings_route_applies() {
        // the ordinary hazard: a leftover `export ANTHROPIC_API_KEY` (the OFFICIAL key) in the daemon's own
        // environment. Left in place next to a third-party base URL it goes straight to that third party.
        val env = mutableMapOf(
            PresetEnv.API_KEY to "sk-ant-api03-OFFICIAL",
            PresetEnv.CUSTOM_HEADERS to "Authorization: Bearer sk-cred-for-another-endpoint",
            PresetEnv.MODEL to "stale-ambient-model",
            "ANTHROPIC_DEFAULT_OPUS_MODEL" to "stale-ambient-opus",
        )
        CleanRoomEnv.applyTo(env, presetActive = false, userConfigDir = settingsDir(gatewaySettings).toPath())
        assertEquals("dummy-settings-token", env[PresetEnv.AUTH_TOKEN])
        assertFalse(PresetEnv.API_KEY in env, "the ambient official key must not survive: $env")
        assertFalse(PresetEnv.CUSTOM_HEADERS in env, "an ambient bearer header must not survive: $env")
        assertFalse(PresetEnv.MODEL in env, "the settings layer owns model routing once it owns the route")
        assertEquals("gw-opus", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertFalse(env.values.any { it.contains("OFFICIAL") || it.contains("another-endpoint") })
    }

    @Test
    fun a_settings_endpoint_without_its_own_credential_never_borrows_the_ambient_one() {
        // fail closed: no route injected at all, so the ambient key keeps talking to the endpoint it was
        // meant for instead of being handed to gw.thirdparty.com
        val env = mutableMapOf(PresetEnv.API_KEY to "sk-ant-api03-OFFICIAL")
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = settingsDir("""{"env":{"ANTHROPIC_BASE_URL":"https://gw.thirdparty.com"}}""").toPath(),
        )
        assertNull(env[PresetEnv.BASE_URL], "an unpaired settings endpoint must not be injected: $env")
        assertEquals("sk-ant-api03-OFFICIAL", env[PresetEnv.API_KEY], "the launch env is left exactly as it was")
    }

    @Test
    fun an_ambient_endpoint_wins_and_never_receives_a_settings_credential() {
        val env = mutableMapOf(PresetEnv.BASE_URL to "https://gw.ambient/v1", PresetEnv.AUTH_TOKEN to "ambient-token")
        CleanRoomEnv.applyTo(env, presetActive = false, userConfigDir = settingsDir(gatewaySettings).toPath())
        assertEquals("https://gw.ambient/v1", env[PresetEnv.BASE_URL])
        assertEquals("ambient-token", env[PresetEnv.AUTH_TOKEN])
        assertFalse(env.values.any { it == "dummy-settings-token" }, "settings token leaked to the ambient endpoint: $env")
        // and the settings gateway's model aliases stay behind too — they name models only IT serves
        assertFalse("ANTHROPIC_DEFAULT_OPUS_MODEL" in env)
    }

    @Test
    fun a_settings_credential_without_a_base_url_targets_the_default_endpoint_only_when_none_is_ambient() {
        val onlyToken = """{"env":{"ANTHROPIC_AUTH_TOKEN":"dummy-settings-token"}}"""
        val free = mutableMapOf<String, String>()
        CleanRoomEnv.applyTo(free, presetActive = false, userConfigDir = settingsDir(onlyToken).toPath())
        assertEquals("dummy-settings-token", free[PresetEnv.AUTH_TOKEN])
        assertNull(free[PresetEnv.BASE_URL], "no endpoint is invented — the official default applies")

        val ambient = mutableMapOf(PresetEnv.BASE_URL to "https://gw.ambient/v1")
        CleanRoomEnv.applyTo(ambient, presetActive = false, userConfigDir = settingsDir(onlyToken).toPath())
        assertFalse(PresetEnv.AUTH_TOKEN in ambient, "a settings token must not be sent to an ambient gateway: $ambient")
    }

    @Test
    fun auth_token_is_preferred_over_api_key_within_the_settings_layer() {
        val env = mutableMapOf<String, String>()
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = settingsDir(
                """{"env":{"ANTHROPIC_BASE_URL":"https://gw.example/v1","ANTHROPIC_AUTH_TOKEN":"tok","ANTHROPIC_API_KEY":"key"}}""",
            ).toPath(),
        )
        assertEquals("tok", env[PresetEnv.AUTH_TOKEN])
        assertFalse(PresetEnv.API_KEY in env, "exactly ONE credential spelling is injected: $env")
    }

    @Test
    fun model_only_settings_are_imported_without_displacing_an_ambient_value() {
        val env = mutableMapOf(PresetEnv.MODEL to "ambient-model")
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = settingsDir(
                """{"env":{"ANTHROPIC_MODEL":"settings-model","ANTHROPIC_SMALL_FAST_MODEL":"settings-small"}}""",
            ).toPath(),
        )
        assertEquals("ambient-model", env[PresetEnv.MODEL], "no endpoint/credential layer was selected, so nothing is displaced")
        assertEquals("settings-small", env[PresetEnv.SMALL_FAST_MODEL], "an unset alias is still filled in")
    }

    // ── an active preset outranks every settings file ────────────────────────

    @Test
    fun an_active_preset_wins_and_the_settings_file_is_ignored() {
        // applyPresetEnv has already scrubbed + injected by the time we run; mixing a settings value into it
        // would send the preset's endpoint a credential from a layer the user switched away from
        val env = mutableMapOf(PresetEnv.BASE_URL to "https://preset.example/v1", PresetEnv.AUTH_TOKEN to "preset-token")
        CleanRoomEnv.applyTo(env, presetActive = true, userConfigDir = settingsDir(gatewaySettings).toPath())
        assertEquals("https://preset.example/v1", env[PresetEnv.BASE_URL])
        assertEquals("preset-token", env[PresetEnv.AUTH_TOKEN])
        assertFalse("ANTHROPIC_DEFAULT_OPUS_MODEL" in env)
        assertEquals("1", env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB], "the subprocess scrub still applies")
    }

    // ── the allow-list is CLOSED ─────────────────────────────────────────────

    @Test
    fun arbitrary_settings_env_keys_never_reach_the_child() {
        val env = mutableMapOf("PATH" to "/usr/bin")
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = settingsDir(
                """
                {"env":{
                  "ANTHROPIC_BASE_URL":"https://gw.example/v1",
                  "ANTHROPIC_AUTH_TOKEN":"dummy-settings-token",
                  "PATH":"/attacker/bin",
                  "BASH_ENV":"/tmp/evil.sh",
                  "NODE_OPTIONS":"--require /tmp/evil.js",
                  "ANTHROPIC_CUSTOM_HEADERS":"Authorization: Bearer sk-someone-elses",
                  "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB":"0",
                  "DISABLE_TELEMETRY":"1"
                }}
                """.trimIndent(),
            ).toPath(),
        )
        assertEquals("/usr/bin", env["PATH"])
        assertFalse("BASH_ENV" in env)
        assertFalse("NODE_OPTIONS" in env)
        assertFalse("DISABLE_TELEMETRY" in env)
        assertFalse(PresetEnv.CUSTOM_HEADERS in env, "a settings header line is scrub-only, never imported: $env")
        // the settings file cannot turn the subprocess scrub off — it is not in the allow-list, and the
        // marker is written before anything is imported
        assertEquals("1", env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
    }

    @Test
    fun malformed_missing_and_non_string_settings_are_harmless() {
        assertEquals(emptyMap(), readFrom(null), "no settings file")
        assertEquals(emptyMap(), readFrom("{not json at all"), "truncated/garbage")
        assertEquals(emptyMap(), readFrom("""["an","array"]"""), "root is not an object")
        assertEquals(emptyMap(), readFrom("""{"env":"not-an-object"}"""), "env is not an object")
        assertEquals(emptyMap(), readFrom("""{"model":"claude-opus-5"}"""), "no env block")
        assertEquals(emptyMap(), readFrom("""{"env":{"ANTHROPIC_BASE_URL":123}}"""), "non-string value")
        assertEquals(emptyMap(), readFrom("""{"env":{"ANTHROPIC_AUTH_TOKEN":true}}"""), "non-string value")
        assertEquals(emptyMap(), readFrom("""{"env":{"ANTHROPIC_AUTH_TOKEN":{"a":"b"}}}"""), "nested object")
        assertEquals(emptyMap(), readFrom("""{"env":{"ANTHROPIC_AUTH_TOKEN":"   "}}"""), "blank value")

        // and a broken file leaves a launch environment untouched
        val env = mutableMapOf(PresetEnv.API_KEY to "sk-ambient")
        CleanRoomEnv.applyTo(env, presetActive = false, userConfigDir = settingsDir("{oops").toPath())
        assertEquals(mapOf(PresetEnv.API_KEY to "sk-ambient", CleanRoomEnv.SUBPROCESS_ENV_SCRUB to "1"), env)
    }

    @Test
    fun malformed_endpoint_values_fail_closed_without_scrubbing_or_exposing_their_credential() {
        listOf(
            "not a URL",
            "/relative/v1",
            "file:///tmp/api",
            "https://",
            "https://user:password@gw.example/v1",
        ).forEach { invalid ->
            val env = mutableMapOf(
                PresetEnv.API_KEY to "dummy-ambient-key",
                PresetEnv.MODEL to "ambient-model",
            )
            CleanRoomEnv.applyTo(
                env,
                presetActive = false,
                userConfigDir = settingsDir(
                    """{"env":{"ANTHROPIC_BASE_URL":"$invalid","ANTHROPIC_AUTH_TOKEN":"dummy-settings-token","ANTHROPIC_MODEL":"settings-model"}}""",
                ).toPath(),
            )
            assertEquals("dummy-ambient-key", env[PresetEnv.API_KEY], "ambient credential changed for $invalid")
            assertEquals("ambient-model", env[PresetEnv.MODEL], "ambient model changed for $invalid")
            assertNull(env[PresetEnv.BASE_URL], "invalid endpoint reached the launch env: $invalid")
            assertFalse(env.values.any { it == "dummy-settings-token" }, "settings credential leaked for $invalid")
            assertEquals("1", env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
        }
    }

    @Test
    fun nul_in_an_allowed_value_is_dropped_before_process_environment_construction() {
        val values = readFrom(
            """{"env":{"ANTHROPIC_AUTH_TOKEN":"dummy\u0000token","ANTHROPIC_MODEL":"model\u0000suffix"}}""",
        )
        assertFalse(PresetEnv.AUTH_TOKEN in values)
        assertFalse(PresetEnv.MODEL in values)

        // A paired endpoint whose credential was malformed now fails closed instead of throwing an
        // IllegalArgumentException whose message contains the rejected environment-variable value.
        val env = mutableMapOf(PresetEnv.API_KEY to "dummy-ambient-key")
        CleanRoomEnv.applyTo(
            env,
            presetActive = false,
            userConfigDir = settingsDir(
                """{"env":{"ANTHROPIC_BASE_URL":"https://gw.example/v1","ANTHROPIC_AUTH_TOKEN":"dummy\u0000token"}}""",
            ).toPath(),
        )
        assertEquals("dummy-ambient-key", env[PresetEnv.API_KEY])
        assertNull(env[PresetEnv.BASE_URL])
        assertEquals("1", env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB])
    }

    @Test
    fun values_are_trimmed_and_the_allow_list_is_exactly_the_documented_set() {
        assertEquals("https://gw.example/v1", readFrom("""{"env":{"ANTHROPIC_BASE_URL":"  https://gw.example/v1  "}}""")[PresetEnv.BASE_URL])
        assertEquals(
            listOf(
                "ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY",
                "ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL",
            ),
            CleanRoomEnv.ALLOWED,
            "widening this list is a security decision, not a refactor",
        )
    }

    // ── the credential must not spread past the parent process ───────────────

    @Test
    fun every_clean_room_path_forces_the_subprocess_env_scrub() {
        // a restricted requester must not be able to read the owner's key back out through a Bash tool call
        listOf(
            mutableMapOf<String, String>() to settingsDir(gatewaySettings), // settings route
            mutableMapOf(PresetEnv.BASE_URL to "https://gw.ambient/v1") to settingsDir(gatewaySettings), // ambient route
            mutableMapOf<String, String>() to settingsDir(null), // nothing configured
        ).forEach { (env, dir) ->
            CleanRoomEnv.applyTo(env, presetActive = false, userConfigDir = dir.toPath())
            assertTrue(env[CleanRoomEnv.SUBPROCESS_ENV_SCRUB] == "1", "missing subprocess scrub: $env")
        }
    }
}
